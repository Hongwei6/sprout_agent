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
const dialogueTab = document.querySelector('#dialogue-tab');
const fileTab = document.querySelector('#file-tab');
const pptTab = document.querySelector('#ppt-tab');
const researchTab = document.querySelector('#research-tab');
const skillsMenu = document.querySelector('#skills-menu');
const skillsTab = document.querySelector('#skills-tab');
const skillsList = document.querySelector('#skills-list');
const selectedSkillBadge = document.querySelector('#selected-skill');
const selectedSkillName = document.querySelector('#selected-skill-name');
const clearSkillButton = document.querySelector('#clear-skill');
const fileControls = document.querySelector('#file-controls');
const fileInput = document.querySelector('#file-input');
const fileStatus = document.querySelector('#file-status');
const composerHint = document.querySelector('.composer-hint');
const pptDownload = document.querySelector('#ppt-download');
const pptDownloadLink = document.querySelector('#ppt-download-link');
const researchProgressPrefix = '深度研究进度\n';
const renameIcon = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5 4 4M4 20l4.5-1 11-11a2.8 2.8 0 0 0-4-4l-11 11L4 20Z"/></svg>';
const archiveIcon = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 4h16v4H4zM6 8v12h12V8M10 12h4"/></svg>';

let conversations = [];
let activeId = null;
let viewToken = 0;
let isSending = false;
let runningId = null;
let chatAbort = null;
let stopPending = false;
let activeArchived = false;
let historyRequest = 0;
let assistantMode = 'dialogue';
let modeSelection = 0;
let selectedSkill = null;
let selectedFile = null;
let isUploading = false;
let uploadingFileName = '';
let fileError = '';
let uploadToken = 0;


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
      } catch (error) {
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
    try {
      const response = await fetch(`/api/conversations/${conversation.id}/title`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title })
      });
      if (!response.ok) { input.setCustomValidity('保存失败，请重试'); input.reportValidity(); return; }
      await refreshHistory();
      if (historyDialog.open) searchHistory().catch((error) => {
        historyResults.textContent = '无法查询历史对话';
      });
    } catch (error) {
      input.setCustomValidity('保存失败，请重试');
      input.reportValidity();
    }
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
      try {
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
      } catch (error) {
        historyResults.textContent = '操作失败，请重试。';
      }
    });
    row.append(title, date, rename, action);
    historyResults.append(row);
  }
  if (!results.length) historyResults.textContent = '没有匹配的对话';
}

function updateComposerState() {
  const fileMode = assistantMode === 'file';
  promptInput.disabled = activeArchived;
  sendButton.disabled = isSending ? stopPending
    : activeArchived || !promptInput.value.trim() || (fileMode && (isUploading || !selectedFile));
  fileInput.disabled = activeArchived || isSending || isUploading;
  dialogueTab.disabled = isUploading || isSending;
  fileTab.disabled = isUploading || isSending;
  pptTab.disabled = isUploading || isSending;
  researchTab.disabled = isUploading || isSending;
  skillsMenu.classList.toggle('disabled', isUploading || isSending);
  skillsTab.setAttribute('aria-disabled', String(isUploading || isSending));
  fileControls.classList.toggle('disabled', fileInput.disabled);
  fileStatus.classList.toggle('error', !!fileError);
  fileControls.hidden = !fileMode;
  selectedSkillBadge.hidden = assistantMode !== 'skills' || !selectedSkill;
  selectedSkillName.textContent = selectedSkill ? `@${selectedSkill}` : '';
  for (const [tab, mode] of [[dialogueTab, 'dialogue'], [fileTab, 'file'], [pptTab, 'ppt'], [researchTab, 'research'], [skillsTab, 'skills']]) {
    const selected = assistantMode === mode;
    tab.classList.toggle('active', selected);
    if (selected) tab.setAttribute('aria-current', 'page');
    else tab.removeAttribute('aria-current');
  }
  promptInput.placeholder = assistantMode === 'ppt' ? '描述 PPT 主题、页数、风格和受众…'
    : assistantMode === 'research' ? '描述要研究的问题、范围或目标…'
    : assistantMode === 'skills' ? (selectedSkill ? `描述要交给 @${selectedSkill} 的任务…` : '描述任务，或提供公开 HTTPS 技能地址请求安装…') : '给萌芽发消息…';
  composerHint.textContent = assistantMode === 'ppt' ? 'PPT 生成完成后可下载 .pptx 文件。'
    : assistantMode === 'research' ? '搜索资料并生成研究报告；可随时停止。'
    : assistantMode === 'skills' ? '技能助手可使用宿主机 bash 运行脚本并将成品保存到指定目录。'
    : '萌芽还在成长中，回答可能不够准确。';
  fileStatus.textContent = isUploading ? `正在上传：${uploadingFileName}…`
    : fileError || (selectedFile ? `已选择：${selectedFile.name}` : '选择文件后即可向文件助手提问。');
  fileStatus.title = fileError || selectedFile?.name || uploadingFileName;
}

