import { useEffect, useRef, useState } from 'react';
import type { ChatMessage, Conversation } from '../types';
import type { StreamingContent, StreamPart } from '../App';

type DisplayMessage = ChatMessage | { role: 'assistant'; parts: StreamPart[] };

interface ChatWindowProps {
  conversation: Conversation | null;
  sending: boolean;
  streaming: StreamingContent | null;
  lastStreamedParts: StreamPart[] | null;
  onSend: (message: string) => void;
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

  return (
    <div className={`message ${msg.role}${collapsed ? ' collapsed' : ''}`}>
      <div className="message-header" onClick={() => setCollapsed((v) => !v)}>
        <span className="role-label">{msg.role}</span>
        {timeStr != null && <span className="message-time">{timeStr}</span>}
        {collapsed && <span className="message-preview">{preview}</span>}
        <button className="btn-msg-toggle" title={collapsed ? 'Expand' : 'Collapse'}>
          {collapsed ? '▸' : '▾'}
        </button>
      </div>
      {!collapsed && (
        <div className="message-content">
          {hasParts
            ? (msg as { role: 'assistant'; parts: StreamPart[] }).parts.map((part, i) =>
                part.type === 'tool' ? (
                  <details key={i} className="message-tool-part" open>
                    <summary>
                      <span>Tool</span>
                      <span className="btn-msg-toggle" />
                    </summary>
                    <pre className="tool-content">{part.content}</pre>
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
      .filter((p) => p && (p.type === 'answer' || p.type === 'tool') && typeof p.content === 'string')
      .map((p) => ({ type: p.type as 'answer' | 'tool', content: p.content }));
    return parts.length > 0 ? parts : null;
  } catch {
    return null;
  }
}

function getDisplayMessages(
  messages: ChatMessage[],
  hideTools: boolean,
  lastStreamedParts: StreamPart[] | null
): DisplayMessage[] {
  let list: DisplayMessage[] = (hideTools ? messages.filter((m) => m.role !== 'tool') : messages).map((msg) => {
    if (msg.role === 'assistant') {
      const parts = parseStructuredContent(msg.content);
      if (parts) return { role: 'assistant' as const, parts };
    }
    return msg;
  });
  if (lastStreamedParts && list.length > 0 && list[list.length - 1].role === 'assistant') {
    const lastUserIdx = Math.max(...list.map((msg, i) => (msg.role === 'user' ? i : -1)));
    list = [...list.slice(0, lastUserIdx + 1), { role: 'assistant', parts: lastStreamedParts }];
  }
  return list;
}

export default function ChatWindow({ conversation, sending, streaming, lastStreamedParts, onSend }: ChatWindowProps) {
  const [input, setInput] = useState('');
  const [hideTools, setHideTools] = useState(false);
  const messagesRef = useRef<HTMLDivElement>(null);
  const topRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const visibleMessages = conversation
    ? getDisplayMessages(conversation.messages, hideTools, lastStreamedParts)
    : [];

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [conversation?.messages.length, streaming?.reasoning, streaming?.parts?.length]);

  const handleSend = () => {
    const trimmed = input.trim();
    if (!trimmed || sending) return;
    setInput('');
    onSend(trimmed);
  };

  const scrollToTop = () => topRef.current?.scrollIntoView({ behavior: 'smooth' });
  const scrollToBottom = () => bottomRef.current?.scrollIntoView({ behavior: 'smooth' });

  if (!conversation) {
    return (
      <main className="chat-window empty">
        <p>Select or create a conversation to start chatting</p>
      </main>
    );
  }

  return (
    <main className="chat-window">
      <header className="chat-header">
        <h2>{conversation.name}</h2>
        <button
          className={`btn-toggle-tools${hideTools ? ' active' : ''}`}
          onClick={() => setHideTools((v) => !v)}
          title={hideTools ? 'Show tool logs' : 'Hide tool logs'}
        >
          {hideTools ? 'Show Tools' : 'Hide Tools'}
        </button>
      </header>

      <div className="messages" ref={messagesRef}>
        <div ref={topRef} />
        {visibleMessages.map((msg, i) => (
          <MessageEntry key={i} msg={msg} />
        ))}
        {sending && (
          <div className="message assistant streaming">
            <div className="message-header">
              <span className="role-label">assistant</span>
            </div>
            <div className="message-content">
              {streaming?.reasoning ? (
                <details className="streaming-reasoning" open>
                  <summary>Thinking</summary>
                  <pre className="reasoning-text">{streaming.reasoning}</pre>
                </details>
              ) : null}
              {(streaming?.reasoning && streaming.parts.length > 0) ? <div className="streaming-divider" /> : null}
              {streaming?.parts.map((part, i) =>
                part.type === 'tool' ? (
                  <details key={i} className="message-tool-part" open>
                    <summary>
                      <span>Tool</span>
                      <span className="btn-msg-toggle" />
                    </summary>
                    <pre className="tool-content">{part.content}</pre>
                  </details>
                ) : (
                  <span key={i} className="streaming-answer">{part.content}</span>
                )
              )}
              {streaming && streaming.parts.length === 0 && !streaming.reasoning && (
                <span className="streaming-answer">Thinking…</span>
              )}
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <div className="chat-input-area">
        <div className="scroll-btns">
          <button className="btn-scroll" onClick={scrollToTop} title="Scroll to top">▲</button>
          <button className="btn-scroll" onClick={scrollToBottom} title="Scroll to bottom">▼</button>
        </div>
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          placeholder="Type a message… (Enter to send, Shift+Enter for newline)"
          disabled={sending}
          rows={2}
        />
        <button className="btn-send" onClick={handleSend} disabled={sending || !input.trim()}>
          Send
        </button>
      </div>
    </main>
  );
}
