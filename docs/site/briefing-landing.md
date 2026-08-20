# Briefing de design — Landing page do Nainer (página de venda)

> **Para quem lê:** este documento é o *brief* para gerar o **HTML/CSS da página principal** do
> site público do Nainer. Quem implementa depois porta o resultado para **Astro** (`site/`, SSG).
> Entregue **um HTML autocontido** (CSS embutido, sem framework, sem CDN, sem JS de terceiro),
> semântico e acessível. Onde houver dúvida de conteúdo, prefira **menos texto e mais clareza**.

---

## 1. O produto em uma frase

**Nainer** é um **ERP em nuvem para o pequeno varejo brasileiro** — PDV, estoque, crediário, caixa,
contas a pagar, DRE e **emissão de NFC-e/NF-e** — que é **grátis para sempre até 100 vendas por
mês**, sem cartão e sem prazo de validade.

**A promessa central da página (o que o visitante precisa entender em 5 segundos):**
> *"Sistema completo de loja, com nota fiscal, de graça até 100 vendas por mês. Sem cartão, sem
> prazo, sem pegadinha. Se sua loja crescer, você paga por volume — nunca por funcionalidade."*

## 2. Quem é o visitante

- Dono ou gerente de **loja pequena** brasileira (roupa, calçado, acessórios, papelaria, pet,
  materiais) — 1 a 15 pessoas na operação, faturamento de R$ 20 mil a R$ 500 mil/mês.
- Hoje usa **caderno, planilha ou um sistema antigo instalado numa máquina só**. Muitos ainda
  emitem nota pelo emissor gratuito da SEFAZ, separado do sistema da loja.
- **Não é técnico.** Desconfia de "teste grátis de 30 dias" porque já foi surpreendido por
  cobrança. Decide no celular, muitas vezes à noite, e chama no **WhatsApp** antes de assinar.
- **Objeção nº 1:** *"depois vão me cobrar"*. **Objeção nº 2:** *"vou perder meu cadastro se parar
  de pagar"*. A página tem que matar as duas, explicitamente, com palavras simples.

## 3. Tom e princípios visuais

- **Honesto e direto, sem hype.** Nada de "revolucione", "solução disruptiva", "líder de mercado".
  O diferencial já é forte — a gratuidade real —, então o texto deve soar **calmo e confiável**.
- **Concreto:** falar de balcão, cliente na fila, fiado, fechamento de caixa, nota que o contador
  pede. Evitar vocabulário de software ("plataforma multicanal escalável").
- **Estética:** clara, respirada, tipografia grande, seções bem separadas. Referências de
  qualidade: Linear, Stripe, Notion — **mas com peso brasileiro**: preço em real bem visível,
  WhatsApp presente, prova social de loja de bairro, nada de aspiracional gringo.
- **Sem ilustração genérica de banco de imagem.** Prefira **mockups da própria tela do sistema**
  (placeholders marcados como `[PRINT: PDV]`, `[PRINT: Fechamento de Caixa]` etc., que eu
  substituo por capturas reais) e formas geométricas simples.

### Paleta (a mesma do produto — obrigatório manter a identidade)

| Token | Claro | Escuro | Uso |
|---|---|---|---|
| `--ground` | `#f5f4f0` | `#12181a` | fundo da página |
| `--surface` | `#ffffff` | `#1a2225` | cards |
| `--ink` / `--ink-muted` | `#20262a` / `#5c6660` | `#e7ecec` / `#93a19e` | texto |
| `--accent` | `#1f6f6b` (teal) | `#4fbdb2` | ação primária, destaques |
| `--line` | `#dee2dc` | `#2a3538` | divisórias |
| `--danger` | `#a63d29` | `#e2836a` | alerta |

- **Tema claro e escuro obrigatórios** — `@media (prefers-color-scheme: dark)` + override por
  `data-theme="light|dark"` no elemento raiz. Declarar `color-scheme` junto dos tokens.
- **Tipografia:** títulos em **serif** (`Georgia, "Iowan Old Style", serif`), corpo em sans do
  sistema (`-apple-system, "Segoe UI", Roboto, sans-serif`). Números/preços com
  `font-variant-numeric: tabular-nums`.
- ⚠️ **`color-mix()` é proibido no projeto** — use `rgba(var(--x-rgb), alpha)`.

