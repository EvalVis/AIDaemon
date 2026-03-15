import type { Bot, Conversation, CreateBotRequest, CreateProviderRequest, FileAttachment, Provider } from './types';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export async function fetchProviders(): Promise<Provider[]> {
  const res = await fetch('/api/providers');
  return res.json();
}

export async function createProvider(req: CreateProviderRequest): Promise<Provider> {
  const res = await fetch('/api/providers', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(req),
  });
  return res.json();
}

export async function deleteProvider(id: string): Promise<void> {
  await fetch(`/api/providers/${id}`, { method: 'DELETE' });
}

export async function fetchConversations(participant?: string | null): Promise<Conversation[]> {
  const url = participant != null && participant !== ''
    ? `/api/conversations?participant=${encodeURIComponent(participant)}`
    : '/api/conversations';
  const res = await fetch(url);
  return res.json();
}

export async function createConversation(
  name: string,
  providerId?: string | null,
  participants?: string[]
): Promise<Conversation> {
  const body: Record<string, unknown> = { name };
  if (providerId != null) body.providerId = providerId;
  if (participants != null && participants.length > 0) body.participants = participants;
  const res = await fetch('/api/conversations', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await res.text() || res.statusText);
  return res.json();
}

export async function addParticipant(conversationId: string, participantName: string): Promise<Conversation> {
  const res = await fetch(`/api/conversations/${encodeURIComponent(conversationId)}/participants`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ participantName }),
  });
  if (!res.ok) throw new Error(await res.text() || res.statusText);
  return res.json();
}

export async function updateConversation(
  id: string,
  patch: { providerId?: string | null }
): Promise<Conversation> {
  const res = await fetch(`/api/conversations/${id}`, {
    method: 'PATCH',
    headers: JSON_HEADERS,
    body: JSON.stringify(patch),
  });
  return res.json();
}

export async function uploadFile(conversationId: string, file: File): Promise<FileAttachment> {
  const formData = new FormData();
  formData.append('file', file);
  const res = await fetch(`/api/conversations/${encodeURIComponent(conversationId)}/files`, { method: 'POST', body: formData });
  if (!res.ok) throw new Error('File upload failed');
  return res.json();
}

export async function sendMessage(
  conversationId: string,
  message: string,
  fileIds?: string[],
  notifyParticipants?: string[]
): Promise<void> {
  const res = await fetch(`/api/conversations/${conversationId}/messages`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ message, fileIds: fileIds ?? [], notifyParticipants: notifyParticipants ?? [] }),
  });
  if (!res.ok) throw new Error(await res.text() || res.statusText);
}

export async function fetchPendingApprovals(): Promise<{ approvalId: string; toolName: string; toolInput: string }[]> {
  const res = await fetch('/api/tools/pending');
  return res.json();
}

export async function approveTool(approvalId: string, note: string): Promise<void> {
  await fetch(`/api/tools/${encodeURIComponent(approvalId)}/approve`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ note }),
  });
}

export async function rejectTool(approvalId: string, note: string): Promise<void> {
  await fetch(`/api/tools/${encodeURIComponent(approvalId)}/reject`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ note }),
  });
}

export async function deleteConversation(id: string): Promise<void> {
  await fetch(`/api/conversations/${id}`, { method: 'DELETE' });
}

export async function fetchBots(): Promise<Bot[]> {
  const res = await fetch('/api/bots');
  return res.json();
}

export async function createBot(req: CreateBotRequest): Promise<Bot> {
  const res = await fetch('/api/bots', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(req),
  });
  return res.json();
}
