import type { Conversation, CreateProviderRequest, Provider } from './types';

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

export async function fetchConversations(): Promise<Conversation[]> {
  const res = await fetch('/api/conversations');
  return res.json();
}

export async function createConversation(name: string, providerId: string): Promise<{ conversationId: string; name: string; providerId: string }> {
  const res = await fetch('/api/conversations', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ name, providerId }),
  });
  return res.json();
}

export interface StreamChunk {
  type: 'reasoning' | 'answer' | 'tool';
  content: string;
}

export async function sendMessage(conversationId: string, message: string): Promise<string> {
  const res = await fetch(`/api/conversations/${conversationId}/messages`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ message }),
  });
  const data = await res.json();
  return data.response;
}

export function sendMessageStream(
  conversationId: string,
  message: string,
  onChunk: (chunk: StreamChunk) => void,
  onDone: () => void,
  onError: (err: unknown) => void
): void {
  fetch(`/api/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ message }),
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

export async function deleteConversation(id: string): Promise<void> {
  await fetch(`/api/conversations/${id}`, { method: 'DELETE' });
}
