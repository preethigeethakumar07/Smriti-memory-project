import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";

// The built site is copied straight into the Android app's assets, where the phone serves it.
export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    outDir: resolve(__dirname, "../app/src/main/assets/dashboard"),
    emptyOutDir: true,
    target: "es2020",
    assetsInlineLimit: 0,
    chunkSizeWarningLimit: 700,
  },
  server: { port: 5173 },
  test: { environment: "node", include: ["src/**/*.test.ts"] },
});
