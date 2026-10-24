import { chapters, decode, naturalCompare, findCover } from './core/reader.mjs';

const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
const defaults = { theme: 'paper', bg: '#f7f3e8', font: 'system-ui', size: 20, leading: 1.9, paragraph: .8 };
const colors = [['#d8dfcd', '#52624b'], ['#e8dfcc', '#857253'], ['#d4dedb', '#4d6c66'], ['#ddd8e1', '#6c6078'], ['#e4d5cc', '#886a59']];
let state = { shelves: [{ id: 'default', name: '未分类' }], books: [], active: 'all', view: 'grid', prefs: { ...defaults } };
let db, current, text = '', sections = [], section = 0, editing, editingShelf, importTarget, busy = false, scrollTimer, searchTimer, searchGeneration = 0;
let importedFont, restoring = false, searchHits = new Map();
const native = window.Android;

function toast(message) { $('toast').textContent = message; $('toast').hidden = false; clearTimeout(toast.timer); toast.timer = setTimeout(() => $('toast').hidden = true, 3800); }
function loading(value, message = '正在整理书籍…') { busy = value; $('busy').hidden = !value; $('busyText').textContent = message; }
function openDialog(id) { $(id).showModal(); }
function closeDialogs() { document.querySelectorAll('dialog[open]').forEach(d => d.close()); }
document.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', () => b.closest('dialog').close()));
document.querySelectorAll('dialog').forEach(d => d.addEventListener('click', e => { if (e.target === d) { const r = d.getBoundingClientRect(); if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) d.close(); } }));

function openDB() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('LNReader', 1);
    request.onupgradeneeded = () => { request.result.createObjectStore('meta'); request.result.createObjectStore('texts'); };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
function get(store, key) {
  return new Promise((resolve, reject) => {
    const request = db.transaction(store).objectStore(store).get(key);
    request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
  });
}
function write(entries, metadata = state) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(['texts', 'meta'], 'readwrite');
    for (const entry of entries) entry.remove ? tx.objectStore('texts').delete(entry.id) : tx.objectStore('texts').put(entry.text, entry.id);
    tx.objectStore('meta').put(structuredClone(metadata), 'state');
    tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || new Error('保存被中断'));
  });
}
let metadataTimer;
function save() {
  const promise = write([]).catch(() => toast('保存失败，设备存储空间可能不足，请勿关闭应用'));
  clearTimeout(metadataTimer);
  metadataTimer = setTimeout(() => {
    if (native) native.writeMetadata(JSON.stringify({ version: 1, shelves: state.shelves, prefs: state.prefs, books: state.books.map(({ cover, ...book }) => book) }));
  }, 1200);
  return promise;
}

