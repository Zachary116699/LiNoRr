import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
const page = await context.newPage();
page.setDefaultTimeout(10000);
const errors = [];
page.on('pageerror', e => errors.push(e.message));
await mkdir(new URL('../artifacts/', import.meta.url), { recursive: true });
try {
  await page.goto('http://localhost:4173');
  await page.locator('#empty').waitFor({ state: 'visible' });
  await page.screenshot({ path: new URL('../artifacts/shelf-empty.png', import.meta.url).pathname });
  await page.locator('#tryDemo').click();
  await page.locator('#readerView').waitFor({ state: 'visible' });
  await page.locator('#catalogBtn').click();
  await page.locator('#chapterList .chapter-item').filter({ hasText: '第二章' }).click();
  await page.locator('#readerChapter').filter({ hasText: '第二章' }).waitFor();
  await page.locator('#bookmarkBtn').click();
  await page.locator('#marksBtn').click();
  assert.equal(await page.locator('#marksList .mark-item').count(), 1);
  await page.locator('#marksDialog [data-close]').click();
  await page.locator('#nightBtn').click();
  assert.equal(await page.locator('#readerView').evaluate(e => getComputedStyle(e).backgroundColor), 'rgb(32, 35, 31)');
  await page.locator('#settingsBtn').click();
  await page.locator('[data-theme="green"]').click();
  await page.locator('#fontSize').fill('25');
  assert.equal(await page.locator('#readingContent').evaluate(e => getComputedStyle(e).fontSize), '25px');
  await page.locator('#settingsDialog [data-close]').click();
  await page.screenshot({ path: new URL('../artifacts/reader.png', import.meta.url).pathname });
  await page.locator('#bookProgress').fill('800');
  await page.waitForTimeout(100);
  assert.ok(Number(await page.locator('#bookProgress').inputValue()) >= 790);
  await page.locator('#backShelf').click();
  await page.locator('#addShelf').click();
  await page.locator('#shelfName').fill('枕边常读');
  await page.locator('#shelfForm .primary').click();
  await page.locator('[data-shelf="all"]').click();
  await page.locator('[data-edit]').click();
  await page.locator('#editTitle').fill('安静的阅读时光');
  await page.locator('#editShelf').selectOption({ label: '枕边常读' });
  await page.locator('#saveBook').click();
  await page.locator('#listView').click();
  await page.locator('#books.list').waitFor();
  await page.reload();
  await page.locator('#books h3').filter({ hasText: '安静的阅读时光' }).waitFor();
  assert.ok(await page.locator('#books').evaluate(e => e.classList.contains('list')));
  await page.locator('#search').fill('收拾钓具');
  await page.locator('#books .book-info p').filter({ hasText: '正文中找到匹配文字' }).waitFor();
  await page.locator('#search').fill('');
  await page.locator('#gridView').click();
  const fixtures = [
    { name: '旅途1.txt', mimeType: 'text/plain', buffer: Buffer.from('第一卷 山野\n第一章 出发\n' + '今天是晴天。\n'.repeat(100)) },
    { name: '旅途2.txt', mimeType: 'text/plain', buffer: Buffer.from([0xb5, 0xda, 0xb6, 0xfe, 0xd5, 0xc2, 0x20, 0xd6, 0xd0, 0xce, 0xc4]) },
    { name: 'cover.png', mimeType: 'image/png', buffer: Buffer.from(await page.evaluate(() => { const c = document.createElement('canvas'); c.width = 80; c.height = 120; c.getContext('2d').fillRect(0, 0, 80, 120); return c.toDataURL().split(',')[1]; }), 'base64') }
  ];
  await page.locator('#importBtn').click();
  await page.locator('#pickFiles').click();
  await page.locator('#fileInput').setInputFiles(fixtures);
  await page.locator('#busy').waitFor({ state: 'hidden' });
  await page.locator('#books h3').filter({ hasText: '旅途1' }).waitFor();
  assert.equal(await page.locator('#books .book-card').count(), 3);
  assert.equal(await page.locator('#books .cover img').count(), 2);
  await page.locator('#importBtn').click();
  await page.locator('[name="grouping"][value="merged"]').check();
  await page.locator('#pickFiles').click();
  await page.locator('#fileInput').setInputFiles(fixtures.slice(0, 2));
  await page.locator('#busy').waitFor({ state: 'hidden' });
  await page.locator('#books h3').filter({ hasText: '合辑' }).waitFor();
  assert.equal(await page.locator('#books .book-card').count(), 4);
  // Re-import is an update, preserving the book identity.
  await page.locator('#importBtn').click();
  await page.locator('[name="grouping"][value="separate"]').check();
  await page.locator('#pickFiles').click();
  await page.locator('#fileInput').setInputFiles(fixtures.slice(0, 2));
  await page.locator('#busy').waitFor({ state: 'hidden' });
  assert.equal(await page.locator('#books .book-card').count(), 4);
  await page.screenshot({ path: new URL('../artifacts/shelf.png', import.meta.url).pathname });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
  assert.equal(overflow, false);
  assert.deepEqual(errors, []);
  console.log('PASS: mobile reading, chapters, bookmarks, themes, typography, progress, persistence, shelves, full-text search, batch import, GB18030, merge and deduplication');
} finally { await context.close(); await browser.close(); }
