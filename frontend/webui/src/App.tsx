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

export default function App() {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [bots, setBots] = useState<Bot[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [selectedBot, setSelectedBot] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [notifyParticipants, setNotifyParticipants] = useState<string[]>([]);
  const [pendingApprovals, setPendingApprovals] = useState<PendingToolApproval[]>([]);
  const [, setDraftVersion] = useState(0);
  const inputDraftRef = useRef('');
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
    const poll = () => {
      const participant = selectedBotRef.current ?? 'user';
      api.fetchConversations(participant).then((list) => setConversations([...list]));
      api.fetchPendingApprovals().then((list) =>
        setPendingApprovals(list.map((a) => ({ approvalId: a.approvalId, toolName: a.toolName, toolInput: a.toolInput })))
      );
    };
    pollIntervalRef.current = setInterval(poll, 3000);
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
    const files = attachments ?? [];
    setConversations((prev) =>
      prev.map((c) =>
        c.id === activeId
          ? { ...c, messages: [...c.messages, { participant: 'user', content: message, timestampMillis: Date.now(), files }] }
          : c,
      ),
    );
    setSending(true);
    const fileIds = files.map((f) => f.id);
    const currentNotify = notifyParticipants.length > 0
      ? notifyParticipants
      : botParticipantsOf(activeConversation);
    try {
      await api.sendMessage(activeId, message, fileIds, currentNotify);
    } catch {
      // message saved even if notify fails
    }
    setSending(false);
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