function shelfOptions(selected) { return state.shelves.map(s => `<option value="${esc(s.id)}" ${selected === s.id ? 'selected' : ''}>${esc(s.name)}</option>`).join(''); }
function bookColor(book) { return colors[(book.title.codePointAt(0) || 0) % colors.length]; }
function coverHTML(book, mini = false) {
  const color = bookColor(book);
  if (mini) return `<div class="mini-cover" style="background:${color[0]};color:${color[1]}">${book.cover ? `<img alt="" src="${esc(book.cover)}" style="width:100%;height:100%;object-fit:cover">` : esc(book.title.slice(0, 9))}</div>`;
  return `<div class="cover" style="background:${color[0]};color:${color[1]}">${book.cover ? `<img alt="${esc(book.title)}封面" loading="lazy" src="${esc(book.cover)}">` : `<span class="cover-label">LN · LOCAL LIBRARY</span><strong>${esc(book.title.slice(0, 22))}</strong><span class="cover-mark">文字之间</span>`}</div>`;
}
const percent = book => book.length ? (book.position >= book.length - 2 ? 100 : Math.min(99, Math.floor((book.position || 0) / book.length * 100))) : 0;
function renderTabs() {
  $('tabs').innerHTML = [{ id: 'all', name: '全部' }, ...state.shelves].map(s => `<button class="tab ${state.active === s.id ? 'active' : ''}" data-shelf="${esc(s.id)}">${esc(s.name)}<small>${state.books.filter(b => s.id === 'all' || b.shelf === s.id).length}</small></button>`).join('');
  $('manageShelf').disabled = state.active === 'all' || state.active === 'default';
}
async function renderBooks() {
  const generation = ++searchGeneration;
  renderTabs();
  const query = $('search').value.trim().toLocaleLowerCase();
  let books = state.books.filter(b => state.active === 'all' || b.shelf === state.active);
  searchHits = new Map();
  if (query) {
    const matches = [];
    for (const book of books) {
      if (book.title.toLocaleLowerCase().includes(query)) matches.push(book);
      else {
        const source = await get('texts', book.id);
        if (generation !== searchGeneration) return;
        const offset = source?.toLocaleLowerCase().indexOf(query) ?? -1;
        if (offset >= 0) { matches.push(book); searchHits.set(book.id, offset); }
      }
    }
    books = matches;
  }
  if (generation !== searchGeneration) return;
  books.sort((a, b) => (b.lastRead || b.added) - (a.lastRead || a.added));
  $('books').classList.toggle('list', state.view === 'list');
  $('gridView').classList.toggle('selected', state.view === 'grid');
  $('listView').classList.toggle('selected', state.view === 'list');
  $('bookCount').textContent = `${books.length} 本`;
  $('books').innerHTML = books.map(book => `<div class="book-card"><button class="book-open" data-open="${esc(book.id)}" aria-label="阅读${esc(book.title)}">${coverHTML(book)}<div class="book-info"><h3>${esc(book.title)}</h3><p>${searchHits.has(book.id) ? '正文中找到匹配文字' : book.lastRead ? `已读 ${percent(book)}% · ${book.chapterCount} 章` : `${book.chapterCount} 章 · 未开始`}</p><div class="book-bar"><span style="width:${percent(book)}%"></span></div></div></button><button class="book-more icon-button" data-edit="${esc(book.id)}" aria-label="管理${esc(book.title)}">${icon('more')}</button></div>`).join('');
  $('empty').hidden = books.length > 0;
  if (!books.length) {
    $('empty').querySelector('h2').textContent = query ? '没有找到这段故事' : '故事，从这里开始';
    $('empty').querySelector('p').innerHTML = query ? '试试其他书名或正文关键词。' : '导入一本 TXT，把喜欢的文字随身带走。<br>不联网，不打扰，只有你和书。';
    $('emptyImport').hidden = Boolean(query); $('tryDemo').hidden = Boolean(query);
  }
  const last = [...state.books].filter(b => b.lastRead).sort((a, b) => b.lastRead - a.lastRead)[0];
  $('continueCard').hidden = !last || Boolean(query);
  if (last) $('continueCard').innerHTML = `${coverHTML(last, true)}<div class="continue-info"><span class="eyebrow">PICK UP WHERE YOU LEFT OFF</span><h2>${esc(last.title)}</h2><p>${esc(last.chapterTitle || '正文')} · 已读 ${percent(last)}%</p></div><button data-open="${esc(last.id)}">继续阅读 ↗</button>`;
}
$('tabs').addEventListener('click', e => { const button = e.target.closest('[data-shelf]'); if (button) { state.active = button.dataset.shelf; save(); renderBooks(); } });
$('books').addEventListener('click', e => { const open = e.target.closest('[data-open]'), edit = e.target.closest('[data-edit]'); if (open) openBook(open.dataset.open, searchHits.get(open.dataset.open)); if (edit) editBook(edit.dataset.edit); });
$('continueCard').addEventListener('click', e => { const button = e.target.closest('[data-open]'); if (button) openBook(button.dataset.open); });
$('search').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => renderBooks().catch(() => toast('搜索失败，请重试')), 250); });
for (const type of ['grid', 'list']) $(type + 'View').onclick = async () => { state.view = type; await save(); renderBooks(); };

