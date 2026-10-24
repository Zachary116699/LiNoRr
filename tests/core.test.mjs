import test from 'node:test';
import assert from 'node:assert/strict';
import { chapters, decode, findCover, naturalCompare } from '../app/src/main/assets/core/reader.mjs';
import { readFile } from 'node:fs/promises';

test('retains chapter hierarchy and preface offsets without losing source text', () => {
  const source = '引子\n第一卷 风起\n第一章 初见\n正文\n第二节 远行\nChapter 2 Return\n尾声\n终';
  const result = chapters(source);
  assert.deepEqual(result.map(c => c.level), [0, 1, 3, 4, 3, 3]);
  assert.equal(result.map(c => source.slice(c.start, c.end)).join(''), source);
});
test('does not treat long prose as a chapter title', () => {
  assert.equal(chapters('第一章 ' + '长'.repeat(100)).length, 1);
  assert.equal(chapters('第一章 ' + '长'.repeat(100))[0].title, '正文');
});
test('bounds both unstructured text and very large chapters', () => {
  for (const source of ['字'.repeat(100000), '第一章 远行\n' + '字'.repeat(100000), Array(2000).fill('长句'.repeat(30)).join('\n')]) {
    const result = chapters(source);
    assert.ok(result.length > 1);
    assert.ok(result.every(c => c.end - c.start <= 30000));
    assert.equal(result.map(c => source.slice(c.start, c.end)).join(''), source);
  }
});
test('decodes Chinese UTF-8, GB18030 and both UTF-16 byte orders', () => {
  assert.equal(decode(new TextEncoder().encode('中文\r\n正文')).text, '中文\n正文');
  assert.equal(decode(new Uint8Array([0xd6, 0xd0, 0xce, 0xc4])).text, '中文');
  assert.equal(decode(new Uint8Array([0xff, 0xfe, 0x2d, 0x4e, 0x87, 0x65])).text, '中文');
  assert.equal(decode(new Uint8Array([0xfe, 0xff, 0x4e, 0x2d, 0x65, 0x87])).text, '中文');
});
test('cover matching is scoped to the same directory and favors matching title', () => {
  assert.equal(findCover('a/书.txt', ['b/书.jpg', 'a/cover.png', 'a/书.JPG']), 'a/书.JPG');
  assert.equal(findCover('a/书.txt', ['b/cover.jpg']), undefined);
  assert.equal(findCover('书.txt', ['Cover.JPG']), 'Cover.JPG');
});
test('volume names use natural order', () => assert.deepEqual(['10.txt', '2.txt', '1.txt'].sort(naturalCompare), ['1.txt', '2.txt', '10.txt']));
test('parses existing xs_local_html fixtures', async () => {
  const buffer = await readFile(new URL('../../xs_local_html/txt/蛊真人.txt', import.meta.url));
  const source = decode(buffer).text;
  const result = chapters(source);
  assert.ok(result.length > 10);
  assert.equal(result.map(c => source.slice(c.start, c.end)).join(''), source);
});
