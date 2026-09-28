package com.agentforge.core.graph.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import com.agentforge.core.graph.domain.GraphModel.*;

public final class GraphExtraction {
    private GraphExtraction() {}
    public record Document(UUID projectId, SourceType type, UUID id, long version, String title, String text) {}
    public record Target(UUID id, EntityType type, String externalId, String name) {}
    public record Claim(UUID id, RelationType type, UUID fromId, UUID toId, Evidence evidence) {}
    public record Projection(Document document, UUID sourceEntityId, List<Target> targets, List<Claim> claims) {}

    private static final Pattern TAG = Pattern.compile(
        "(?i)^\\s*(?:[-*]\\s*)?(Modifies Service|Modifies API|Service|API|Issue):\\s*(.*?)\\s*$");
    private static final Pattern API_TARGET = Pattern.compile("(?i)^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+/\\S+$");
    public static UUID stable(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); }

    public static Projection extract(Document document) {
        var targets = new java.util.LinkedHashMap<String, Target>();
        var claims = new ArrayList<Claim>();
        String text = document.text() == null ? "" : document.text();
        UUID sourceEntityId = stable(document.projectId()+":entity:"+document.type()+":"+document.id());
        int offset = 0;
        int lineNumber = 0;
        int matched = 0;
        while (offset < text.length()) {
            int end = text.indexOf('\n', offset);
            if (end < 0) end = text.length();
            int lineEnd = end > offset && text.charAt(end-1) == '\r' ? end-1 : end;
            String line = text.substring(offset, lineEnd);
            var match = TAG.matcher(line);
            if (match.matches()) {
                String tag = match.group(1).toLowerCase(Locale.ROOT);
                String name = match.group(2).trim();
                boolean allowed = document.type() == SourceType.WIKI
                    ? !tag.startsWith("modifies ") : tag.startsWith("modifies ");
                if (allowed && !name.isEmpty()) {
                    if (line.length() > 2000) throw new IllegalArgumentException("Graph extraction evidence exceeds 2000 characters.");
                    if (name.length() > 200) throw new IllegalArgumentException("Graph extraction target exceeds 200 characters.");
                    EntityType type = tag.endsWith("service") ? EntityType.SERVICE
                        : tag.endsWith("api") ? EntityType.API : EntityType.ISSUE;
                    if (type == EntityType.API && !API_TARGET.matcher(name).matches()) {
                        offset = end + 1;
                        lineNumber++;
                        continue;
                    }
                    if (++matched > 50) throw new IllegalArgumentException("Graph extraction exceeds 50 lines.");
                    String key = type+":"+name;
                    String external = "extract:"+document.type()+":"+document.id()+":"+type+":"+stable(key);
                    var target = targets.computeIfAbsent(key, ignored -> new Target(
                        stable(document.projectId()+":entity:"+type+":"+external),type,external,name));
                    RelationType relationType = document.type() == SourceType.WIKI ? RelationType.DESCRIBES : RelationType.MODIFIES;
                    UUID relationId = stable(document.projectId()+":relation:"+relationType+":"+sourceEntityId+":"+target.id());
                    var source = new Source(document.type(),document.id(),document.version());
                    UUID evidenceId = stable(relationId+":evidence:"+document.type()+":"+document.id()+":"+document.version()+":"+lineNumber+":"+offset+":"+lineEnd);
                    var evidence = new Evidence(evidenceId,document.projectId(),source,lineNumber,offset,lineEnd,line,1.0,1);
                    claims.add(new Claim(relationId,relationType,sourceEntityId,target.id(),evidence));
                }
            }
            offset = end + 1;
            lineNumber++;
        }
        return new Projection(document,sourceEntityId,List.copyOf(targets.values()),List.copyOf(claims));
    }
}
