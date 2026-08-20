# Logotipia do Nainer

Entregue pelo Claude Design em 2026-08-19, junto com a v3 da landing. Este documento é a fonte —
o arquivo `logotipia.html` do projeto de design mostra as variações renderizadas.

## O que é a marca

**Wordmark serifado** (Georgia / Iowan Old Style — a mesma dos títulos do site, sem fonte externa)
ao lado de um **símbolo**: o "N" num quadrado arredondado, com um **traço neon** — alusão ao cupom
saindo da impressora.

| Cor | Claro | Escuro |
|---|---|---|
| Teal da marca | `#1f6f6b` | `#4fbdb2` |
| Neon (traço) | `#2cf5e2` | `#2cf5e2` |
| Tinta (wordmark) | `#20262a` | `#e7ecec` |

## Regras

- **O traço neon nunca aparece sem o "N".** Ele é parte do símbolo, não um enfeite avulso.
- **Área de respiro mínima:** a altura do traço × 2 em volta de tudo.
- **Sobre foto:** usar a versão de fundo escuro ou a monocromática — nunca a colorida.
- **Com o símbolo ao lado, o wordmark é tinta, não neon.** Dois elementos brilhando competem
  entre si e o destaque deixa de destacar (foi por isso que `.logo b` voltou a `--ink` na v3).

## Onde cada versão vive no código

| Uso | Arquivo |
|---|---|
| Dentro do site (topo, rodapé) | `site/src/componentes/MarcaNainer.astro` |
| Demais páginas (assinar, entrar, legais) | `.brand-mark` em `site/src/layouts/Base.astro` |
| Favicon da landing | data URI no `<head>` de `site/src/pages/index.astro` |
| Arquivo para uso externo (og:image, apps, e-mail) | `site/public/logo.svg` |

⚠️ **Por que existem duas formas do mesmo desenho:** o componente usa `fill="var(--accent)"` e
acompanha o tema claro/escuro sozinho — isso só funciona com **SVG inline**. Um SVG carregado por
`<img>` ou como favicon **não enxerga** as variáveis da página, então `public/logo.svg` tem as
cores literais. Mudou a marca? Mude nos dois.

## Pendências

- 🔴 **`og.png` (1200×630)** ainda não existe — a landing referencia e hoje dá 404 ao compartilhar.
  Precisa ser gerado com a marca sobre fundo `#f5f4f0` ou `#12181a`.
- ✅ O backoffice (`admin/`) já usa o símbolo (lateral e login).
- 🔴 O ERP (`web/`) ainda usa a marca só em texto — falta aplicar no cabeçalho e no login.
