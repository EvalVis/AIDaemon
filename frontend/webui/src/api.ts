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

export async function sendMessage(conversationId: string, message: string): Promise<string> {
  const res = await fetch(`/api/conversations/${conversationId}/messages`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ message }),
  });
  const data = await res.json();
  return data.response;
}

export async function deleteConversation(id: string): Promise<void> {
  await fetch(`/api/conversations/${id}`, { method: 'DELETE' });
}
