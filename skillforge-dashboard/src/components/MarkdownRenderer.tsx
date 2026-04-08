import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';

interface MarkdownRendererProps {
  content: string;
}

const MarkdownRenderer: React.FC<MarkdownRendererProps> = ({ content }) => {
  return (
    <div className="markdown-body">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code({ inline, className, children, ...props }: any) {
            const match = /language-(\w+)/.exec(className || '');
            if (!inline && match) {
              return (
                <SyntaxHighlighter
                  style={oneDark as any}
                  language={match[1]}
                  PreTag="div"
                  customStyle={{
                    margin: '8px 0',
                    borderRadius: 6,
                    fontSize: 13,
                  }}
                  {...props}
                >
                  {String(children).replace(/\n$/, '')}
                </SyntaxHighlighter>
              );
            }
            return (
              <code
                className={className}
                style={{
                  background: 'rgba(135, 131, 120, 0.15)',
                  color: '#c7254e',
                  padding: '2px 6px',
                  borderRadius: 4,
                  fontSize: '0.9em',
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                }}
                {...props}
              >
                {children}
              </code>
            );
          },
          a({ children, href, ...props }: any) {
            return (
              <a href={href} target="_blank" rel="noopener noreferrer" {...props}>
                {children}
              </a>
            );
          },
          table({ children, ...props }: any) {
            return (
              <div style={{ overflowX: 'auto', margin: '8px 0' }}>
                <table
                  style={{
                    borderCollapse: 'collapse',
                    width: '100%',
                    fontSize: 13,
                  }}
                  {...props}
                >
                  {children}
                </table>
              </div>
            );
          },
          th({ children, ...props }: any) {
            return (
              <th
                style={{
                  border: '1px solid #d9d9d9',
                  padding: '6px 12px',
                  background: '#fafafa',
                  textAlign: 'left',
                }}
                {...props}
              >
                {children}
              </th>
            );
          },
          td({ children, ...props }: any) {
            return (
              <td
                style={{
                  border: '1px solid #d9d9d9',
                  padding: '6px 12px',
                }}
                {...props}
              >
                {children}
              </td>
            );
          },
          blockquote({ children, ...props }: any) {
            return (
              <blockquote
                style={{
                  borderLeft: '4px solid #d9d9d9',
                  padding: '4px 12px',
                  margin: '8px 0',
                  color: '#666',
                  background: '#fafafa',
                }}
                {...props}
              >
                {children}
              </blockquote>
            );
          },
          h1: ({ children, ...props }: any) => <h2 style={{ marginTop: 12, marginBottom: 8 }} {...props}>{children}</h2>,
          h2: ({ children, ...props }: any) => <h3 style={{ marginTop: 12, marginBottom: 8 }} {...props}>{children}</h3>,
          h3: ({ children, ...props }: any) => <h4 style={{ marginTop: 10, marginBottom: 6 }} {...props}>{children}</h4>,
          p: ({ children, ...props }: any) => <p style={{ margin: '6px 0', lineHeight: 1.7 }} {...props}>{children}</p>,
          ul: ({ children, ...props }: any) => <ul style={{ paddingLeft: 22, margin: '6px 0' }} {...props}>{children}</ul>,
          ol: ({ children, ...props }: any) => <ol style={{ paddingLeft: 22, margin: '6px 0' }} {...props}>{children}</ol>,
          li: ({ children, ...props }: any) => <li style={{ margin: '2px 0' }} {...props}>{children}</li>,
          hr: (props: any) => <hr style={{ border: 'none', borderTop: '1px solid #e8e8e8', margin: '12px 0' }} {...props} />,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
};

// content 是唯一 prop;memo 化避免父组件因输入框等不相关 state 变化时
// 触发昂贵的 markdown + 代码高亮重渲染。
export default React.memo(MarkdownRenderer);
