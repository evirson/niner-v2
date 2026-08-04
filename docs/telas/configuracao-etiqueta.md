# Configuração de Etiqueta de Produtos

**Status (2026-08-04):** modelo de dados e tela completos e testados ao vivo (CRUD, drag-and-drop,
código de barras real, produto de exemplo). Só "Emissão de Etiqueta de Produtos" (impressão de
verdade) continua em Implementações Futuras — esta tela é só o editor de layout.

## Contexto

Etiqueta de código de barras impressa em rolo (impressora térmica dedicada), múltiplas colunas
por linha. Uma configuração nomeada descreve: as dimensões do rolo/etiqueta/bordas, a posição de
cada coluna no rolo físico, e quais campos do produto aparecem em cada etiqueta — com posição
livre (x/y) e estilo próprios. Validado contra um modelo real de etiqueta impressa (PDF fornecido
pelo dono do produto: nome da loja em fundo preto/letra branca no topo, descrição, variação
(cor/tamanho), código de barras com dígitos legíveis embaixo, e preço em destaque).

## Decisões de escopo (perguntadas e confirmadas nesta sessão)

- **Por tenant, não por empresa** — mesma lógica de `produto`/`cfg_variante_linha`, que já são
  por tenant. A tela de Emissão (futura) ainda escolhe a empresa (pro `cfg_nome_etiqueta` e pro
  preço), só o layout da etiqueta é compartilhado entre as empresas do tenant.
- **Várias configurações nomeadas** (cadastro completo, não singleton) — o usuário cria
  "Etiqueta 3 colunas", "Etiqueta grande 2 colunas" etc., e escolhe qual usar na hora de emitir.
  Faz sentido dado que existem 2 telas separadas no menu (Configuração e Emissão).
- **Posicionamento livre (x/y) desde a 1ª fase** — cada campo tem sua própria coordenada dentro
  da etiqueta, não um empilhamento automático. Decisão explícita do dono do produto (a opção mais
  simples — só ordem, sem x/y — foi oferecida e recusada).

## Tabelas (`db/migration/V029__cfg_etiqueta.sql`)

### `cfg_etiqueta_config` — cabeçalho

Uma linha por configuração nomeada: `nome`, `largura_rolo_mm`, `numero_colunas` (`CHECK` 1 a 6),
`largura_etiqueta_mm`, `altura_etiqueta_mm`, 4 bordas (`borda_superior/inferior/esquerda/
direita_mm`), `ativo` (fallback de inativar, mesmo padrão do resto do sistema).

### `cfg_etiqueta_coluna` — filha, posição de cada coluna no rolo físico

Uma linha por coluna (`numero_coluna` 1..N, `posicao_inicial_mm`). **Não é calculada por
fórmula** — embora nos exemplos dados as posições sejam regulares (`borda_esquerda +
(coluna-1) × (largura_etiqueta + vão)`), o valor fica explícito e editável porque rolos com
espaçamento irregular entre colunas existem na prática. A tela (quando construída) pode
*sugerir* o valor calculado ao adicionar uma coluna nova, sem forçar.

### `cfg_etiqueta_campo` — filha, o que é impresso e como

Uma linha por campo presente na etiqueta: `campo` (ENUM `campo_etiqueta`, ver abaixo),
`posicao_x_mm`/`posicao_y_mm` (relativos ao canto superior-esquerdo da própria etiqueta, não do
rolo), `largura_mm`/`altura_mm` (bounding box — largura/altura do texto ou do código de barras),
`fonte` (ENUM `fonte_etiqueta`), `tamanho_fonte_pt`, `negrito`, `fundo_preto` (true = fundo
preto/letra branca, como o nome da loja no PDF de referência; false = padrão, letra preta sem
fundo), `alinhamento` (ENUM `alinhamento_etiqueta_campo`), `exibir_texto_legivel` (só p/
`SKU_BARRAS`/`EAN_BARRAS` — mostra os dígitos embaixo das barras).

`UNIQUE (id_config_etiqueta, campo)` — cada campo aparece no máximo uma vez por configuração.

### ENUM `campo_etiqueta` — os 10 campos possíveis, mapeados pra colunas reais já existentes

