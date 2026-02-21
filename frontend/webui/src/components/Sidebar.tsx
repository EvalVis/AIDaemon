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

type TreeNode = Conversation & { children: TreeNode[] };

function buildTree(conversations: Conversation[]): TreeNode[] {
  const byId = new Map<string, TreeNode>();
  for (const c of conversations) {
    byId.set(c.id, { ...c, children: [] });
  }
  const roots: TreeNode[] = [];
  for (const c of conversations) {
    const node = byId.get(c.id)!;
    const pid = c.parentConversationId ?? null;
    if (pid == null) {
      roots.push(node);
    } else {
      const parent = byId.get(pid);
      if (parent) parent.children.push(node);
      else roots.push(node);
    }
  }
  for (const node of byId.values()) {
    node.children.sort((a, b) => a.name.localeCompare(b.name));
  }
  return roots;
}

function hasActiveDescendant(node: TreeNode, activeId: string | null): boolean {
  if (!activeId) return false;
  if (node.id === activeId) return true;
  return node.children.some((c) => hasActiveDescendant(c, activeId));
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
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  const tree = buildTree(conversations);

  const toggleExpand = (id: string, e: React.MouseEvent, currentlyExpanded: boolean) => {
    e.stopPropagation();
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (currentlyExpanded) next.add(id);
      else next.delete(id);
      return next;
    });
    setExpanded((prev) => {
      const next = new Set(prev);
      if (currentlyExpanded) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const renderConversation = (node: TreeNode, isSub: boolean) => {
    const hasChildren = node.children.length > 0;
    const isActive = node.id === activeId;
    const autoExpanded = hasChildren && hasActiveDescendant(node, activeId);
    const isExpanded =
      !collapsed.has(node.id) && (expanded.has(node.id) || autoExpanded);
    return (
      <div
        key={node.id}
        className={`conversation-entry${isSub ? ' subconversation' : ''}${isActive ? ' active' : ''}`}
      >
        <div className="conversation-item" onClick={() => onSelectConversation(node.id)}>
          {hasChildren ? (
            <button
              className="btn-expand"
              onClick={(e) => toggleExpand(node.id, e, isExpanded)}
              title={isExpanded ? 'Collapse' : 'Expand'}
            >
              {isExpanded ? '▾' : '▸'}
            </button>
          ) : !isSub ? (
            <button
              className="btn-expand"
              onClick={(e) => toggleExpand(node.id, e, expanded.has(node.id))}
              title={expanded.has(node.id) ? 'Collapse' : 'Expand'}
            >
              {expanded.has(node.id) ? '▾' : '▸'}
            </button>
          ) : (
            <span className="btn-expand-placeholder" />
          )}
          <span className="conv-name">{node.name}</span>
          <span className="conv-count">{node.messages.length}</span>
          <button
            className="btn-delete-conv"
            onClick={(e) => {
              e.stopPropagation();
              if (window.confirm(`Delete conversation "${node.name}"?`)) {
                onDeleteConversation(node.id);
              }
            }}
          >
            &times;
          </button>
        </div>
        {hasChildren && isExpanded && (
          <div className="subconversation-list">
            {node.children.map((child) => renderConversation(child, true))}
          </div>
        )}
        {!hasChildren && !isSub && expanded.has(node.id) && (
          <div className="conv-preview">
            {node.messages.slice(-4).map((m, i) => (
              <div key={i} className={`conv-preview-msg ${m.role}`}>
                <span className="conv-preview-role">{m.role}</span>
                <span className="conv-preview-text">
                  {typeof m.content === 'string' ? m.content.slice(0, 80) : ''}
                  {typeof m.content === 'string' && m.content.length > 80 ? '…' : ''}
                </span>
              </div>
            ))}
            {node.messages.length === 0 && (
              <span className="conv-preview-empty">No messages</span>
            )}
          </div>
        )}
      </div>
    );
  };

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
        {tree.map((root) => renderConversation(root, false))}
        {tree.length === 0 && (
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
