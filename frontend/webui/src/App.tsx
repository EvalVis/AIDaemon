import { useEffect, useRef, useState } from 'react';
import Sidebar from './components/Sidebar';
import ChatWindow from './components/ChatWindow';
import * as api from './api';
import type { Conversation, CreateProviderRequest, Provider } from './types';

export interface StreamPart {
  type: 'answer' | 'tool' | 'reasoning';
  content: string;
}

export interface StreamingContent {
  reasoning: string;
  parts: StreamPart[];
}

export default function App() {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [streaming, setStreaming] = useState<StreamingContent | null>(null);
  const [lastStreamedContent, setLastStreamedContent] = useState<{ conversationId: string; reasoning: string; parts: StreamPart[] } | null>(null);
  const streamingRef = useRef<StreamingContent | null>(null);

  useEffect(() => {
    api.fetchProviders().then(setProviders);
    api.fetchConversations().then(setConversations);
  }, []);

  const activeConversation = conversations.find((c) => c.id === activeId) ?? null;

  const handleAddProvider = async (req: CreateProviderRequest) => {
    const created = await api.createProvider(req);
    setProviders((prev) => [...prev, created]);
  };

  const handleCreateConversation = async (name: string, providerId?: string | null) => {
    const res = await api.createConversation(name, providerId);
    const conv: Conversation = {
      id: res.conversationId,
      name: res.name,
      providerId: res.providerId ?? null,
      messages: [],
    };
    setConversations((prev) => [...prev, conv]);
    setActiveId(conv.id);
  };

  const handleUpdateConversationProvider = async (id: string, providerId: string | null) => {
    const updated = await api.updateConversation(id, { providerId });
    setConversations((prev) => prev.map((c) => (c.id === id ? { ...c, providerId: updated.providerId } : c)));
  };

  const handleDeleteConversation = async (id: string) => {
    await api.deleteConversation(id);
    setConversations((prev) => prev.filter((c) => c.id !== id));
    if (activeId === id) setActiveId(null);
  };

  const handleSend = async (message: string) => {
    if (!activeId) return;
    setLastStreamedContent(null);
    setConversations((prev) =>
      prev.map((c) =>
        c.id === activeId
          ? { ...c, messages: [...c.messages, { role: 'user', content: message, timestampMillis: Date.now() }] }
          : c,
      ),
    );
    setSending(true);
    const initial: StreamingContent = { reasoning: '', parts: [] };
    setStreaming(initial);
    streamingRef.current = initial;
    api.sendMessageStream(
      activeId,
      message,
      (chunk) => {
        setStreaming((prev) => {
          if (!prev) return prev;
          let next: StreamingContent;
          if (chunk.type === 'reasoning') {
            next = { ...prev, reasoning: prev.reasoning + chunk.content };
          } else if (chunk.type === 'tool') {
            next = { ...prev, parts: [...prev.parts, { type: 'tool', content: chunk.content }] };
          } else {
            const prevParts = prev.parts;
            const last = prevParts[prevParts.length - 1];
            if (last?.type === 'answer') {
              next = {
                ...prev,
                parts: [...prevParts.slice(0, -1), { type: 'answer', content: last.content + chunk.content }],
              };
            } else {
              next = { ...prev, parts: [...prevParts, { type: 'answer', content: chunk.content }] };
            }
          }
          streamingRef.current = next;
          return next;
        });
      },
      () => {
        const ref = streamingRef.current;
        if (activeId && ref && (ref.reasoning || ref.parts.length > 0)) {
          setLastStreamedContent({ conversationId: activeId, reasoning: ref.reasoning ?? '', parts: ref.parts });
        }
        setSending(false);
        setStreaming(null);
        streamingRef.current = null;
        api.fetchConversations().then(setConversations);
      },
      () => {
        setSending(false);
        setStreaming(null);
        setConversations((prev) =>
          prev.map((c) =>
            c.id === activeId
              ? { ...c, messages: [...c.messages, { role: 'assistant', content: 'Error: request failed', timestampMillis: Date.now() }] }
              : c,
          ),
        );
      }
    );
  };

  return (
    <div className="flex h-screen">
      <Sidebar
        providers={providers}
        conversations={conversations}
        activeId={activeId}
        onSelectConversation={setActiveId}
        onCreateConversation={handleCreateConversation}
        onDeleteConversation={handleDeleteConversation}
        onAddProvider={handleAddProvider}
      />
      <ChatWindow
        conversation={activeConversation}
        providers={providers}
        sending={sending}
        streaming={streaming}
        lastStreamedContent={activeId && lastStreamedContent?.conversationId === activeId ? lastStreamedContent : null}
        onSend={handleSend}
        onUpdateProvider={handleUpdateConversationProvider}
      />
    </div>
  );
}
