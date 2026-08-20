// Syntax-checks the inline <script> in index.html.
//
// The browser app is one large inline script, so `node --check index.html`
// cannot see it and a syntax error would only surface as a blank page in
// production. This extracts the script blocks and checks them as a module.
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const html = readFileSync('index.html', 'utf8');
const blocks = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);

if (blocks.length === 0) {
  console.error('No inline script found in index.html — did the page structure change?');
  process.exit(1);
}

const out = join(mkdtempSync(join(tmpdir(), 'seven-')), 'inline.js');
writeFileSync(out, blocks.join('\n;\n'));
execFileSync(process.execPath, ['--check', out], { stdio: 'inherit' });
console.log(`inline script OK (${blocks.length} block(s), ${blocks.join('').length} chars)`);
