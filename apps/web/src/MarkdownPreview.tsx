import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export function normalizeMarkdownContent(content: string) {
  const trimmed = content.trim();
  const fenced = trimmed.match(/^```(?:markdown|md)?[ \t]*\r?\n([\s\S]*?)\r?\n```$/i);
  return fenced ? fenced[1].trim() : content;
}

export function MarkdownPreview({ content }: { content: string }) {
  const normalizedContent = normalizeMarkdownContent(content);
  if (!normalizedContent.trim()) return <p className="empty-state">暂无 Markdown 预览</p>;
  return (
    <div className="markdown-preview">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{ a: ({ href, children, title }) => <a href={href} title={title} target="_blank" rel="noreferrer noopener">{children}</a> }}
      >
        {normalizedContent}
      </ReactMarkdown>
    </div>
  );
}
