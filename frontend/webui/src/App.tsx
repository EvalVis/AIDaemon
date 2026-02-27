import { useEffect, useRef, useState } from 'react';
import Sidebar from './components/Sidebar';
import ChatWindow from './components/ChatWindow';
import * as api from './api';
import type { Bot, Conversation, CreateBotRequest, CreateProviderRequest, Provider } from './types';

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
  const [bots, setBots] = useState<Bot[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [streaming, setStreaming] = useState<StreamingContent | null>(null);
  const [lastStreamedContent, setLastStreamedContent] = useState<{ conversationId: string; reasoning: string; parts: StreamPart[] } | null>(null);
  const [, setDraftVersion] = useState(0);
  const inputDraftRef = useRef('');
  const streamingRef = useRef<StreamingContent | null>(null);
  const pollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const activeIdRef = useRef(activeId);
  activeIdRef.current = activeId;

  const setInputDraft = (value: string) => {
    inputDraftRef.current = value;
    setDraftVersion((v) => v + 1);
  };

  useEffect(() => {
    api.fetchProviders().then(setProviders);
    api.fetchConversations('user').then(setConversations);
    api.fetchBots().then(setBots);
  }, []);

  useEffect(() => {
    return () => {
      if (pollIntervalRef.current) clearInterval(pollIntervalRef.current);
    };
  }, []);

  const activeConversation = conversations.find((c) => c.id === activeId) ?? null;

  const handleAddProvider = async (req: CreateProviderRequest) => {
    const created = await api.createProvider(req);
    setProviders((prev) => [...prev, created]);
  };

  const handleAddBot = async (req: CreateBotRequest) => {
    const created = await api.createBot(req);
    setBots((prev) => [...prev, created]);
  };

  const handleCreateConversation = async (name: string, providerId?: string | null) => {
    const res = await api.createConversation(name, providerId);
    const conv: Conversation = {
      id: res.conversationId,
      name: res.name,
      providerId: res.providerId ?? null,
      botName: res.botName ?? null,
      messages: [],
    };
    setConversations((prev) => [...prev, conv]);
    setActiveId(conv.id);
  };

  const handleOpenDirectChat = async (botName: string, providerId?: string | null) => {
    const conv = await api.getOrCreateDirectConversation(botName, providerId);
    setConversations((prev) => {
      const exists = prev.some((c) => c.id === conv.id);
      if (exists) return prev.map((c) => (c.id === conv.id ? conv : c));
      return [...prev, conv];
    });
    setActiveId(conv.id);
  };

  const handleUpdateConversationProvider = async (id: string, providerId: string | null) => {
    const updated = await api.updateConversation(id, { providerId });
    setConversations((prev) =>
      prev.map((c) => (c.id === id ? { ...c, providerId: updated.providerId ?? null } : c)),
    );
  };

  const handleUpdateConversationBot = async (id: string, botName: string | null) => {
    const updated = await api.updateConversation(id, { botName });
    setConversations((prev) =>
      prev.map((c) => (c.id === id ? { ...c, botName: updated.botName ?? null } : c)),
    );
  };

  const handleDeleteConversation = async (id: string) => {
    await api.deleteConversation(id);
    setConversations((prev) => prev.filter((c) => c.id !== id));
    if (activeId === id) setActiveId(null);
  };

  const handleSend = async (message: string) => {
    if (!activeId) return;
    inputDraftRef.current = '';
    setDraftVersion((v) => v + 1);
    if (pollIntervalRef.current) {
      clearInterval(pollIntervalRef.current);
      pollIntervalRef.current = null;
    }
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
        const poll = () => {
          api.fetchConversations('user').then((list) => setConversations([...list]));
        };
        poll();
        pollIntervalRef.current = setInterval(poll, 2500);
        setTimeout(() => {
          if (pollIntervalRef.current) {
            clearInterval(pollIntervalRef.current);
            pollIntervalRef.current = null;
          }
        }, 60000);
      },
      () => {
        setSending(false);
        setStreaming(null);
        streamingRef.current = null;
        api.fetchConversations('user').then(setConversations);
      }
    );
  };

  return (
    <div className="flex h-screen">
      <Sidebar
        providers={providers}
        bots={bots}
        conversations={conversations}
        activeId={activeId}
        onSelectConversation={setActiveId}
        onCreateConversation={handleCreateConversation}
        onOpenDirectChat={handleOpenDirectChat}
        onDeleteConversation={handleDeleteConversation}
        onAddProvider={handleAddProvider}
        onAddBot={handleAddBot}
      />
      <ChatWindow
        key={activeId ?? ''}
        conversation={activeConversation}
        providers={providers}
        bots={bots}
        sending={sending}
        streaming={streaming}
        lastStreamedContent={activeId && lastStreamedContent?.conversationId === activeId ? lastStreamedContent : null}
        inputDraft={inputDraftRef.current}
        onInputDraftChange={setInputDraft}
        onSend={handleSend}
        onUpdateProvider={handleUpdateConversationProvider}
        onUpdateBot={handleUpdateConversationBot}
      />
    </div>
  );
}
