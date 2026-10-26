const STORAGE_KEY = 'sprout-agent-conversations';
const form = document.querySelector('#chat-form');
const promptInput = document.querySelector('#prompt');
const sendButton = document.querySelector('#send-button');
const messages = document.querySelector('#messages');
const welcome = document.querySelector('#welcome');
const historyList = document.querySelector('#conversation-list');

let conversations = loadConversations();
let activeId = null;

function loadConversations() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]');
    return Array.isArray(saved) ? saved : [];
  } catch {
    return [];
  }
}

function saveConversations() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(conversations));
}

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

function renderConversation(conversation) {
  messages.replaceChildren();
  welcome.hidden = conversation.messages.length > 0;
  for (const item of conversation.messages) {
    const bubble = document.createElement('div');
    bubble.className = `message ${item.role}`;
    bubble.textContent = item.text;
    messages.append(bubble);
  }
  messages.scrollTop = messages.scrollHeight;
}

function openConversation(id) {
  activeId = id;
  const conversation = conversations.find((item) => item.id === id);
  if (conversation) renderConversation(conversation);
  renderHistory();
}

function startConversation() {
  activeId = null;
  messages.replaceChildren();
  welcome.hidden = false;
  promptInput.focus();
  renderHistory();
}

function addMessage(conversation, role, text) {
  conversation.messages.push({ role, text });
  const bubble = document.createElement('div');
  bubble.className = `message ${role}`;
  bubble.textContent = text;
  messages.append(bubble);
  bubble.scrollIntoView({ block: 'nearest' });
}

form.addEventListener('submit', (event) => {
  event.preventDefault();
  const text = promptInput.value.trim();
  if (!text) return;

  let conversation = conversations.find((item) => item.id === activeId);
  if (!conversation) {
    conversation = { id: crypto.randomUUID(), title: text.slice(0, 32), messages: [] };
    conversations.unshift(conversation);
    activeId = conversation.id;
  }
  welcome.hidden = true;
  addMessage(conversation, 'user', text);
  addMessage(conversation, 'notice', '萌芽的模型服务尚未接入。这条消息已保存在当前浏览器中；配置 AI 服务后即可开始对话。');
  promptInput.value = '';
  promptInput.style.height = 'auto';
  sendButton.disabled = true;
  saveConversations();
  renderHistory();
});

promptInput.addEventListener('input', () => {
  sendButton.disabled = promptInput.value.trim().length === 0;
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
document.querySelector('#clear-chat').addEventListener('click', () => {
  if (!activeId) return;
  conversations = conversations.filter((item) => item.id !== activeId);
  saveConversations();
  startConversation();
});

document.querySelectorAll('.suggestion').forEach((button) => {
  button.addEventListener('click', () => {
    promptInput.value = button.dataset.prompt;
    promptInput.dispatchEvent(new Event('input'));
    form.requestSubmit();
  });
});

renderHistory();
