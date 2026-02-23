import { useEffect, useRef, useState } from 'react';
import type { ChatMessage, Conversation, Provider } from '../types';
import type { StreamingContent, StreamPart } from '../App';

type DisplayMessage = ChatMessage | { role: 'assistant'; parts: StreamPart[] };

interface ChatWindowProps {
  conversation: Conversation | null;
  providers: Provider[];
  sending: boolean;
  streaming: StreamingContent | null;
  lastStreamedContent: { reasoning: string; parts: StreamPart[] } | null;
  inputDraft: string;
  onInputDraftChange: (value: string) => void;
  onSend: (message: string) => void;
  onUpdateProvider: (conversationId: string, providerId: string | null) => void;
}

const PREVIEW_LENGTH = 120;

function formatTime(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => n.toString().padStart(2, '0');
  const pad3 = (n: number) => n.toString().padStart(3, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad3(d.getMilliseconds())}`;
}

function MessageEntry({ msg }: { msg: DisplayMessage }) {
  const hasParts = 'parts' in msg && msg.parts != null;
  const [collapsed, setCollapsed] = useState(msg.role === 'tool' && !hasParts);
  const content = 'content' in msg ? msg.content : '';
  const preview = content.slice(0, PREVIEW_LENGTH) + (content.length > PREVIEW_LENGTH ? '…' : '');
  const timeStr = 'timestampMillis' in msg && msg.timestampMillis != null && msg.timestampMillis > 0
    ? formatTime(msg.timestampMillis) : null;

  const messageStyles =
    msg.role === 'user'
      ? 'max-w-[80%] py-2.5 px-3.5 rounded-lg text-sm leading-relaxed whitespace-pre-wrap break-words self-end bg-user-bg text-text-bright'
      : msg.role === 'tool'
        ? 'max-w-[90%] py-2.5 px-3.5 rounded-lg text-xs leading-relaxed whitespace-pre-wrap break-words self-start bg-tool-bg border border-tool-border font-mono'
        : 'max-w-[80%] py-2.5 px-3.5 rounded-lg text-sm leading-relaxed whitespace-pre-wrap break-words self-start bg-assistant-bg border border-border';

  return (
    <div className={`${messageStyles}${collapsed ? ' pb-0' : ''}`}>
      <div
        className="flex items-baseline gap-2 cursor-pointer select-none"
        onClick={() => setCollapsed((v) => !v)}
      >
        <span className="text-[0.6875rem] font-semibold uppercase tracking-wide text-text-dim shrink-0">
          {msg.role}
        </span>
        {timeStr != null && (
          <span className="text-[0.6875rem] tabular-nums text-text-dim opacity-85">{timeStr}</span>
        )}
        {collapsed && (
          <span className="flex-1 text-[0.8125rem] text-text-dim whitespace-nowrap overflow-hidden text-ellipsis">
            {preview}
          </span>
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
            ? (msg as { role: 'assistant'; parts: StreamPart[] }).parts.map((part, i) =>
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
                  <span key={i}>{part.content}</span>
                )
              )
            : content}
        </div>
      )}
    </div>
  );
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

function getDisplayMessages(
  messages: ChatMessage[],
  hideTools: boolean,
  lastStreamedContent: { reasoning: string; parts: StreamPart[] } | null
): DisplayMessage[] {
  let list: DisplayMessage[] = (hideTools ? messages.filter((m) => m.role !== 'tool') : messages).map((msg) => {
    if (msg.role === 'assistant') {
      const parts = parseStructuredContent(msg.content);
      if (parts) return { role: 'assistant' as const, parts };
    }
    return msg;
  });
  if (lastStreamedContent && list.length > 0) {
    const lastUserIdx = Math.max(...list.map((msg, i) => (msg.role === 'user' ? i : -1)));
    const afterLastUser = list.slice(lastUserIdx + 1);
    if (afterLastUser.length === 1 && afterLastUser[0].role === 'assistant') {
      const parts: StreamPart[] = lastStreamedContent.reasoning
        ? [{ type: 'reasoning', content: lastStreamedContent.reasoning }, ...lastStreamedContent.parts]
        : lastStreamedContent.parts;
      list = [...list.slice(0, lastUserIdx + 1), { role: 'assistant', parts }];
    }
  }
  return list;
}

export default function ChatWindow({ conversation, providers, sending, streaming, lastStreamedContent, inputDraft, onInputDraftChange, onSend, onUpdateProvider }: ChatWindowProps) {
  const [hideTools, setHideTools] = useState(false);
  const messagesRef = useRef<HTMLDivElement>(null);
  const topRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const visibleMessages = conversation
    ? getDisplayMessages(conversation.messages, hideTools, lastStreamedContent)
    : [];

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [conversation?.messages.length, streaming?.reasoning, streaming?.parts?.length]);

  const hasProvider = Boolean(conversation?.providerId);
  const handleSend = () => {
    const trimmed = inputDraft.trim();
    if (!trimmed || sending || !hasProvider) return;
    onSend(trimmed);
  };

  const scrollToTop = () => topRef.current?.scrollIntoView({ behavior: 'smooth' });
  const scrollToBottom = () => bottomRef.current?.scrollIntoView({ behavior: 'smooth' });

  if (!conversation) {
    return (
      <main className="flex-1 flex flex-col min-w-0 flex items-center justify-center text-text-dim text-base">
        <p>Select or create a conversation to start chatting</p>
      </main>
    );
  }

  return (
    <main className="flex-1 flex flex-col min-w-0">
      <header className="flex flex-wrap items-center gap-3 py-3.5 px-5 border-b border-border">
        <h2 className="flex-1 min-w-0 text-base font-semibold text-text-bright truncate">{conversation.name}</h2>
        <button
          className={`py-1 px-3 border rounded-lg cursor-pointer text-xs transition-all duration-150 whitespace-nowrap ${
            hideTools
              ? 'bg-bg-active border-accent text-accent'
              : 'bg-bg-input text-text-dim border-border hover:border-accent hover:text-text-bright'
          }`}
          onClick={() => setHideTools((v) => !v)}
          title={hideTools ? 'Show tool logs' : 'Hide tool logs'}
        >
          {hideTools ? 'Show Tools' : 'Hide Tools'}
        </button>
      </header>

      <div className="flex-1 overflow-y-auto py-4 px-5 flex flex-col gap-3" ref={messagesRef}>
        <div ref={topRef} />
        {visibleMessages.map((msg, i) => (
          <MessageEntry key={i} msg={msg} />
        ))}
        {sending && (
          <div className="max-w-[80%] py-2.5 px-3.5 rounded-lg text-sm leading-relaxed self-start bg-assistant-bg border border-border opacity-95">
            <div className="flex items-baseline gap-2 cursor-pointer select-none">
              <span className="text-[0.6875rem] font-semibold uppercase tracking-wide text-text-dim shrink-0">
                assistant
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
                  <span key={i} className="whitespace-pre-wrap break-words">{part.content}</span>
                )
              )}
              {streaming && streaming.parts.length === 0 && !streaming.reasoning && (
                <span className="whitespace-pre-wrap break-words">Thinking…</span>
              )}
            </div>
          </div>
        )}
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
        <textarea
          value={inputDraft}
          onChange={(e) => onInputDraftChange(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          placeholder="Type a message… (Enter to send, Shift+Enter for newline)"
          disabled={sending || !hasProvider}
          rows={2}
          className="flex-1 py-2.5 px-3.5 bg-bg-input text-text border border-border rounded-lg font-inherit text-sm resize-none outline-none leading-normal focus:border-accent"
        />
        <div className="flex flex-col gap-1 items-end shrink-0">
          <select
            value={conversation.providerId ?? ''}
            onChange={(e) => onUpdateProvider(conversation.id, e.target.value || null)}
            className="py-1.5 px-2.5 bg-bg-input text-text border border-border rounded-lg text-[0.8125rem] outline-none focus:border-accent min-w-[140px]"
          >
            <option value="">Select agent…</option>
            {providers.map((p) => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
          {!hasProvider && (
            <span className="text-[0.6875rem] text-text-dim">Select agent to send</span>
          )}
          <button
            className="py-2.5 px-5 bg-accent text-white border-0 rounded-lg cursor-pointer text-sm font-medium transition-colors duration-150 w-full hover:bg-accent-hover disabled:opacity-40 disabled:cursor-not-allowed"
            onClick={handleSend}
            disabled={sending || !hasProvider || !inputDraft.trim()}
          >
            Send
          </button>
        </div>
      </div>
    </main>
  );
}