| Valor | Origem |
|---|---|
| `NOME_EMPRESA` | `empresa.cfg_nome_etiqueta` (já existe desde V014, nunca lido por nenhuma tela até agora) |
| `DESCRICAO_PRODUTO` | `produto.descricao` |
| `MARCA` | `produto.marca` |
| `REFERENCIA` | `produto.referencia` |
| `PRECO_VENDA` | `produto.preco_venda` |
| `PRECO_OFERTA` | `produto.preco_oferta` |
| `SKU_BARRAS` | `produto_barra.sku` (código de barras interno, sempre `gerar_ean13_interno()`) |
| `EAN_BARRAS` | `produto_barra.ean` (GTIN do fabricante, nullable) |
| `VARIANTE_LINHA` | `cfg_variante_linha.descricao` (via `produto_barra.id_variante_linha`, nullable) |
| `VARIANTE_COLUNA` | `cfg_variante_coluna.descricao` (via `produto_barra.id_variante_coluna`, nullable) |

Nenhuma tabela nova pra esses 7 campos que já existiam — só referenciados via o ENUM, não
duplicados.

### ENUM `fonte_etiqueta` — provisório

`ARIAL`, `COURIER`, `TIMES_NEW_ROMAN`. Conjunto pequeno e seguro, mas depende de uma decisão
ainda em aberto: impressão via impressora térmica dedicada (ZPL/EPL — fonte limitada ao firmware)
ou via impressão de navegador (CSS, como o comprovante térmico 80mm já faz — qualquer fonte web
serve). Fácil de estender (`ALTER TYPE ADD VALUE`, banco em construção) quando essa decisão for
tomada — não bloqueia o restante do modelo.

## Tela

CRUD padrão (lista + form, ADMIN-only, grupo "Configurações" do menu — ao lado de Usuários e
Parâmetros do Sistema) mais um editor visual sem precedente no projeto: o usuário digita as
dimensões do cabeçalho e **arrasta** os campos pra posicionar dentro de uma etiqueta desenhada em
escala real de milímetros.

**Backend** — `com.vetor.niner.configuracao.etiqueta` (`EtiquetaConfigDtos`/`Service`/
`Controller`, `EtiquetaConfigCrudTest` com 11 casos): mesmo padrão de `JdbcClient` cru do resto do
domínio. Coleções filhas (`colunas`/`campos`) salvas por apaga-tudo-e-reinsere dentro da mesma
transação do cabeçalho (mesmo princípio de `ProdutoService.salvarCategorias`). `excluir()` é
sempre um DELETE de verdade — diferente de Produto/Plano de Contas, nada ainda referencia
`cfg_etiqueta_config` por FK (a tela de Emissão que consumiria isso não existe), então não há
dependente real pra checar; `ativo` é só um "desativar sem apagar" editável direto no form.
Endpoint extra `GET /api/v1/etiquetas-config/produtos-exemplo` — **não** reaproveita a busca de
produto do Kardex (`VariacaoEncontrada`), que não tem referência/preço/EAN; DTO próprio
(`ProdutoExemploResponse`) com tudo que `campo_etiqueta` pode imprimir.

**Frontend** (`web/src/pages/etiquetaconfig/`): `EtiquetaConfigLista.tsx`/`EtiquetaConfigForm.tsx`
seguem o esqueleto padrão de cadastro (paginação, `InfoRegistro`, `BotaoFecharTela`, Enter-como-Tab).
O cabeçalho usa a nova máscara `mascararEtiquetaMm`/`completarEtiquetaMm`/`desmascararEtiquetaMm`
(`lib/masks.ts`, 2 casas — reaproveita os mesmos helpers privados genéricos de
`mascararPercentual`). Mudar "Número de Colunas" redimensiona a lista de colunas automaticamente
(sugere `borda_esquerda + (n-1)×(largura_etiqueta+vão)` pra colunas novas, sem forçar — confirmado
funcionando: rolo 110mm/3 colunas/34mm sugeriu 0/34/68mm sozinho).

