import React, { useEffect, useRef, useState } from 'react';
import { message } from 'antd';
import { CLOSE_ICON, PLUS_ICON } from './icons';

interface NewSkillModalProps {
  onClose: () => void;
  onUpload: (file: File) => void;
  uploading: boolean;
}

export const NewSkillModal: React.FC<NewSkillModalProps> = ({ onClose, onUpload, uploading }) => {
  const [mode, setMode] = useState<'upload' | 'blank'>('upload');
  const [file, setFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const onFile = (f: File | undefined) => {
    if (!f) return;
    if (!/\.(zip|tar|tgz)$/i.test(f.name)) { message.warning('Only .zip files are supported'); return; }
    setFile(f);
  };

  return (
    <div className="sf-modal-scrim" onClick={onClose}>
      <div className="sf-modal" onClick={e => e.stopPropagation()}>
        <div className="sf-modal-h">
          <h3>New skill</h3>
          <button className="sf-icon-btn" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
        </div>

        <div className="sf-modal-tabs">
          <button className={`sf-modal-tab-btn ${mode === 'upload' ? 'on' : ''}`} onClick={() => setMode('upload')}>Upload bundle</button>
          <button className={`sf-modal-tab-btn ${mode === 'blank' ? 'on' : ''}`} onClick={() => setMode('blank')}>Start blank</button>
        </div>

        <div className="sf-modal-body">
          {mode === 'upload' && (
            <>
              <div
                className={`sf-upload ${dragOver ? 'drag' : ''} ${file ? 'has-file' : ''}`}
                onClick={() => fileRef.current?.click()}
                onDragOver={e => { e.preventDefault(); setDragOver(true); }}
                onDragLeave={() => setDragOver(false)}
                onDrop={e => { e.preventDefault(); setDragOver(false); onFile(e.dataTransfer.files[0]); }}
              >
                <input ref={fileRef} type="file" accept=".zip" style={{ display: 'none' }} onChange={e => onFile(e.target.files?.[0])} />
                {!file ? (
                  <>
                    <div className="sf-upload-icon">{PLUS_ICON}</div>
                    <div className="sf-upload-t">Drop a <b>.zip</b> skill bundle here</div>
                    <div className="sf-upload-s">or click to browse</div>
                    <div className="sf-upload-hint">
                      Bundle must contain a <code>SKILL.md</code> manifest at the root.
                    </div>
                  </>
                ) : (
                  <div className="sf-upload-ok">
                    <div>
                      <div className="sf-upload-filename">{file.name}</div>
                      <div className="sf-upload-filemeta">{(file.size / 1024).toFixed(1)} KB</div>
                    </div>
                    <button className="sf-mini-btn" onClick={e => { e.stopPropagation(); setFile(null); }}>replace</button>
                  </div>
                )}
              </div>
            </>
          )}

          {mode === 'blank' && (
            <div className="sf-empty-state" style={{ padding: '30px 20px' }}>
              Blank skill creation requires the CLI.<br />
              Use <code style={{ fontFamily: 'var(--font-mono)', background: 'var(--bg-hover)', padding: '2px 6px', borderRadius: 3 }}>skillforge skill init</code> to scaffold a new skill.
            </div>
          )}
        </div>

        <div className="sf-modal-f">
          <button className="btn-ghost-sf" onClick={onClose}>Cancel</button>
          <button
            className="btn-primary-sf"
            disabled={!file || uploading}
            onClick={() => file && onUpload(file)}
          >
            {uploading ? 'Uploading…' : 'Install skill'}
          </button>
        </div>
      </div>
    </div>
  );
};
