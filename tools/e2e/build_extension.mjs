// Builds an unpacked MV3 extension with esbuild (same entry points, manifest and CSP as the Vite build) for
// browser E2E runs where Vite/Rollup native binaries are unavailable. Usage:
//   node tools/e2e/build_extension.mjs <outDir> [apiOrigin]
import { mkdir, writeFile, rm } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../extension');
// esbuild comes from extension/node_modules (installed by npm ci).
const { build } = createRequire(path.join(root, 'package.json'))('esbuild');
const { backendOrigin, extensionManifest } = await import(path.join(root, 'src/config.mjs'));
const out = path.resolve(process.argv[2] ?? 'dist-e2e');
const origin = backendOrigin(process.argv[3] ?? 'http://127.0.0.1:8080');
await rm(out, { recursive: true, force: true });
await mkdir(path.join(out, 'assets'), { recursive: true });
await build({ entryPoints: { panel: path.join(root, 'src/main.jsx') }, outdir: path.join(out, 'assets'), bundle: true, format: 'esm', jsx: 'automatic',
  target: 'chrome116', minify: true, define: { __API_ORIGIN__: JSON.stringify(origin), 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
await build({ entryPoints: [path.join(root, 'src/service-worker.mjs')], outfile: path.join(out, 'service-worker.js'), bundle: true, format: 'esm', target: 'chrome116', logLevel: 'warning' });
await writeFile(path.join(out, 'sidepanel.html'), '<!doctype html><html lang="vi"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>Meeting to Task</title><link rel="stylesheet" href="./assets/panel.css"></head><body><div id="root"></div><script type="module" src="./assets/panel.js"></script></body></html>');
await writeFile(path.join(out, 'manifest.json'), JSON.stringify(extensionManifest(origin), null, 2));
console.log(`Extension built at ${out} for ${origin}`);