async function refreshSkills() {
  skillsList.textContent = '正在加载技能…';
  try {
    const response = await fetch('/api/skills');
    if (!response.ok) throw new Error('无法读取技能');
    const skills = await response.json();
    skillsList.replaceChildren();
    const heading = document.createElement('strong');
    heading.textContent = `已安装技能（${skills.length}）`;
    skillsList.append(heading);
    if (!skills.length) {
      skillsList.append(document.createTextNode('暂无技能，可在对话中提供公开 HTTPS SKILL.md 或技能 ZIP 地址安装。'));
      return;
    }
    const list = document.createElement('ul');
    for (const skill of skills) {
      const row = document.createElement('li');
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'skill-option';
      button.title = `${skill.name}：${skill.description}`;
      const name = document.createElement('b');
      name.textContent = skill.name;
      const description = document.createElement('span');
      description.textContent = skill.description;
      button.append(name, description);
      button.addEventListener('click', () => {
        if (isUploading || isSending) return;
        setAssistantMode('skills');
        selectedSkill = skill.name;
        skillsMenu.open = false;
        updateComposerState();
        promptInput.focus();
      });
      row.append(button);
      list.append(row);
    }
    skillsList.append(list);
  } catch {
    skillsList.textContent = '技能列表加载失败，请重新展开。';
  }
}

function setAssistantMode(mode) {
  modeSelection += 1;
  assistantMode = mode;
  if (mode !== 'skills') selectedSkill = null;
  if (mode !== 'skills') skillsMenu.open = false;
  updateComposerState();
}

function resetFileSelection() {
  uploadToken += 1;
  selectedFile = null;
  fileError = '';
  isUploading = false;
  uploadingFileName = '';
  fileInput.value = '';
  setAssistantMode('dialogue');
}

function renderConversation(conversation, isPpt = false) {
  messages.replaceChildren();
  welcome.hidden = conversation.messages.length > 0;
  for (const item of conversation.messages) {
    if (item.role === 'assistant' && Array.isArray(item.timeline) && item.timeline.length) {
      const timeline = addSkillTimeline(false);
      for (const entry of item.timeline) appendSkillTimeline(timeline, entry);
      flushSkillTimeline(timeline);
      timeline.section.open = item.timeline.some(entry => entry.type === 'error')
        || [...timeline.tools.values()].some(row => row.dataset.status !== 'success');
      timeline.heading.textContent = timeline.section.open ? '技能执行记录（需关注）' : '技能执行记录';
    } else if (item.role === 'assistant' && item.thinking) {
      if (item.thinking.startsWith(researchProgressPrefix)) {
        addPptProgress(item.thinking.slice(researchProgressPrefix.length), 'research');
      } else if (isPpt) addPptProgress(item.thinking);
      else addThinking(item.thinking, false);
    }
    if (!(isPpt && item.role === 'assistant' && isPptDownloadAnswer(item.text))
      && !(item.role === 'assistant' && item.thinking?.startsWith(researchProgressPrefix) && !item.text)) {
      addMessage(item.role, item.text, item.fileName);
    }
    if (item.role === 'assistant') addReferences(item.references);
    if (item.role === 'assistant') addRecommendations(item.recommendations);
  }
  messages.scrollTop = messages.scrollHeight;
}

function isPptDownloadAnswer(text) {
  return /^\[下载 PPT 文件\]\(\/api\/ppt\/conversations\/[0-9a-f]{32}\/download\)$/.test((text || '').trim());
}

