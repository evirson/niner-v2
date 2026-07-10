# niner-web

App do **ERP do lojista** (tenant) — React 19 + Vite + TypeScript. É onde o cliente
opera a loja depois de criar a conta no site (`site/`).

## O que já tem

- **Auth JWT**: login (slug da loja + email + senha → `POST /api/publico/login`) e
  **handoff SSO** do site (`#token=...` na URL no 1º acesso pós-signup → guarda o token
  e limpa a URL). Guarda de rota redireciona ao login sem sessão; 401 desloga.
- **Shell do ERP**: cabeçalho + navegação lateral (Painel, Produtos, Estoque, Pedidos, Canais) + Sair.
- **Painel**: dá boas-vindas e mostra loja/assinatura/usuário via `GET /api/v1/eu`
  (dados por TanStack Query).
- **Placeholders** "em construção" para as áreas de domínio (endpoints ainda não existem).
- **Design system §3.7**: tokens CSS, tema claro/escuro; mesma paleta do site.

## Base-URL da API em runtime

`public/config.js` define `window.NINER_API_BASE` (e o site define `NINER_WEB_BASE` para o
handoff). Spec §3.1: o front lê a base-URL em runtime, não embutida — troque o arquivo em
produção sem rebuild.

## Rodar em dev

```bash
cd web && npm install && npm run dev     # http://localhost:5173
```
Precisa da API no ar e do banco migrado. Fluxo completo: crie a conta em
`http://localhost:5175/assinar` → "Ir para o sistema" leva a este app já logado.

Build de produção: `npm run build` (saída em `dist/`).

## Próximo

Áreas de domínio reais (Produtos, Estoque, Pedidos, Canais) conforme os endpoints
`/api/v1/**` forem implementados no backend. Componente `AjudaDaTela` (R22, §3.7.1).
