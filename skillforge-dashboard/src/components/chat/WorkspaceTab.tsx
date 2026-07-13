import { useId, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  ArrowLeftOutlined,
  CloseOutlined,
  DownloadOutlined,
  FileExcelOutlined,
  FileImageOutlined,
  FileOutlined,
  FilePdfOutlined,
  FileTextOutlined,
  FileWordOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  LoadingOutlined,
  RightOutlined,
  RobotOutlined,
  SearchOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { getChatAttachmentBlob } from '../../api';
import {
  getWorkspaceContent,
  getWorkspaceEntries,
  type WorkspaceContentResponse,
  type WorkspaceEntry,
  type WorkspaceEntriesResponse,
} from '../../api/workspace';
import MarkdownRenderer from '../MarkdownRenderer';
import type { ChatAttachmentRef, ChatMessage } from '../ChatWindow';
import {
  collectWorkspaceAttachments,
  type WorkspaceAttachment,
} from './workspaceAttachments';
import './WorkspaceTab.css';

const MAX_VISIBLE_ITEMS = 50;
const DOWNLOAD_URL_LIFETIME_MS = 1_000;

interface WorkspaceTabProps {
  messages: ChatMessage[];
  sessionId?: string | null;
  sessionTitle?: string;
  userId?: number;
}

type WorkspaceView = 'session' | 'myspace';

function fileIcon(kind: ChatAttachmentRef['kind']) {
  switch (kind) {
    case 'image': return <FileImageOutlined />;
    case 'pdf': return <FilePdfOutlined />;
    case 'word': return <FileWordOutlined />;
    case 'excel': return <FileExcelOutlined />;
    case 'csv': return <FileTextOutlined />;
  }
}

function fileMeta(item: WorkspaceAttachment): string {
  if (item.kind === 'pdf' && item.pageCount) return `${item.pageCount} pages`;
  if (item.kind === 'excel' && item.sheetCount) return `${item.sheetCount} sheets`;
  return item.kind.toUpperCase();
}

function formatBytes(sizeBytes?: number): string {
  if (sizeBytes == null || !Number.isFinite(sizeBytes)) return 'File';
  if (sizeBytes < 1024) return `${sizeBytes} B`;
  if (sizeBytes < 1024 * 1024) {
    return `${(sizeBytes / 1024).toFixed(sizeBytes < 10 * 1024 ? 1 : 0)} KB`;
  }
  return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
}

function WorkspaceAttachmentRow({
  item,
  sessionId,
  userId,
}: {
  item: WorkspaceAttachment;
  sessionId: string;
  userId?: number;
}) {
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState(false);

  const download = async () => {
    if (downloading || userId == null) return;
    setDownloading(true);
    setError(false);
    try {
      const response = await getChatAttachmentBlob(item.attachmentId, userId, sessionId);
      const url = URL.createObjectURL(response.data);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = item.filename;
      anchor.rel = 'noopener noreferrer';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), DOWNLOAD_URL_LIFETIME_MS);
    } catch {
      setError(true);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="workspace-file-row">
      <span className={`workspace-file-icon kind-${item.kind}`} aria-hidden="true">
        {fileIcon(item.kind)}
      </span>
      <span className="workspace-file-copy">
        <span className="workspace-file-name" title={item.filename}>{item.filename}</span>
        <span className="workspace-file-meta">
          {fileMeta(item)}{item.caption ? ` · ${item.caption}` : ''}
        </span>
        {error && (
          <span className="workspace-file-error" role="alert">Download failed. Try again.</span>
        )}
      </span>
      <button
        type="button"
        className="workspace-file-action"
        aria-label={`Download ${item.filename}`}
        title={`Download ${item.filename}`}
        disabled={downloading || userId == null}
        onClick={() => void download()}
      >
        {downloading ? <LoadingOutlined spin /> : <DownloadOutlined />}
      </button>
    </div>
  );
}

function WorkspaceSection({
  title,
  items,
  sessionId,
  userId,
}: {
  title: string;
  items: WorkspaceAttachment[];
  sessionId: string;
  userId?: number;
}) {
  if (items.length === 0) return null;
  return (
    <section className="workspace-file-section">
      <div className="rail-section-title"><span>{title}</span><span>{items.length}</span></div>
      <div className="workspace-file-list">
        {items.map((item) => (
          <WorkspaceAttachmentRow
            key={item.attachmentId}
            item={item}
            sessionId={sessionId}
            userId={userId}
          />
        ))}
      </div>
    </section>
  );
}