function setPptDownload(id, ready) {
  pptDownload.hidden = !ready;
  if (ready) pptDownloadLink.href = `/api/ppt/conversations/${encodeURIComponent(id)}/download`;
  else pptDownloadLink.removeAttribute('href');
}

function addPptProgress(initial = '', mode = 'ppt') {
  const section = document.createElement('section');
  section.className = `ppt-progress${mode === 'research' ? ' research-progress' : ''}`;
  const heading = document.createElement('div');
  heading.className = 'ppt-progress-header';
  heading.textContent = mode === 'research' ? '深度研究进度' : 'PPT 生成进度';
  const log = document.createElement('div');
  log.className = 'ppt-progress-log';
  log.setAttribute('role', 'log');
  log.setAttribute('aria-label', heading.textContent);
  log.textContent = initial.trim();
  section.append(heading, log);
  messages.append(section);
  log.scrollTop = log.scrollHeight;
  return { section, log };
}

function appendPptProgress(panel, text) {
  panel.log.textContent += (panel.log.textContent ? '\n' : '') + text;
  panel.log.scrollTop = panel.log.scrollHeight;
}

function showPptStatus(text) {
  const log = [...messages.querySelectorAll('.ppt-progress-log')].at(-1);
  const panel = log ? { log } : addPptProgress();
  if (!panel.log.textContent.endsWith(text)) appendPptProgress(panel, text);
}

async function openConversation(id) {
  activeId = id;
  activeArchived = false;
  resetFileSelection();
  const token = ++viewToken;
  setPptDownload(null, false);
  const fileRequestToken = uploadToken;
  const selectedModeToken = modeSelection;
  let hasSavedMessages = false;
  renderHistory();
  try {
    const response = await fetch(`/api/conversations/${encodeURIComponent(id)}`);
    if (!response.ok) throw new Error('无法读取对话');
    const conversation = await response.json();
    hasSavedMessages = conversation.messages.length > 0;
    if (token !== viewToken) return;
    activeArchived = conversation.archived;
    if (fileRequestToken === uploadToken) {
      for (let i = conversation.messages.length - 1; i >= 0; i--) {
        const item = conversation.messages[i];
        if (item.fileId) {
          selectedFile = { id: item.fileId, name: item.fileName };
          if (selectedModeToken === modeSelection) assistantMode = 'file';
          break;
        }
      }
    }
    const lastAssistant = [...conversation.messages].reverse().find(item => item.role === 'assistant');
    if (selectedModeToken === modeSelection && lastAssistant?.thinking?.startsWith(researchProgressPrefix)) {
      assistantMode = 'research';
    }
    renderConversation(conversation);
    updateComposerState();
  } catch (error) {
    if (token === viewToken) {
      startConversation();
    }
    return;
  }
  try {
    const response = await fetch(`/api/ppt/conversations/${encodeURIComponent(id)}`);
    if (response.status === 404) return;
    if (!response.ok) throw new Error('无法读取 PPT 任务');
    const instance = await response.json();
    if (token !== viewToken) return;
    if (selectedModeToken === modeSelection) setAssistantMode('ppt');
    renderConversation(conversation, true);
    setPptDownload(id, instance.status === 'SUCCESS');
    welcome.hidden = true;
    if (!hasSavedMessages && instance.outline) addMessage('assistant', instance.outline);
    if (instance.status === 'FAILED') {
      showPptStatus(`PPT 任务失败：${instance.error || '请检查生成日志'}。已保存进度，可根据原因调整后重试。`);
    } else if (instance.status === 'RENDER') {
      showPptStatus('页面 Schema 与配图已保存，可发送“继续”生成 PPT 文件。');
    } else if (instance.status !== 'SUCCESS' && instance.pptSchema) {
      showPptStatus('页面 Schema 已保存，配图尚未完成；可发送“继续”恢复生成。');
    } else if (instance.status !== 'SUCCESS' && instance.outline) {
      showPptStatus('PPT 大纲已完成，正在准备页面 Schema。');
    } else if (instance.status === 'REQUIREMENT') {
      showPptStatus('等待补充需求；当前尚无 PPT 文件。');
    } else if (instance.status !== 'SUCCESS') {
      showPptStatus('PPT 大纲尚未生成，当前没有 PPT 文件。');
    }
  } catch {
    if (token === viewToken) addMessage('notice', '无法读取 PPT 大纲，请稍后重试。');
  }
}

