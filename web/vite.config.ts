/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],

  server: {
    // OAuth クライアントの「承認済みの JavaScript 生成元」と一致させる必要がある。
    // ポートが勝手にずれるとログインだけが失敗するので strictPort にしている。
    port: 5173,
    strictPort: true,
  },

  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
