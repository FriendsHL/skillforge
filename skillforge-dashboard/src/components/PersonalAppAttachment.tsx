import React, { useEffect, useMemo, useRef, useState } from 'react';
import { AppstoreOutlined, LoadingOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Modal, Tag } from 'antd';
import { getChatAttachmentBlob } from '../api';

const BRIDGE_SOURCE = 'skillforge-personal-app';
const OPEN_EXTERNAL_URL = 'open-external-url';

export function buildPersonalAppDocument(source: string): string {
  const bridge = `<script>(function(){document.addEventListener('click',function(event){var target=event.target&&event.target.closest?event.target.closest('[data-sf-url]'):null;if(!target){return;}var url=target.getAttribute('data-sf-url');if(!url){return;}event.preventDefault();event.stopPropagation();window.parent.postMessage({source:'${BRIDGE_SOURCE}',type:'${OPEN_EXTERNAL_URL}',url:url},'*');},true);})();<\/script>`;
  return /<\/body\s*>/i.test(source)
    ? source.replace(/<\/body\s*>/i, `${bridge}</body>`)
    : `${source}${bridge}`;
}

export function safeExternalURL(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    if ((url.protocol !== 'https:' && url.protocol !== 'http:') || url.username || url.password) {
      return null;
    }
    return url.toString();
  } catch {
    return null;
  }
}

interface PersonalAppAttachmentProps {
  attachmentId: string;
  filename: string;
  title?: string;
  caption?: string;
  userId: number;
  sessionId?: string;
}

const PersonalAppAttachment: React.FC<PersonalAppAttachmentProps> = ({
  attachmentId,
  filename,
  title,
  caption,
  userId,
  sessionId,
}) => {
  const [html, setHtml] = useState<string | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [retryAttempt, setRetryAttempt] = useState(0);
  const [open, setOpen] = useState(false);
  const [pendingExternalURL, setPendingExternalURL] = useState<string | null>(null);
  const iframeRef = useRef<HTMLIFrameElement>(null);

  useEffect(() => {
    let cancelled = false;
    setHtml(null);
    setLoadError(false);
    getChatAttachmentBlob(attachmentId, userId, sessionId)
      .then(async (response) => {
        const source = await response.data.text();
        if (!cancelled) setHtml(source);
      })
      .catch(() => {
        if (!cancelled) setLoadError(true);
      });
    return () => { cancelled = true; };
  }, [attachmentId, retryAttempt, sessionId, userId]);

  useEffect(() => {
    if (!open) return undefined;
    const receiveMessage = (event: MessageEvent) => {
      if (event.source !== iframeRef.current?.contentWindow) return;
      const payload = event.data;
      if (!payload || payload.source !== BRIDGE_SOURCE || payload.type !== OPEN_EXTERNAL_URL) return;
      const url = safeExternalURL(payload.url);
      if (url) setPendingExternalURL(url);
    };
    window.addEventListener('message', receiveMessage);
    return () => window.removeEventListener('message', receiveMessage);
  }, [open]);

  const document = useMemo(() => html == null ? undefined : buildPersonalAppDocument(html), [html]);
  const displayTitle = title?.trim() || caption?.trim() || filename;

  return (
    <div style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', gap: 6 }}>
      <Tag
        icon={loadError ? undefined : html == null ? <LoadingOutlined /> : <AppstoreOutlined />}
        color={loadError ? 'error' : 'processing'}
        style={{ margin: 0, padding: '5px 10px', borderRadius: 8 }}
      >
        {displayTitle}{loadError ? ' · Load failed' : html == null ? ' · Loading' : ''}
      </Tag>
      {loadError ? (
        <Button size="small" icon={<ReloadOutlined />} onClick={() => setRetryAttempt((value) => value + 1)}>
          Retry
        </Button>
      ) : (
        <Button
          size="small"
          type="primary"
          disabled={html == null}
          onClick={() => setOpen(true)}
          data-testid={`personal-app-open-${attachmentId}`}
        >
          Open Personal App
        </Button>
      )}
      {caption && <span style={{ color: 'var(--fg-3, #8a8a93)', fontSize: 11 }}>{caption}</span>}

      <Modal
        open={open}
        title={displayTitle}
        footer={null}
        width="min(1100px, calc(100vw - 32px))"
        destroyOnHidden
        onCancel={() => setOpen(false)}
      >
        {document && (
          <iframe
            ref={iframeRef}
            title={displayTitle}
            srcDoc={document}
            sandbox="allow-scripts"
            data-testid="personal-app-frame"
            style={{ width: '100%', height: 'min(72vh, 780px)', border: 0, borderRadius: 8 }}
          />
        )}
      </Modal>

      <Modal
        open={pendingExternalURL != null}
        title="Open external link?"
        okText="Open"
        cancelText="Cancel"
        onCancel={() => setPendingExternalURL(null)}
        onOk={() => {
          if (pendingExternalURL) window.open(pendingExternalURL, '_blank', 'noopener,noreferrer');
          setPendingExternalURL(null);
        }}
      >
        <p style={{ overflowWrap: 'anywhere' }}>{pendingExternalURL}</p>
      </Modal>
    </div>
  );
};

export default React.memo(PersonalAppAttachment);
