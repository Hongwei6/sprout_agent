const form = document.querySelector('#chat-form');
const promptInput = document.querySelector('#prompt');
const sendButton = document.querySelector('#send-button');
const messages = document.querySelector('#messages');
const welcome = document.querySelector('#welcome');
const historyList = document.querySelector('#conversation-list');
const historyDialog = document.querySelector('#history-dialog');
const historySearch = document.querySelector('#history-search');
const historyFilter = document.querySelector('#history-filter');
const historyResults = document.querySelector('#history-results');
const renameIcon = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5 4 4M4 20l4.5-1 11-11a2.8 2.8 0 0 0-4-4l-11 11L4 20Z"/></svg>';
const archiveIcon = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 4h16v4H4zM6 8v12h12V8M10 12h4"/></svg>';

let conversations = [];
let activeId = null;
let viewToken = 0;
let isSending = false;
let activeArchived = false;
let historyRequest = 0;

function renderHistory() {
  historyList.replaceChildren();
  for (const conversation of conversations) {
    const row = document.createElement('div');
    row.className = `history-entry${conversation.id === activeId ? ' active' : ''}`;
    const title = document.createElement('button');
    title.type = 'button';
    title.className = 'history-item';
    title.textContent = conversation.title;
    title.title = conversation.title;
    title.addEventListener('click', () => openConversation(conversation.id));
    const rename = document.createElement('button');
    rename.type = 'button';
    rename.className = 'conversation-action';
    rename.innerHTML = renameIcon;
    rename.title = `重命名：${conversation.title}`;
    rename.setAttribute('aria-label', rename.title);
    rename.addEventListener('click', () => editTitle(conversation, title));
    const archive = document.createElement('button');
    archive.type = 'button';
    archive.className = 'conversation-action';
    archive.innerHTML = archiveIcon;
    archive.title = `归档：${conversation.title}`;
    archive.setAttribute('aria-label', archive.title);
    archive.addEventListener('click', async () => {
      try {
        const response = await fetch(`/api/conversations/${conversation.id}/archive`, { method: 'PATCH' });
        if (!response.ok) throw new Error('归档失败');
        if (activeId === conversation.id) startConversation();
        await refreshHistory();
      } catch {
        addMessage('notice', '归档失败，请稍后重试。');
      }
    });
    row.append(title, rename, archive);
    historyList.append(row);
  }
}

function editTitle(conversation, titleButton) {
  const input = document.createElement('input');
  input.className = 'title-edit';
  input.maxLength = 50;
  input.value = conversation.title;
  titleButton.replaceWith(input);
  input.focus();
  input.select();
  const cancel = () => { if (input.isConnected) input.replaceWith(titleButton); };
  input.addEventListener('keydown', async (event) => {
    if (event.key === 'Escape') { cancel(); return; }
    if (event.key !== 'Enter') return;
    const title = input.value.trim();
    if (!title || title.length > 50) { input.setCustomValidity('标题长度必须为 1 到 50 个字符'); input.reportValidity(); return; }
    if (title === conversation.title) { cancel(); return; }
    const response = await fetch(`/api/conversations/${conversation.id}/title`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title })
    });
    if (!response.ok) { input.setCustomValidity('保存失败，请重试'); input.reportValidity(); return; }
    await refreshHistory();
    if (historyDialog.open) searchHistory().catch(() => {});
  });
  input.addEventListener('blur', cancel);
}

async function refreshHistory() {
  const response = await fetch('/api/conversations');
  if (!response.ok) throw new Error('无法读取对话列表');
  conversations = await response.json();
  renderHistory();
}

async function searchHistory() {
  const request = ++historyRequest;
  const params = new URLSearchParams({ archived: historyFilter.value, query: historySearch.value.trim() });
  const response = await fetch(`/api/conversations?${params}`);
  if (!response.ok) throw new Error('无法查询历史对话');
  const results = await response.json();
  if (request !== historyRequest || !historyDialog.open) return;
  historyResults.replaceChildren();
  for (const conversation of results) {
    const row = document.createElement('div');
    row.className = 'history-row';
    const title = document.createElement('button');
    title.type = 'button';
    title.textContent = conversation.title;
    title.addEventListener('click', () => {
      historyDialog.close();
      openConversation(conversation.id);
    });
    const date = document.createElement('time');
    date.textContent = new Date(conversation.updatedAt).toLocaleString('zh-CN');
    const rename = document.createElement('button');
    rename.type = 'button';
    rename.className = 'conversation-action';
    rename.innerHTML = renameIcon;
    rename.title = `重命名：${conversation.title}`;
    rename.setAttribute('aria-label', rename.title);
    rename.addEventListener('click', () => editTitle(conversation, title));
    const action = document.createElement('button');
    action.type = 'button';
    action.textContent = historyFilter.value === 'true' ? '恢复' : '归档';
    action.addEventListener('click', async () => {
      const archived = historyFilter.value !== 'true';
      const response = await fetch(`/api/conversations/${conversation.id}/${archived ? 'archive' : 'restore'}`,
        { method: 'PATCH' });
      if (!response.ok) {
        historyResults.textContent = '操作失败，请重试。';
        return;
      }
      if (activeId === conversation.id) {
        if (archived) startConversation();
        else activeArchived = false;
        updateComposerState();
      }
      await Promise.all([refreshHistory(), searchHistory()]);
    });
    row.append(title, date, rename, action);
    historyResults.append(row);
  }
  if (!results.length) historyResults.textContent = '没有匹配的对话';
}

