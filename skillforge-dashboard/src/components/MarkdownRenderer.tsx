import React, { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { CopyOutlined, CheckOutlined } from '@ant-design/icons';
import 'github-markdown-css/github-markdown-light.css';

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
        background: 'rgba(255,255,255,0.15)',
        border: '1px solid rgba(255,255,255,0.25)',
        borderRadius: 4,
        color: '#ccc',
        cursor: 'pointer',
        padding: '2px 8px',
        fontSize: 12,
        display: 'flex',
        alignItems: 'center',
        gap: 4,
        zIndex: 1,
      }}
    >
      {copied ? <><CheckOutlined /> Copied</> : <><CopyOutlined /> Copy</>}
    </button>
  );
};

const MarkdownRenderer: React.FC<MarkdownRendererProps> = ({ content }) => {
  return (
    <div className="markdown-body" style={{ fontSize: 14, background: 'transparent' }}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a({ href, children, ...props }: React.ComponentPropsWithoutRef<'a'>) {
            const safe = href && /^(https?:\/\/|\/|#)/.test(href) ? href : '#';
            return (
              <a href={safe} target="_blank" rel="noopener noreferrer" {...props}>
                {children}
              </a>
            );
          },
          code({ inline, className, children, ...props }: any) {
            const match = /language-(\w+)/.exec(className || '');
            const codeStr = String(children).replace(/\n$/, '');
            if (!inline && match) {
              return (
                <div style={{ position: 'relative', margin: '12px 0' }}>
                  <div style={{
                    background: '#1e1e1e',
                    borderRadius: '8px 8px 0 0',
                    padding: '4px 12px',
                    fontSize: 11,
                    color: '#999',
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
              <code className={className} {...props}>
                {children}
              </code>
            );
          },
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
};

export default React.memo(MarkdownRenderer);