function SessionWorkspace({
  messages,
  sessionId,
  sessionTitle = 'Current session',
  userId,
}: WorkspaceTabProps) {
  const allItems = useMemo(() => collectWorkspaceAttachments(messages), [messages]);
  const visibleItems = allItems.slice(-MAX_VISIBLE_ITEMS);
  const sources = visibleItems.filter((item) => item.origin === 'source');
  const generated = visibleItems.filter((item) => item.origin === 'generated');

  if (!sessionId) {
    return <div className="rail-empty-rd">Select a session to view its workspace.</div>;
  }

  return (
    <div className="workspace-session-view">
      <header className="workspace-tab-header">
        <div>
          <div className="workspace-tab-kicker">Session workspace</div>
          <h2>{sessionTitle}</h2>
          <p>Files referenced or produced in this session.</p>
        </div>
        <span className="workspace-tab-icon" aria-hidden="true"><FolderOpenOutlined /></span>
      </header>

      {allItems.length === 0 ? (
        <div className="workspace-empty">
          <FolderOpenOutlined aria-hidden="true" />
          <strong>No files in this session yet.</strong>
          <span>Uploaded and agent-produced files will appear here.</span>
        </div>
      ) : (
        <>
          <div className="workspace-summary" aria-label="Workspace file summary">
            <span><UploadOutlined />{sources.length} source{sources.length === 1 ? '' : 's'}</span>
            <span><RobotOutlined />{generated.length} generated</span>
          </div>
          {allItems.length > MAX_VISIBLE_ITEMS && (
            <p className="workspace-limit-note">
              Showing the {MAX_VISIBLE_ITEMS} most recent of {allItems.length} files.
            </p>
          )}
          <WorkspaceSection title="Sources" items={sources} sessionId={sessionId} userId={userId} />
          <WorkspaceSection title="Generated" items={generated} sessionId={sessionId} userId={userId} />
        </>
      )}
    </div>
  );
}

function Breadcrumbs({
  rootLabel,
  path,
  onNavigate,
}: {
  rootLabel: string;
  path: string;
  onNavigate: (path: string) => void;
}) {
  const parts = path ? path.split('/') : [];
  return (
    <nav className="workspace-breadcrumbs" aria-label="MySpace path">
      {parts.length === 0 ? (
        <span aria-current="page" title={rootLabel}>{rootLabel}</span>
      ) : (
        <button type="button" onClick={() => onNavigate('')} title={rootLabel}>{rootLabel}</button>
      )}
      {parts.map((part, index) => {
        const partPath = parts.slice(0, index + 1).join('/');
        const current = index === parts.length - 1;
        return (
          <span className="workspace-breadcrumb-part" key={partPath}>
            <RightOutlined aria-hidden="true" />
            {current ? (
              <span aria-current="page" title={part}>{part}</span>
            ) : (
              <button type="button" onClick={() => onNavigate(partPath)} title={part}>{part}</button>
            )}
          </span>
        );
      })}
    </nav>
  );
}

function WorkspaceBrowserRow({
  entry,
  onOpenFolder,
  onPreview,
}: {
  entry: WorkspaceEntry;
  onOpenFolder: (path: string) => void;
  onPreview: (path: string) => void;
}) {
  const isDirectory = entry.type === 'directory';
  const actionLabel = isDirectory
    ? `Open folder ${entry.name}`
    : entry.previewable
      ? `Preview ${entry.name}`
      : `${entry.name} preview unavailable`;
  return (
    <button
      type="button"
      className="workspace-browser-row"
      aria-label={actionLabel}
      title={entry.name}
      disabled={!isDirectory && !entry.previewable}
      onClick={() => isDirectory ? onOpenFolder(entry.path) : onPreview(entry.path)}
    >
      <span className="workspace-browser-icon" aria-hidden="true">
        {isDirectory ? <FolderOutlined /> : <FileOutlined />}
      </span>
      <span className="workspace-browser-copy">
        <span className="workspace-browser-name">{entry.name}</span>
        <span className="workspace-browser-meta">
          {isDirectory ? 'Folder' : entry.previewable ? formatBytes(entry.sizeBytes) : 'Preview unavailable'}
        </span>
      </span>
      {(isDirectory || entry.previewable) && (
        <RightOutlined className="workspace-browser-chevron" aria-hidden="true" />
      )}
    </button>
  );
}