function showImport() { $('importShelf').innerHTML = shelfOptions(state.active === 'all' ? 'default' : state.active); openDialog('importDialog'); }
$('importBtn').onclick = $('emptyImport').onclick = showImport;
function chooseImport(folder) {
  importTarget = $('importShelf').value;
  const mode = document.querySelector('[name="grouping"]:checked').value;
  $('importDialog').close();
  if (native) { loading(true, '请选择本地文件…'); folder ? native.pickFolder(mode) : native.pickFiles(mode); }
  else { const input = $(folder ? 'folderInput' : 'fileInput'); input.dataset.mode = mode; input.value = ''; input.click(); }
}
$('pickFiles').onclick = () => chooseImport(false); $('pickFolder').onclick = () => chooseImport(true);
for (const input of [$('fileInput'), $('folderInput')]) input.onchange = async () => {
  const files = Array.from(input.files || []).map(file => ({ file, name: file.name, path: file.webkitRelativePath || file.name, source: `browser:${file.webkitRelativePath || file.name}`, size: file.size }));
  await importFiles({ files, mode: input.dataset.mode, name: input.id === 'folderInput' ? files[0]?.path.split('/')[0] || '合辑' : '合辑' });
};
async function bytes(file) {
  if (file.file) return file.file.arrayBuffer();
  const response = await fetch(file.url); if (!response.ok) throw new Error(`无法读取 ${file.name}`); return response.arrayBuffer();
}
async function coverData(file) {
  if (!file || file.size > 15 * 1024 * 1024) return '';
  const blob = new Blob([await bytes(file)]);
  const image = await createImageBitmap(blob);
  try {
    const canvas = document.createElement('canvas');
    const scale = Math.min(1, 480 / Math.max(image.width, image.height));
    canvas.width = image.width * scale; canvas.height = image.height * scale;
    canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
    return canvas.toDataURL('image/jpeg', .85);
  } finally { image.close(); }
}
async function stableID(value) {
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2, '0')).join('').slice(0, 24);
}
function safePreferences(raw) {
  const value = raw || {};
  return { theme: ['paper', 'green', 'night', 'custom'].includes(value.theme) ? value.theme : 'paper', bg: /^#[\da-f]{6}$/i.test(value.bg) ? value.bg : defaults.bg,
    font: /^(system-ui|serif|sans-serif|monospace|device-\d+|imported)$/.test(value.font) ? value.font : 'system-ui',
    size: Math.max(14, Math.min(36, Number(value.size) || 20)), leading: Math.max(1.2, Math.min(2.8, Number(value.leading) || 1.9)), paragraph: Math.max(0, Math.min(2.5, Number(value.paragraph) || 0)) };
}
async function importFiles(payload) {
  loading(true);
  const txts = payload.files.filter(f => /\.txt$/i.test(f.name)).sort((a, b) => naturalCompare(a.path, b.path));
  if (!txts.length) { loading(false); toast('没有找到 TXT 文件'); return; }
  const images = payload.files.filter(f => /\.(png|jpe?g|webp)$/i.test(f.name));
  const groups = payload.mode === 'merged' ? [txts] : txts.map(f => [f]);
  let added = 0, updated = 0, failures = [], coverFailures = 0;
  const backup = payload.metadata?.version === 1 ? payload.metadata : null;
  const restoredBooks = new Map((Array.isArray(backup?.books) ? backup.books : []).filter(b => b && typeof b.id === 'string').map(b => [b.id, b]));
  // Only restore classification on a fresh install, never overwrite a working library.
  if (state.books.length === 0 && Array.isArray(backup?.shelves)) {
    const restored = backup.shelves.filter(s => s && typeof s.id === 'string' && /^[\w-]{1,80}$/.test(s.id) && typeof s.name === 'string').slice(0, 100).map(s => ({ id: s.id, name: s.name.slice(0, 20) }));
    if (restored.length) state.shelves = restored.some(s => s.id === 'default') ? restored : [{ id: 'default', name: '未分类' }, ...restored];
    if (backup.prefs) state.prefs = safePreferences(backup.prefs);
  }
  for (const group of groups) {
    const name = payload.mode === 'merged' ? (payload.name === '合辑' ? group[0].name.replace(/\.txt$/i, '') + ' · 合辑' : payload.name) : group[0].name.replace(/\.txt$/i, '');
    $('busyText').textContent = `正在整理 ${added + updated + failures.length + 1}/${groups.length} · ${name}`;
    try {
      if (group.reduce((sum, f) => sum + f.size, 0) > 80 * 1024 * 1024) throw new Error('单本超过 80MB，请按文件分别导入');
      const id = await stableID((payload.mode || 'separate') + ':' + (payload.folder || '') + ':' + group.map(f => f.source).join('|'));
      const existing = state.books.find(b => b.id === id);
      const restored = restoredBooks.get(id);
      const parts = [], encodings = new Set();
      for (const file of group) {
        const decoded = decode(await bytes(file));
        if (decoded.text.includes('\u0000')) throw new Error('文本包含空字符，请检查编码或文件类型');
        encodings.add(decoded.encoding);
        parts.push(group.length > 1 ? `\n\n# ${file.name.replace(/\.txt$/i, '')}\n\n${decoded.text}` : decoded.text);
      }
      const source = parts.join('\n\n').replace(/\r\n?/g, '\n');
      if (!source.trim()) throw new Error('文件内容为空');
      let catalog = chapters(source);
      // Retain file boundaries even when individual volumes have no headings.
      if (group.length > 1) {
        let cursor = 0;
        const boundaries = parts.map((part, i) => { const start = cursor; cursor += part.length + (i < parts.length - 1 ? 2 : 0); return { title: group[i].name.replace(/\.txt$/i, ''), start, level: 1 }; });
        catalog = [...boundaries, ...catalog.filter(c => c.start > 0 || !['前言', '正文'].includes(c.title))].sort((a, b) => a.start - b.start);
        catalog = catalog.filter((c, i, all) => i === 0 || c.start !== all[i - 1].start).map((c, i, all) => ({ ...c, end: all[i + 1]?.start ?? source.length }));
      }
      const imagePaths = images.map(i => i.path);
      const coverPath = findCover(group[0].path, imagePaths) || (payload.mode === 'merged' ? findCover(`${payload.name}.txt`, imagePaths) : undefined);
      let cover = existing?.cover || '';
      try { cover = await coverData(images.find(f => f.path === coverPath)) || cover; } catch { coverFailures++; }
      const old = existing || restored;
      const book = { id, title: String(existing?.title || restored?.title || name).slice(0, 120), shelf: existing?.shelf || (restored && state.shelves.some(s => s.id === restored.shelf) ? restored.shelf : importTarget || 'default'),
        cover, encoding: [...encodings].join(' / '), length: source.length, chapterCount: catalog.length, catalog,
        position: Math.max(0, Math.min(source.length - 1, Number(old?.position) || 0)), marks: Array.isArray(old?.marks) ? old.marks.filter(m => m && Number.isFinite(m.offset) && m.offset < source.length).slice(0, 500) : [],
        lastRead: Number(old?.lastRead) || 0, added: existing?.added || Date.now(), sources: group.map(f => f.path), folder: payload.folder || '' };
      if (!state.shelves.some(s => s.id === book.shelf)) book.shelf = 'default';
      const next = { ...state, books: [...state.books.filter(b => b.id !== id), book] };
      await write([{ id, text: source }], next);
      state = next;
      existing ? updated++ : added++;
    } catch (error) { failures.push(`${name}：${error.message}`); }
    await new Promise(resolve => requestAnimationFrame(resolve));
  }
  loading(false); applyPreferences(); renderBooks(); save();
  toast(`导入完成：新增 ${added} 本${updated ? `，更新 ${updated} 本` : ''}${failures.length ? `，失败 ${failures.length} 本（${failures[0]}）` : ''}${coverFailures ? '；部分封面无效，已使用文字封面' : ''}`);
}

