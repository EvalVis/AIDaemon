export interface FileAttachment {
  id: string;
  name: string;
  mimeType: string;
}

export interface ChatMessage {
  participant: string;
  content: string;
  timestampMillis?: number;
  files?: FileAttachment[];
}

export interface Conversation {
  id: string;
  name: string;
  providerId: string | null;
  messages: ChatMessage[];
  parentConversationId?: string | null;
  createdAtMillis?: number | null;
  participant1?: string | null;
  participant2?: string | null;
}

export interface Provider {
  id: string;
  name: string;
  type: 'OPENAI' | 'ANTHROPIC' | 'OLLAMA' | 'GEMINI' | 'DALLE_3';
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

export interface PendingToolApproval {
  approvalId: string;
  toolName: string;
  toolInput: string;
  // Present only for file edit tools (createFile / modifyFile / deleteFile)
  operation?: 'CREATE' | 'MODIFY' | 'DELETE';
  path?: string;
  oldContent?: string | null;
  newContent?: string | null;
}