function WorkspacePreview({
  path,
  cacheScope,
  onBack,
}: {
  path: string;
  cacheScope: number | 'authenticated';
  onBack: () => void;
}) {
  const contentQuery = useQuery<WorkspaceContentResponse>({
    queryKey: ['workspace', 'content', cacheScope, path],
    queryFn: () => getWorkspaceContent(path).then((response) => response.data),
    staleTime: 60_000,
  });
  const filename = path.split('/').pop() ?? path;
  const isMarkdown = /\.md$/i.test(filename);

  return (
    <section className="workspace-preview" aria-label={`Preview ${filename}`}>
      <header className="workspace-preview-header">
        <button type="button" className="workspace-icon-button" onClick={onBack} aria-label="Back to files">
          <ArrowLeftOutlined />
        </button>
        <div><strong title={filename}>{filename}</strong><span title={path}>{path}</span></div>
      </header>

      {contentQuery.isLoading && contentQuery.data === undefined && (
        <div className="workspace-browser-state" role="status">
          <LoadingOutlined spin aria-hidden="true" /><span>Loading preview...</span>
        </div>
      )}
      {contentQuery.isError && contentQuery.data === undefined && (
        <div className="workspace-browser-state is-error" role="alert">
          <strong>Could not preview this file.</strong>
          <button type="button" onClick={() => void contentQuery.refetch()} aria-label="Retry file preview">Retry</button>
        </div>
      )}
      {contentQuery.isError && contentQuery.data !== undefined && (
        <p className="workspace-refresh-notice" role="status" aria-live="polite">
          Could not refresh this preview. Showing previously loaded content.
        </p>
      )}
      {contentQuery.data && (
        <>
          {contentQuery.data.binary || contentQuery.data.content == null ? (
            <div className="workspace-browser-state">
              <FileOutlined aria-hidden="true" /><strong>Preview unavailable for this file.</strong>
            </div>
          ) : isMarkdown ? (
            <div className="workspace-markdown-preview">
              <MarkdownRenderer content={contentQuery.data.content} />
            </div>
          ) : (
            <pre className="workspace-text-preview" data-testid="workspace-text-preview">
              {contentQuery.data.content}
            </pre>
          )}
          {contentQuery.data.truncated && (
            <p className="workspace-preview-notice">Preview truncated for safety.</p>
          )}
        </>
      )}
    </section>
  );
}

function MySpaceBrowser({ userId }: { userId?: number }) {
  const [currentPath, setCurrentPath] = useState('');
  const [filter, setFilter] = useState('');
  const [previewPath, setPreviewPath] = useState<string | null>(null);
  const cacheScope = userId ?? 'authenticated';
  const entriesQuery = useQuery<WorkspaceEntriesResponse>({
    queryKey: ['workspace', 'entries', cacheScope, currentPath],
    queryFn: () => getWorkspaceEntries(currentPath).then((response) => response.data),
    staleTime: 30_000,
  });
  const visibleEntries = useMemo(() => {
    const normalizedFilter = filter.trim().toLocaleLowerCase();
    const entries = entriesQuery.data?.entries ?? [];
    return normalizedFilter
      ? entries.filter((entry) => entry.name.toLocaleLowerCase().includes(normalizedFilter))
      : entries;
  }, [entriesQuery.data?.entries, filter]);

  const navigate = (path: string) => {
    setCurrentPath(path);
    setFilter('');
    setPreviewPath(null);
  };

  if (previewPath) {
    return (
      <WorkspacePreview
        path={previewPath}
        cacheScope={cacheScope}
        onBack={() => setPreviewPath(null)}
      />
    );
  }

  return (
    <div className="workspace-browser">
      <header className="workspace-browser-header">
        <div className="workspace-tab-kicker">Repository files</div>
        <h2>MySpace</h2>
        <p>Browse authorized files without adding them to the conversation.</p>
      </header>
      <div className="workspace-browser-tools">
        <div className="workspace-location-row">
          <FolderOpenOutlined aria-hidden="true" />
          <Breadcrumbs
            rootLabel={entriesQuery.data?.rootLabel || 'MySpace'}
            path={currentPath}
            onNavigate={navigate}
          />
          {entriesQuery.data && (
            <span className="workspace-entry-count" aria-label={`${visibleEntries.length} visible entries`}>
              {visibleEntries.length}
            </span>
          )}
        </div>
        <div className="workspace-filter">
          <SearchOutlined aria-hidden="true" />
          <input
            type="search"
            aria-label="Filter current directory"
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            placeholder="Filter files"
          />
          {filter ? (
            <button
              type="button"
              aria-label="Clear file filter"
              title="Clear filter"
              onClick={() => setFilter('')}
            >
              <CloseOutlined />
            </button>
          ) : (
            <span className="workspace-filter-spacer" aria-hidden="true" />
          )}
        </div>
      </div>

      {entriesQuery.isLoading && entriesQuery.data === undefined && (
        <div className="workspace-browser-state" role="status">
          <LoadingOutlined spin aria-hidden="true" /><span>Loading folder...</span>
        </div>
      )}
      {entriesQuery.isError && entriesQuery.data === undefined && (
        <div className="workspace-browser-state is-error" role="alert">
          <strong>Could not load this folder.</strong>
          <button type="button" onClick={() => void entriesQuery.refetch()} aria-label="Retry loading folder">Retry</button>
        </div>
      )}
      {entriesQuery.isError && entriesQuery.data !== undefined && (
        <p className="workspace-refresh-notice" role="status" aria-live="polite">
          Could not refresh this folder. Showing previously loaded files.
        </p>
      )}
      {entriesQuery.data && visibleEntries.length === 0 && (
        <div className="workspace-browser-state">
          <FolderOpenOutlined aria-hidden="true" />
          <strong>{filter.trim() ? 'No matching files.' : 'This folder is empty.'}</strong>
        </div>
      )}
      {entriesQuery.data && visibleEntries.length > 0 && (
        <>
          <div className="workspace-browser-list">
            {visibleEntries.map((entry) => (
              <WorkspaceBrowserRow
                key={entry.path}
                entry={entry}
                onOpenFolder={navigate}
                onPreview={setPreviewPath}
              />
            ))}
          </div>
          {entriesQuery.data.truncated && (
            <p className="workspace-directory-notice">Showing the first entries in this folder.</p>
          )}
        </>
      )}
    </div>
  );
}

