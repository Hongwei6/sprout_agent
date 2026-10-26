const form = document.querySelector('#chat-form');
const promptInput = document.querySelector('#prompt');
const sendButton = document.querySelector('#send-button');
const messages = document.querySelector('#messages');
const welcome = document.querySelector('#welcome');
const historyList = document.querySelector('#conversation-list');

let conversations = [];
let activeId = null;
let viewToken = 0;
let isSending = false;

function renderHistory() {
  historyList.replaceChildren();
  for (const conversation of conversations) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `history-item${conversation.id === activeId ? ' active' : ''}`;
    button.textContent = conversation.title;
    button.title = conversation.title;
    button.addEventListener('click', () => openConversation(conversation.id));
    historyList.append(button);
  }
}

async function refreshHistory() {
  const response = await fetch('/api/conversations');
  if (!response.ok) throw new Error('无法读取对话列表');
  conversations = await response.json();
  renderHistory();
}

function renderConversation(conversation) {
  messages.replaceChildren();
  welcome.hidden = conversation.messages.length > 0;
  for (const item of conversation.messages) addMessage(item.role, item.text);
  messages.scrollTop = messages.scrollHeight;
}

async function openConversation(id) {
  activeId = id;
  const token = ++viewToken;
  renderHistory();
  try {
    const response = await fetch(`/api/conversations/${encodeURIComponent(id)}`);
    if (!response.ok) throw new Error('无法读取对话');
    const conversation = await response.json();
    if (token === viewToken) renderConversation(conversation);
  } catch {
    if (token === viewToken) startConversation();
  }
}

function startConversation() {
  activeId = null;
  viewToken += 1;
  messages.replaceChildren();
  welcome.hidden = false;
  promptInput.focus();
  renderHistory();
}

function addMessage(role, text) {
  const bubble = document.createElement('div');
  bubble.className = `message ${role}`;
  bubble.textContent = text;
  messages.append(bubble);
  bubble.scrollIntoView({ block: 'nearest' });
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = promptInput.value.trim();
  if (!text || isSending) return;

  const requestedConversationId = activeId;
  const token = viewToken;
  isSending = true;
  welcome.hidden = true;
  addMessage('user', text);
  promptInput.value = '';
  promptInput.style.height = 'auto';
  sendButton.disabled = true;

  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId: requestedConversationId, message: text })
    });
    const result = await response.json();
    if (token === viewToken) {
      if (result.conversationId) activeId = result.conversationId;
      addMessage(response.ok ? 'assistant' : 'notice', result.answer || '萌芽暂时无法回复，请稍后重试。');
      refreshHistory().catch(() => {});
    }
  } catch {
    if (token === viewToken) addMessage('notice', '无法连接萌芽服务，请确认后端已启动后重试。');
  } finally {
    isSending = false;
    sendButton.disabled = promptInput.value.trim().length === 0;
  }
});

promptInput.addEventListener('input', () => {
  sendButton.disabled = isSending || promptInput.value.trim().length === 0;
  promptInput.style.height = 'auto';
  promptInput.style.height = `${Math.min(promptInput.scrollHeight, 150)}px`;
});

promptInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    if (!sendButton.disabled) form.requestSubmit();
  }
});

document.querySelector('#new-chat').addEventListener('click', startConversation);
document.querySelector('#mobile-new-chat').addEventListener('click', startConversation);
document.querySelector('#clear-chat').addEventListener('click', async () => {
  if (!activeId) return;
  const id = activeId;
  try {
    const response = await fetch(`/api/conversations/${encodeURIComponent(id)}`, { method: 'DELETE' });
    if (!response.ok) throw new Error('无法删除对话');
    if (activeId === id) startConversation();
    await refreshHistory();
  } catch {
    if (activeId === id) addMessage('notice', '删除对话失败，请稍后重试。');
  }
});

document.querySelectorAll('.suggestion').forEach((button) => {
  button.addEventListener('click', () => {
    promptInput.value = button.dataset.prompt;
    promptInput.dispatchEvent(new Event('input'));
    form.requestSubmit();
  });
});

refreshHistory().catch(() => {});
