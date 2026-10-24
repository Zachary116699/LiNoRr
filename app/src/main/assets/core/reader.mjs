// Chapter hierarchy adapted from ../xs_local_html/index.html.
export function chapters(text) {
  const lines = text.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n').split('\n');
  const found = [];
  const levels = { 卷: 1, 部: 1, 篇: 2, 章: 3, 节: 4, 目: 5, 话: 5, 回: 5, 集: 5 };
  let offset = 0;
  for (const line of lines) {
    const label = line.trim().replace(/^[=\-*_#\s]+|[=\-*_#\s]+$/g, '');
    const match = label.length <= 90 && (label.match(/^第\s*[零〇一二两三四五六七八九十百千万\d]+\s*(卷|部|篇|章|节|目|话|回|集)(?:\s*[:：、.．-]?\s*.*)$/)
      || label.match(/^(序章|序言|序|楔子|尾声|终章|结语|后记)(?:$|[\s:：、.．-].*)/)
      || label.match(/^(?:chapter|part|book|volume)\s+(?:\d+|[ivxlcdm]+)\b.*$/i));
    if (match) found.push({ title: label, start: offset, level: levels[match[1]] || (/^(part|book|volume)/i.test(label) ? 1 : 3) });
    offset += line.length + 1;
  }
  if (!found.length) {
    found.push({ title: '正文', start: 0, level: 3 });
    // Bound rendering cost for texts without headings; split only at line ends.
    let next = 18000;
    let cursor = 0;
    for (const line of lines) {
      if (cursor >= next) {
        found.push({ title: `正文 · ${found.length + 1}`, start: cursor, level: 3 });
        next = cursor + 18000;
      }
      cursor += line.length + 1;
    }
  } else if (found[0].start > 0) found.unshift({ title: '前言', start: 0, level: 0 });
  const normalized = lines.join('\n');
  const bounded = [];
  for (let i = 0; i < found.length; i++) {
    const item = found[i], end = found[i + 1]?.start ?? normalized.length;
    bounded.push(item);
    let cursor = item.start;
    while (end - cursor > 30000) {
      let split = normalized.lastIndexOf('\n', cursor + 24000);
      if (split <= cursor + 12000) split = cursor + 24000;
      else split++;
      bounded.push({ title: `${item.title} · 续 ${bounded.filter(c => c.start > item.start).length + 1}`, start: split, level: item.level });
      cursor = split;
    }
  }
  return bounded.map((item, i) => ({ ...item, end: bounded[i + 1]?.start ?? normalized.length }));
}

export function decode(buffer) {
  const bytes = new Uint8Array(buffer);
  if (bytes[0] === 255 && bytes[1] === 254) return { text: new TextDecoder('utf-16le').decode(bytes).replace(/^\uFEFF/, ''), encoding: 'UTF-16LE' };
  if (bytes[0] === 254 && bytes[1] === 255) return { text: new TextDecoder('utf-16be').decode(bytes).replace(/^\uFEFF/, ''), encoding: 'UTF-16BE' };
  for (const encoding of ['utf-8', 'gb18030']) {
    try { return { text: new TextDecoder(encoding, { fatal: true }).decode(bytes).replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n'), encoding }; } catch {}
  }
  throw new Error('无法识别文本编码，请转换为 UTF-8 后重试');
}

export const naturalCompare = (a, b) => a.localeCompare(b, 'zh-CN', { numeric: true, sensitivity: 'base' });

export function findCover(txtPath, imagePaths) {
  const parent = txtPath.includes('/') ? txtPath.slice(0, txtPath.lastIndexOf('/') + 1) : '';
  const stem = txtPath.slice(parent.length).replace(/\.txt$/i, '').toLowerCase();
  const candidates = imagePaths.filter(p => p.slice(0, p.lastIndexOf('/') + 1) === parent && /\.(png|jpe?g|webp)$/i.test(p));
  return candidates.find(p => p.slice(parent.length).replace(/\.[^.]+$/, '').toLowerCase() === stem)
    || candidates.find(p => /^(cover|folder|封面)\.(png|jpe?g|webp)$/i.test(p.slice(parent.length)));
}
