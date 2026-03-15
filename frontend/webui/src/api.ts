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

export async function getOrCreateDirectConversation(
  botName: string,
  providerId?: string | null
): Promise<Conversation> {
  const params = providerId ? `?providerId=${encodeURIComponent(providerId)}` : '';
  const res = await fetch(`/api/conversations/direct/${encodeURIComponent(botName)}${params}`);
  if (!res.ok) {
    const err = await res.text();
    throw new Error(err || res.statusText);
  }
  return res.json();
}

export async function createConversation(
  name: string,
  providerId?: string | null
): Promise<{ conversationId: string; name: string; providerId: string | null }> {
  const res = await fetch('/api/conversations', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(providerId != null ? { name, providerId } : { name }),
  });
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

export interface StreamChunk {
  type: 'reasoning' | 'answer' | 'tool' | 'tool_pending';
  content: string;
}

export async function uploadFile(conversationId: string, file: File): Promise<FileAttachment> {
  const formData = new FormData();
  formData.append('file', file);
  const res = await fetch(`/api/conversations/${encodeURIComponent(conversationId)}/files`, { method: 'POST', body: formData });
  if (!res.ok) throw new Error('File upload failed');
  return res.json();
}

export async function sendMessage(conversationId: string, message: string, fileIds?: string[]): Promise<string> {
  const res = await fetch(`/api/conversations/${conversationId}/messages`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ message, fileIds: fileIds ?? [] }),
  });
  const data = await res.json();
  return data.response;
}

export function sendMessageStream(
  conversationId: string,
  message: string,
  onChunk: (chunk: StreamChunk) => void,
  onDone: () => void,
  onError: (err: unknown) => void,
  fileIds?: string[]
): void {
  fetch(`/api/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ message, fileIds: fileIds ?? [] }),
  })
    .then(async (res) => {
      if (!res.ok || !res.body) {
        onError(new Error(res.statusText));
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const events = buffer.split('\n\n');
        buffer = events.pop() ?? '';
        for (const event of events) {
          const dataLine = event.split('\n').find((l) => l.startsWith('data:'));
          if (dataLine) {
            const data = dataLine.slice(5).trim();
            if (data) {
              try {
                onChunk(JSON.parse(data) as StreamChunk);
              } catch {
                // skip non-JSON
              }
            }
          }
        }
      }
      if (buffer.trim()) {
        const dataLine = buffer.split('\n').find((l) => l.startsWith('data:'));
        if (dataLine) {
          const data = dataLine.slice(5).trim();
          if (data) {
            try {
              onChunk(JSON.parse(data) as StreamChunk);
            } catch {
              // skip
            }
          }
        }
      }
      onDone();
    })
    .catch(onError);
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