function startConversation() {
  activeId = null;
  activeArchived = false;
  resetFileSelection();
  setPptDownload(null, false);
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
    try {
      const url = new URL(link.getAttribute('href'));
      if (!['http:', 'https:'].includes(url.protocol)) throw new TypeError('不支持的链接协议');
      link.href = url.href;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
    } catch {
      link.removeAttribute('href');
    }
  }
}

function addMessage(role, text, fileName) {
  const bubble = document.createElement('div');
  bubble.className = `message ${role}`;
  const skillTag = role === 'user' && /^使用技能 @([A-Za-z0-9][A-Za-z0-9_-]*)\n/.exec(text);
  if (role === 'assistant') renderAnswer(bubble, text);
  else if (skillTag) {
    const badge = document.createElement('span');
    badge.className = 'message-skill';
    badge.textContent = `@${skillTag[1]}`;
    bubble.append(badge, document.createTextNode(text.slice(skillTag[0].length)));
  } else bubble.textContent = text;
  if (role === 'user' && fileName) {
    const attachment = document.createElement('small');
    attachment.className = 'message-attachment';
    attachment.textContent = `附件：${fileName}`;
    bubble.append(attachment);
  }
  messages.append(bubble);
  bubble.scrollIntoView({ block: 'nearest' });
  return bubble;
}

function addThinking(text, open = true) {
  const section = document.createElement('details');
  section.className = 'thinking';
  section.open = open;
  const heading = document.createElement('summary');
  heading.textContent = open ? '正在思考…' : '思考过程';
  const content = document.createElement('div');
  content.className = 'thinking-content';
  content.textContent = text;
  section.append(heading, content);
  messages.append(section);
  return { section, heading, content };
}

function addSkillTimeline(open = true) {
  const section = document.createElement('details');
  section.className = 'skill-timeline';
  section.open = open;
  const heading = document.createElement('summary');
  heading.textContent = open ? '正在执行技能…' : '技能执行记录';
  const log = document.createElement('div');
  log.className = 'skill-timeline-log';
  log.setAttribute('role', 'log');
  log.setAttribute('aria-label', '技能执行步骤');
  log.setAttribute('aria-live', 'polite');
  log.setAttribute('aria-relevant', 'additions text');
  section.append(heading, log);
  messages.append(section);
  return { section, heading, log, tools: new Map(), currentThought: null, queue: [], scheduled: 0, follow: open };
}

function appendSkillTimeline(panel, entry) {
  panel.queue.push(entry);
  if (!panel.scheduled) {
    panel.scheduled = requestAnimationFrame(() => {
      panel.scheduled = 0;
      flushSkillTimeline(panel);
    });
  }
}

