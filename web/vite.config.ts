import { defineConfig } from "vite";

export default defineConfig({
  base: "./",
  build: {
    outDir: "../tandem/static",
    emptyOutDir: true,
  },
});
