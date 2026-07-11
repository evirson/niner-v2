# niner-site

Site público de aquisição do Niner — **Astro (SSG)**, escolhido para SEO/Core Web
Vitals (ADR-011). É a superfície onde o cliente conhece o produto e faz a
**assinatura-teste** (trial de **60 dias**, sem cartão — R12; posicionamento de ERP
multicanal, concorrente do Bling).

## Páginas

- `/` — **home institucional** (proposta de valor + planos). Página longa e animada
  posicionando o Niner como ERP multicanal: hero com painel decorativo + **demo de
  sincronização estoque→canais**, faixa de stats com **contadores**, contraste
  problema→solução, "como funciona" (3 passos), recursos, canais, planos, FAQ e CTA.
  HTML estático (bom para SEO); os planos são atualizados com dados reais de
  `GET /api/publico/planos` via progressive enhancement.
- `/assinar` — formulário de conta-teste → `POST /api/publico/assinar`. No sucesso,
  guarda o token (auto-login) e redireciona para `/bem-vindo`.
- `/bem-vindo` — primeiro uso: lê o token, chama `GET /api/v1/eu` e confirma que a loja
  foi liberada em modo teste. (O botão "Ir para o sistema" abrirá o app `web/` quando existir.)

## Movimento e acessibilidade

O sistema visual vive em `src/styles/site.css` (portado do golden
`docs/padroes/nainer_institucional/`, rebrandeado Niner). O movimento é feito em CSS/SVG
puro, **sem dependências**: scroll-reveal (`.reveal` via IntersectionObserver em
`Base.astro`), contadores (`[data-count]`), painel do hero flutuante e demo de sync.
**Tudo respeita `prefers-reduced-motion`** — sob "reduzir movimento", o conteúdo aparece
estático e completo. Sem JS (crawlers), os `.reveal` também aparecem (progressive enhancement).

> **Trocar o mockup do hero por print real:** no `src/pages/index.astro`, o bloco
> `.hero-visual` traz um painel desenhado em CSS com o marcador
> `<!-- TODO: trocar por print real do painel do ERP -->`. Basta substituir o `.mock` por
> um `<img>` (ou `<picture>`) do screenshot; o restante do layout do hero não muda.

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
(`prefers-color-scheme` + override `data-theme`, persistido em `localStorage` pelo toggle
da navbar). Golden files: `docs/padroes/cadastro_fornecedor_campos_cinza.html` (formulários)
e `docs/padroes/nainer_institucional/` (institucional — base do `site.css`).

## Pendências

- **R22 (ajuda de tela + vídeo em toda tela):** ainda não implementado no site — adicionar
  o componente `AjudaDaTela` (§3.7.1).
- Páginas de conteúdo/SEO (blog, features, pricing detalhado) e sitemap/robots.
- Integração real com o app `web/` no botão "Ir para o sistema".
