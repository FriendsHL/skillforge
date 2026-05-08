import React, { useMemo, useState } from 'react';
import { Empty, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import {
  getSkillFiles,
  getSkillFileContent,
  type SkillFilesResponse,
  type SkillFileContentResponse,
} from '../../api';

interface SkillFileBrowserProps {
  skillId: number | string;
  userId: number;
}

interface TreeNode {
  name: string;
  path: string;        // full relative path
  isDir: boolean;
  size?: number;
  children?: Map<string, TreeNode>;
}

/**
 * V2.5 — file browser for a skill package.
 *
 * Skills are file packages (SKILL.md + scripts/ + references/ + assets/ + hooks/);
 * the legacy {@code SkillDetail.skillMd} only surfaced SKILL.md, hiding the rest.
 * This component walks the BE file-tree endpoint and lets the operator click into
 * any file to read its content.
 */
export const SkillFileBrowser: React.FC<SkillFileBrowserProps> = ({ skillId, userId }) => {
  const [selectedPath, setSelectedPath] = useState<string>('SKILL.md');

  const { data: filesData, isLoading } = useQuery<SkillFilesResponse>({
    queryKey: ['skill-files', skillId, userId],
    queryFn: () => getSkillFiles(skillId, userId).then((r) => r.data),
    enabled: skillId != null && userId > 0,
    staleTime: 30_000,
  });

  // Build a tree from flat path list.
  const rootChildren = useMemo<Map<string, TreeNode>>(() => {
    const root = new Map<string, TreeNode>();
    if (!filesData?.files) return root;
    for (const f of filesData.files) {
      const parts = f.path.split('/');
      let cursor = root;
      for (let i = 0; i < parts.length; i++) {
        const name = parts[i];
        const isLast = i === parts.length - 1;
        const fullPath = parts.slice(0, i + 1).join('/');
        let node = cursor.get(name);
        if (!node) {
          node = {
            name,
            path: fullPath,
            isDir: !isLast,
            size: isLast ? f.size : undefined,
            children: isLast ? undefined : new Map(),
          };
          cursor.set(name, node);
        }
        if (!isLast && node.children) cursor = node.children;
      }
    }
    return root;
  }, [filesData]);

  // Auto-select SKILL.md if available; otherwise first file.
  const fileExists = useMemo(() => {
    if (!filesData?.files) return false;
    return filesData.files.some((f) => f.path === selectedPath);
  }, [filesData, selectedPath]);

  React.useEffect(() => {
    if (filesData?.files?.length && !fileExists) {
      // Selected path doesn't exist (e.g. version switch) — fall back to SKILL.md or first file.
      const skillMd = filesData.files.find((f) => f.path === 'SKILL.md');
      setSelectedPath(skillMd ? 'SKILL.md' : filesData.files[0].path);
    }
  }, [filesData, fileExists]);

  const { data: contentData, isLoading: contentLoading } = useQuery<SkillFileContentResponse>({
    queryKey: ['skill-file-content', skillId, selectedPath, userId],
    queryFn: () => getSkillFileContent(skillId, selectedPath, userId).then((r) => r.data),
    enabled: skillId != null && userId > 0 && fileExists,
    staleTime: 60_000,
  });

  if (isLoading) {
    return (
      <div style={{ padding: 32, textAlign: 'center' }}>
        <Spin />
      </div>
    );
  }

  if (!filesData?.files?.length) {
    return (
      <Empty
        description={
          filesData?.error ?? 'No files found for this skill (the package directory is empty or missing on disk).'
        }
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '240px 1fr', gap: 12, height: '100%' }}>
      {/* Left: file tree */}
      <div
        style={{
          border: '1px solid var(--border-subtle, #2a2a31)',
          borderRadius: 6,
          background: 'var(--bg-base, #15151a)',
          padding: 8,
          overflowY: 'auto',
          fontFamily: 'var(--font-mono, monospace)',
          fontSize: 12,
        }}
      >
        <FileTreeNodes nodes={rootChildren} selectedPath={selectedPath} onSelect={setSelectedPath} depth={0} />
      </div>

      {/* Right: file content */}
      <div
        style={{
          border: '1px solid var(--border-subtle, #2a2a31)',
          borderRadius: 6,
          background: 'var(--bg-base, #15151a)',
          padding: 14,
          overflow: 'auto',
        }}
      >
        <div
          style={{
            fontFamily: 'var(--font-mono, monospace)',
            fontSize: 11,
            color: 'var(--fg-4, #8a8a93)',
            marginBottom: 10,
            wordBreak: 'break-all',
          }}
        >
          {selectedPath}
          {contentData?.size != null && ` · ${contentData.size} bytes`}
        </div>
        {contentLoading ? (
          <Spin />
        ) : contentData ? (
          <pre
            style={{
              margin: 0,
              whiteSpace: 'pre-wrap',
              fontFamily: 'var(--font-mono, monospace)',
              fontSize: 12.5,
              lineHeight: 1.55,
              color: 'var(--fg-1, #d4d4d8)',
            }}
          >
            {contentData.content}
          </pre>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Select a file to view" />
        )}
      </div>
    </div>
  );
};

/** Recursive renderer for tree nodes (folders collapse on click). */
const FileTreeNodes: React.FC<{
  nodes: Map<string, TreeNode>;
  selectedPath: string;
  onSelect: (path: string) => void;
  depth: number;
}> = ({ nodes, selectedPath, onSelect, depth }) => {
  // Sort: dirs first, then files; within each group alphabetically.
  const sorted = Array.from(nodes.values()).sort((a, b) => {
    if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
    return a.name.localeCompare(b.name);
  });
  return (
    <div>
      {sorted.map((node) => (
        <FileTreeNode
          key={node.path}
          node={node}
          selectedPath={selectedPath}
          onSelect={onSelect}
          depth={depth}
        />
      ))}
    </div>
  );
};

const FileTreeNode: React.FC<{
  node: TreeNode;
  selectedPath: string;
  onSelect: (path: string) => void;
  depth: number;
}> = ({ node, selectedPath, onSelect, depth }) => {
  const [open, setOpen] = useState(true);
  const isSelected = !node.isDir && node.path === selectedPath;
  return (
    <div>
      <div
        onClick={() => (node.isDir ? setOpen((v) => !v) : onSelect(node.path))}
        style={{
          padding: '3px 8px',
          paddingLeft: 8 + depth * 12,
          cursor: 'pointer',
          background: isSelected ? 'var(--accent-soft, rgba(99,102,241,0.18))' : 'transparent',
          color: isSelected ? 'var(--accent-primary, #8b8df5)' : 'var(--fg-2, #c0c0c8)',
          borderRadius: 3,
          userSelect: 'none',
          display: 'flex',
          alignItems: 'center',
          gap: 4,
        }}
      >
        <span style={{ fontSize: 10, color: 'var(--fg-4, #8a8a93)', width: 10 }}>
          {node.isDir ? (open ? '▾' : '▸') : ''}
        </span>
        <span>{node.isDir ? node.name + '/' : node.name}</span>
      </div>
      {node.isDir && open && node.children && (
        <FileTreeNodes
          nodes={node.children}
          selectedPath={selectedPath}
          onSelect={onSelect}
          depth={depth + 1}
        />
      )}
    </div>
  );
};