function WorkspaceTab(props: WorkspaceTabProps) {
  const [view, setView] = useState<WorkspaceView>('session');
  const sessionTabRef = useRef<HTMLButtonElement>(null);
  const mySpaceTabRef = useRef<HTMLButtonElement>(null);
  const id = useId();

  const selectView = (next: WorkspaceView, focus = false) => {
    setView(next);
    if (focus) {
      window.requestAnimationFrame(() => {
        (next === 'session' ? sessionTabRef : mySpaceTabRef).current?.focus();
      });
    }
  };
  const handleTabKeyDown = (event: React.KeyboardEvent, active: WorkspaceView) => {
    let next: WorkspaceView | null = null;
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      next = active === 'session' ? 'myspace' : 'session';
    } else if (event.key === 'Home') {
      next = 'session';
    } else if (event.key === 'End') {
      next = 'myspace';
    }
    if (next) {
      event.preventDefault();
      selectView(next, true);
    }
  };

  return (
    <div className="workspace-tab">
      <div className="workspace-view-tabs" role="tablist" aria-label="Workspace views">
        <button
          ref={sessionTabRef}
          id={`${id}-session-tab`}
          type="button"
          role="tab"
          aria-selected={view === 'session'}
          aria-controls={`${id}-session-panel`}
          tabIndex={view === 'session' ? 0 : -1}
          onClick={() => selectView('session')}
          onKeyDown={(event) => handleTabKeyDown(event, 'session')}
        >
          This session
        </button>
        <button
          ref={mySpaceTabRef}
          id={`${id}-myspace-tab`}
          type="button"
          role="tab"
          aria-selected={view === 'myspace'}
          aria-controls={`${id}-myspace-panel`}
          tabIndex={view === 'myspace' ? 0 : -1}
          onClick={() => selectView('myspace')}
          onKeyDown={(event) => handleTabKeyDown(event, 'myspace')}
        >
          MySpace
        </button>
      </div>

      {view === 'session' ? (
        <div id={`${id}-session-panel`} role="tabpanel" aria-labelledby={`${id}-session-tab`}>
          <SessionWorkspace {...props} />
        </div>
      ) : (
        <div id={`${id}-myspace-panel`} role="tabpanel" aria-labelledby={`${id}-myspace-tab`}>
          <MySpaceBrowser userId={props.userId} />
        </div>
      )}
    </div>
  );
}

export default WorkspaceTab;
