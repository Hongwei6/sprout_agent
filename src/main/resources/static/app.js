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
  for (const item of conversation.messages) {
    addMessage(item.role, item.text);
    if (item.role === 'assistant') addReferences(item.references);
  }
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
  return bubble;
}

function addReferences(references) {
  if (!Array.isArray(references)) return;
  const section = document.createElement('section');
  section.className = 'references';
  const list = document.createElement('ol');
  for (const source of references) {
    let url;
    try {
      url = new URL(source.url);
      if (!['http:', 'https:'].includes(url.protocol)) continue;
    } catch {
      continue;
    }
    const item = document.createElement('li');
    const link = document.createElement('a');
    link.href = url.href;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.textContent = source.title || source.url;
    item.append(link);
    if (source.content) {
      const excerpt = document.createElement('p');
      excerpt.textContent = source.content;
      item.append(excerpt);
    }
    list.append(item);
  }
  if (!list.childElementCount) return;
  const heading = document.createElement('strong');
  heading.textContent = `参考来源（${list.childElementCount}）`;
  section.append(heading, list);
  messages.append(section);
  section.scrollIntoView({ block: 'nearest' });
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

  let searchStatus = null;
  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      body: JSON.stringify({ conversationId: requestedConversationId, message: text })
    });
    if (!response.ok || !response.body) throw new Error('无法开始对话');
    const bubble = token === viewToken ? addMessage('assistant', '') : null;
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let completed = false;
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
      buffer = buffer.replace(/\r\n/g, '\n');
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const name = frame.split('\n').find(line => line.startsWith('event:'))?.slice(6).trim();
        const data = frame.split('\n').filter(line => line.startsWith('data:'))
          .map(line => line.slice(5)).join('\n');
        if (name === 'conversation' && token === viewToken) activeId = data;
        if (name === 'thinking' && token === viewToken) {
          if (!searchStatus) searchStatus = addMessage('notice', data);
          else searchStatus.textContent = data;
        }
        if (name === 'delta' && bubble) {
          if (searchStatus) {
            searchStatus.remove();
            searchStatus = null;
          }
          bubble.textContent += data;
          bubble.scrollIntoView({ block: 'nearest' });
        }
        if (name === 'reference' && token === viewToken) {
          const reference = JSON.parse(data);
          if (reference.type === 'reference') {
            addReferences(typeof reference.content === 'string'
              ? JSON.parse(reference.content) : reference.content);
          }
        }
        if (name === 'error') throw new Error(data);
        if (name === 'done') {
          if (searchStatus) searchStatus.remove();
          completed = true;
        }
      }
      if (done) break;
    }
    if (!completed) throw new Error('对话流提前中断');
    if (token === viewToken) refreshHistory().catch(() => {});
  } catch (error) {
    if (searchStatus) searchStatus.remove();
    if (token === viewToken) addMessage('notice', error.message || '无法连接萌芽服务，请稍后重试。');
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