window.nativeEvent = async (type, payload) => {
  if (type === 'import') { try { await importFiles(payload); } catch (e) { loading(false); toast('导入失败：' + e.message); } }
  else if (type === 'error') { loading(false); toast(payload.message); }
  else if (type === 'cancel') loading(false);
  else if (type === 'font') { importedFont = new FontFace('ImportedReader', 'url(/custom-font?t=' + Date.now() + ')'); try { await importedFont.load(); document.fonts.add(importedFont); ensureImportedOption(); state.prefs.font = 'imported'; applyPreferences(); save(); } catch { toast('字体加载失败，请选择有效的字体文件'); } }
};
$('syncFolder').onclick = () => {
  if (!native) { toast('浏览器预览请重新选择原文件夹，同名文件会更新并保留进度'); showImport(); return; }
  importTarget = state.active === 'all' ? 'default' : state.active;
  loading(true, '正在扫描本地文件夹…'); native.syncFolder();
};

function showShelfDialog(edit) {
  editingShelf = edit ? state.active : null;
  $('shelfDialogTitle').textContent = edit ? '重命名书架' : '新建书架';
  $('shelfName').value = edit ? state.shelves.find(s => s.id === editingShelf)?.name || '' : '';
  $('deleteShelf').hidden = !edit; openDialog('shelfDialog');
}
$('addShelf').onclick = () => showShelfDialog(false); $('manageShelf').onclick = () => showShelfDialog(true);
$('shelfForm').onsubmit = e => {
  e.preventDefault(); const name = $('shelfName').value.trim(); if (!name) return;
  if (state.shelves.some(s => s.name === name && s.id !== editingShelf)) { toast('这个书架名称已经存在'); return; }
  if (editingShelf) state.shelves.find(s => s.id === editingShelf).name = name;
  else { const id = crypto.randomUUID(); state.shelves.push({ id, name }); state.active = id; }
  save(); renderBooks(); $('shelfDialog').close();
};
$('deleteShelf').onclick = () => { state.books.filter(b => b.shelf === editingShelf).forEach(b => b.shelf = 'default'); state.shelves = state.shelves.filter(s => s.id !== editingShelf); state.active = 'all'; save(); renderBooks(); $('shelfDialog').close(); toast('书架已删除，书籍已移至未分类'); };
function editBook(id) {
  editing = id; const book = state.books.find(b => b.id === id);
  $('editTitle').value = book.title; $('editShelf').innerHTML = shelfOptions(book.shelf); openDialog('bookDialog');
}
$('saveBook').onclick = () => { const name = $('editTitle').value.trim(); if (!name) { toast('书名不能为空'); return; } const book = state.books.find(b => b.id === editing); book.title = name; book.shelf = $('editShelf').value; save(); renderBooks(); $('bookDialog').close(); };
$('deleteBook').onclick = () => openDialog('confirmDialog');
$('confirmDelete').onclick = async () => {
  const next = { ...state, books: state.books.filter(b => b.id !== editing) };
  try { await write([{ id: editing, remove: true }], next); state = next; save(); closeDialogs(); renderBooks(); toast('已从书架移除，原文件保留'); } catch { toast('移除失败，请重试'); }
};

