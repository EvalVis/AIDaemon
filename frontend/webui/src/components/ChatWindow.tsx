import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import type { ChatMessage, Conversation, FileAttachment, PendingToolApproval, Provider } from '../types';
import type { StreamingContent, StreamPart } from '../App';
import * as api from '../api';

type DisplayMessage = ChatMessage | { participant: string; parts: StreamPart[] };

interface ChatWindowProps {
  headerTitle: string;
  conversation: Conversation | null;
  providers: Provider[];
  sending: boolean;
  streaming: StreamingContent | null;
  lastStreamedContent: { reasoning: string; parts: StreamPart[] } | null;
  inputDraft: string;
  onInputDraftChange: (value: string) => void;
  onSend: (message: string, attachments?: FileAttachment[]) => void;
  onUpdateProvider: (conversationId: string, providerId: string | null) => void;
  pendingApprovals: PendingToolApproval[];
  onApproveTool: (approvalId: string, note: string) => void;
  onRejectTool: (approvalId: string, note: string) => void;
}

const PREVIEW_LENGTH = 120;

function formatTime(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => n.toString().padStart(2, '0');
  const pad3 = (n: number) => n.toString().padStart(3, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad3(d.getMilliseconds())}`;
}

function participantDisplayName(participant: string): string {
  return participant || 'user';
}

function getMessageParticipant(msg: DisplayMessage): string {
  return msg.participant;
}

function getMessagePlainText(msg: DisplayMessage, includeThinking: boolean): string {
  if ('parts' in msg && msg.parts != null) {
    return msg.parts
      .filter((p) => p.type === 'answer' || (includeThinking && p.type === 'reasoning'))
      .map((p) => p.content)
      .join(' ')
      .trim();
  }
  if ('content' in msg) return msg.content;
  return '';
}

function MicIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
      <rect x="4.5" y="0.5" width="5" height="8" rx="2.5" fill="currentColor" stroke="none" />
      <path d="M2 6.5a5 5 0 0010 0" />
      <line x1="7" y1="11.5" x2="7" y2="13.5" />
      <line x1="4.5" y1="13.5" x2="9.5" y2="13.5" />
    </svg>
  );
}

function SpeakerIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M1.5 4.5H3.5L7 1.5V10.5L3.5 7.5H1.5Z" fill="currentColor" stroke="none" />
      <path d="M9 3.5a4 4 0 010 5" />
    </svg>
  );
}

function PaperclipIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12.5 6.5L6 13a4 4 0 01-5.657-5.657l7-7a2.5 2.5 0 013.536 3.536L4.5 10.5a1 1 0 01-1.414-1.414L9 3" />
    </svg>
  );
}

function FileIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M7 1H3a1 1 0 00-1 1v8a1 1 0 001 1h6a1 1 0 001-1V4L7 1z" />
      <path d="M7 1v3h3" />
    </svg>
  );
}

function FileChip({
  file,
  onRemove,
  onClick,
}: {
  file: { id: string; name: string; mimeType: string };
  onRemove?: () => void;
  onClick?: () => void;
}) {
  return (
    <div
      className={`inline-flex items-center gap-1 py-0.5 pl-2 pr-1 rounded-md border border-border bg-bg-input text-text-dim text-xs max-w-[160px] ${onClick ? 'cursor-pointer hover:border-accent hover:text-text-bright' : ''}`}
      title={file.name}
      onClick={onClick}
    >
      <FileIcon />
      <span className="truncate">{file.name}</span>
      {onRemove && (
        <button
          className="ml-0.5 text-text-dim hover:text-danger text-[0.7rem] leading-none cursor-pointer shrink-0"
          onClick={(e) => { e.stopPropagation(); onRemove(); }}
          title="Remove"
        >
          ✕
        </button>
      )}
    </div>
  );
}

type DiffLine =
  | { type: 'added'; content: string }
  | { type: 'deleted'; content: string }
  | { type: 'unchanged'; content: string };

function computeDiff(oldContent: string | null | undefined, newContent: string | null | undefined,
                     operation: PendingToolApproval['operation']): DiffLine[] {
  if (operation === 'CREATE') {
    return (newContent ?? '').split('\n').map((l) => ({ type: 'added', content: l }));
  }
  if (operation === 'DELETE') {
    return (oldContent ?? '').split('\n').map((l) => ({ type: 'deleted', content: l }));
  }
  const oldLines = (oldContent ?? '').split('\n');
  const newLines = (newContent ?? '').split('\n');
  if (oldLines.length * newLines.length > 200_000) {
    return [
      ...oldLines.map((l) => ({ type: 'deleted' as const, content: l })),
      ...newLines.map((l) => ({ type: 'added' as const, content: l })),
    ];
  }
  const m = oldLines.length, n = newLines.length;
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));
  for (let i = 1; i <= m; i++)
    for (let j = 1; j <= n; j++)
      dp[i][j] = oldLines[i - 1] === newLines[j - 1] ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1]);
  const result: DiffLine[] = [];
  let i = m, j = n;
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
      result.unshift({ type: 'unchanged', content: oldLines[i - 1] }); i--; j--;
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      result.unshift({ type: 'added', content: newLines[j - 1] }); j--;
    } else {
      result.unshift({ type: 'deleted', content: oldLines[i - 1] }); i--;
    }
  }
  return result;
}

function ToolApprovalCard({
  approval,
  onApprove,
  onReject,
}: {
  approval: PendingToolApproval;
  onApprove: (note: string) => void;
  onReject: (note: string) => void;
}) {
  const [expanded, setExpanded] = useState(true);
  const [note, setNote] = useState('');
  const isFileOp = Object.hasOwn(approval, 'operation') && approval.operation != null;

  const diff = isFileOp ? computeDiff(approval.oldContent, approval.newContent, approval.operation) : null;
  const addedCount = diff ? diff.filter((l) => l.type === 'added').length : 0;
  const deletedCount = diff ? diff.filter((l) => l.type === 'deleted').length : 0;

  return (
    <div className="self-stretch mx-0 py-3 px-4 rounded-lg border border-accent/50 bg-accent/10 text-sm">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-[0.6875rem] font-semibold uppercase tracking-wide text-accent">Tool approval required</span>
        <span className="ml-1 font-mono text-text-bright text-xs bg-bg-input px-2 py-0.5 rounded border border-border">{approval.toolName}</span>
        {isFileOp && (addedCount > 0 || deletedCount > 0) && (
          <span className="text-[0.6875rem]">
            {addedCount > 0 && <span className="text-green-400">+{addedCount}</span>}
            {addedCount > 0 && deletedCount > 0 && <span className="text-text-dim mx-0.5">/</span>}
            {deletedCount > 0 && <span className="text-red-400">-{deletedCount}</span>}
          </span>
        )}
      </div>

      {isFileOp && approval.path && (
        <div className="mb-2">
          <span className="font-mono text-xs text-text-bright bg-bg-input px-2 py-0.5 rounded border border-border truncate max-w-full inline-block">
            {approval.path}
          </span>
        </div>
      )}

      <div className="mb-3">
        <button
          className="text-[0.6875rem] text-text-dim hover:text-text-bright transition-colors duration-100 flex items-center gap-1"
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? '▾' : '▸'} {isFileOp ? 'Diff' : 'Input'}
        </button>
        {expanded && (
          isFileOp && diff ? (
            <div className="mt-1.5 rounded-lg border border-border overflow-hidden max-h-[400px] overflow-y-auto">
              {diff.length === 0 ? (
                <div className="p-2 text-xs text-text-dim font-mono">(no changes)</div>
              ) : (
                <table className="w-full border-collapse text-xs font-mono">
                  <tbody>
                    {diff.map((line, idx) => (
                      <tr key={idx} className={line.type === 'added' ? 'bg-green-950/60' : line.type === 'deleted' ? 'bg-red-950/60' : ''}>
                        <td className="w-6 text-center select-none text-[0.65rem] px-1 py-0 border-r border-border text-text-dim">
                          {line.type === 'added' ? '+' : line.type === 'deleted' ? '−' : ' '}
                        </td>
                        <td className={`px-2 py-0 whitespace-pre break-all leading-5 ${line.type === 'added' ? 'text-green-300' : line.type === 'deleted' ? 'text-red-300' : 'text-text-dim'}`}>
                          {line.content}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          ) : (
            <pre className="mt-1.5 p-2 bg-bg rounded-lg text-xs text-text-dim whitespace-pre-wrap break-words max-h-[160px] overflow-y-auto border border-border">
              {(() => {
                try { return JSON.stringify(JSON.parse(approval.toolInput), null, 2); }
                catch { return approval.toolInput; }
              })()}
            </pre>
          )
        )}
      </div>
      <textarea
        value={note}
        onChange={(e) => setNote(e.target.value)}
        placeholder="Optional note (appended to tool output)…"
        rows={2}
        className="w-full mb-3 py-1.5 px-2.5 bg-bg text-text text-xs border border-border rounded-lg outline-none focus:border-accent resize-none placeholder:text-text-dim"
      />
      <div className="flex gap-2">
        <button
          className="py-1.5 px-4 bg-accent text-white rounded-lg text-xs font-medium cursor-pointer transition-colors duration-150 hover:bg-accent-hover"
          onClick={() => onApprove(note)}
        >
          Approve
        </button>
        <button
          className="py-1.5 px-4 bg-bg-input text-danger border border-danger/50 rounded-lg text-xs font-medium cursor-pointer transition-colors duration-150 hover:bg-danger/10"
          onClick={() => onReject(note)}
        >
          Reject
        </button>
      </div>
    </div>
  );
}

function MessageEntry({
  msg,
  speaking,
  onSpeak,
}: {
  msg: DisplayMessage;
  speaking?: boolean;
  onSpeak?: () => void;
}) {
  const participant = getMessageParticipant(msg);
  const hasParts = 'parts' in msg && msg.parts != null;
  const userParts = participant === 'user' && 'content' in msg ? parseUserParts(msg.content) : null;
  const [collapsed, setCollapsed] = useState(participant === 'tool' && !hasParts);
  const content = 'content' in msg ? msg.content : '';
  const preview = content.slice(0, PREVIEW_LENGTH) + (content.length > PREVIEW_LENGTH ? '…' : '');
  const timeStr = 'timestampMillis' in msg && msg.timestampMillis != null && msg.timestampMillis > 0
    ? formatTime(msg.timestampMillis) : null;
  const displayName = participantDisplayName(participant);
  const canSpeak = participant !== 'tool';

  const messageStyles =
    participant === 'user'
      ? 'max-w-[80%] py-2.5 px-3.5 rounded-lg text-sm leading-relaxed whitespace-pre-wrap break-words self-end bg-user-bg text-text-bright'
      : participant === 'tool'
        ? 'max-w-[90%] py-2.5 px-3.5 rounded-lg text-xs leading-relaxed whitespace-pre-wrap break-words self-start bg-tool-bg border border-tool-border font-mono'
        : 'max-w-[80%] py-2.5 px-3.5 rounded-lg text-sm leading-relaxed whitespace-pre-wrap break-words self-start bg-assistant-bg border border-border';

  return (
    <div className={`${messageStyles}${collapsed ? ' pb-0' : ''}`}>
      <div
        className="flex items-baseline gap-2 cursor-pointer select-none"
        onClick={() => setCollapsed((v) => !v)}
      >
        <span className={`text-[0.6875rem] font-semibold tracking-wide text-text-dim shrink-0 ${['user', 'assistant', 'tool'].includes(displayName.toLowerCase()) ? 'uppercase' : ''}`}>
          {displayName}
        </span>
        {timeStr != null && (
          <span className="text-[0.6875rem] tabular-nums text-text-dim opacity-85">{timeStr}</span>
        )}
        {collapsed && (
          <span className="flex-1 text-[0.8125rem] text-text-dim whitespace-nowrap overflow-hidden text-ellipsis">
            {preview}
          </span>
        )}
        {canSpeak && onSpeak && (
          <button
            className={`flex items-center justify-center w-6 h-6 shrink-0 rounded-md border text-[0.65rem] font-medium cursor-pointer transition-all duration-150 ${
              speaking
                ? 'border-accent text-accent bg-bg-active'
                : 'border-border bg-bg-input/60 text-text-dim hover:border-accent hover:text-accent hover:bg-bg-hover'
            }`}
            title={speaking ? 'Stop reading' : 'Read aloud'}
            onClick={(e) => { e.stopPropagation(); onSpeak(); }}
          >
            <SpeakerIcon />
          </button>
        )}
        <button
          className="flex items-center justify-center w-6 h-6 shrink-0 ml-auto rounded-md border border-border bg-bg-input/60 text-text-dim text-[0.65rem] font-medium cursor-pointer transition-all duration-150 hover:border-accent hover:text-accent hover:bg-bg-hover"
          title={collapsed ? 'Expand' : 'Collapse'}
        >
          {collapsed ? '▸' : '▾'}
        </button>
      </div>
      {!collapsed && (
        <div className="text-text-bright mt-1.5 min-w-0 break-words">
          {hasParts
            ? (msg as { parts: StreamPart[] }).parts.map((part, i) =>
                part.type === 'tool' ? (
                  <details key={i} className="message-tool-part my-2 py-2 px-2.5 bg-tool-bg border border-tool-border rounded-lg text-tool-text max-w-full overflow-hidden" open>
                    <summary className="flex items-center gap-2 cursor-pointer text-xs font-semibold uppercase text-tool-summary list-none [&::-webkit-details-marker]:hidden [&::marker]:hidden">
                      <span>Tool</span>
                      <span className="btn-msg-toggle flex items-center justify-center w-6 h-6 ml-auto shrink-0 rounded-md border border-tool-border bg-tool-bg/80 text-[0.65rem] font-medium leading-none transition-all duration-150 hover:border-tool-summary hover:opacity-90" />
                    </summary>
                    <pre className="mt-1.5 font-mono text-xs text-tool-text whitespace-pre-wrap break-words max-w-full overflow-x-auto overflow-y-hidden">
                      {part.content}
                    </pre>
                  </details>
                ) : part.type === 'reasoning' ? (
                  <details key={i} className="mb-2" open>
                    <summary className="cursor-pointer text-text-dim text-xs font-semibold uppercase">
                      Thinking
                    </summary>
                    <pre className="mt-1.5 p-2 bg-bg rounded-lg text-xs text-text-dim whitespace-pre-wrap break-words max-h-[200px] overflow-y-auto">
                      {part.content}
                    </pre>
                  </details>
                ) : (
                  <span key={i}>{renderAnswerContent(part.content)}</span>
                )
              )
            : userParts
              ? userParts.map((p, i) =>
                  p.type === 'file' ? (
                    <FileChip key={i} file={p} onClick={() => window.open(`/api/files/${p.id}`, '_blank')} />
                  ) : (
                    <span key={i}>{p.content}</span>
                  )
                )
              : (
                <>
                  {'files' in msg && (msg as ChatMessage).files && (msg as ChatMessage).files!.length > 0 && (
                    <div className="flex flex-wrap gap-1 mb-1.5">
                      {(msg as ChatMessage).files!.map((f) => (
                        <FileChip key={f.id} file={f} onClick={() => window.open(`/api/files/${f.id}`, '_blank')} />
                      ))}
                    </div>
                  )}
                  {content}
                </>
              )}
        </div>
      )}
    </div>
  );
}

function renderAnswerContent(content: string): ReactNode {
  const imgRegex = /!\[([^\]]*)\]\((\/api\/files\/[^)]+)\)/g;
  const nodes: React.ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = imgRegex.exec(content)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(<span key={lastIndex}>{content.slice(lastIndex, match.index)}</span>);
    }
    nodes.push(
      <img
        key={match.index}
        src={match[2]}
        alt={match[1]}
        className="max-w-full rounded-lg my-2 block"
        style={{ maxHeight: '512px' }}
      />
    );
    lastIndex = imgRegex.lastIndex;
  }
  if (lastIndex < content.length) {
    nodes.push(<span key={lastIndex}>{content.slice(lastIndex)}</span>);
  }
  return nodes.length > 0 ? <>{nodes}</> : content;
}

function parseStructuredContent(content: string): StreamPart[] | null {
  if (!content || typeof content !== 'string') return null;
  const trimmed = content.trim();
  if (!trimmed.startsWith('{"parts":')) return null;
  try {
    const data = JSON.parse(content) as { parts?: Array<{ type: string; content: string }> };
    if (!Array.isArray(data.parts)) return null;
    const parts: StreamPart[] = data.parts
      .filter((p) => p && (p.type === 'answer' || p.type === 'tool' || p.type === 'reasoning') && typeof p.content === 'string')
      .map((p) => ({ type: p.type as 'answer' | 'tool' | 'reasoning', content: p.content }));
    return parts.length > 0 ? parts : null;
  } catch {
    return null;
  }
}

function filterParts(parts: StreamPart[], hideToolsAndThinking: boolean): StreamPart[] {
  if (!hideToolsAndThinking) return parts;
  const answerParts = parts.filter((p) => p.type === 'answer');
  return answerParts.map((p, i, arr) => ({
    ...p,
    content: p.content + (i < arr.length - 1 ? '\n\n' : ''),
  }));
}

function getDisplayMessages(
  messages: ChatMessage[],
  hideToolsAndThinking: boolean,
  lastStreamedContent: { reasoning: string; parts: StreamPart[] } | null,
  conversation: Conversation | null
): DisplayMessage[] {
  let list: DisplayMessage[] = messages.map((msg) => {
    if (getMessageParticipant(msg) !== 'user' && getMessageParticipant(msg) !== 'tool') {
      const parts = parseStructuredContent(msg.content);
      if (parts) return { participant: msg.participant, parts: filterParts(parts, hideToolsAndThinking) };
    }
    return msg;
  });
  if (lastStreamedContent && list.length > 0) {
    const lastUserIdx = Math.max(...list.map((msg, i) => (getMessageParticipant(msg) === 'user' ? i : -1)));
    const afterLastUser = list.slice(lastUserIdx + 1);
    if (afterLastUser.length === 1 && getMessageParticipant(afterLastUser[0]) !== 'user' && getMessageParticipant(afterLastUser[0]) !== 'tool') {
      const parts: StreamPart[] = lastStreamedContent.reasoning
        ? [{ type: 'reasoning', content: lastStreamedContent.reasoning }, ...lastStreamedContent.parts]
        : lastStreamedContent.parts;
      const replyingParticipant = conversation?.participant1 && conversation?.participant2
        ? (conversation.participant1 === 'user' ? conversation.participant2 : conversation.participant2 === 'user' ? conversation.participant1 : conversation.participant2)
        : 'assistant';
      list = [...list.slice(0, lastUserIdx + 1), { participant: replyingParticipant, parts: filterParts(parts, hideToolsAndThinking) }];
    }
  }
  return list;
}

// CSS class applied to inline file chips inside the contentEditable input div.
// Must match classes used in JSX FileChip to survive Tailwind tree-shaking.
const FILE_CHIP_CLASS =
  'inline-flex items-center gap-1 py-0.5 pl-1.5 pr-1 rounded border border-border bg-bg-input text-text-dim text-xs mx-0.5 align-middle select-none';

const BLOCK_TAGS = new Set(['DIV', 'P', 'LI', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6']);

function walkPlainText(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? '';
  if (node.nodeType !== Node.ELEMENT_NODE) return '';
  const el = node as HTMLElement;
  if (el.dataset.fileId) return '';
  if (el.tagName === 'BR') return '\n';
  const isBlock = BLOCK_TAGS.has(el.tagName);
  const parts: string[] = [];
  el.childNodes.forEach((child, i) => {
    const s = walkPlainText(child);
    if (isBlock && i > 0 && s) parts.push('\n');
    parts.push(s);
  });
  return parts.join('');
}

function editablePlainText(el: HTMLDivElement): string {
  return walkPlainText(el);
}

function editableHasContent(el: HTMLDivElement): boolean {
  if (editablePlainText(el).trim()) return true;
  return Array.from(el.childNodes).some(
    (n) => n.nodeType === Node.ELEMENT_NODE && !!(n as HTMLElement).dataset.fileId
  );
}

type UserPart = { type: 'text'; content: string } | { type: 'file'; id: string; name: string; mimeType: string };

function parseUserParts(content: string): UserPart[] | null {
  if (!content || !content.trim().startsWith('{"user_parts":')) return null;
  try {
    const data = JSON.parse(content) as { user_parts?: Array<{ type: string; content?: string; id?: string; name?: string; mimeType?: string }> };
    if (!Array.isArray(data.user_parts)) return null;
    return data.user_parts
      .filter((p) => p.type === 'text' || p.type === 'file')
      .map((p) =>
        p.type === 'file'
          ? { type: 'file' as const, id: p.id ?? '', name: p.name ?? '', mimeType: p.mimeType ?? '' }
          : { type: 'text' as const, content: p.content ?? '' }
      );
  } catch {
    return null;
  }
}

function appendTextPart(rawParts: UserPart[], text: string): void {
  if (!text) return;
  const last = rawParts[rawParts.length - 1];
  if (last?.type === 'text') {
    (last as { type: 'text'; content: string }).content += text;
  } else {
    rawParts.push({ type: 'text', content: text });
  }
}

function walkEditableContent(node: Node, rawParts: UserPart[], files: FileAttachment[]): void {
  if (node.nodeType === Node.TEXT_NODE) {
    appendTextPart(rawParts, node.textContent ?? '');
    return;
  }
  if (node.nodeType !== Node.ELEMENT_NODE) return;
  const el = node as HTMLElement;
  if (el.dataset.fileId) {
    const file: FileAttachment = { id: el.dataset.fileId, name: el.dataset.fileName ?? '', mimeType: el.dataset.fileMimeType ?? '' };
    rawParts.push({ type: 'file', id: file.id, name: file.name, mimeType: file.mimeType });
    files.push(file);
    return;
  }
  if (el.tagName === 'BR') {
    appendTextPart(rawParts, '\n');
    return;
  }
  const isBlock = BLOCK_TAGS.has(el.tagName);
  el.childNodes.forEach((child, i) => {
    if (isBlock && i > 0) appendTextPart(rawParts, '\n');
    walkEditableContent(child, rawParts, files);
  });
}

function extractEditableContent(el?: HTMLDivElement | null): { parts: UserPart[]; files: FileAttachment[] } {
  if (!el) return { parts: [], files: [] };
  const rawParts: UserPart[] = [];
  const files: FileAttachment[] = [];
  walkEditableContent(el, rawParts, files);
  if (rawParts.length > 0 && rawParts[0].type === 'text') {
    (rawParts[0] as { type: 'text'; content: string }).content = (rawParts[0] as { type: 'text'; content: string }).content.trimStart();
  }
  const lastPart = rawParts[rawParts.length - 1];
  if (lastPart?.type === 'text') {
    (lastPart as { type: 'text'; content: string }).content = (lastPart as { type: 'text'; content: string }).content.trimEnd();
  }
  const parts = rawParts.filter((p) => p.type === 'file' || (p as { type: 'text'; content: string }).content.length > 0);
  return { parts, files };
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

export default function ChatWindow({
  headerTitle,
  conversation,
  providers,
  sending,
  streaming,
  lastStreamedContent,
  inputDraft,
  onInputDraftChange,
  onSend,
  onUpdateProvider,
  pendingApprovals,
  onApproveTool,
  onRejectTool,
}: ChatWindowProps) {
  const [hideToolsAndThinking, setHideToolsAndThinking] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [speakingIdx, setSpeakingIdx] = useState<number | null>(null);
  const [uploadingFile, setUploadingFile] = useState(false);
  const [hasContent, setHasContent] = useState(false);
  const messagesRef = useRef<HTMLDivElement>(null);
  const topRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const recognitionRef = useRef<any>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const editableRef = useRef<HTMLDivElement>(null);
  // true while we are the source of an inputDraft change — prevents syncing back
  const isInternalChangeRef = useRef(false);

  // Stop recording and TTS when conversation changes
  useEffect(() => {
    recognitionRef.current?.stop();
    setIsRecording(false);
    speechSynthesis.cancel();
    setSpeakingIdx(null);
  }, [conversation?.id]);

  // Sync external inputDraft changes (e.g. speech recognition) into the editable div
  // while preserving file chips already embedded in the div.
  useLayoutEffect(() => {
    if (isInternalChangeRef.current) {
      isInternalChangeRef.current = false;
      return;
    }
    const el = editableRef.current;
    if (!el) return;
    // Collect existing chip nodes to re-append after text
    const chips = Array.from(el.childNodes).filter(
      (n) => n.nodeType === Node.ELEMENT_NODE && (n as HTMLElement).dataset.fileId
    );
    el.innerHTML = '';
    if (inputDraft) el.appendChild(document.createTextNode(inputDraft));
    chips.forEach((c) => el.appendChild(c));
    setHasContent(editableHasContent(el));
  }, [inputDraft]);

  const visibleMessages = conversation
    ? getDisplayMessages(conversation.messages, hideToolsAndThinking, lastStreamedContent, conversation)
    : [];

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [conversation?.messages.length, streaming?.reasoning, streaming?.parts?.length]);

  const hasProvider = Boolean(conversation?.providerId);

  const handleSend = () => {
    if (sending || !hasProvider) return;
    const { parts, files } = extractEditableContent(editableRef.current);
    if (parts.length === 0) return;
    const el = editableRef.current;
    if (el) {
      el.innerHTML = '';
      isInternalChangeRef.current = true;
      onInputDraftChange('');
      setHasContent(false);
    }
    const message = files.length > 0
      ? JSON.stringify({ user_parts: parts })
      : ((parts[0] as { type: 'text'; content: string })?.content ?? '');
    onSend(message, files.length > 0 ? files : undefined);
  };

  const handleEditableInput = () => {
    const el = editableRef.current;
    if (!el) return;
    isInternalChangeRef.current = true;
    onInputDraftChange(editablePlainText(el));
    setHasContent(editableHasContent(el));
  };

  const handleEditableClick = (e: React.MouseEvent<HTMLDivElement>) => {
    const target = e.target as HTMLElement;
    if (target.dataset.removeChipId) {
      const chip = target.closest('[data-file-id]') as HTMLElement | null;
      chip?.remove();
      isInternalChangeRef.current = true;
      const el = editableRef.current;
      if (el) {
        onInputDraftChange(editablePlainText(el));
        setHasContent(editableHasContent(el));
      }
    }
  };

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    if (files.length === 0) return;
    e.target.value = '';
    setUploadingFile(true);
    try {
      const uploaded = await Promise.all(files.map((f) => api.uploadFile(conversation!.id, f)));
      uploaded.forEach(insertFileChip);
    } catch {
      // ignore upload errors silently
    } finally {
      setUploadingFile(false);
    }
  };

  const insertFileChip = (attachment: FileAttachment) => {
    const el = editableRef.current;
    if (!el) return;
    el.focus();
    const chip = document.createElement('span');
    chip.contentEditable = 'false';
    chip.dataset.fileId = attachment.id;
    chip.dataset.fileName = attachment.name;
    chip.dataset.fileMimeType = attachment.mimeType;
    chip.className = FILE_CHIP_CLASS;
    chip.title = attachment.name;
    chip.innerHTML =
      `<svg width="11" height="11" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0"><path d="M7 1H3a1 1 0 00-1 1v8a1 1 0 001 1h6a1 1 0 001-1V4L7 1z"/><path d="M7 1v3h3"/></svg>` +
      `<span style="max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${escapeHtml(attachment.name)}</span>` +
      `<span data-remove-chip-id="${attachment.id}" style="cursor:pointer;margin-left:2px;opacity:0.7;flex-shrink:0" title="Remove">✕</span>`;

    const sel = window.getSelection();
    if (sel && sel.rangeCount > 0 && el.contains(sel.getRangeAt(0).commonAncestorContainer)) {
      const range = sel.getRangeAt(0);
      range.deleteContents();
      range.insertNode(chip);
      range.setStartAfter(chip);
      range.collapse(true);
      sel.removeAllRanges();
      sel.addRange(range);
    } else {
      el.appendChild(chip);
    }
    // Ensure a text node follows so typing continues naturally
    const space = document.createTextNode('\u00A0');
    chip.after(space);
    const r = document.createRange();
    r.setStart(space, 1);
    r.collapse(true);
    const s = window.getSelection();
    s?.removeAllRanges();
    s?.addRange(r);

    isInternalChangeRef.current = true;
    onInputDraftChange(editablePlainText(el));
    setHasContent(true);
  };

  const scrollToTop = () => topRef.current?.scrollIntoView({ behavior: 'smooth' });
  const scrollToBottom = () => bottomRef.current?.scrollIntoView({ behavior: 'smooth' });

  const toggleRecording = () => {
    if (isRecording) {
      recognitionRef.current?.stop();
      setIsRecording(false);
      return;
    }
    const SpeechRecognitionAPI =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognitionAPI) {
      alert('Speech recognition is not supported in this browser. Try Chrome or Edge.');
      return;
    }
    const recognition = new SpeechRecognitionAPI();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = 'en-US';
    // Capture the base text at recording start; all mutations use this as ground truth
    const startBase = editableRef.current ? editablePlainText(editableRef.current) : inputDraft;
    let committedText = startBase;
    recognition.onresult = (event: any) => {
      let interim = '';
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const t = event.results[i][0].transcript;
        if (event.results[i].isFinal) {
          committedText += (committedText && !committedText.endsWith(' ') ? ' ' : '') + t.trim();
        } else {
          interim = t;
        }
      }
      if (interim) {
        onInputDraftChange(committedText + (committedText && !committedText.endsWith(' ') ? ' ' : '') + interim + ' […]');
      } else {
        onInputDraftChange(committedText);
      }
    };
    recognition.onend = () => {
      setIsRecording(false);
      onInputDraftChange(committedText);
    };
    recognition.onerror = () => {
      setIsRecording(false);
      onInputDraftChange(committedText);
    };
    recognitionRef.current = recognition;
    recognition.start();
    setIsRecording(true);
  };

  const speakMessage = (msg: DisplayMessage, idx: number) => {
    if (speakingIdx === idx) {
      speechSynthesis.cancel();
      setSpeakingIdx(null);
      return;
    }
    const text = getMessagePlainText(msg, !hideToolsAndThinking);
    if (!text.trim()) return;
    speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.onend = () => setSpeakingIdx(null);
    utterance.onerror = () => setSpeakingIdx(null);
    setSpeakingIdx(idx);
    speechSynthesis.speak(utterance);
  };

  if (!conversation) {
    return (
      <main className="flex-1 flex flex-col min-w-0">
        <header className="flex flex-wrap items-center gap-3 py-3.5 px-5 border-b border-border">
          <h2 className="flex-1 min-w-0 text-base font-semibold text-text-bright truncate">{headerTitle}</h2>
        </header>
        <div className="flex-1 flex items-center justify-center text-text-dim text-base">
          <p>
            {headerTitle === 'User'
              ? 'Select or create a conversation to start chatting'
              : `Select or create a conversation with ${headerTitle} to start chatting`}
          </p>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 flex flex-col min-w-0">
      <header className="flex flex-wrap items-center gap-3 py-3.5 px-5 border-b border-border">
        <h2 className="flex-1 min-w-0 text-base font-semibold text-text-bright truncate">{headerTitle}</h2>
        <button
          className={`py-1 px-3 border rounded-lg cursor-pointer text-xs transition-all duration-150 whitespace-nowrap ${
            hideToolsAndThinking
              ? 'bg-bg-active border-accent text-accent'
              : 'bg-bg-input text-text-dim border-border hover:border-accent hover:text-text-bright'
          }`}
          onClick={() => setHideToolsAndThinking((v) => !v)}
          title={hideToolsAndThinking ? 'Show tools and thinking' : 'Hide tools and thinking'}
        >
          {hideToolsAndThinking ? 'Show tools and thinking' : 'Hide tools and thinking'}
        </button>
      </header>

      <div className="flex-1 overflow-y-auto py-4 px-5 flex flex-col gap-3" ref={messagesRef}>
        <div ref={topRef} />
        {visibleMessages.map((msg, i) => (
          <MessageEntry
            key={i}
            msg={msg}
            speaking={speakingIdx === i}
            onSpeak={() => speakMessage(msg, i)}
          />
        ))}
        {sending && (() => {
          const replyingParticipant = conversation?.participant1 && conversation?.participant2
            ? (conversation.participant1 === 'user' ? conversation.participant2 : conversation.participant2 === 'user' ? conversation.participant1 : conversation.participant2)
            : 'assistant';
          const assistantLabel = participantDisplayName(replyingParticipant);
          return (
          <div className="max-w-[80%] py-2.5 px-3.5 rounded-lg text-sm leading-relaxed self-start bg-assistant-bg border border-border opacity-95">
            <div className="flex items-baseline gap-2 cursor-pointer select-none">
              <span className={`text-[0.6875rem] font-semibold tracking-wide text-text-dim shrink-0 ${['user', 'assistant', 'tool'].includes(assistantLabel.toLowerCase()) ? 'uppercase' : ''}`}>
                {assistantLabel}
              </span>
            </div>
            <div className="text-text-bright mt-1.5 min-w-0 break-words">
              {streaming?.reasoning ? (
                <details className="mb-2" open>
                  <summary className="cursor-pointer text-text-dim text-xs font-semibold uppercase">
                    Thinking
                  </summary>
                  <pre className="mt-1.5 p-2 bg-bg rounded-lg text-xs text-text-dim whitespace-pre-wrap break-words max-h-[200px] overflow-y-auto">
                    {streaming.reasoning}
                  </pre>
                </details>
              ) : null}
              {streaming?.reasoning && streaming.parts.length > 0 ? (
                <div className="h-px bg-border my-2" />
              ) : null}
              {streaming?.parts.map((part, i) =>
                part.type === 'tool' ? (
                  <details key={i} className="message-tool-part my-2 py-2 px-2.5 bg-tool-bg border border-tool-border rounded-lg text-tool-text max-w-full overflow-hidden" open>
                    <summary className="flex items-center gap-2 cursor-pointer text-xs font-semibold uppercase text-tool-summary list-none [&::-webkit-details-marker]:hidden [&::marker]:hidden">
                      <span>Tool</span>
                      <span className="btn-msg-toggle flex items-center justify-center w-6 h-6 ml-auto shrink-0 rounded-md border border-tool-border bg-tool-bg/80 text-[0.65rem] font-medium leading-none transition-all duration-150 hover:border-tool-summary hover:opacity-90" />
                    </summary>
                    <pre className="mt-1.5 font-mono text-xs text-tool-text whitespace-pre-wrap break-words max-w-full overflow-x-auto overflow-y-hidden">
                      {part.content}
                    </pre>
                  </details>
                ) : (
                  <span key={i} className="whitespace-pre-wrap break-words">{renderAnswerContent(part.content)}</span>
                )
              )}
              {streaming && streaming.parts.length === 0 && !streaming.reasoning && (
                <span className="whitespace-pre-wrap break-words">Thinking…</span>
              )}
            </div>
          </div>
          );
        })()}
        {pendingApprovals.map((approval) => (
          <ToolApprovalCard
            key={approval.approvalId}
            approval={approval}
            onApprove={(note) => onApproveTool(approval.approvalId, note)}
            onReject={(note) => onRejectTool(approval.approvalId, note)}
          />
        ))}
        <div ref={bottomRef} />
      </div>

      <div className="flex items-end gap-2 py-3 px-5 border-t border-border bg-bg-sidebar">
        <div className="flex flex-col gap-1">
          <button
            className="w-7 h-7 bg-bg-input text-text-dim border border-border rounded-lg cursor-pointer text-[0.7rem] flex items-center justify-center transition-all duration-150 hover:border-accent hover:text-text-bright p-0 disabled:opacity-50 disabled:cursor-not-allowed"
            onClick={scrollToTop}
            title="Scroll to top"
          >
            ▲
          </button>
          <button
            className="w-7 h-7 bg-bg-input text-text-dim border border-border rounded-lg cursor-pointer text-[0.7rem] flex items-center justify-center transition-all duration-150 hover:border-accent hover:text-text-bright p-0"
            onClick={scrollToBottom}
            title="Scroll to bottom"
          >
            ▼
          </button>
        </div>
        <div
          ref={editableRef}
          contentEditable={sending || !hasProvider ? 'false' : 'true'}
          suppressContentEditableWarning
          onInput={handleEditableInput}
          onClick={handleEditableClick}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          data-placeholder="Type a message… (Enter to send, Shift+Enter for newline)"
          className={`flex-1 min-h-[3rem] max-h-40 overflow-y-auto py-2.5 px-3.5 bg-bg-input text-text border border-border rounded-lg text-sm leading-normal outline-none focus:border-accent empty:before:content-[attr(data-placeholder)] empty:before:text-text-dim ${sending || !hasProvider ? 'opacity-50 cursor-not-allowed' : ''}`}
        />
        <input
          ref={fileInputRef}
          type="file"
          multiple
          className="hidden"
          onChange={handleFileSelect}
        />
        <button
          className={`w-9 h-9 flex items-center justify-center rounded-lg border cursor-pointer transition-all duration-150 shrink-0 ${
            isRecording
              ? 'bg-danger/20 border-danger text-danger animate-pulse'
              : 'bg-bg-input text-text-dim border-border hover:border-accent hover:text-accent hover:bg-bg-hover'
          } disabled:opacity-40 disabled:cursor-not-allowed`}
          onClick={toggleRecording}
          disabled={sending || !hasProvider}
          title={isRecording ? 'Stop recording' : 'Speak (voice input)'}
        >
          <MicIcon />
        </button>
        <button
          className="w-9 h-9 flex items-center justify-center rounded-lg border cursor-pointer transition-all duration-150 shrink-0 bg-bg-input text-text-dim border-border hover:border-accent hover:text-accent hover:bg-bg-hover disabled:opacity-40 disabled:cursor-not-allowed"
          onClick={() => fileInputRef.current?.click()}
          disabled={sending || !hasProvider || uploadingFile}
          title="Attach files"
        >
          <PaperclipIcon />
        </button>
        <div className="flex flex-col gap-1 items-end shrink-0">
          <select
            value={conversation.providerId ?? ''}
            onChange={(e) => onUpdateProvider(conversation.id, e.target.value || null)}
            className="py-1.5 px-2.5 bg-bg-input text-text border border-border rounded-lg text-[0.8125rem] outline-none focus:border-accent min-w-[140px]"
          >
            <option value="">Select agent…</option>
            {providers.filter((p) => p.type !== 'DALLE_3').map((p) => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
          {!hasProvider && (
            <span className="text-[0.6875rem] text-text-dim">Select agent to send</span>
          )}
          <button
            className="py-2.5 px-5 bg-accent text-white border-0 rounded-lg cursor-pointer text-sm font-medium transition-colors duration-150 w-full hover:bg-accent-hover disabled:opacity-40 disabled:cursor-not-allowed"
            onClick={handleSend}
            disabled={sending || !hasProvider || !hasContent}
          >
            Send
          </button>
        </div>
      </div>
    </main>
  );
}
