import { useEffect, useRef, useState } from 'react';
import Sidebar from './components/Sidebar';
import ChatWindow from './components/ChatWindow';
import * as api from './api';
import type { Bot, Conversation, CreateBotRequest, CreateProviderRequest, FileAttachment, PendingToolApproval, Provider } from './types';

function botParticipantsOf(conv: Conversation | null): string[] {
  if (!conv) return [];
  const participants = conv.participants ?? [];
  return participants.filter((p) => p !== 'user');
}

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
  const [selectedBot, setSelectedBot] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [notifyParticipants, setNotifyParticipants] = useState<string[]>([]);
  const [streaming, setStreaming] = useState<StreamingContent | null>(null);
  const [pendingApprovals, setPendingApprovals] = useState<PendingToolApproval[]>([]);
  const [lastStreamedContent, setLastStreamedContent] = useState<{ conversationId: string; reasoning: string; parts: StreamPart[] } | null>(null);
  const [, setDraftVersion] = useState(0);
  const inputDraftRef = useRef('');
  const streamingRef = useRef<StreamingContent | null>(null);
  const pollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const activeIdRef = useRef(activeId);
  const selectedBotRef = useRef(selectedBot);
  activeIdRef.current = activeId;
  selectedBotRef.current = selectedBot;

  const setInputDraft = (value: string) => {
    inputDraftRef.current = value;
    setDraftVersion((v) => v + 1);
  };

  useEffect(() => {
    api.fetchProviders().then(setProviders);
    api.fetchBots().then(setBots);
  }, []);

  useEffect(() => {
    const participant = selectedBot ?? 'user';
    api.fetchConversations(participant).then(setConversations);
  }, [selectedBot]);

  useEffect(() => {
    return () => {
      if (pollIntervalRef.current) clearInterval(pollIntervalRef.current);
    };
  }, []);

  const activeConversation = conversations.find((c) => c.id === activeId) ?? null;

  const displayConversations = conversations;
  const headerTitle = selectedBot ?? 'User';

  useEffect(() => {
    if (activeId != null && !displayConversations.some((c) => c.id === activeId)) {
      setActiveId(null);
    }
  }, [selectedBot, activeId, displayConversations]);

  useEffect(() => {
    setNotifyParticipants(botParticipantsOf(activeConversation));
  }, [activeId]);

  const handleAddProvider = async (req: CreateProviderRequest) => {
    const created = await api.createProvider(req);
    setProviders((prev) => [...prev, created]);
  };

  const handleAddBot = async (req: CreateBotRequest) => {
    const created = await api.createBot(req);
    setBots((prev) => [...prev, created]);
  };

  const handleCreateConversation = async (name: string, providerId?: string | null, participants?: string[]) => {
    const conv = await api.createConversation(name, providerId, participants);
    setConversations((prev) => {
      const exists = prev.some((c) => c.id === conv.id);
      return exists ? prev.map((c) => (c.id === conv.id ? conv : c)) : [...prev, conv];
    });
    setActiveId(conv.id);
    setNotifyParticipants(botParticipantsOf(conv));
  };

  const handleAddParticipant = async (conversationId: string, participantName: string) => {
    const updated = await api.addParticipant(conversationId, participantName);
    setConversations((prev) => prev.map((c) => (c.id === conversationId ? updated : c)));
    if (conversationId === activeId) {
      setNotifyParticipants(botParticipantsOf(updated));
    }
  };

  const handleUpdateConversationProvider = async (id: string, providerId: string | null) => {
    const updated = await api.updateConversation(id, { providerId });
    setConversations((prev) =>
      prev.map((c) => (c.id === id ? { ...c, providerId: updated.providerId ?? null } : c)),
    );
  };


  const handleDeleteConversation = async (id: string) => {
    await api.deleteConversation(id);
    setConversations((prev) => prev.filter((c) => c.id !== id));
    if (activeId === id) setActiveId(null);
  };

  const handleSend = async (message: string, attachments?: FileAttachment[]) => {
    if (!activeId) return;
    inputDraftRef.current = '';
    setDraftVersion((v) => v + 1);
    if (pollIntervalRef.current) {
      clearInterval(pollIntervalRef.current);
      pollIntervalRef.current = null;
    }
    setLastStreamedContent(null);
    const files = attachments ?? [];
    setConversations((prev) =>
      prev.map((c) =>
        c.id === activeId
          ? { ...c, messages: [...c.messages, { participant: 'user', content: message, timestampMillis: Date.now(), files }] }
          : c,
      ),
    );
    setSending(true);
    const initial: StreamingContent = { reasoning: '', parts: [] };
    setStreaming(initial);
    streamingRef.current = initial;
    const fileIds = files.map((f) => f.id);
    const currentNotify = notifyParticipants.length > 0
      ? notifyParticipants
      : botParticipantsOf(activeConversation);
    api.sendMessageStream(
      activeId,
      message,
      (chunk) => {
        if (chunk.type === 'tool_pending') {
          try {
            const approval = JSON.parse(chunk.content) as PendingToolApproval;
            setPendingApprovals((prev) => [...prev, approval]);
          } catch {
            // skip malformed chunk
          }
          return;
        }
        if (chunk.type === 'tool') {
          // Tool finished (approved or rejected) — remove matching pending approval
          setPendingApprovals((prev) => {
            if (prev.length === 0) return prev;
            // Remove the first pending entry whose toolName appears in the tool chunk content
            const idx = prev.findIndex((p) => chunk.content.includes(p.toolName));
            if (idx === -1) return prev;
            return [...prev.slice(0, idx), ...prev.slice(idx + 1)];
          });
        }
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
        setPendingApprovals([]);
        const participant = selectedBotRef.current ?? 'user';
        const poll = () => {
          api.fetchConversations(participant).then((list) => setConversations([...list]));
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
        setPendingApprovals([]);
        api.fetchConversations(selectedBotRef.current ?? 'user').then(setConversations);
      },
      fileIds,
      currentNotify
    );
  };

  const handleApproveTool = async (approvalId: string, note: string) => {
    setPendingApprovals((prev) => prev.filter((p) => p.approvalId !== approvalId));
    await api.approveTool(approvalId, note);
  };

  const handleRejectTool = async (approvalId: string, note: string) => {
    setPendingApprovals((prev) => prev.filter((p) => p.approvalId !== approvalId));
    await api.rejectTool(approvalId, note);
  };


  return (
    <div className="flex h-screen">
      <Sidebar
        providers={providers}
        bots={bots}
        conversations={displayConversations}
        activeId={activeId}
        selectedBot={selectedBot}
        onSelectBot={setSelectedBot}
        onSelectConversation={setActiveId}
        onCreateConversation={handleCreateConversation}
        onDeleteConversation={handleDeleteConversation}
        onAddProvider={handleAddProvider}
        onAddBot={handleAddBot}
        onAddParticipant={handleAddParticipant}
      />
      <ChatWindow
        key={activeId ?? ''}
        headerTitle={headerTitle}
        conversation={activeConversation}
        providers={providers}
        sending={sending}
        streaming={streaming}
        lastStreamedContent={activeId && lastStreamedContent?.conversationId === activeId ? lastStreamedContent : null}
        inputDraft={inputDraftRef.current}
        onInputDraftChange={setInputDraft}
        onSend={handleSend}
        onUpdateProvider={handleUpdateConversationProvider}
        pendingApprovals={pendingApprovals}
        onApproveTool={handleApproveTool}
        onRejectTool={handleRejectTool}
        notifyParticipants={notifyParticipants}
        onNotifyParticipantsChange={setNotifyParticipants}
      />
    </div>
  );
}
