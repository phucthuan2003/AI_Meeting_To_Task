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
      input: { panel: 'sidepanel.html', offscreen: 'offscreen.html', microphone: 'microphone.html', 'service-worker': 'src/service-worker.mjs', 'pcm-processor': 'src/pcm-processor.mjs' },
      output: { entryFileNames: (chunk) => ['service-worker', 'pcm-processor'].includes(chunk.name) ? `${chunk.name}.js` : 'assets/[name]-[hash].js' },
    },
  },
  plugins: [{
    name: 'mv3-manifest',
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'manifest.json', source: JSON.stringify(extensionManifest(origin), null, 2) });
    },
  }],
});
