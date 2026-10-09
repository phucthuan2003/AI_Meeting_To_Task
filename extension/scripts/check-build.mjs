import assert from 'node:assert/strict';
import { readFile, readdir, stat } from 'node:fs/promises';
import { backendOrigin, extensionManifest } from '../src/config.mjs';
const expected = extensionManifest(backendOrigin(process.env.API_ORIGIN));
const actual = JSON.parse(await readFile('dist/manifest.json', 'utf8'));
assert.deepEqual(actual, expected);
await stat(`dist/${actual.side_panel.default_path}`);
await stat(`dist/${actual.background.service_worker}`);
const html = await readFile('dist/sidepanel.html', 'utf8');
assert.ok(!/<script(?![^>]*\bsrc=)[^>]*>/i.test(html), 'MV3 requires external scripts');
assert.ok(!/https?:\/\//.test(html), 'No remotely hosted assets');
assert.ok(!actual.content_scripts && !actual.externally_connectable && !actual.web_accessible_resources);
for (const file of await readdir('dist/assets')) {
  if (file.endsWith('.js')) {
    const code = await readFile(`dist/assets/${file}`, 'utf8');
    assert.ok(!/\beval\s*\(|new Function\s*\(/.test(code), 'No eval / remote code');
  }
}
console.log(`MV3 build verified; backend ${backendOrigin(process.env.API_ORIGIN)}`);
