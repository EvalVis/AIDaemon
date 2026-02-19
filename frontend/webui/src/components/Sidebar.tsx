import { useState } from 'react';
import type { Conversation, CreateProviderRequest, Provider } from '../types';

interface SidebarProps {
  providers: Provider[];
  conversations: Conversation[];
  activeId: string | null;
  onSelectConversation: (id: string) => void;
  onCreateConversation: (name: string, providerId: string) => void;
  onDeleteConversation: (id: string) => void;
  onAddProvider: (req: CreateProviderRequest) => void;
}

export default function Sidebar({
  providers,
  conversations,
  activeId,
  onSelectConversation,
  onCreateConversation,
  onDeleteConversation,
  onAddProvider,
}: SidebarProps) {
  const [showProviderForm, setShowProviderForm] = useState(false);
  const [showNewConv, setShowNewConv] = useState(false);

  return (
    <aside className="sidebar">
      <ProviderButton
        show={showProviderForm}
        onToggle={() => setShowProviderForm(!showProviderForm)}
        providers={providers}
        onAdd={onAddProvider}
      />

      <div className="sidebar-section">
        <button className="btn-new-conv" onClick={() => setShowNewConv(!showNewConv)}>
          + New Conversation
        </button>
        {showNewConv && (
          <NewConversationForm
            providers={providers}
            onCreate={(name, pid) => {
              onCreateConversation(name, pid);
              setShowNewConv(false);
            }}
          />
        )}
      </div>

      <nav className="conversation-list">
        {conversations.map((c) => (
          <div
            key={c.id}
            className={`conversation-item${c.id === activeId ? ' active' : ''}`}
            onClick={() => onSelectConversation(c.id)}
          >
            <span className="conv-name">{c.name}</span>
            <span className="conv-count">{c.messages.length}</span>
            <button
              className="btn-delete-conv"
              onClick={(e) => {
                e.stopPropagation();
                onDeleteConversation(c.id);
              }}
            >
              &times;
            </button>
          </div>
        ))}
        {conversations.length === 0 && (
          <p className="empty-hint">No conversations yet</p>
        )}
      </nav>
    </aside>
  );
}

function ProviderButton({
  show,
  onToggle,
  providers,
  onAdd,
}: {
  show: boolean;
  onToggle: () => void;
  providers: Provider[];
  onAdd: (req: CreateProviderRequest) => void;
}) {
  const [name, setName] = useState('');
  const [type, setType] = useState('OPENAI');
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');

  const handleSubmit = () => {
    if (!name || !apiKey) return;
    onAdd({ name, type, apiKey, baseUrl: baseUrl || undefined, model: model || undefined });
    setName('');
    setApiKey('');
    setBaseUrl('');
    setModel('');
    onToggle();
  };

  return (
    <div className="sidebar-section provider-section">
      <button className="btn-add-provider" onClick={onToggle}>
        {show ? 'Cancel' : '+ Add Provider'}
      </button>
      {show && (
        <div className="provider-form">
          <input placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} />
          <select value={type} onChange={(e) => setType(e.target.value)}>
            <option value="OPENAI">OpenAI</option>
            <option value="ANTHROPIC">Anthropic</option>
            <option value="OLLAMA">Ollama</option>
            <option value="GEMINI">Gemini</option>
          </select>
          <input placeholder="API Key" type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} />
          <input placeholder="Base URL (optional)" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
          <input placeholder="Model (optional)" value={model} onChange={(e) => setModel(e.target.value)} />
          <button className="btn-submit" onClick={handleSubmit}>Add</button>
        </div>
      )}
      {providers.length > 0 && (
        <div className="provider-list">
          {providers.map((p) => (
            <span key={p.id} className="provider-tag">{p.name} ({p.model})</span>
          ))}
        </div>
      )}
    </div>
  );
}

function NewConversationForm({
  providers,
  onCreate,
}: {
  providers: Provider[];
  onCreate: (name: string, providerId: string) => void;
}) {
  const [name, setName] = useState('');
  const [providerId, setProviderId] = useState(providers[0]?.id ?? '');

  return (
    <div className="new-conv-form">
      <input
        placeholder="Conversation name"
        value={name}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && name && providerId) onCreate(name, providerId);
        }}
      />
      <select value={providerId} onChange={(e) => setProviderId(e.target.value)}>
        {providers.map((p) => (
          <option key={p.id} value={p.id}>{p.name}</option>
        ))}
      </select>
      <button className="btn-submit" onClick={() => name && providerId && onCreate(name, providerId)}>
        Create
      </button>
    </div>
  );
}