## 4. Estrutura da página (ordem das seções)

1. **Topo fixo** — logo "Nainer", links âncora (Recursos · Preço · Fiscal · Dúvidas), botão
   secundário **Entrar** e botão primário **Criar conta grátis**.
2. **Herói** — `<h1>` com a promessa; subtítulo de uma linha; dois botões (**Criar conta grátis**
   / **Falar no WhatsApp**); micro-copy embaixo: *"Sem cartão de crédito. Sem prazo. Você começa a
   usar em 1 minuto."*; à direita (ou abaixo no mobile) `[PRINT: PDV]`.
3. **Faixa de credibilidade** — 4 números/itens curtos: *"NFC-e e NF-e inclusos"*, *"Seus dados
   nunca são apagados"*, *"Funciona no balcão e no celular"*, *"Suporte por WhatsApp"*.
4. **O problema** (3 cartões curtos) — caderno/planilha que não fecha; nota fiscal em sistema
   separado; fiado sem controle. Linguagem de loja, não de software.
5. **O que o Nainer faz** (grade de 6–8 recursos, ícone + título + 1 linha):
   PDV rápido · Estoque com variação (cor/tamanho/grade) · **NFC-e e NF-e** · Crediário e cobrança
   · Abertura/fechamento de caixa · Contas a pagar e receber · DRE e fluxo de caixa · Etiquetas
   de produto.
6. **Preço — a seção mais importante.** Dois blocos lado a lado:
   - **Gratuito** (destacado): *"R$ 0 · para sempre"*, **até 100 vendas/mês**, "todas as funções
     liberadas, inclusive nota fiscal", "CNPJs, usuários e produtos ilimitados", "sem cartão",
     CTA **Criar conta grátis**.
   - **Cresceu?** — explicação da cobrança **por faixa de volume de vendas**, com alternador
     **Mensal / Anual (−15%)** e uma pequena tabela de faixas (500, 1.000, 1.500… vendas/mês).
     ⚠️ **Os preços vêm da API em runtime** (`GET /api/publico/planos`) — deixe a tabela marcada
     com valores de exemplo e `data-preco-faixa` nos elementos, para eu preencher via JS.
   - Abaixo, em texto pequeno e claro: *"Se você passar de 100 vendas num mês, a gente avisa antes
     e ainda libera uma folga. Nada é apagado, nunca."*
7. **Fiscal** — seção própria (é o gancho de aquisição): NFC-e e NF-e emitidas de dentro do
   sistema, certificado A1, contingência, XML guardado por 5 anos. Uma frase que o contador
   entenda: *"O XML fica arquivado e disponível para o seu contador."*
8. **Como começar** — 3 passos: criar conta (1 min) → cadastrar produtos (importa planilha) →
   vender. Cada passo com 1 linha.
9. **Prova social** — 2 ou 3 depoimentos curtos com nome, loja e cidade. **Marque como
   `[PLACEHOLDER: depoimento real]`** — não invente cliente nem número.
10. **FAQ** (acordeão `<details>`, sem JS): "É grátis mesmo?" · "O que acontece se eu passar de
    100 vendas?" · "Perco meus dados se parar de pagar?" · "Preciso de certificado digital?" ·
    "Funciona com mais de um CNPJ?" · "Meus dados ficam onde?" · "Como cancelo?"
11. **CTA final** — repetição do herói, fundo `--accent`, botão grande + WhatsApp.
12. **Rodapé** — Vetor Sistemas, CNPJ, contato, WhatsApp, Instagram, e-mail, links legais
    (Termos, Privacidade/LGPD), aviso de cookies próprio.

## 5. SEO — requisitos obrigatórios

- **Um único `<h1>`**, com a palavra-chave principal: *sistema para loja com nota fiscal grátis*.
  Hierarquia `h2`/`h3` correta em todas as seções.
- **`<title>`** ≤ 60 caracteres e **`<meta name="description">`** ≤ 155, escritos para clique, não
  para robô. Sugestão de título: *"Nainer — sistema para loja com NFC-e, grátis até 100 vendas/mês"*.
- **Open Graph + Twitter Card** completos (`og:title`, `og:description`, `og:image` 1200×630,
  `og:url`, `og:type=website`, `og:locale=pt_BR`).
