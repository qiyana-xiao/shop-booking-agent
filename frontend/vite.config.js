import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 5173 开发服务，/api 全部代理到 Spring Boot 8080，前端不感知后端地址
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
