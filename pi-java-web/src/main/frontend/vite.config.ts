import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";

// 前端源码位于 src/main/frontend，构建产物直接写入 src/main/resources/web，
// 由 PiWebServer 从 classpath /web 提供（顶层 index.html + assets/*）。
// Maven 通过 frontend-maven-plugin 在 generate-resources 阶段触发本构建。
export default defineConfig({
  root: ".",
  plugins: [tailwindcss()],
  build: {
    outDir: "../resources/web",
    emptyOutDir: true,
    rollupOptions: {
      input: "index.html",
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api/ws": {
        target: "ws://localhost:8788",
        ws: true,
      },
    },
  },
});