function flushSkillTimeline(panel) {
  if (panel.scheduled) {
    cancelAnimationFrame(panel.scheduled);
    panel.scheduled = 0;
  }
  if (!panel.queue.length) return;
  const sticky = panel.log.scrollHeight - panel.log.scrollTop - panel.log.clientHeight < 48;
  const entries = panel.queue.splice(0);
  for (const entry of entries) {
    if (entry.type === 'thinking' && typeof entry.content === 'string') {
      if (!panel.currentThought) {
        panel.currentThought = document.createElement('div');
        panel.currentThought.className = 'skill-timeline-thinking';
        panel.log.append(panel.currentThought);
      }
      panel.currentThought.textContent += entry.content;
    } else if (entry.type === 'tool' && typeof entry.toolName === 'string') {
      panel.currentThought = null;
      const id = String(entry.toolCallId ?? '');
      let row = panel.tools.get(id);
      if (!row) {
        row = document.createElement('div');
        row.className = 'skill-timeline-tool';
        const icon = document.createElement('span');
        icon.className = 'skill-timeline-icon';
        icon.setAttribute('aria-hidden', 'true');
        const label = document.createElement('span');
        label.className = 'skill-timeline-label';
        const status = document.createElement('span');
        status.className = 'skill-timeline-status';
        row.append(icon, label, status);
        panel.log.append(row);
        panel.tools.set(id, row);
      }
      const state = ['running', 'success', 'error'].includes(entry.status) ? entry.status : 'error';
      row.dataset.status = state;
      row.querySelector('.skill-timeline-label').textContent = entry.toolName;
      row.querySelector('.skill-timeline-status').textContent =
        state === 'running' ? '执行中' : state === 'success' ? '已完成' : '执行失败';
    } else if (entry.type === 'error' && typeof entry.message === 'string') {
      panel.currentThought = null;
      const notice = document.createElement('div');
      notice.className = 'skill-timeline-error';
      notice.textContent = entry.message;
      panel.log.append(notice);
    }
  }
  if (sticky) panel.log.scrollTop = panel.log.scrollHeight;
  if (panel.follow) panel.section.scrollIntoView({ block: 'nearest' });
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


dialogueTab.addEventListener('click', () => setAssistantMode('dialogue'));
fileTab.addEventListener('click', () => setAssistantMode('file'));
pptTab.addEventListener('click', () => setAssistantMode('ppt'));
researchTab.addEventListener('click', () => setAssistantMode('research'));
skillsTab.addEventListener('click', (event) => {
  if (isUploading || isSending) event.preventDefault();
  else setAssistantMode('skills');
});
skillsMenu.addEventListener('toggle', () => { if (skillsMenu.open) refreshSkills(); });
clearSkillButton.addEventListener('click', () => {
  selectedSkill = null;
  updateComposerState();
  promptInput.focus();
});

fileInput.addEventListener('change', async () => {
  const file = fileInput.files[0];
  if (!file) return;
  const token = ++uploadToken;
  const view = viewToken;
  const conversationId = activeId;
  selectedFile = null;
  fileError = '';
  isUploading = true;
  uploadingFileName = file.name;
  fileInput.value = '';
  updateComposerState();
  try {
    const body = new FormData();
    body.append('file', file);
    const response = await fetch('/api/files', { method: 'POST', body });
    if (!response.ok) throw new Error('上传失败');
    const result = await response.json();
    if (typeof result.fileId !== 'string' || !result.fileId) throw new Error('上传失败');
    if (token === uploadToken && view === viewToken && activeId === conversationId) {
      selectedFile = { id: result.fileId, name: result.name || file.name };
    }
  } catch {
    if (token === uploadToken && view === viewToken && activeId === conversationId) {
      fileError = `上传「${file.name}」失败，请重试。`;
    }
  } finally {
    if (token === uploadToken && view === viewToken && activeId === conversationId) {
      isUploading = false;
      uploadingFileName = '';
      updateComposerState();
    }
  }
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = promptInput.value.trim();
  const mode = assistantMode;
  const message = mode === 'skills' && selectedSkill ? `使用技能 @${selectedSkill}\n${text}` : text;
  if (!text || isSending || activeArchived || (mode === 'file' && (isUploading || !selectedFile))) return;
  if (mode === 'skills') skillsMenu.open = false;
  const requestedConversationId = activeId;
  const token = viewToken;
  const attachment = mode === 'file' ? selectedFile : null;
  isSending = true;
  welcome.hidden = true;
  const userBubble = addMessage('user', message, attachment?.name);
  promptInput.value = '';
  promptInput.style.height = 'auto';
  chatAbort = new AbortController();
  sendButton.type = 'button';
  sendButton.disabled = false;
  sendButton.title = '停止生成';
  sendButton.setAttribute('aria-label', '停止生成');
  sendButton.textContent = '■';
  updateComposerState();

  let searchStatus = null;
  const skillTimeline = mode === 'skills' && token === viewToken ? addSkillTimeline() : null;
  let skillHasError = false;
  let skillBubble = null;
  let thinking = null;
  let taskProgress = (mode === 'ppt' || mode === 'research') && token === viewToken
    ? addPptProgress('', mode) : null;
  if (mode === 'ppt') setPptDownload(null, false);
  const flushThinkingText = () => {
    if (!thinking || !thinking.pending) return;
    if (thinking.frame) {
      cancelAnimationFrame(thinking.frame);
      thinking.frame = 0;
    }
    const box = thinking.content;
    const sticky = box.scrollHeight - box.scrollTop - box.clientHeight < 48;
    box.textContent += thinking.pending;
    thinking.pending = '';
    if (sticky) box.scrollTop = box.scrollHeight;
    thinking.section.scrollIntoView({ block: 'nearest' });
  };
  const finishThinking = () => {
    if (thinking) {
      flushThinkingText();
      thinking.section.open = false;
      thinking.heading.textContent = '思考过程';
    }
  };
  let bubble = null;
  let answer = '';
  let answerRendered = '';
  let answerFrame = 0;
  const flushAnswerText = () => {
    if (answerFrame) {
      cancelAnimationFrame(answerFrame);
      answerFrame = 0;
    }
    if (bubble && answer !== answerRendered) {
      answerRendered = answer;
      renderAnswer(bubble, answer);
      if (mode === 'skills') bubble.hidden = false;
      bubble.scrollIntoView({ block: 'nearest' });
    }
  };
  try {
    const response = await fetch(mode === 'ppt' ? '/api/ppt'
      : mode === 'research' ? '/api/research'
      : mode === 'skills' ? '/api/skills/chat' : attachment ? '/api/file-chat' : '/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
      signal: chatAbort.signal,
      body: JSON.stringify({
        conversationId: requestedConversationId, message,
        ...(attachment ? { fileId: attachment.id } : {})
      })
    });
    if (!response.ok || !response.body) throw new Error('无法开始对话');
    bubble = token === viewToken ? addMessage('assistant', '') : null;
    if (mode === 'skills' && bubble) bubble.hidden = true;
    if (mode === 'skills') skillBubble = bubble;
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let completed = false;
    let pptCompleted = false;
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
        if (name === 'conversation') {
          runningId = data;
          sendButton.disabled = false;
          if (token === viewToken) {
            activeId = data;
            updateComposerState();
          }
        }
        if (name === 'reasoning' && token === viewToken) {
          if (mode === 'skills') {
            appendSkillTimeline(skillTimeline, { type: 'thinking', content: data });
          } else {
            if (!thinking) {
              thinking = addThinking('', true);
              if (bubble) messages.insertBefore(thinking.section, bubble);
            }
            thinking.pending = (thinking.pending || '') + data;
            if (!thinking.frame) {
              thinking.frame = requestAnimationFrame(() => {
                thinking.frame = 0;
                flushThinkingText();
              });
            }
          }
        }
        if (mode === 'skills' && skillTimeline && token === viewToken
          && (name === 'tool_start' || name === 'tool_end' || name === 'stage_error')) {
          const stage = JSON.parse(data);
          if (name === 'stage_error') {
            skillHasError = true;
            appendSkillTimeline(skillTimeline, { type: 'error', message: stage.message });
          } else {
            const status = name === 'tool_start' ? 'running' : stage.status;
            if (status === 'error') skillHasError = true;
            appendSkillTimeline(skillTimeline, {
              type: 'tool', toolCallId: stage.toolCallId, toolName: stage.toolName, status
            });
          }
        }
        if ((name === 'thinking' || name === 'stage') && mode !== 'skills' && token === viewToken) {
          if (mode === 'ppt' || mode === 'research') {
            if (!taskProgress) {
              taskProgress = addPptProgress('', mode);
              if (bubble) messages.insertBefore(taskProgress.section, bubble);
            }
            appendPptProgress(taskProgress, data);
          } else if (!searchStatus) searchStatus = addMessage('notice', data);
          else searchStatus.textContent = data;
        }
        if (name === 'delta' && bubble) {
          if (mode !== 'skills') finishThinking();
          if (searchStatus) {
            searchStatus.remove();
            searchStatus = null;
          }
          answer += data;
          if (!(mode === 'ppt' && ('[下载 PPT 文件]'.startsWith(answer)
            || answer.startsWith('[下载 PPT 文件]('))) && !answerFrame) {
            answerFrame = requestAnimationFrame(() => {
              answerFrame = 0;
              flushAnswerText();
            });
          }
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
        if (name === 'complete' && skillTimeline) {
          flushSkillTimeline(skillTimeline);
          if (!skillTimeline.log.hasChildNodes()) skillTimeline.section.remove();
          else {
            skillTimeline.section.open = skillHasError;
            skillTimeline.heading.textContent = skillHasError ? '技能执行记录（需关注）' : '技能执行记录';
          }
        }
        if (name === 'stopped' || name === 'done') finishThinking();
        if (name === 'stopped') {
          if (searchStatus) searchStatus.remove();
          if (skillTimeline) {
            flushSkillTimeline(skillTimeline);
            appendSkillTimeline(skillTimeline, { type: 'error', message: '已停止生成' });
            flushSkillTimeline(skillTimeline);
            skillTimeline.section.open = true;
            skillTimeline.heading.textContent = '技能执行记录（已停止）';
          }
          completed = true;
        }
        if (name === 'done') {
          if (searchStatus) searchStatus.remove();
          completed = true;
          if (mode === 'ppt') pptCompleted = true;
        }
      }
      if (done) break;
    }
    flushAnswerText();
    if (skillTimeline) flushSkillTimeline(skillTimeline);
    if (!completed) throw new Error('对话流提前中断');
    if (bubble && ((mode === 'ppt' && (!answer.trim() || isPptDownloadAnswer(answer)))
      || (mode === 'research' && !answer.trim()) || (mode === 'skills' && !answer.trim()))) bubble.remove();
    if (pptCompleted && token === viewToken && runningId) {
      try {
        const statusResponse = await fetch(`/api/ppt/conversations/${encodeURIComponent(runningId)}`);
        if (!statusResponse.ok) throw new Error('无法读取 PPT 任务状态');
        const instance = await statusResponse.json();
        if (token === viewToken) {
          if (instance.status === 'FAILED') {
            showPptStatus(`PPT 任务失败：${instance.error || '请检查生成日志'}。已保存进度，可根据原因调整后重试。`);
          } else if (instance.status === 'SUCCESS') {
            setPptDownload(runningId, true);
          } else if (instance.status === 'RENDER') {
            showPptStatus('页面 Schema 与配图已保存，可发送“继续”生成 PPT 文件。');
          } else if (instance.pptSchema) {
            showPptStatus('页面 Schema 已保存，配图尚未完成；可发送“继续”恢复生成。');
          } else if (instance.outline) showPptStatus('PPT 大纲已完成，正在准备页面 Schema。');
          else if (instance.status === 'REQUIREMENT') showPptStatus('等待补充需求；当前尚无 PPT 文件。');
        }
      } catch {
        if (token === viewToken) showPptStatus('无法读取 PPT 任务状态，请稍后查看历史记录。');
      }
    }
    if (token === viewToken) refreshHistory().catch(() => {});
  } catch (error) {
    finishThinking();
    flushAnswerText();
    if (searchStatus) searchStatus.remove();
    if (skillTimeline) {
      if (skillBubble && !skillBubble.textContent.trim()) skillBubble.remove();
      appendSkillTimeline(skillTimeline, {
        type: 'error',
        message: stopPending && error.name === 'AbortError'
          ? '已停止生成' : error.message || '无法连接萌芽服务，请稍后重试。'
      });
      skillTimeline.section.open = true;
      skillTimeline.heading.textContent = '技能执行记录（需关注）';
    } else if (token === viewToken && !(stopPending && error.name === 'AbortError')) {
      addMessage('notice', error.message || '无法连接萌芽服务，请稍后重试。');
    }
  } finally {
    sendButton.type = 'submit';
    stopPending = false;
    sendButton.title = '';
    sendButton.setAttribute('aria-label', '发送消息');
    sendButton.textContent = '↑';
    chatAbort = null;
    runningId = null;
    isSending = false;
    if (mode === 'skills' && skillsMenu.open) refreshSkills();
    updateComposerState();
  }
});

sendButton.addEventListener('click', async (event) => {
  if (!isSending || sendButton.disabled) return;
  event.preventDefault();
  stopPending = true;
  sendButton.disabled = true;
  try {
    if (runningId) {
      const response = await fetch(`/api/chat/${encodeURIComponent(runningId)}/stop`, { method: 'PATCH' });
      if (!response.ok) throw new Error('停止失败');
    } else {
      chatAbort.abort();
    }
  } catch {
    stopPending = false;
    sendButton.disabled = false;
    addMessage('notice', '停止失败，请稍后重试。');
  }
});

promptInput.addEventListener('input', () => {
  updateComposerState();
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
  } catch (error) {
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
