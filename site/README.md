# niner-site

Site público de aquisição do Niner — **Astro (SSG)**, escolhido para SEO/Core Web
Vitals (ADR-011). É a superfície onde o cliente conhece o produto e faz a
**assinatura-teste** (trial de 14 dias, sem cartão — R12).

## Páginas

- `/` — landing (proposta de valor + planos). HTML estático (bom para SEO); os planos
  são atualizados com dados reais de `GET /api/publico/planos` via progressive enhancement.
- `/assinar` — formulário de conta-teste → `POST /api/publico/assinar`. No sucesso,
  guarda o token (auto-login) e redireciona para `/bem-vindo`.
- `/bem-vindo` — primeiro uso: lê o token, chama `GET /api/v1/eu` e confirma que a loja
  foi liberada em modo teste. (O botão "Ir para o sistema" abrirá o app `web/` quando existir.)

## Base-URL da API em runtime

`public/config.js` define `window.NINER_API_BASE` (spec §3.1: o front **lê** a base-URL
em runtime, não embutida no bundle). Em produção, troque esse arquivo para apontar à API
real — sem rebuild do site.

## Rodar em dev

```bash
cd site && npm install && npm run dev     # http://localhost:5175
```
Precisa da API no ar (`docker compose up -d db && docker compose run --rm flyway && (cd api && ./mvnw spring-boot:run)`).
Ou via compose: `docker compose --profile fronts up site`.

Build de produção (HTML estático em `dist/`): `npm run build`.

## Design system (§3.7)

Cores por tokens CSS (`--ground`, `--surface`, `--accent` teal, …) com tema claro/escuro
(`prefers-color-scheme` + override `data-theme`). Golden file:
`docs/padroes/cadastro_fornecedor_campos_cinza.html`.

## Pendências

- **R22 (ajuda de tela + vídeo em toda tela):** ainda não implementado no site — adicionar
  o componente `AjudaDaTela` (§3.7.1).
- Páginas de conteúdo/SEO (blog, features, pricing detalhado) e sitemap/robots.
- Integração real com o app `web/` no botão "Ir para o sistema".
