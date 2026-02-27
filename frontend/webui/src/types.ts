export interface ChatMessage {
  role: string;
  content: string;
  timestampMillis?: number;
}

export interface Conversation {
  id: string;
  name: string;
  providerId: string | null;
  botName?: string | null;
  messages: ChatMessage[];
  parentConversationId?: string | null;
  createdAtMillis?: number | null;
  participant1?: string | null;
  participant2?: string | null;
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

export interface Bot {
  name: string;
}

export interface CreateBotRequest {
  name: string;
  soul: string;
}
