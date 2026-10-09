import { defineConfig } from 'vite';
import { backendOrigin, extensionManifest } from './src/config.mjs';

// Build-time selection keeps the token destination aligned with host permissions/CSP.
const origin = backendOrigin(process.env.API_ORIGIN);
export default defineConfig({
  base: './',
  define: { __API_ORIGIN__: JSON.stringify(origin) },
  build: {
    target: 'chrome116',
    rollupOptions: {
      input: { panel: 'sidepanel.html', 'service-worker': 'src/service-worker.mjs' },
      output: { entryFileNames: (chunk) => chunk.name === 'service-worker' ? 'service-worker.js' : 'assets/[name]-[hash].js' },
    },
  },
  plugins: [{
    name: 'mv3-manifest',
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'manifest.json', source: JSON.stringify(extensionManifest(origin), null, 2) });
    },
  }],
});
