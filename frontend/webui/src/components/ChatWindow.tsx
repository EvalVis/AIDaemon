import { useEffect, useRef, useState } from 'react';
import type { Conversation } from '../types';

interface ChatWindowProps {
  conversation: Conversation | null;
  sending: boolean;
  onSend: (message: string) => void;
}

export default function ChatWindow({ conversation, sending, onSend }: ChatWindowProps) {
  const [input, setInput] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [conversation?.messages.length]);

  const handleSend = () => {
    const trimmed = input.trim();
    if (!trimmed || sending) return;
    setInput('');
    onSend(trimmed);
  };

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
      </header>

      <div className="messages">
        {conversation.messages.map((msg, i) => (
          <div key={i} className={`message ${msg.role}`}>
            <span className="role-label">{msg.role}</span>
            <div className="message-content">{msg.content}</div>
          </div>
        ))}
        {sending && (
          <div className="message assistant loading">
            <span className="role-label">assistant</span>
            <div className="message-content">Thinking...</div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <div className="chat-input-area">
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          placeholder="Type a message... (Enter to send, Shift+Enter for newline)"
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
