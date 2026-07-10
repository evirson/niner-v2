import { defineConfig } from 'astro/config';

// Site público do Niner — SSG (HTML estático) para SEO. A base-URL da API é lida em
// runtime (public/config.js), não embutida no bundle (spec §3.1: front lê API em runtime).
export default defineConfig({
  site: 'https://niner.com.br',
  server: { port: 5175 },
});
