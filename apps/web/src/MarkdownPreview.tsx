import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export function MarkdownPreview({ content }: { content: string }) {
  if (!content.trim()) return <p className="empty-state">暂无 Markdown 预览</p>;
  return (
    <div className="markdown-preview">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{ a: ({ href, children, title }) => <a href={href} title={title} target="_blank" rel="noreferrer noopener">{children}</a> }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}