- **JSON-LD** com `SoftwareApplication` (+ `offers` com `price: 0`, `priceCurrency: BRL`),
  `Organization` e `FAQPage` refletindo **exatamente** as perguntas da seção 10.
- `<html lang="pt-BR">`, `<link rel="canonical">`, favicon, `theme-color` para os dois temas.
- **Performance é SEO:** meta de **LCP < 2,0 s** e **CLS < 0,05** em 4G. Sem webfont externa
  (usar fontes do sistema), imagens com `width`/`height` declarados, `loading="lazy"` fora da
  dobra, CSS crítico inline. **Zero JavaScript de terceiro.**
- **Acessibilidade:** contraste AA nos dois temas, foco visível, navegação por teclado no
  acordeão e no menu, `alt` descritivo, área de toque ≥ 44px.

## 6. Rastreamento — o HTML precisa nascer instrumentado (ADR-017)

Medição é **própria, first-party**. Não inclua GA4, Meta Pixel ou qualquer script externo.
O que o HTML precisa entregar pronto:

- **Todo elemento clicável relevante com `data-evento`** — o JS do site (que eu escrevo) lê esse
  atributo e envia para `POST /api/publico/eventos`:

| Elemento | Atributo |
|---|---|
| Botão "Criar conta grátis" (herói, preço, CTA final) | `data-evento="INICIO_SIGNUP" data-rotulo="heroi\|preco\|rodape"` |
| Botão/link WhatsApp | `data-evento="CLIQUE_WHATSAPP" data-rotulo="<seção>"` |
| Link Instagram | `data-evento="CLIQUE_INSTAGRAM"` |
| Alternador Mensal/Anual | `data-evento="TOGGLE_CICLO" data-rotulo="mensal\|anual"` |
| Abertura de item do FAQ | `data-evento="FAQ" data-rotulo="<pergunta>"` |
| Âncora do menu | `data-evento="NAV" data-rotulo="<destino>"` |

- **Marcos de rolagem:** seções principais com `id` estável (`#recursos`, `#preco`, `#fiscal`,
  `#duvidas`) para eu medir profundidade de leitura.
- **Aviso de cookies próprio** (banner discreto no rodapé, dispensável em 1 clique) com texto
  curto de LGPD e link para a política — sem biblioteca de consentimento de terceiro.
- **WhatsApp:** o link deve ser `https://wa.me/<NUMERO>?text=<mensagem pré-preenchida>` com o
  número em `[PLACEHOLDER: número WhatsApp]` e a mensagem *"Olá! Vi o Nainer no site e queria
  saber mais."*

## 7. Restrições técnicas (o que eu vou precisar portar)

- **Um arquivo HTML**, CSS em `<style>` no `<head>`, **sem** `<script>` externo. JS inline só se
  for essencial ao layout — o comportamento (beacon, preços em runtime, tema) eu implemento
  depois em Astro.
- **Mobile-first de verdade** — mais da metade deste público chega pelo celular. Nada de tabela
  que estoura: a tabela de faixas precisa rolar dentro de um contêiner com `overflow-x: auto`.
- **Sem `<input type="date">`, sem máscara própria** — não há formulário complexo nesta página;
  o cadastro acontece em `/assinar`, que já existe.
- Textos em **português do Brasil**, sem estrangeirismo desnecessário ("checkout" → "pagamento",
  "pricing" → "preço").
- Marque com `[PLACEHOLDER: ...]` **tudo** que for número real, depoimento, foto ou contato — a
  página não pode inventar cliente, métrica ou selo.

## 8. Entregável

1. `index.html` autocontido, pronto para abrir no navegador, nos dois temas.
2. Uma lista curta, no fim do arquivo (em comentário HTML), de: fontes usadas, tokens
   introduzidos além dos da tabela e o que ficou como placeholder.

## 9. O que **não** fazer

- Não prometer prazo de teste ("30 dias grátis") — o modelo é **gratuito sem prazo**, e trocar isso
  quebra a promessa central.
- Não listar recurso que o produto ainda não tem: **integração com marketplace (Mercado Livre,
  Shopee, Amazon) está em desenvolvimento** e só pode aparecer, se aparecer, como *"em breve"*.
- Não usar selo de segurança, prêmio, logo de cliente ou número de usuários inventados.
- Não colocar formulário de cadastro na landing (o fluxo é o botão → `/assinar`).
