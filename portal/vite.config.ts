import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { resolve } from 'node:path';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, resolve(import.meta.dirname, '..'), '');
  const token = process.env.KUBESPAWN_DEV_TOKEN || env.KUBESPAWN_DEV_TOKEN;
  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': {
          target: process.env.API_PROXY_TARGET || `http://127.0.0.1:${env.API_PORT || 8080}`,
          changeOrigin: false,
          headers: token ? { Authorization: `Bearer ${token}` } : {},
        },
      },
    },
  };
});
