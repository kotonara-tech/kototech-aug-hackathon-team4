import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const here = dirname(fileURLToPath(import.meta.url));

/**
 * デモ画面だけを、ファイルを開くだけで動く 1 枚の HTML に固めるためのビルド。
 *
 * ES モジュールのままだと file:// から読み込めないので、IIFE で 1 ファイルに寄せ、
 * そのあと scripts/inline-demo.mjs が HTML へ埋め込む。
 * 当日、Node.js もサーバーも無い PC でデモできる状態を保つための構成。
 */
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist-demo',
    emptyOutDir: true,
    assetsInlineLimit: 0,
    rollupOptions: {
      input: resolve(here, 'demo.html'),
      output: {
        format: 'iife',
        inlineDynamicImports: true,
        entryFileNames: 'demo.js',
        assetFileNames: 'demo.[ext]',
      },
    },
  },
});