async function openBook(id, offset) {
  if (busy) return;
  try {
    const book = state.books.find(b => b.id === id); if (!book) return;
    const source = await get('texts', id); if (!source) { toast('正文副本缺失，请重新导入原文件'); return; }
    current = book; text = source; sections = book.catalog;
    current.lastRead = Date.now();
    $('readerBook').textContent = book.title;
    $('shelfView').hidden = true; $('readerView').hidden = false; document.body.classList.add('reading');
    applyPreferences();
    jumpOffset(offset ?? book.position ?? 0);
    save();
  } catch (error) { toast('无法打开书籍：' + error.message); }
}
function chapterFor(offset) { let index = sections.findIndex(s => s.end > offset); return index < 0 ? sections.length - 1 : index; }
function jumpOffset(offset) {
  const target = Math.max(0, Math.min(text.length - 1, offset));
  section = chapterFor(target); renderChapter(target);
}
function renderChapter(offset = sections[section].start) {
  restoring = true; const chapter = sections[section];
  current.position = offset;
  $('readerChapter').textContent = chapter.title;
  current.chapterTitle = chapter.title;
  const content = $('readingContent'); content.replaceChildren();
  const title = document.createElement('h1'); title.textContent = chapter.title; content.append(title);
  let cursor = chapter.start;
  const lines = text.slice(chapter.start, chapter.end).split('\n');
  for (const line of lines) {
    const start = cursor; cursor += line.length + 1;
    if (!line.trim() || line.replace(/^#+\s*/, '').trim() === chapter.title) continue;
    // Long unbroken lines are chunked to avoid a single huge layout node.
    for (let j = 0; j < line.length; j += 1200) {
      const p = document.createElement('p'); p.textContent = line.slice(j, j + 1200); p.dataset.offset = start + j; p.dataset.end = Math.min(start + j + 1200, cursor - 1); content.append(p);
    }
  }
  $('prevChapter').disabled = section === 0; $('nextChapter').disabled = section === sections.length - 1;
  $('positionLabel').textContent = `${section + 1} / ${sections.length} 章`;
  requestAnimationFrame(() => {
    const scroll = $('readingScroll'); scroll.scrollTop = 0;
    const ps = Array.from(content.querySelectorAll('p'));
    const p = ps.find(p => Number(p.dataset.end) > offset) || ps.at(-1);
    if (p && offset > chapter.start) scroll.scrollTop = p.offsetTop - content.offsetTop + (offset - Number(p.dataset.offset)) / Math.max(1, Number(p.dataset.end) - Number(p.dataset.offset)) * p.offsetHeight;
    current.position = offset; updatePositionUI(); restoring = false;
  });
}
function capturePosition() {
  if (!current || restoring) return;
  const scroll = $('readingScroll'), bounds = scroll.getBoundingClientRect();
  const paragraphs = $('readingContent').querySelectorAll('p');
  let offset = sections[section].start;
  for (const p of paragraphs) {
    const r = p.getBoundingClientRect();
    if (r.bottom >= bounds.top + 12) { const fraction = Math.max(0, Math.min(1, (bounds.top + 12 - r.top) / r.height)); offset = Number(p.dataset.offset) + Math.floor((Number(p.dataset.end) - Number(p.dataset.offset)) * fraction); break; }
  }
  if (scroll.scrollHeight > scroll.clientHeight + 2 && scroll.scrollTop + scroll.clientHeight >= scroll.scrollHeight - 4) offset = Math.max(sections[section].start, sections[section].end - 1);
  current.position = offset; current.lastRead = Date.now(); updatePositionUI(); save();
}
function updatePositionUI() {
  $('readPercent').textContent = `${percent(current)}%`;
  $('bookProgress').value = Math.round((current.position || 0) / current.length * 1000);
  const marked = current.marks.some(m => Math.abs(m.offset - (current.position || 0)) < 100);
  $('bookmarkBtn').classList.toggle('active', marked); $('bookmarkBtn').setAttribute('aria-label', marked ? '已添加书签' : '添加书签');
}
$('readingScroll').onscroll = () => { if (restoring) return; clearTimeout(scrollTimer); scrollTimer = setTimeout(capturePosition, 220); };
function backShelf() { capturePosition(); clearTimeout(scrollTimer); closeDialogs(); $('readerView').hidden = true; $('shelfView').hidden = false; document.body.classList.remove('reading'); native?.night(false); renderBooks(); current = null; text = ''; }
$('backShelf').onclick = backShelf;
window.handleBack = () => { const dialog = document.querySelector('dialog[open]'); if (dialog) dialog.close(); else if (current) backShelf(); else if (native) native.exit(); };
$('prevChapter').onclick = () => { capturePosition(); if (section > 0) { section--; renderChapter(); save(); } };
$('nextChapter').onclick = () => { capturePosition(); if (section < sections.length - 1) { section++; renderChapter(); save(); } };
$('bookProgress').onchange = () => { const value = Number($('bookProgress').value); jumpOffset(Math.floor(value / 1000 * (text.length - 1))); save(); };

function renderCatalog() {
  const query = $('chapterSearch').value.trim().toLocaleLowerCase();
  $('chapterCount').textContent = `${sections.length} 章`;
  const entries = sections.map((c, i) => ({ ...c, index: i })).filter(c => c.title.toLocaleLowerCase().includes(query));
  const fragment = document.createDocumentFragment();
  // Flat chronological list with semantic level indentation preserves navigation order.
  for (const c of entries) {
    const button = document.createElement('button'); button.className = `chapter-item ${c.index === section ? 'active' : ''} ${c.level <= 2 ? 'volume' : ''}`;
    button.style.paddingLeft = `${12 + Math.min(3, Math.max(0, c.level - 1)) * 8}px`;
    button.innerHTML = `<span class="chapter-number">${c.index + 1}</span><span>${esc(c.title)}</span>`;
    button.onclick = () => { capturePosition(); section = c.index; renderChapter(); $('catalogDialog').close(); setTimeout(save, 50); };
    fragment.append(button);
  }
  if (!entries.length) { const p = document.createElement('p'); p.className = 'note'; p.textContent = '没有匹配的章节'; fragment.append(p); }
  $('chapterList').replaceChildren(fragment);
}
$('catalogBtn').onclick = () => { $('chapterSearch').value = ''; renderCatalog(); openDialog('catalogDialog'); $('chapterList').querySelector('.active')?.scrollIntoView({ block: 'center' }); };
$('chapterSearch').oninput = renderCatalog;
$('bookmarkBtn').onclick = () => {
  capturePosition();
  if (current.marks.some(m => Math.abs(m.offset - current.position) < 100)) { toast('这个位置已经有书签'); return; }
  current.marks.push({ offset: current.position, title: sections[section].title, date: Date.now() }); save(); updatePositionUI(); toast('已添加书签');
};
function renderMarks() {
  $('marksList').innerHTML = current.marks.length ? current.marks.map((m, i) => `<div class="mark-item"><button data-mark="${i}">${esc(m.title)}<small>${Math.floor(m.offset / current.length * 100)}% · ${new Date(m.date).toLocaleDateString('zh-CN')}</small></button><button data-remove-mark="${i}" class="icon-button" aria-label="移除书签">${icon('close')}</button></div>`).join('') : '<p class="note">还没有书签。点击阅读页右上角，记下此刻。</p>';
}
$('marksBtn').onclick = () => { renderMarks(); openDialog('marksDialog'); };
$('marksList').onclick = e => { const jump = e.target.closest('[data-mark]'), remove = e.target.closest('[data-remove-mark]'); if (jump) { capturePosition(); jumpOffset(current.marks[Number(jump.dataset.mark)].offset); $('marksDialog').close(); setTimeout(save, 50); } if (remove) { current.marks.splice(Number(remove.dataset.removeMark), 1); save(); renderMarks(); updatePositionUI(); } };

function applyPreferences() {
  const p = state.prefs, root = document.documentElement;
  const bg = p.theme === 'night' ? '#20231f' : p.theme === 'green' ? '#e5ebdf' : p.theme === 'custom' ? p.bg : '#f7f3e8';
  const rgb = bg.match(/\w\w/g).map(n => parseInt(n, 16));
  const dark = rgb[0] * .299 + rgb[1] * .587 + rgb[2] * .114 < 135;
  root.style.setProperty('--reader-bg', bg); root.style.setProperty('--reader-ink', dark ? '#deded4' : '#363c32');
  root.style.setProperty('--font', p.font === 'imported' ? 'ImportedReader, system-ui' : /^device-/.test(p.font) ? `${p.font}, system-ui` : p.font);
  root.style.setProperty('--size', p.size + 'px'); root.style.setProperty('--leading', p.leading); root.style.setProperty('--paragraph', p.paragraph + 'em');
  for (const [id, value, display] of [['fontSize', p.size, p.size + ' px'], ['lineHeight', p.leading, p.leading + ' 倍'], ['paragraph', p.paragraph, p.paragraph + ' em']]) { $(id).value = value; $(id + 'Value').value = display; }
  $('fontFamily').value = p.font; $('bgColor').value = bg;
  ['red', 'green', 'blue'].forEach((name, i) => $('rgb-' + name).value = rgb[i]);
  document.querySelectorAll('[data-theme]').forEach(button => button.classList.toggle('selected', button.dataset.theme === p.theme));
  $('nightBtn').innerHTML = icon(p.theme === 'night' ? 'sun' : 'moon') + `<span>${p.theme === 'night' ? '日间' : '夜间'}</span>`;
  if (current) native?.night(dark);
}
function changePreferences(change) {
  capturePosition(); const offset = current?.position; Object.assign(state.prefs, change); applyPreferences();
  if (current && offset != null) renderChapter(offset); save();
}
$('settingsBtn').onclick = () => { applyPreferences(); openDialog('settingsDialog'); };
document.querySelectorAll('[data-theme]').forEach(b => b.onclick = () => changePreferences({ theme: b.dataset.theme }));
$('nightBtn').onclick = () => changePreferences({ theme: state.prefs.theme === 'night' ? 'paper' : 'night' });
$('bgColor').oninput = () => changePreferences({ theme: 'custom', bg: $('bgColor').value });
for (const name of ['red', 'green', 'blue']) $('rgb-' + name).onchange = () => {
  const hex = ['red', 'green', 'blue'].map(c => Math.max(0, Math.min(255, Math.round(Number($('rgb-' + c).value) || 0))).toString(16).padStart(2, '0')).join('');
  changePreferences({ theme: 'custom', bg: '#' + hex });
};
$('fontFamily').onchange = async () => {
  const value = $('fontFamily').value;
  try { await document.fonts.load(`20px "${value === 'imported' ? 'ImportedReader' : value}"`); changePreferences({ font: value }); } catch { toast('字体不可用，请选择其他字体'); }
};
for (const [id, property] of [['fontSize', 'size'], ['lineHeight', 'leading'], ['paragraph', 'paragraph']]) $(id).oninput = () => changePreferences({ [property]: Number($(id).value) });
$('resetSettings').onclick = () => changePreferences({ ...defaults });
function ensureImportedOption() { if (!$('fontFamily').querySelector('[value="imported"]')) $('fontFamily').add(new Option('自选字体', 'imported')); }
$('pickFont').onclick = () => { if (native) native.pickFont(); else { $('fontInput').value = ''; $('fontInput').click(); } };
$('fontInput').onchange = async () => {
  const file = $('fontInput').files[0]; if (!file) return;
  try { const buffer = await file.arrayBuffer(); importedFont = new FontFace('ImportedReader', buffer); await importedFont.load(); document.fonts.add(importedFont); await new Promise((resolve, reject) => { const tx = db.transaction('meta', 'readwrite'); tx.objectStore('meta').put(buffer, 'font'); tx.oncomplete = resolve; tx.onerror = reject; }); ensureImportedOption(); changePreferences({ font: 'imported' }); } catch { toast('字体加载失败，请选择有效的 TTF / OTF 字体'); }
};

$('tryDemo').onclick = async () => {
  const paragraphs = [
    ['第一章 风经过书页', '午后的风从窗缝里走进来，把桌上的书翻过一页。街上还有些声音，隔着一层薄薄的阳光，像是很远的事情。', '有些时候，阅读只是为了给自己留一处安静的地方。不必赶路，不必急着回答。文字在眼前缓缓展开，一条陌生的小径，也就这样出现在生活里。'],
    ['第二章 在路上', '走过小桥的时候，天光渐渐柔和下来。河面映着树影，有人在岸边慢慢收拾钓具。', '每一本书都是一条可以重新出发的路。你记得某个句子，某场雨，某个人停下来的背影。等到很久以后，它们依然在某个普通的日子里，悄悄照亮你。'],
    ['第三章 留白', '夜色一点一点落在窗台。合上书，房间里只剩下钟表轻轻走动的声音。', '故事没有结束，只是暂时停在了这里。下次打开时，书页还在，那个尚未抵达的远方也还在。愿你在文字之间，总能找到属于自己的片刻。']
  ];
  const source = 'LNReader 阅读体验\n\n这是一段内置的原创试读。可用来体验目录、书签、字体和夜间模式，之后可以从书架移除。\n\n' + paragraphs.map(([title, a, b]) => title + '\n\n' + Array.from({ length: 5 }, () => a + '\n\n' + b).join('\n\n')).join('\n\n');
  importTarget = 'default'; await importFiles({ files: [{ name: '文字之间 · 试读.txt', path: '文字之间 · 试读.txt', source: 'demo', size: source.length * 3, file: new File([source], '文字之间 · 试读.txt') }], mode: 'separate', name: '试读' });
  const book = state.books.find(b => b.sources.includes('文字之间 · 试读.txt')); if (book) openBook(book.id);
};
document.addEventListener('visibilitychange', () => { if (document.hidden) { capturePosition(); save(); } });
window.addEventListener('pagehide', capturePosition);

async function boot() {
  try {
    db = await openDB(); const stored = await get('meta', 'state'); if (stored) state = stored;
    state.prefs = safePreferences(state.prefs);
    if (native) {
      const families = JSON.parse(native.fonts());
      for (const [i, family] of families.entries()) {
        const name = 'device-' + i; document.fonts.add(new FontFace(name, `url(/font/${encodeURIComponent(family.file)})`));
        $('fontFamily').add(new Option(family.name, name));
      }
      if (state.prefs.font === 'imported') { importedFont = new FontFace('ImportedReader', 'url(/custom-font)'); await importedFont.load().then(f => { document.fonts.add(f); ensureImportedOption(); }).catch(() => state.prefs.font = 'system-ui'); }
      const folder = native.folderName(); if (folder) $('syncFolder').title = '同步 ' + folder;
    } else {
      const font = await get('meta', 'font'); if (font) { importedFont = new FontFace('ImportedReader', font); await importedFont.load(); document.fonts.add(importedFont); ensureImportedOption(); }
    }
    applyPreferences(); await renderBooks();
  } catch (error) { toast('本地存储初始化失败：' + error.message); }
}
boot();
