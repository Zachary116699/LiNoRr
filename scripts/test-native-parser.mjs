import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const project = fileURLToPath(new URL('../', import.meta.url));
const output = mkdtempSync(join(tmpdir(), 'lnreader-parser-'));
const executable = name => process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', name) : name;
try {
  execFileSync(executable('javac'), ['-d', output, 'app/src/main/java/com/lnreader/app/BookParser.java', 'app/src/main/java/com/lnreader/app/DisplayText.java', 'app/src/main/java/com/lnreader/app/BackupFiles.java', 'app/src/main/java/com/lnreader/app/ChapterTree.java', 'app/src/main/java/com/lnreader/app/Pagination.java', 'tests/BookParserTest.java'], { cwd: project, stdio: 'inherit' });
  execFileSync(executable('java'), ['-ea', '-cp', output, 'com.lnreader.app.BookParserTest', '../xs_local_html/txt/蛊真人.txt'], { cwd: project, stdio: 'inherit' });
} finally { rmSync(output, { recursive: true, force: true }); }