function updateComposerState() {
  promptInput.disabled = activeArchived;
  sendButton.disabled = activeArchived || isSending || !promptInput.value.trim();
}

function renderConversation(conversation) {
  messages.replaceChildren();
  welcome.hidden = conversation.messages.length > 0;
  for (const item of conversation.messages) {
    addMessage(item.role, item.text);
    if (item.role === 'assistant') addReferences(item.references);
    if (item.role === 'assistant') addRecommendations(item.recommendations);
  }
  messages.scrollTop = messages.scrollHeight;
}

async function openConversation(id) {
  activeId = id;
  activeArchived = false;
  const token = ++viewToken;
  renderHistory();
  try {
    const response = await fetch(`/api/conversations/${encodeURIComponent(id)}`);
    if (!response.ok) throw new Error('无法读取对话');
    const conversation = await response.json();
    if (token === viewToken) {
      activeArchived = conversation.archived;
      renderConversation(conversation);
      updateComposerState();
    }
  } catch {
    if (token === viewToken) startConversation();
  }
}

function startConversation() {
  activeId = null;
  activeArchived = false;
  updateComposerState();
  viewToken += 1;
  messages.replaceChildren();
  welcome.hidden = false;
  promptInput.focus();
  renderHistory();
}

function renderAnswer(bubble, text) {
  if (!window.marked || !window.DOMPurify) {
    bubble.textContent = text;
    return;
  }
  bubble.innerHTML = DOMPurify.sanitize(marked.parse(text), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['img', 'iframe', 'form', 'style']
  });
  for (const link of bubble.querySelectorAll('a[href]')) {
    if (!['http:', 'https:'].includes(new URL(link.href).protocol)) {
      link.removeAttribute('href');
      continue;
    }
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
  }
}

function addMessage(role, text) {
  const bubble = document.createElement('div');
  bubble.className = `message ${role}`;
  if (role === 'assistant') renderAnswer(bubble, text);
  else bubble.textContent = text;
  messages.append(bubble);
  bubble.scrollIntoView({ block: 'nearest' });
  return bubble;
}

function addReferences(references) {
  if (!Array.isArray(references)) return;
  const section = document.createElement('details');
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
    const preview = document.createElement('div');
    preview.className = 'reference-preview';
    const address = document.createElement('small');
    address.textContent = url.hostname;
    preview.append(address);
    if (source.content) {
      const excerpt = document.createElement('p');
      excerpt.textContent = source.content;
      preview.append(excerpt);
    }
    item.append(preview);
    list.append(item);
  }
  if (!list.childElementCount) return;
  const heading = document.createElement('summary');
  heading.textContent = `参考来源（${list.childElementCount}）`;
  section.append(heading, list);
  messages.append(section);
}
function addRecommendations(questions) {
  if (!Array.isArray(questions) || !questions.length) return;
  const section = document.createElement('section');
  section.className = 'recommendations';
  const title = document.createElement('strong');
  title.textContent = '你还可以问';
  section.append(title);
  for (const question of questions) {
    if (typeof question !== 'string' || !question.trim()) continue;
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = question;
    button.addEventListener('click', () => {
      promptInput.value = question;
      form.requestSubmit();
    });
    section.append(button);
  }
  if (section.childElementCount > 1) messages.append(section);
}


form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = promptInput.value.trim();
  if (!text || isSending || activeArchived) return;

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
    let answer = '';
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
        if (name === 'conversation' && token === viewToken) {
          activeId = data;
          updateComposerState();
        }
        if (name === 'thinking' && token === viewToken) {
          if (!searchStatus) searchStatus = addMessage('notice', data);
          else searchStatus.textContent = data;
        }
        if (name === 'delta' && bubble) {
          if (searchStatus) {
            searchStatus.remove();
            searchStatus = null;
          }
          answer += data;
          renderAnswer(bubble, answer);
          bubble.scrollIntoView({ block: 'nearest' });
        }
        if (name === 'reference' && token === viewToken) {
          const reference = JSON.parse(data);
          if (reference.type === 'reference') {
            addReferences(typeof reference.content === 'string'
              ? JSON.parse(reference.content) : reference.content);
          }
        }
        if (name === 'recommend' && token === viewToken) {
          const recommendation = JSON.parse(data);
          if (recommendation.type === 'recommend') addRecommendations(recommendation.content);
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
    sendButton.disabled = activeArchived || promptInput.value.trim().length === 0;
  }
});

promptInput.addEventListener('input', () => {
  sendButton.disabled = activeArchived || isSending || promptInput.value.trim().length === 0;
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
document.querySelector('#mobile-history').addEventListener('click', () => {
  historyDialog.showModal();
  searchHistory().catch(() => { historyResults.textContent = '无法查询历史对话'; });
});
document.querySelector('#mobile-new-chat').addEventListener('click', startConversation);
document.querySelector('#history-open').addEventListener('click', () => {
  historyDialog.showModal();
  searchHistory().catch(() => { historyResults.textContent = '无法查询历史对话'; });
});
document.querySelector('#history-close').addEventListener('click', () => historyDialog.close());
historySearch.addEventListener('input', () => {
  searchHistory().catch(() => { historyResults.textContent = '无法查询历史对话'; });
});
historyFilter.addEventListener('change', () => {
  searchHistory().catch(() => { historyResults.textContent = '无法查询历史对话'; });
});
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
