export interface ChatMessage {
  role: string;
  content: string;
  timestampMillis?: number;
}

export interface Conversation {
  id: string;
  name: string;
  providerId: string;
  messages: ChatMessage[];
  parentConversationId?: string | null;
}

export interface Provider {
  id: string;
  name: string;
  type: 'OPENAI' | 'ANTHROPIC' | 'OLLAMA' | 'GEMINI';
  baseUrl: string;
  model: string;
}

export interface CreateProviderRequest {
  name: string;
  type: string;
  apiKey: string;
  baseUrl?: string;
  model?: string;
}
