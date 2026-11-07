import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
/** Compile assets for the same-origin Java gateway; watch mode uses the same transport as production. */
export default defineConfig({ plugins: [react()], build: { outDir: "dist" } });