**`EditorEtiquetaCanvas.tsx`** é o componente central — régua em mm (topo/esquerda, marca a cada
5mm), zoom (50%–300%), paleta lateral dos campos ainda não usados, e cada campo colocado é uma
`CampoEtiquetaVisual.tsx` (componente "puro", reaproveitado também pela prévia do rolo completo,
só leitura) posicionada via `left/top` calculados de mm→px. Interação por **Pointer Events
nativos**, sem biblioteca de drag-and-drop (`dnd-kit`/`react-dnd` resolvem reordenar lista, não
posicionamento livre 2D com régua/snap/zoom — decisão registrada, não uma omissão): arrastar move
só x/y (snap de 0,5mm, travado dentro da etiqueta); tamanho/fonte/estilo ficam no
`PainelPropriedadesCampo.tsx` (aparece ao selecionar um campo); setas do teclado dão nudge fino
(0,5mm, Shift = 5mm); Delete/Backspace remove o campo selecionado. Campo cujo retângulo invade a
borda configurada ganha contorno vermelho de aviso (não bloqueia — a borda é guia, não parede
rígida). Código de barras (SKU/EAN) renderiza de verdade via `jsbarcode` (sempre `CODE128`, nunca
`EAN13` — aceita qualquer valor sem checar dígito verificador, então nunca quebra o editor com um
SKU/EAN incompleto; a simbologia exata de impressão é problema da futura tela de Emissão, aqui é
só layout). "Escolher produto de exemplo" (`ProdutoExemploModal.tsx`, mesmo padrão visual de
`PesquisaVariacaoModal.tsx` do Kardex, endpoint próprio) troca os placeholders genéricos por
descrição/marca/preço/variação/SKU/EAN reais de um produto do catálogo, ao vivo.

**2 bugs pegos só no teste manual ao vivo (nenhum teste automatizado cobria isso):**

1. **`import * as JsBarcode from 'jsbarcode'` tipava certo no `tsc` mas quebrava em runtime**
   (`TypeError: JsBarcode is not a function`) — `@types/jsbarcode` declara `export =` (CJS
   callable), e o interop do Vite/esbuild pra `import * as X` desse tipo de módulo não produz um
   objeto chamável (o `tsc`, com `moduleResolution: bundler`, valida o TIPO mas não pega esse
   descompasso de runtime). Corrigido trocando pra `import JsBarcode from 'jsbarcode'` (default
   import — `tsc` aceita mesmo sem `esModuleInterop` explícito, por causa do modo bundler).
2. **Mutações rápidas em sequência podiam se perder** (2 cliques rápidos na paleta, ou segurar uma
   seta do teclado): `adicionarCampo`/`removerCampo`/`moverCampoRelativo` liam o array `campos`
   fechado na prop do componente — se duas mutações acontecem no mesmo ciclo do React sem
   re-render entre elas (bem plausível em pointermove de arraste rápido, ou tecla em repetição),
   a segunda sobrescrevia a primeira em vez de acumular. Corrigido trocando `aoMudarCampos` de
   "recebe o array pronto" pra "recebe uma função atualizadora" (`(atual) => novo`, padrão de
   updater funcional do React) — cada mutação sempre parte do estado mais recente de verdade,
   nunca de um snapshot capturado no closure. Verificado ao vivo (JS direto no console): 2 cliques
   síncronos na paleta agora resultam nos 2 campos (antes só 1 sobrevivia); 2 keydowns síncronos
   (seta + Shift+seta) agora somam 5,5mm corretamente (antes só o último "vencia", 5mm).

Testado ao vivo ponta a ponta: criar (cabeçalho + 3 campos + produto de exemplo real) → salvar →
visualizar (confere que tudo persistiu — nome, dimensões, bordas, colunas, posição/estilo de cada
campo) → excluir (real, com popup de confirmação). Suíte de backend inteira (`mvn test`) e
`tsc --noEmit` limpos depois das correções.

## Pendências explícitas, fora do escopo desta migration

- **Lógica de emissão** (qual empresa, se `PRECO_OFERTA` está dentro da janela de datas, como
  tratar `VARIANTE_LINHA`/`VARIANTE_COLUNA` nulos) — fica pra quando a tela de Emissão for
  especificada, não é responsabilidade do schema.
- **Estilo "contorno" (borda preta, sem preenchimento)** — reparado no PDF de referência (a caixa
  do tamanho "M"), mas não pedido; `fundo_preto` cobre só o binário preto-cheio/sem-fundo pedido.
  Se vier a ser pedido, é um campo novo (`estilo_fundo` com 3 valores em vez de boolean).
- **Sobreposição visual de dois campos** (x/y colidindo) — não validada pelo banco.
