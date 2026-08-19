import sitemap from '@astrojs/sitemap';
import { defineConfig } from 'astro/config';

// Site público do Nainer — SSG (HTML estático) para SEO. A base-URL da API é lida em
// runtime (public/config.js), não embutida no bundle (spec §3.1: front lê API em runtime).
export default defineConfig({
  site: 'https://nainer.com.br',
  server: { port: 5175 },
  // SEO (ADR-017 / briefing da landing): sitemap gerado no build. `robots.txt` (public/) aponta
  // para ele. As páginas de handoff não entram — não têm conteúdo indexável e canibalizariam a
  // busca por "entrar no Nainer".
  integrations: [sitemap({ filter: (pagina) => !pagina.includes('/entrar') && !pagina.includes('/bem-vindo') })],
});
