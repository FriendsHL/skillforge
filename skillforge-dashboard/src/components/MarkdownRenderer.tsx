import React, { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { CopyOutlined, CheckOutlined } from '@ant-design/icons';

interface MarkdownRendererProps {
  content: string;
}

const CopyButton: React.FC<{ text: string }> = ({ text }) => {
  const [copied, setCopied] = useState(false);
  const handleCopy = () => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };
  return (
    <button
      onClick={handleCopy}
      style={{
        position: 'absolute',
        top: 8,
        right: 8,
        background: 'rgba(255,255,255,0.1)',
        border: '1px solid rgba(255,255,255,0.2)',
        borderRadius: 4,
        color: '#ccc',
        cursor: 'pointer',
        padding: '2px 6px',
        fontSize: 12,
        display: 'flex',
        alignItems: 'center',
        gap: 4,
      }}
    >
      {copied ? <><CheckOutlined /> Copied</> : <><CopyOutlined /> Copy</>}
    </button>
  );
};

const MarkdownRenderer: React.FC<MarkdownRendererProps> = ({ content }) => {
  return (
    <div className="markdown-body" style={{ fontSize: 14, lineHeight: 1.7 }}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code({ inline, className, children, ...props }: any) {
            const match = /language-(\w+)/.exec(className || '');
            const codeStr = String(children).replace(/\n$/, '');
            if (!inline && match) {
              return (
                <div style={{ position: 'relative', margin: '12px 0' }}>
                  <div style={{
                    background: '#1e1e1e',
                    borderRadius: '8px 8px 0 0',
                    padding: '6px 12px',
                    fontSize: 11,
                    color: '#888',
                    borderBottom: '1px solid #333',
                    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                  }}>
                    {match[1]}
                  </div>
                  <CopyButton text={codeStr} />
                  <SyntaxHighlighter
                    style={oneDark as any}
                    language={match[1]}
                    PreTag="div"
                    customStyle={{
                      margin: 0,
                      borderRadius: '0 0 8px 8px',
                      fontSize: 13,
                      padding: '12px 16px',
                    }}
                    {...props}
                  >
                    {codeStr}
                  </SyntaxHighlighter>
                </div>
              );
            }
            if (!inline && codeStr.includes('\n')) {
              return (
                <div style={{ position: 'relative', margin: '12px 0' }}>
                  <CopyButton text={codeStr} />
                  <pre style={{
                    background: '#1e1e1e',
                    color: '#d4d4d4',
                    padding: '12px 16px',
                    borderRadius: 8,
                    fontSize: 13,
                    overflow: 'auto',
                    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                  }}>
                    <code>{codeStr}</code>
                  </pre>
                </div>
              );
            }
            return (
              <code
                className={className}
                style={{
                  background: 'rgba(135, 131, 120, 0.12)',
                  color: '#c7254e',
                  padding: '2px 6px',
                  borderRadius: 4,
                  fontSize: '0.88em',
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
              <a
                href={href}
                target="_blank"
                rel="noopener noreferrer"
                style={{ color: '#1677ff', textDecoration: 'none' }}
                {...props}
              >
                {children}
              </a>
            );
          },
          table({ children, ...props }: any) {
            return (
              <div style={{ overflowX: 'auto', margin: '12px 0', borderRadius: 8, border: '1px solid #e8e8e8' }}>
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
          thead({ children, ...props }: any) {
            return (
              <thead style={{ background: '#fafafa' }} {...props}>
                {children}
              </thead>
            );
          },
          th({ children, ...props }: any) {
            return (
              <th
                style={{
                  borderBottom: '2px solid #e8e8e8',
                  padding: '8px 12px',
                  textAlign: 'left',
                  fontWeight: 600,
                  fontSize: 12,
                  color: '#555',
                  whiteSpace: 'nowrap',
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
                  borderBottom: '1px solid #f0f0f0',
                  padding: '8px 12px',
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
                  borderLeft: '4px solid #1677ff',
                  padding: '8px 16px',
                  margin: '12px 0',
                  color: '#555',
                  background: '#f6f8fa',
                  borderRadius: '0 6px 6px 0',
                }}
                {...props}
              >
                {children}
              </blockquote>
            );
          },
          h1: ({ children, ...props }: any) => (
            <h2 style={{ marginTop: 20, marginBottom: 10, paddingBottom: 6, borderBottom: '1px solid #eee', fontSize: 20, fontWeight: 600 }} {...props}>{children}</h2>
          ),
          h2: ({ children, ...props }: any) => (
            <h3 style={{ marginTop: 18, marginBottom: 8, fontSize: 17, fontWeight: 600 }} {...props}>{children}</h3>
          ),
          h3: ({ children, ...props }: any) => (
            <h4 style={{ marginTop: 14, marginBottom: 6, fontSize: 15, fontWeight: 600 }} {...props}>{children}</h4>
          ),
          h4: ({ children, ...props }: any) => (
            <h5 style={{ marginTop: 12, marginBottom: 4, fontSize: 14, fontWeight: 600 }} {...props}>{children}</h5>
          ),
          p: ({ children, ...props }: any) => (
            <p style={{ margin: '8px 0', lineHeight: 1.75 }} {...props}>{children}</p>
          ),
          ul: ({ children, ...props }: any) => (
            <ul style={{ paddingLeft: 24, margin: '8px 0' }} {...props}>{children}</ul>
          ),
          ol: ({ children, ...props }: any) => (
            <ol style={{ paddingLeft: 24, margin: '8px 0' }} {...props}>{children}</ol>
          ),
          li: ({ children, ...props }: any) => (
            <li style={{ margin: '4px 0', lineHeight: 1.7 }} {...props}>{children}</li>
          ),
          hr: (props: any) => (
            <hr style={{ border: 'none', borderTop: '1px solid #e8e8e8', margin: '16px 0' }} {...props} />
          ),
          strong: ({ children, ...props }: any) => (
            <strong style={{ fontWeight: 600 }} {...props}>{children}</strong>
          ),
          img: ({ src, alt, ...props }: any) => (
            <img
              src={src}
              alt={alt}
              style={{ maxWidth: '100%', borderRadius: 8, margin: '8px 0' }}
              {...props}
            />
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
};

export default React.memo(MarkdownRenderer);
