import { useState } from 'react';
import type { Conversation, CreateProviderRequest, Provider } from '../types';

interface SidebarProps {
  providers: Provider[];
  conversations: Conversation[];
  activeId: string | null;
  onSelectConversation: (id: string) => void;
  onCreateConversation: (name: string, providerId?: string | null) => void;
  onDeleteConversation: (id: string) => void;
  onAddProvider: (req: CreateProviderRequest) => void;
}

type TreeNode = Conversation & { children: TreeNode[] };

function formatCreationTime(ms: number): string {
  const d = new Date(ms);
  const now = new Date();
  const sameDay = d.getDate() === now.getDate() && d.getMonth() === now.getMonth() && d.getFullYear() === now.getFullYear();
  if (sameDay) {
    return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (d.getDate() === yesterday.getDate() && d.getMonth() === yesterday.getMonth()) {
    return 'Yesterday ' + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' }) + ' ' + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

function byNewestFirst(a: { createdAtMillis?: number | null }, b: { createdAtMillis?: number | null }): number {
  const ta = a.createdAtMillis ?? 0;
  const tb = b.createdAtMillis ?? 0;
  return tb - ta;
}

function buildTree(conversations: Conversation[]): TreeNode[] {
  const sorted = [...conversations].sort(byNewestFirst);
  const byId = new Map<string, TreeNode>();
  for (const c of sorted) {
    byId.set(c.id, { ...c, children: [] });
  }
  const roots: TreeNode[] = [];
  for (const c of sorted) {
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
    node.children.sort(byNewestFirst);
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

  const regular = conversations.filter((c) => !c.name.startsWith('[SJ]')).sort(byNewestFirst);
  const scheduled = conversations.filter((c) => c.name.startsWith('[SJ]')).sort(byNewestFirst);
  const regularTree = buildTree(regular);
  const scheduledTree = buildTree(scheduled);

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
      <div key={node.id}>
        <div
          className={`flex items-center gap-1.5 px-3 py-2.5 cursor-pointer transition-colors duration-100 ${isSub ? 'pl-3' : ''} ${isActive ? 'bg-bg-active' : 'hover:bg-bg-hover'}`}
          onClick={() => onSelectConversation(node.id)}
        >
          {hasChildren ? (
            <button
              className="flex items-center justify-center w-6 h-6 shrink-0 rounded-md border border-border bg-bg-input/60 text-text-dim text-[0.65rem] font-medium cursor-pointer transition-all duration-150 hover:border-accent hover:text-accent hover:bg-bg-hover"
              onClick={(e) => toggleExpand(node.id, e, isExpanded)}
              title={isExpanded ? 'Collapse' : 'Expand'}
            >
              {isExpanded ? '▾' : '▸'}
            </button>
          ) : !isSub ? (
            <button
              className="flex items-center justify-center w-6 h-6 shrink-0 rounded-md border border-border bg-bg-input/60 text-text-dim text-[0.65rem] font-medium cursor-pointer transition-all duration-150 hover:border-accent hover:text-accent hover:bg-bg-hover"
              onClick={(e) => toggleExpand(node.id, e, expanded.has(node.id))}
              title={expanded.has(node.id) ? 'Collapse' : 'Expand'}
            >
              {expanded.has(node.id) ? '▾' : '▸'}
            </button>
          ) : (
            <span className="w-6 shrink-0 inline-block" />
          )}
          <span className={`flex-1 min-w-0 flex flex-col`}>
            <span className={`text-sm whitespace-nowrap overflow-hidden text-ellipsis ${isSub ? 'text-[0.8125rem] text-text' : 'text-text-bright'}`}>
              {node.name}
            </span>
            {node.createdAtMillis != null && (
              <span className="text-[0.6875rem] text-text-dim">
                {formatCreationTime(node.createdAtMillis)}
              </span>
            )}
          </span>
          <span className="text-xs text-text-dim min-w-5 text-right shrink-0">{node.messages.length}</span>
          <button
            className="bg-transparent border-0 text-text-dim text-base cursor-pointer py-0 px-1 leading-none transition-colors duration-150 hover:text-danger"
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
          <div className="border-l-2 border-border ml-1.5">
            {node.children.map((child) => renderConversation(child, true))}
          </div>
        )}
        {!hasChildren && !isSub && expanded.has(node.id) && (
          <div className="py-1 px-3 pb-2 pl-8 flex flex-col gap-0.5 bg-bg-sidebar">
            {node.messages.slice(-4).map((m, i) => (
              <div key={i} className="flex gap-1.5 text-xs leading-snug">
                <span className="text-text-dim shrink-0 font-semibold uppercase text-[0.625rem] mt-px">{m.role}</span>
                <span className="text-text overflow-hidden text-ellipsis whitespace-nowrap">
                  {typeof m.content === 'string' ? m.content.slice(0, 80) : ''}
                  {typeof m.content === 'string' && m.content.length > 80 ? '…' : ''}
                </span>
              </div>
            ))}
            {node.messages.length === 0 && (
              <span className="text-xs text-text-dim">No messages</span>
            )}
          </div>
        )}
      </div>
    );
  };

  return (
    <aside className="w-[280px] min-w-[280px] bg-bg-sidebar border-r border-border flex flex-col overflow-hidden">
      <ProviderButton
        show={showProviderForm}
        onToggle={() => setShowProviderForm(!showProviderForm)}
        providers={providers}
        onAdd={onAddProvider}
      />

      <div className="p-3 border-b border-border">
        <button
          className="w-full py-2 px-3 bg-bg-input text-text-bright border border-border rounded-lg cursor-pointer text-sm transition-colors duration-150 hover:bg-bg-hover"
          onClick={() => setShowNewConv(!showNewConv)}
        >
          + New Conversation
        </button>
        {showNewConv && (
          <NewConversationForm
            onCreate={(name) => {
              onCreateConversation(name);
              setShowNewConv(false);
            }}
          />
        )}
      </div>

      <nav className="flex-1 overflow-y-auto py-2 flex flex-col gap-4">
        <div>
          <h3 className="px-3 py-1.5 text-[0.6875rem] font-semibold uppercase tracking-wide text-text-dim">
            Conversations
          </h3>
          {regularTree.map((root) => renderConversation(root, false))}
          {regularTree.length === 0 && (
            <p className="text-center py-4 px-3 text-text-dim text-[0.8125rem]">No conversations yet</p>
          )}
        </div>
        <div>
          <h3 className="px-3 py-1.5 text-[0.6875rem] font-semibold uppercase tracking-wide text-text-dim">
            Scheduled jobs
          </h3>
          {scheduledTree.map((root) => renderConversation(root, false))}
          {scheduledTree.length === 0 && (
            <p className="text-center py-4 px-3 text-text-dim text-[0.8125rem]">No scheduled job runs yet</p>
          )}
        </div>
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
    <div className="p-3 border-b border-border">
      <button
        className="w-full py-2 px-3 bg-bg-input text-text-bright border border-border rounded-lg cursor-pointer text-sm transition-colors duration-150 hover:bg-bg-hover"
        onClick={onToggle}
      >
        {show ? 'Cancel' : '+ Add Provider'}
      </button>
      {show && (
        <div className="flex flex-col gap-1.5 mt-2">
          <input
            placeholder="Name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="p-1.5 px-2.5 bg-bg-input text-text border border-border rounded-lg text-[0.8125rem] outline-none focus:border-accent"
          />
          <select
            value={type}
            onChange={(e) => setType(e.target.value)}
            className="p-1.5 px-2.5 bg-bg-input text-text border border-border rounded-lg text-[0.8125rem] outline-none focus:border-accent"
          >
            <option value="OPENAI">OpenAI</option>
            <option value="ANTHROPIC">Anthropic</option>
            <option value="OLLAMA">Ollama</option>
            <option value="GEMINI">Gemini</option>
          </select>
          <input
            placeholder="API Key"
            type="password"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            className="p-1.5 px-2.5 bg-bg-input text-text border border-border rounded-lg text-[0.8125rem] outline-none focus:border-accent"
          />
          <input
            placeholder="Base URL (optional)"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            className="p-1.5 px-2.5 bg-bg-input text-text border border-border rounded-lg text-[0.8125rem] outline-none focus:border-accent"
          />
          <input
            placeholder="Model (optional)"
            value={model}
            onChange={(e) => setModel(e.target.value)}
            className="p-1.5 px-2.5 bg-bg-input text-text border border-border rounded-lg text-[0.8125rem] outline-none focus:border-accent"
          />
          <button
            className="py-1.5 px-3 bg-accent text-white border-0 rounded-lg cursor-pointer text-[0.8125rem] transition-colors duration-150 hover:bg-accent-hover"
            onClick={handleSubmit}
          >
            Add
          </button>
        </div>
      )}
      {providers.length > 0 && (
        <div className="flex flex-wrap gap-1 mt-2">
          {providers.map((p) => (
            <span key={p.id} className="text-xs text-text-dim bg-bg-input py-0.5 px-2 rounded-xl">
              {p.name} ({p.model})
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

function NewConversationForm({ onCreate }: { onCreate: (name: string) => void }) {
  const [name, setName] = useState('');

  return (
    <div className="flex flex-col gap-1.5 mt-2">
      <input
        placeholder="Conversation name"
        value={name}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && name.trim()) onCreate(name.trim());
        }}
        className="p-1.5 px-2.5 bg-bg-input text-text border border-border rounded-lg text-[0.8125rem] outline-none focus:border-accent"
      />
      <button
        className="py-1.5 px-3 bg-accent text-white border-0 rounded-lg cursor-pointer text-[0.8125rem] transition-colors duration-150 hover:bg-accent-hover"
        onClick={() => name.trim() && onCreate(name.trim())}
      >
        Create
      </button>
    </div>
  );
}
