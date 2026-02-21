import { useEffect, useRef, useState } from 'react';
import type { ChatMessage, Conversation } from '../types';
import type { StreamingContent } from '../App';

interface ChatWindowProps {
  conversation: Conversation | null;
  sending: boolean;
  streaming: StreamingContent | null;
  onSend: (message: string) => void;
}

const PREVIEW_LENGTH = 120;

function formatTime(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => n.toString().padStart(2, '0');
  const pad3 = (n: number) => n.toString().padStart(3, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad3(d.getMilliseconds())}`;
}

function MessageEntry({ msg }: { msg: ChatMessage }) {
  const [collapsed, setCollapsed] = useState(msg.role === 'tool');

  const preview = msg.content.slice(0, PREVIEW_LENGTH) + (msg.content.length > PREVIEW_LENGTH ? '…' : '');
  const timeStr = msg.timestampMillis != null && msg.timestampMillis > 0 ? formatTime(msg.timestampMillis) : null;

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
      {!collapsed && <div className="message-content">{msg.content}</div>}
    </div>
  );
}

export default function ChatWindow({ conversation, sending, streaming, onSend }: ChatWindowProps) {
  const [input, setInput] = useState('');
  const [hideTools, setHideTools] = useState(false);
  const messagesRef = useRef<HTMLDivElement>(null);
  const topRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [conversation?.messages.length, streaming?.reasoning, streaming?.answer]);

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

  const visibleMessages = hideTools
    ? conversation.messages.filter((m) => m.role !== 'tool')
    : conversation.messages;

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
              {streaming?.reasoning && streaming?.answer ? <div className="streaming-divider" /> : null}
              <span className="streaming-answer">{streaming?.answer ?? (streaming ? '' : 'Thinking…')}</span>
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
