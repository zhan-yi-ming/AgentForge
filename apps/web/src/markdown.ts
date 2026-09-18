export function normalizeMarkdownContent(content: string) {
  const trimmed = content.trim();
  const fenced = trimmed.match(/^```(?:markdown|md)?[ \t]*\r?\n([\s\S]*?)\r?\n```$/i);
  return fenced ? fenced[1].trim() : content;
}
