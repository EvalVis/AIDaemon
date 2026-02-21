import { useEffect, useState } from 'react';
import Sidebar from './components/Sidebar';
import ChatWindow from './components/ChatWindow';
import * as api from './api';
import type { Conversation, CreateProviderRequest, Provider } from './types';

export interface StreamingContent {
  reasoning: string;
  answer: string;
}

export default function App() {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [streaming, setStreaming] = useState<StreamingContent | null>(null);

  useEffect(() => {
    api.fetchProviders().then(setProviders);
    api.fetchConversations().then(setConversations);
  }, []);

  const activeConversation = conversations.find((c) => c.id === activeId) ?? null;

  const handleAddProvider = async (req: CreateProviderRequest) => {
    const created = await api.createProvider(req);
    setProviders((prev) => [...prev, created]);
  };

  const handleCreateConversation = async (name: string, providerId: string) => {
    const res = await api.createConversation(name, providerId);
    const conv: Conversation = {
      id: res.conversationId,
      name: res.name,
      providerId: res.providerId,
      messages: [],
    };
    setConversations((prev) => [...prev, conv]);
    setActiveId(conv.id);
  };

  const handleDeleteConversation = async (id: string) => {
    await api.deleteConversation(id);
    setConversations((prev) => prev.filter((c) => c.id !== id));
    if (activeId === id) setActiveId(null);
  };

  const handleSend = async (message: string) => {
    if (!activeId) return;
    setConversations((prev) =>
      prev.map((c) =>
        c.id === activeId
          ? { ...c, messages: [...c.messages, { role: 'user', content: message }] }
          : c,
      ),
    );
    setSending(true);
    setStreaming({ reasoning: '', answer: '' });
    api.sendMessageStream(
      activeId,
      message,
      (chunk) => {
        setStreaming((prev) => {
          if (!prev) return prev;
          if (chunk.type === 'reasoning') {
            return { ...prev, reasoning: prev.reasoning + chunk.content };
          }
          return { ...prev, answer: prev.answer + chunk.content };
        });
      },
      () => {
        setSending(false);
        setStreaming(null);
        api.fetchConversations().then(setConversations);
      },
      () => {
        setSending(false);
        setStreaming(null);
        setConversations((prev) =>
          prev.map((c) =>
            c.id === activeId
              ? { ...c, messages: [...c.messages, { role: 'assistant', content: 'Error: request failed' }] }
              : c,
          ),
        );
      }
    );
  };

  return (
    <div className="app-layout">
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
        sending={sending}
        streaming={streaming}
        onSend={handleSend}
      />
    </div>
  );
}
