# Configuração de Etiqueta de Produtos

**Status (2026-08-05):** modelo de dados e tela completos e testados ao vivo (CRUD, drag-and-drop,
redimensionar por mouse, código de barras real, produto de exemplo). "Emissão de Etiqueta de
Produtos" (impressão de verdade ligada a produto/estoque, `docs/telas/etiqueta-emissao.md`) **já
existe** — reaproveita 100% os endpoints desta tela (`GET /api/v1/etiquetas-config[/{id}]`), zero
código novo aqui. Esta tela também tem um **Teste de Impressão** próprio (ver seção no fim) que
imprime o layout configurado de verdade, sem depender da Emissão. Revisão de UX em 2026-08-05 (ver
linha do tempo do dia em `docs/PROGRESSO.md`), em três rodadas na mesma data: **rodada 1** —
campos reduzidos de 10 para 4 (Marca/Referência/Variação passaram a ser concatenados dentro da
Descrição), redimensionamento por mouse, sincronização ao vivo entre editor e prévia do rolo,
quebra de linha em texto longo, proporção do código de barras corrigida, e busca de produto de
exemplo passou a incluir produtos sem variação/SKU cadastrado; **rodada 2** — máximo de colunas do
rolo reduzido de 6 para 4, cabeçalho do formulário reorganizado em 3 colunas lado a lado (menos
rolagem vertical) e o botão **Testar Impressão**; **rodada 3** — código de barras `EAN13` de
verdade (era `CODE128`), quadro/borda em volta de cada etiqueta no Teste de Impressão, e o painel
de propriedades do campo virou popup.

## Contexto

Etiqueta de código de barras impressa em rolo (impressora térmica dedicada), múltiplas colunas
por linha. Uma configuração nomeada descreve: as dimensões do rolo/etiqueta/bordas, a posição de
cada coluna no rolo físico, e quais campos do produto aparecem em cada etiqueta — com posição
livre (x/y) e estilo próprios. Validado contra um modelo real de etiqueta impressa (PDF fornecido
pelo dono do produto: nome da loja em fundo preto/letra branca no topo, descrição, variação
(cor/tamanho), código de barras com dígitos legíveis embaixo, e preço em destaque).

## Decisões de escopo (perguntadas e confirmadas nesta sessão)

- **Por tenant, não por empresa** — mesma lógica de `produto`/`cfg_cor`, que já são
  por tenant. A tela de Emissão (~~futura~~ — **já existe**, `docs/telas/etiqueta-emissao.md`)
  ainda escolhe a empresa (pro `cfg_nome_etiqueta` e pro preço), só o layout da etiqueta é
  compartilhado entre as empresas do tenant.
- **Várias configurações nomeadas** (cadastro completo, não singleton) — o usuário cria
  "Etiqueta 3 colunas", "Etiqueta grande 2 colunas" etc., e escolhe qual usar na hora de emitir.
  Faz sentido dado que existem 2 telas separadas no menu (Configuração e Emissão).
- **Posicionamento livre (x/y) desde a 1ª fase** — cada campo tem sua própria coordenada dentro
  da etiqueta, não um empilhamento automático. Decisão explícita do dono do produto (a opção mais
  simples — só ordem, sem x/y — foi oferecida e recusada).

## Tabelas (`db/migration/V029__cfg_etiqueta.sql`)

> ⚠️ **Esta seção descreve o modelo COMO NASCEU em 2026-08-05. A V057 (2026-08-20) o encolheu:**
> `cfg_etiqueta_coluna` **não existe mais** e as 4 colunas `borda_*` foram removidas; no lugar
> entraram `margem_esquerda_mm` e `espacamento_horizontal_mm` em `cfg_etiqueta_config` (com
> `espacamento_vertical_mm`, da V056), e a posição de cada coluna passou a ser **derivada**. O
> modelo vigente está em *"✅ 2026-08-20, rodada 2 — a geometria virou DERIVADA (V057)"*, no fim
> deste arquivo. O que vem abaixo fica como registro de por que o modelo era assim.

### `cfg_etiqueta_config` — cabeçalho

Uma linha por configuração nomeada: `nome`, `largura_rolo_mm`, `numero_colunas` (`CHECK` 1 a 4 —
era 1 a 6, reduzido em 2026-08-05 a pedido do dono do produto; ver seção de ajustes no fim),
`largura_etiqueta_mm`, `altura_etiqueta_mm`, 4 bordas (`borda_superior/inferior/esquerda/
direita_mm`), `ativo` (fallback de inativar, mesmo padrão do resto do sistema).

### `cfg_etiqueta_coluna` — filha, posição de cada coluna no rolo físico

Uma linha por coluna (`numero_coluna` 1..N, `posicao_inicial_mm`). **Não é calculada por
fórmula** — embora nos exemplos dados as posições sejam regulares (`borda_esquerda +
(coluna-1) × (largura_etiqueta + vão)`), o valor fica explícito e editável porque rolos com
espaçamento irregular entre colunas existem na prática. A tela (construída em 2026-08-05,
`web/src/pages/configuracoes/`) pode *sugerir* o valor calculado ao adicionar uma coluna nova,
sem forçar.

### `cfg_etiqueta_campo` — filha, o que é impresso e como

Uma linha por campo presente na etiqueta: `campo` (ENUM `campo_etiqueta`, ver abaixo),
`posicao_x_mm`/`posicao_y_mm` (relativos ao canto superior-esquerdo da própria etiqueta, não do
rolo), `largura_mm`/`altura_mm` (bounding box — largura/altura do texto ou do código de barras),
`fonte` (ENUM `fonte_etiqueta`), `tamanho_fonte_pt`, `negrito`, `fundo_preto` (true = fundo
preto/letra branca, como o nome da loja no PDF de referência; false = padrão, letra preta sem
fundo), `alinhamento` (ENUM `alinhamento_etiqueta_campo`), `exibir_texto_legivel` (só p/
`SKU_BARRAS`/`EAN_BARRAS` — mostra os dígitos embaixo das barras).

`UNIQUE (id_config_etiqueta, campo)` — cada campo aparece no máximo uma vez por configuração.

### ENUM `campo_etiqueta` — os 4 campos possíveis, mapeados pra colunas reais já existentes

| Valor | Origem |
|---|---|
| `NOME_EMPRESA` | `empresa.cfg_nome_etiqueta` (já existe desde V014, nunca lido por nenhuma tela até agora) |
| `DESCRICAO_PRODUTO` | `produto.descricao` — **concatenada** (ver abaixo) |
| `PRECO_VENDA` | `produto.preco_venda` |
| `SKU_BARRAS` | `produto_barra.sku` (código de barras interno, sempre `gerar_ean13_interno()`) |

Nenhuma tabela nova pra esses campos — só referenciados via o ENUM, não duplicados.

**Revisão de 2026-08-05 — de 10 campos pra 4 (pedido do dono do produto):** `MARCA`, `REFERENCIA`,
`PRECO_OFERTA`, `EAN_BARRAS`, `VARIANTE_LINHA` e `VARIANTE_COLUNA` deixaram de ser campos
posicionáveis separadamente. `PRECO_OFERTA`/`EAN_BARRAS` foram removidos por completo (ninguém
usava, `ProdutoExemplo`/`ProdutoExemploResponse` também perderam esses dois campos). Já
`MARCA`/`REFERENCIA`/`VARIANTE_LINHA`/`VARIANTE_COLUNA` continuam existindo como dado bruto no
endpoint de produto-exemplo, mas agora são **concatenados automaticamente dentro de
`DESCRICAO_PRODUTO`** — a função `montarDescricaoImpressa` (`web/src/lib/etiquetaConfig.ts`) junta
descrição + marca + referência + variação de linha + variação de coluna nessa ordem, **pulando
qualquer pedaço vazio ou que já apareça dentro da descrição** (comparação sem diferenciar
maiúsculas/minúsculas — evita repetir "ADIDAS ADIDAS" quando a marca já está escrita na
descrição). Objetivo: só um campo pra posicionar/arrastar em vez de cinco. Migration `V029`
editada no lugar (banco em construção, nenhuma configuração salva usava os 6 valores removidos —
checado antes de editar).

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
`cfg_etiqueta_config` por FK (~~a tela de Emissão que consumiria isso não existe~~ — a tela de
Emissão **existe** desde 2026-08-05, `docs/telas/etiqueta-emissao.md`, mas consome a configuração
só na hora de imprimir, sem gravar FK pra ela), então não há dependente real pra checar; `ativo`
é só um "desativar sem apagar" editável direto no form.
Endpoint extra `GET /api/v1/etiquetas-config/produtos-exemplo` — **não** reaproveita a busca de
produto do Kardex (`VariacaoEncontrada`), que não tem referência/preço; DTO próprio
(`ProdutoExemploResponse`) com tudo que `campo_etiqueta` pode imprimir: `idVariacao`, `sku`,
`descricao`, `marca`, `referencia`, `precoVenda`, `variacaoCor`, `variacaoTamanho`
(`api/src/main/java/com/vetor/niner/configuracao/etiqueta/EtiquetaConfigDtos.java:146-155`).
**Não há campo `ean`** — o código de barras da etiqueta é sempre o `sku` (que já é um EAN-13
gerado por `gerar_ean13_interno()`); `EAN_BARRAS` foi removido dos campos posicionáveis (ver
acima).

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
rígida). Código de barras (SKU/EAN) renderiza de verdade via `jsbarcode` — ~~sempre `CODE128`,
nunca `EAN13`~~ **desatualizado: hoje é `format: 'EAN13'`**
(`web/src/pages/etiquetaconfig/CampoEtiquetaVisual.tsx:66`), trocado na rodada 3 de 2026-08-05
(ver seção abaixo): o `sku` sempre vem de `gerar_ean13_interno()`, então tem 13 dígitos e dígito
verificador válido por construção. Valor não desenhável (vazio/incompleto) cai no `catch` e deixa
o SVG em branco em vez de derrubar o editor. "Escolher produto de exemplo" (`ProdutoExemploModal.tsx`, mesmo padrão visual de
`PesquisaVariacaoModal.tsx` do Kardex, endpoint próprio) troca os placeholders genéricos por
descrição/marca/referência/preço/cor/tamanho/SKU reais de um produto do catálogo, ao vivo (o DTO
não devolve EAN — ver acima).

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

## Ajustes de 2026-08-05 (todos testados ao vivo no navegador)

1. **Texto que não cabe na largura do campo agora quebra linha** (`CampoEtiquetaVisual.tsx`) —
   era `whiteSpace: nowrap` + `textOverflow: ellipsis` (cortava com "..."), virou `whiteSpace:
   normal` + `wordBreak/overflowWrap: break-word`.
2. **Prévia do rolo completo passou a atualizar em tempo real enquanto o usuário digita** posição/
   largura/altura no painel de propriedades, não só ao arrastar com o mouse (que já era ao vivo) —
   `CampoMm` (`PainelPropriedadesCampo.tsx`) comprometia o valor só no `onBlur`; agora comprometa a
   cada tecla também, com uma flag `focado` pra evitar que o próprio eco do `onChange` (valor
   volta como prop, reformatado) apague o que o usuário está digitando no meio da palavra.
3. **Redimensionar campo por mouse** — alça (quadradinho) no canto inferior-direito do campo
   selecionado, arrastável (`aoIniciarRedimensionar`/`aoMoverRedimensionar` em
   `EditorEtiquetaCanvas.tsx`, mesmo raciocínio do arraste de posição já existente: tamanho
   ABSOLUTO calculado a partir do início do gesto, snap de 0,5mm, mínimo de 2mm, não deixa passar
   da borda direita/inferior da etiqueta). Antes só dava pra редimensionar digitando o número.
4. **Código de barras desproporcional entre o editor grande e a prévia pequena do rolo** — causa:
   o `viewBox` que o `jsbarcode` gera tem largura fixa (função só do texto codificado) mas altura
   variável (função da escala do desenho), então a proporção do `viewBox` mudava entre editor/
   prévia e o SVG (sem `preserveAspectRatio`) deixava espaço vazio ao redor das barras — mais sobra
   quanto menor a escala, fazendo o código parecer menor na prévia. Corrigido com
   `preserveAspectRatio="none"` no `<svg>`, esticando as barras pra preencher a caixa inteira nos
   dois lugares.
5. **Busca de "produto de exemplo" só trazia produtos com variação/SKU cadastrado** — como a tela
   de variação/SKU ainda não existe, quase nenhum produto novo tem linha em `produto_barra`, então
   a busca voltava vazia (ou só os poucos produtos de teste com SKU inserido manualmente no banco
   em sessões anteriores). `EtiquetaConfigService.buscarProdutosExemplo` trocou a base da query de
   `produto_barra` (INNER JOIN) pra `produto` (LEFT JOIN em `produto_barra`) — todo produto ativo
   aparece agora, com ou sem variação; sem SKU real, o código de barras da prévia cai no valor de
   mentirinha (mesmo comportamento de quando nenhum produto está selecionado).

## Ajustes de 2026-08-05, rodada 2 (layout compacto + Teste de Impressão)

1. **Máximo de colunas do rolo: 6 → 4** (pedido direto do dono do produto). Alterado em 3 camadas
   pra não ficar inconsistente: `CHECK (numero_colunas BETWEEN 1 AND 4)` em `V029` (migration
   editada no lugar — banco em construção), mensagem de validação em
   `EtiquetaConfigService.validar()` ("Número de colunas deve ser entre 1 e 4"), e as opções do
   `<select>` no `EtiquetaConfigForm.tsx` (`OPCOES_NUMERO_COLUNAS`, era `[1,2,3,4,5,6]`). 11 testes
   de `EtiquetaConfigCrudTest` continuam verdes (o teste de limite usa `numeroColunas: 7`, que
   segue inválido nos dois casos, não precisou mudar).

2. **Cabeçalho do formulário reorganizado em 3 colunas lado a lado**, pra reduzir a rolagem
   vertical — pedido veio com um mockup ASCII exato do layout desejado. Antes eram 4 seções
   empilhadas (Identificação / Rolo e Etiqueta / Bordas / Posição das Colunas), cada uma ocupando a
   largura toda com os campos em linha. Agora: uma linha só no topo com "Ativa" (checkbox estreito)
   + "Nome" (campo largo), e logo abaixo **3 cartões (`.etiqueta-subcard`) lado a lado** — "Rolo e
   Etiqueta (mm)", "Bordas (mm)" e "Posição das Colunas (mm)" — cada um com os próprios campos
   empilhados verticalmente dentro do cartão (não mais em grade horizontal). "Campos da Etiqueta"
   (produto de exemplo + editor visual) continua embaixo, ocupando a largura toda.
   - **Bug pego no teste ao vivo:** o cabeçalho usa `col-10` (grid de 12 colunas, §3.7) pro campo
     Nome, mas essa classe **nunca existiu** em `styles.css` — só ia até `col-9` e depois `col-12`
     direto. Sem CSS correspondente, o `div` caía no comportamento padrão do grid (`span 1`), e o
     campo Nome renderizava com ~110px de largura em vez de ocupar quase a linha toda. Corrigido
     adicionando `.col-10 { grid-column: span 10; }` ao grid de colunas em `styles.css` — confirmado
     visualmente depois (campo Nome ocupando a largura esperada).

3. **Botão "Testar Impressão"** — pedido novo: um jeito de imprimir fisicamente N cópias do layout
   configurado pra testar numa impressora real, sem depender da tela de Emissão (que precisa
   de produto/estoque reais e, ~~na época, ainda nem tinha sido especificada~~ — **hoje existe**:
   `docs/telas/etiqueta-emissao.md`; o Teste de Impressão continua útil justamente por não exigir
   produto/estoque). Fica no topo da tela
   (`topbar-acoes`, fora do `<fieldset disabled>` — funciona também no modo somente-leitura),
   habilitado só quando há rolo/etiqueta/altura preenchidos e ao menos 1 campo posicionado
   (`podeImprimir`).
   - **Fluxo:** clicar abre `TesteImpressaoModal.tsx` (novo arquivo) perguntando a quantidade
     (inteiro, 1 a 200, mesmo padrão visual de `ConfirmarSalvarModal`). Confirmando, fecha o modal
     e dispara a impressão.
   - **Distribuição das etiquetas** (`linhasParaImprimir`, `web/src/lib/etiquetaConfig.ts`): enche
     as colunas configuradas uma linha por vez, na ordem física do rolo (`numeroColuna` crescente)
     — ex.: 3 colunas + 8 etiquetas → linhas de 3/3/2. Função pura, sem estado, só recebe
     quantidade + array de colunas.
   - **Escala física exata:** reaproveita `CampoEtiquetaVisual.tsx` (mesmo componente do editor e
     da prévia do rolo — nada duplicado), mas com `escalaPxPorMm = MM_PARA_PX_IMPRESSAO = 96/25,4`
     (a equivalência padrão que o navegador usa entre CSS px e mm físicos ao imprimir), não o
     `PX_POR_MM_BASE` do editor (zoom de tela arbitrário). Isso faz 1mm configurado sair como 1mm
     impresso de verdade, sem precisar de unidades CSS `mm` nos elementos.
   - **Tamanho de página dinâmico:** a largura do rolo é configurável por tenant (diferente do
     80mm fixo do Comprovante de Crediário ou do A4 do Fechamento de Caixa, que já usam a mesma
     técnica de isolamento — `styles.css`). Por isso o `@page` não é estático: `EtiquetaConfigForm`
     injeta um `<style>` em runtime (`@page etiqueta-teste-impressao { size: ${larguraRoloMm}mm
     auto; margin: 0; }`, altura `auto` pro driver cortar no fim do conteúdo — mesmo raciocínio do
     rolo térmico contínuo) e remove esse `<style>` depois (evento `afterprint` ou cleanup do
     `useEffect`, o que vier primeiro). O isolamento visual (esconder o resto do app, mostrar só as
     etiquetas) é a mesma regra estática de sempre em `styles.css`
     (`.etiqueta-imprimir, .etiqueta-imprimir * { visibility: visible; }`), já que `body *
     {visibility:hidden}` já é global desde o Comprovante.
   - **Testado ao vivo** interceptando `window.print` (pra não travar a automação com o diálogo
     nativo do SO): distribuição 3+3+2 confirmada pra 8 etiquetas/3 colunas, largura da linha
     batendo exatamente `110mm × 96/25,4 = 415,748px`, `@page` injetado com o valor certo do rolo, e
     limpeza (remoção do `<style>` e do conteúdo) confirmada depois do `afterprint`. Visualmente
     (forçando a região a aparecer na tela, fora do `@media print`) as etiquetas saem com nome da
     empresa, descrição, preço e código de barras reais — mesma aparência do editor/prévia do rolo.

## Ajustes de 2026-08-05, rodada 3 (EAN-13 real + quadro no Teste de Impressão + popup de propriedades)

Três pedidos pontuais, cada um testado ao vivo antes do próximo.

1. **Código de barras `CODE128` → `EAN13` de verdade** (`CampoEtiquetaVisual.tsx`, `jsbarcode`).
   Pergunta neutra do usuário primeiro ("o padrão é EAN-13?"), implementação só depois de
   confirmado que era isso mesmo que ele queria — seguro trocar porque `sku` sempre vem de
   `gerar_ean13_interno()` (13 dígitos, dígito verificador correto por construção, nunca falha a
   validação do `EAN13` em produção). Achado de quebra um bug real: o valor de exemplo
   (`VALOR_BARRA_EXEMPLO`, usado no editor antes de escolher um produto real) tinha o dígito
   verificador **errado** desde que foi escrito — nunca importava com `CODE128` (que não valida
   nada), mas com `EAN13` (que valida e recusa desenhar se não bater) ficaria em branco. Corrigido
   de `'9000000000017'` para `'9000000000018'`.
2. **Quadro (borda 1px preta) em volta de cada etiqueta no Teste de Impressão** — guia de corte
   pra testar em papel comum antes do rolo de etiqueta de verdade. `box-sizing: border-box`
   (global) garante que a borda fica **por dentro** do tamanho configurado, não estoura a etiqueta
   vizinha.
3. **Painel de propriedades do campo virou popup** (`PainelPropriedadesCampo.tsx`) — antes ficava
   fixo ao lado do canvas, disputando espaço horizontal e forçando scroll em telas menores. Agora
   abre em `.modal-overlay`/`.modal` (mesmo padrão do resto do sistema) ao selecionar um campo;
   `EditorEtiquetaCanvas.tsx` deixou de renderizar o painel inline. Sincronização ao vivo com o
   canvas/prévia do rolo continua funcionando com o popup aberto (testado: mover um slider no
   popup atualiza a posição do campo no canvas atrás, em tempo real).

## 🔴 2026-08-20 — o que a etiqueta impressa revelou (3 defeitos e a reforma da tela)

Diagnóstico feito com material real na mão: as duas telas de configuração, o PDF gerado e a **foto
de uma folha impressa**. O que a foto mostrava: fileira 1 quase certa, fileira 2 sem o cabeçalho,
fileira 4 inteiramente fora do adesivo — e, em todas, a descrição do produto em três linhas
invadindo o preço.

### Defeito 1 — a tela mentia sobre o tamanho do texto (⚠️ o central)

`CampoEtiquetaVisual` dimensiona tudo em `mm × escalaPxPorMm`, mas o `font-size` saía em **`pt`**,
que é unidade **absoluta de tela** — 7pt são sempre ~9,3px, não importa quanto vale 1mm no
contexto:

| Contexto | 1mm vale | 7pt aparentam ser | erro |
|---|---|---|---|
| Editor a 200% | 12px | 0,78mm | texto **3,2× menor** que o real |
| Impressão | 3,78px | 2,47mm | **correto** |
| Prévia do rolo | 3px | 3,11mm | 26% maior |

Por isso a descrição cabia numa linha no editor e quebrava em três no papel. **A impressão sempre
esteve certa; a tela é que mentia** — e a prova é que a correção (pt → mm → px pela mesma escala)
produz no papel exatamente o mesmo resultado de antes: `pt × 25,4/72 × 96/25,4 = pt × 96/72`, que é
o que `Npt` já valia a 96dpi. O mesmo tratamento foi dado à altura do texto legível do código de
barras, que também era pixel fixo.

### Defeito 2 — não existia espaçamento vertical entre fileiras

A impressão empilhava as fileiras usando `alturaEtiquetaMm` como passo. Rolo com gap entre as
fileiras → o conteúdo sobe a cada fileira. **O erro se acumula**, e é por isso que ninguém o pega
antes: a primeira etiqueta sai perfeita. Coluna nova `espacamento_vertical_mm` (V056), passo =
`altura + espaçamento`, aplicada na Emissão **e** no Testar Impressão.

### Defeito 3 — o texto cortado sumia em silêncio

`overflow: hidden` esconde o excesso na tela; ele reaparece bagunçado no papel. Agora
`CampoEtiquetaVisual` **mede** (`scrollHeight` × `clientHeight`) e a tela avisa por extenso qual
campo não cabe.

---

## A reforma da tela: uma só forma de medir o rolo

A primeira versão do conserto deixou uma incoerência que o dono do produto pegou de imediato:
*"o espaço horizontal e o vertical eu tenho que informar, ou só informo a posição onde cada
etiqueta começa?"*. E ele tinha razão — o vertical pedia **espaço** e o horizontal pedia
**posição**, duas formas de pensar a mesma medida física.

**Hoje é uma só: informa-se sempre o espaço em branco, nas duas direções.** O card
"Espaçamento entre Etiquetas (mm)" reúne as três medidas que se tiram com a régua:

| Campo | O que medir no rolo |
|---|---|
| Margem até a 1ª coluna | borda do rolo → começo da 1ª etiqueta |
| ↔ Espaço entre colunas | fim de uma etiqueta → começo da vizinha |
| ↕ Espaço entre fileiras | fim de uma etiqueta → começo da de baixo |

A posição de cada coluna passou a ser **calculada** e mostrada como conferência
("Cada coluna começa em: 3,00 · 43,00 · 83,00"). Continua sendo o que vai para o banco —
`cfg_etiqueta_coluna.posicao_inicial_mm` é a fonte de verdade da impressão; o que mudou é quem a
preenche.

### ⚠️ "Rolo irregular — digitar cada posição" não é recurso avançado

É o que impede a tela de **estragar** um modelo que já funcionava. Ao abrir um modelo salvo, a tela
deduz margem e espaço das posições gravadas e **cai sozinha no modo manual** quando elas não seguem
passo constante. Sem isso, abrir um rolo irregular recalcularia as posições dele em silêncio.

⚠️ **Armadilha de ordem de efeitos, resolvida com uma trava explícita:** o recálculo automático e a
dedução rodam no mesmo commit do React, e o recálculo é declarado depois — ele leria margem/espaço
ainda vazios e `colunasManuais` ainda `false`. Em rolo regular isso se auto-corrige no render
seguinte; em rolo **irregular**, não — as posições já teriam sido sobrescritas antes de o modo
manual ligar. O estado `medidasDeduzidas` bloqueia qualquer recálculo até a dedução terminar.

### Avisos que a tela não dava

- **"O conteúdo não cabe em ..."** — o mais importante; é o defeito 3 exposto.
- **Coluna que passa da largura do rolo** — o excedente é cortado sem avisar. (No modelo que
  motivou o diagnóstico, a coluna 3 terminava em 113mm num rolo de 110mm.)
- **Colunas que se sobrepõem.**
- **Prévia do rolo com duas fileiras** — sem a segunda, o espaço entre fileiras não tinha como ser
  conferido antes de gastar rolo.

### Como acertar um modelo existente

Se a impressão está deslizando, o valor a medir é o **espaço entre fileiras**. Se o texto sai
cortado nas colunas da direita, o **espaço entre colunas** está menor que o real — informe o
medido e as posições se recalculam.

---

## ✅ 2026-08-20, rodada 2 — a geometria virou DERIVADA (V057) e o modelo encolheu

Proposta do dono do produto, na mesma sessão, depois de ler a rodada 1:

> *"Você já sabe a largura do rolo, o número de colunas, a largura e a altura da etiqueta. O que
> preciso a mais é margem até a primeira coluna, espaçamento entre colunas e espaçamento entre
> fileiras. Com isso você sabe a área de impressão e a posição x,y de cada etiqueta. Aí fica fácil
> gerar a impressão. O que você acha disso?"*

Está certo, e expõe um defeito de fundo do modelo original: ele **guardava dado redundante**.
`cfg_etiqueta_coluna.posicao_inicial_mm` sempre foi calculável — e, por ser digitado à mão, era
exatamente ali que o erro entrava. Com a posição derivada, **o erro deixa de ser representável**.

### O modelo inteiro, em 7 números

| | |
|---|---|
| largura do rolo · nº de colunas · largura da etiqueta · altura da etiqueta | já existiam |
| margem até a 1ª coluna · espaço entre colunas · espaço entre fileiras | as três medidas de régua |

```
x da coluna i (base 0) = margem + i × (larguraEtiqueta + espacoHorizontal)
y da fileira f         =            f × (alturaEtiqueta + espacoVertical)
área de impressão      = larguraEtiqueta × alturaEtiqueta
```

### O que saiu

- **Tabela `cfg_etiqueta_coluna`** — apagada (com backfill antes, ver abaixo). Perde-se representar
  **rolo de espaçamento irregular**; não é perda real, matriz de corte é padrão repetido. Junto foi
  embora toda a complexidade que a rodada 1 tinha criado para conviver com isso: modo manual,
  dedução de margem/espaço na abertura e a trava de ordem de efeitos que ela exigia.
- **As 4 bordas** (`borda_superior/inferior/esquerda/direita_mm`) — decisão explícita do dono do
  produto ("vamos esquecer as bordas"). Elas **nunca afetaram a impressão**: eram só a área
  tracejada de aviso no editor. O aviso útil continua, agora contra a borda **real** do adesivo.
- Duas validações de coerência da lista de colunas (quantidade divergente, número repetido) — sem
  lista, não há como estar incoerente. No lugar entrou a validação que **importa fisicamente**:
  *as colunas cabem no rolo?*, agora conferida **também no servidor**, o que antes era impossível
  (com posições digitadas, o backend não tinha como saber onde cada coluna começava sem confiar no
  que recebeu).

### 🔴 Uma armadilha real na própria migration, pega só porque o resultado foi conferido

A V057 nasceu com um backfill que saiu **zerado em silêncio**. Migration roda como `niner_owner`,
e com `FORCE ROW LEVEL SECURITY` (V024) **nem o dono da tabela escapa da política**: sem
`app.id_tenant` no contexto, o `SELECT` em `cfg_etiqueta_coluna` devolveu 0 linhas e o `UPDATE` em
`cfg_etiqueta_config` casou 0 linhas — as colunas novas ficaram no `DEFAULT 0` e a migration
terminou anunciando **sucesso**.

Medido no banco de dev: a config existente tinha colunas em 2,50 / 40,50 / 78,50 (margem 2,50,
espaço 4,00) e virou margem 0,00, espaço 0,00.

Conserto: `NO FORCE ROW LEVEL SECURITY` nas duas tabelas antes do backfill, `FORCE` de volta
depois. `NO FORCE` e não `DISABLE` — libera só o **dono**, mantendo a política valendo para
`niner_app`. Provado em isolamento: como `niner_owner`, `count(*)` na mesma tabela devolve **0 com
FORCE e 1 com NO FORCE**.

⚠️ É o mesmo defeito que já tinha mordido o backup do produto (`pg_dump` sem `BYPASSRLS` levando
estrutura completa e zero linha de cliente). **Migration que LÊ dado de tenant para transformá-lo
precisa tratar RLS explicitamente** — e o único jeito de perceber é conferir o resultado no banco,
porque o Flyway não tem como saber que zero linha era o número errado.

### A tela final

O card "Espaçamento entre Etiquetas (mm)" tem os três campos de régua e mostra o resultado como
conferência:

> Cada etiqueta começa em: **2,50 · 40,50 · 78,50** mm · ocupam **112,50** dos **110,00** mm do rolo.

…e, nesse exemplo, o aviso de que **não cabem** — que é exatamente o defeito da configuração que
motivou o diagnóstico inteiro.
## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `configuracoes.etiquetaconfig.lista`** — várias configurações nomeadas por
  tenant (impressoras/rolos diferentes), "Nova configuração"/"Editar"/"Visualizar" (só leitura).
  `url_video`: `NULL` por ora.
  - ⚠️ O erro comum *"a impressão de verdade fica pra 'Emissão de Etiqueta de Produtos' (ainda em
    Implementações Futuras)"* ficou **desatualizado** — a Emissão existe desde 2026-08-05
    (`docs/telas/etiqueta-emissao.md`); o texto no `AjudaDaTela.tsx` ainda não foi corrigido.
- **`chave_tela`: `configuracoes.etiquetaconfig.form`** — cabeçalho em mm (rolo, colunas 1 a 4,
  etiqueta, bordas) + posição de cada coluna (livre, não calculada), e o editor visual: clicar na
  paleta pra colocar o campo, arrastar pra posicionar, alça pra redimensionar, setas do teclado pro
  ajuste fino (Shift = 5mm), popup de propriedades ao clicar num campo, "Mostrar os dígitos
  embaixo" no código de barras, produto de exemplo, régua/zoom, prévia do rolo completo e "Testar
  Impressão". Erros comuns: contorno vermelho = campo invadindo a borda (avisa, não bloqueia),
  Delete/Backspace devolve o campo pra paleta, "Testar Impressão" desabilitado sem rolo/etiqueta
  ou sem nenhum campo posicionado. Texto em `web/src/components/AjudaDaTela.tsx`. `url_video`:
  `NULL` por ora.

## Pendências explícitas, fora do escopo desta migration

- **Estilo "contorno" (borda preta, sem preenchimento)** — reparado no PDF de referência (a caixa
  do tamanho "M"), mas não pedido; `fundo_preto` cobre só o binário preto-cheio/sem-fundo pedido.
  Se vier a ser pedido, é um campo novo (`estilo_fundo` com 3 valores em vez de boolean).
- **Sobreposição visual de dois campos** (x/y colidindo) — não validada pelo banco.

---

## 🔴 2026-08-21 — a impressão real numa Argox OS-2140: a escala, e três defeitos de impressão

Sessão inteira de diagnóstico com o rolo e a impressora do dono do produto na mão. O sintoma
inicial: *"está imprimindo muito à direita, e o deslocamento aumenta na 2ª e na 3ª coluna; na
vertical também está saindo muito para baixo"*.

**Estado ao fim do dia: resolvido na horizontal, ⏭️ PENDENTE na vertical.** Ver "Onde parou".

### O que tornou o diagnóstico possível: o quadro impresso é a régua

`EtiquetaConfigForm.tsx` desenha `border: '1px solid #000'` em cada etiqueta — **só no Teste de
Impressão**, como guia de corte. Isso faz da folha impressa um instrumento de medida: o quadro
preto é *onde mandamos imprimir* e o adesivo é *onde a etiqueta está*. Fotografando os dois
juntos dá para medir a diferença por pixel e — o que mais importa — medir **razões** entre
impresso e físico no mesmo plano, que são imunes a perspectiva e não precisam de escala.

⚠️ **A deriva era para a ESQUERDA, não para a direita.** O passo impresso era *menor* que o passo
do rolo, então o conteúdo caía no vão à esquerda de cada etiqueta (foi o que cortou o "B" de
"BOTA" na coluna 2, e mais ainda na 3). Quem relata o defeito descreve o movimento relativo que
enxerga; confiar na direção relatada teria invertido a correção inteira.

### A causa raiz: o campo pedia a largura errada

A Argox OS-2140 **aceita rolo de 110 mm mas o cabeçote só imprime 104**. O campo se chamava
"Largura do Rolo", então foi preenchido com 110 — e o driver encolheu a página inteira para caber
no que alcança. Medido pela régua de calibragem: **93 mm onde deveriam sair 100** (≈ 0,93; e
110 × 0,93 = 102,3 mm, que é a área imprimível real).

Encolher a página encolhe *tudo*, inclusive o **passo entre colunas** — e o adesivo não encolhe
junto. Daí o erro que cresce a cada coluna. `Escala: 100%` no Chrome **não resolve**: quem escala
é o driver, encaixando a página na área física.

**Correção:** o campo passou a se chamar **"Largura de Impressão"**, com ajuda dizendo que é a
largura que a *impressora alcança*, não a do papel, e que declarar mais faz o driver encolher
tudo. A coluna do banco continua `largura_rolo_mm` (migration aplicada é imutável) — só o rótulo
conta a verdade. Trocando 110 → 102, **o alinhamento horizontal ficou correto**.

### A régua de calibragem (`ReguaCalibragem.tsx`) e os DOIS defeitos que ela sofreu

Duas réguas de 100 mm (deitada e em pé) impressas antes das etiquetas, ligadas por uma caixa no
popup do Teste de Impressão. Existem porque, olhando só a etiqueta, é **impossível** separar
"medida do cadastro errada" de "impressora escalando" — os dois produzem o mesmo estrago, e o
conserto de um não conserta o outro. Régua longa porque erro percentual só é legível em distância
longa: 3% em 33 mm é 1 mm (dentro do erro de leitura), em 100 mm é 3 mm.

Duas versões morreram na impressora, cada uma por um motivo, e as duas lições são gerais:

1. **`background: #000` NÃO IMPRIME.** Saiu do papel uma régua só com os números. O navegador
   suprime cor de fundo na impressão por padrão (caixa "Gráficos de segundo plano", desmarcada de
   fábrica). O diagnóstico veio do que *saiu*: números (texto, `color`) ✅, código de barras
   (SVG `<rect>` do JsBarcode) ✅, quadro da etiqueta (`border`) ✅, traços da régua
   (`background`) ❌.
2. **`border` imprime, mas a espessura é arredondada para pixel inteiro.** Medido no navegador:
   0,5 mm pedido (1,89 px) virou **1 px = 0,26 mm**. Térmica é 1 bit — haste fina sai falhada.
3. **Solução: SVG.** Conteúdo (não fundo) e vetor (rasterizado na resolução de saída, sem
   arredondar em pixel de tela). O código de barras é a prova viva de que funciona nesta
   impressora. Espessura conferida no DOM: **0,50 mm exatos**.

⚠️ **Defeito irmão, latente, corrigido junto:** o campo com **`fundo_preto`** em
`CampoEtiquetaVisual` usa `background: #000` com texto branco — na tela fica perfeito, no papel
sairia **branco no branco, campo invisível**. Ninguém tinha usado a opção ainda. Resolvido com
`print-color-adjust: exact` (+ `-webkit-`).

### Mais duas correções de geometria, da mesma sessão

- **Página de altura exata, não `auto`.** `@page` passou de `size: L mm auto` para
  `size: L mm H mm`, com `H` = calibragem + fileiras × passo. Com `auto`, quem decide onde a folha
  acaba é o driver, e a fileira atravessada pela quebra sai partida no meio do adesivo sem aviso.
  Rolo contínuo é uma folha só. Vale nas **duas** telas (Configuração e Emissão).
- **Fileira POSICIONADA, não empilhada** (`yDaFileira`, `alturaFolhaMm` em `etiquetaConfig.ts`).
  Empilhando `<div>`s de altura fracionária (33,5 mm = 126,614 px), o arredondamento de cada
  fileira **se soma**. Derivada do índice, como o x das colunas na V057. Conferido no DOM:
  fileiras em 132,00 / 165,50 / 199,00 / 232,50 mm — passo exato, sem acúmulo.

### ⚠️ O limite físico que nenhum número resolve: 3 colunas não cabem nesta impressora

> ⛔ **ESTA SEÇÃO ESTÁ SUPERADA (2026-08-21, tarde).** As 3 colunas **cabem** e estão imprimindo
> alinhadas. O erro abaixo é de premissa: os "~102 mm de alcance" saíram da régua de calibragem,
> que na verdade estava medindo a página **encolhida pelo driver** — o papel configurado nele
> (101,6 mm) era menor que a página declarada (110 mm), e o Chrome encolhe para caber. Corrigido o
> papel do driver para 110 mm, o cabeçote alcança o rolo inteiro. Ver *"✅ 2026-08-21 (tarde)"* no
> fim deste arquivo. O raciocínio fica registrado porque o **método** (medir razões no papel) foi
> correto; o que faltou foi desconfiar do instrumento.


Medidas do rolo (régua do dono do produto): etiqueta **34,0 × 29,5 mm**, rolo **110 mm**. A
aritmética fecha exatamente: `2 + 34 + 2 + 34 + 2 + 34 + 2 = 110`.

```
etiquetas no rolo:  2 ────── 36 ─ 38 ────── 72 ─ 74 ────── 108 mm
cabeçote alcança:   0 ──────────────────────────────── 102 mm  ✂
```

A 3ª etiqueta termina em **108 mm** e a impressora chega a **~102**: sobram 28 mm úteis dos 34.
O código de barras tem 30 mm e teria de encolher para ~27 — **abaixo do mínimo de 80% do padrão
EAN-13**, com risco de não ler no caixa. Nenhuma combinação de margem/largura/espaçamento
contorna isso; os 108 mm são o rolo, não uma escolha.

**Decisão do dono do produto (2026-08-21): ficar com 2 COLUNAS neste rolo.** As outras opções,
registradas caso o assunto volte: 3 colunas com código de barras menor (risco de leitura), ou
trocar por rolo cujas 3 colunas caibam em 102 mm (etiquetas de até 32 mm).

⚠️ **A trava "as 3 colunas não cabem no rolo" é esse mesmo limite falando mais cedo** — ela
impediu salvar 3 colunas com 102 mm de largura de impressão. A trava está certa; o que faltava
era entender que a mensagem descrevia um fato físico, não um erro de digitação.

### 💡 O modelo tem um ponto cego que esta impressora expôs

"Largura da Etiqueta" significa **duas coisas ao mesmo tempo**: o tamanho do adesivo físico *e* a
caixa onde o conteúdo pode ser desenhado. Enquanto a impressora alcança o rolo inteiro, são a
mesma coisa. Nesta Argox **não são** — o adesivo tem 34 mm mas, na 3ª coluna, só 28 mm ficam ao
alcance do cabeçote. Se a opção "3 colunas com código de barras menor" for pedida, é aqui que o
modelo precisa mudar (dois campos, não um).

### ⏭️ Onde parou (retomar por aqui)

**Configuração `id_config_etiqueta = 1` (tenant 1) no ambiente de dev**, como está salva agora:

| campo | valor salvo | medido no rolo |
|---|---|---|
| Largura de Impressão | **102,00** ✅ | (área imprimível da Argox) |
| Número de Colunas | **2** ✅ | (decisão do dono do produto) |
| Largura da Etiqueta | 33,00 | 34,00 |
| Altura da Etiqueta | 30,00 | **29,50** |
| Margem até a 1ª coluna | 2,50 | 2,00 |
| Espaço entre colunas | 3,50 | 2,00 |
| Espaço entre fileiras | 3,50 | **2,20** |
| *(passo horizontal)* | *36,50* | *36,00* |
| *(passo vertical)* | *33,50* | *31,70* |

Só os dois primeiros campos foram alterados — e **isso é informação**: o alinhamento horizontal
se resolveu *com os espaçamentos antigos*, o que mostra que o problema era, em altíssimo grau, só
a escala.

**Resultado do teste de 40 etiquetas (20 fileiras, Escala 100%, Margens Nenhuma):**

- Legenda da régua deitada: **90 mm** (ela se ajusta ao espaço: com rolo 102 e margem 2,5 não
  cabem 100 — ver `comprimentoReguaHorizontalMm`).
- Régua em pé: **101 mm** para 100 pedidos → **vertical 1:1, escala resolvida**.
- Última fileira: **horizontal dentro do quadro ✅, vertical desalinhada ❌**, nas duas colunas.

**Próximo passo, já decidido:** o desalinhamento vertical acumulado confirma que o passo vertical
está errado. Aplicar **Altura da Etiqueta = 29,50** e **Espaço entre fileiras = 2,20** (passo
31,70 contra os 33,50 de hoje) e reimprimir as 40, conferindo se a última fileira cai no adesivo.
Não foram aplicados ainda de propósito: vieram de medição em foto, e trocar número que está
funcionando por estimativa não verificada é como se perde um resultado bom.

**Pendência menor:** o dono do produto mediu **95 mm** numa régua que a legenda declara com
**90 mm** (+5,6%). Isso brigaria com "horizontal dentro do quadro" — com 5,6% de sobra, a coluna
2 estaria ~3 mm fora. Provável erro de leitura (a régua tinha 90 e ele esperava 100), mas **medir
de novo, do traço do 0 ao traço do 90**, antes de dar a horizontal por encerrada.

**Pendência de cadastro:** o modelo ainda se chama "CCALCADOS 3 COLUNAS" e tem 2 colunas.
Renomear para "CCALCADOS 2 COLUNAS".

---

## ✅ 2026-08-21 (tarde) — as 3 colunas voltam: quem mandava era o papel do driver

Fecha o que a seção anterior deixou pela metade, e **corrige a conclusão dela**. Estado final:
3 colunas alinhadas, imprimindo na Argox OS-2140.

### O modelo de impressão mudou: uma página por FILEIRA

A manhã montava todas as fileiras numa página só (`@page` de 102 × 634 mm para 40 etiquetas).
Impressora de etiqueta **não imprime folha**: com mídia *die-cut* e papel de 101,6 × 152,4 mm no
driver, ela fatiava o trabalho em pedaços de 152,4 mm — que não são múltiplos do passo, então cada
corte caía no meio de um adesivo. Sintoma relatado: *"pula 5 fileiras e aí sai a impressão"*
(152,4 ÷ 31,7 = 4,8 fileiras) e a primeira etiqueta 3 mm para baixo.

⚠️ **Distinção que dirigiu o diagnóstico: erro já na PRIMEIRA fileira não é erro de passo.** Passo
só se manifesta acumulado. Enquanto o defeito crescia a cada fileira, a geometria era suspeita;
quando passou a aparecer logo na primeira, a origem vertical (driver) virou a única explicação.

Hoje: `@page` = largura × `passoVertical`, uma fileira por página, `break-after: page` entre elas.
Quem encaixa cada página no adesivo é o **sensor de gap** da impressora. `yDaFileira` e
`alturaFolhaMm` foram removidos de `etiquetaConfig.ts` (o comentário no lugar delas conta por quê).
Erro de passo deixa de existir por construção: não tem onde acumular.

⚠️ Altura do bloco da fileira = altura da **etiqueta**, não do passo, embora a página valha o
passo. Um bloco tão alto quanto a página é o caso limite da paginação: um arredondamento de
sub-pixel o empurra para a página seguinte e nasce uma página em branco entre cada etiqueta. O
branco do gap quem dá é o `@page`.

### Duas regras de CSS que escondem trabalho em silêncio

1. **`visibility: hidden` esconde o pixel mas mantém o espaço.** O isolamento global do projeto
   (`body * { visibility: hidden }`) funciona para documento de uma página porque o bloco é
   `position: absolute`. Para paginar é preciso fluxo normal — e aí o espaço dos elementos
   invisíveis empurra a primeira etiqueta. Solução: o bloco vai por **portal** para filho direto
   do `<body>` e o resto some com `display: none`.
2. **`overflow: hidden` corta em vez de paginar.** `html, body, #root { height: 100%;
   overflow: hidden }` (styles.css) é o shell de altura travada do ERP. Na impressão, saía **uma**
   página com a primeira fileira e as outras 19 desapareciam sem aviso. Precisa de `height: auto`
   + `overflow: visible` no `<html>` **e** no `<body>` — destravar só um não resolve.

Ambas presas a `.imprimindo-etiquetas`, classe posta em `documentElement` e `body` durante a
impressão. Sem essa âncora, as regras apagariam o `#root` de toda impressão do produto. A classe
antiga `.etiqueta-imprimir` continua existindo intacta — o Orçamento em bobina ainda a usa.

### ⛔ A causa raiz de verdade: papel do driver menor que a página declarada

O dono do produto contestou a conclusão de "3 colunas não cabem" com a evidência mais forte
possível: *um sistema legado em Delphi imprime as 3 colunas nesta mesma impressora*. Mandou o
template (`.rtm`, DFM binário do ReportBuilder) e um PDF. O que eles dizem:

| propriedade do template legado | valor |
|---|---|
| `PrinterSetup.mmPaperWidth` | **110 mm** (o papel inteiro) |
| `PrinterSetup.mmPaperHeight` | **30 mm** → **uma página por fileira**, o mesmo desenho |
| `Columns` / `ColumnPositions` | 3, em **3,5 / 39 / 75 mm** |
| `mmColumnWidth` | 34 mm |
| `ppCodBr1` (EAN-13) | caixa 34 mm, `taRightJustify`, `mmBarWidth` **0,31 mm** (94% do padrão) |

No PDF, as barras vão de **9,78 mm a 109,43 mm** e os três grupos de texto distam 35,52 e 35,99 mm
(batendo com os `ColumnPositions`). **O legado pinta até ~109 mm e a impressora imprime.**

Portanto o alcance do cabeçote nunca foi o limite. O que havia era: **o papel configurado no
driver era menor que a página declarada, e o Chrome encolhe a página para caber nele**. A régua de
calibragem da manhã mediu esse encolhimento, e o número foi lido como se fosse o cabeçote — um
instrumento correto respondendo outra pergunta.

> ⚠️ **Regra geral, vale para toda impressão do produto:** o papel configurado no driver tem de ser
> **igual ou maior** que a página declarada no `@page`. Menor, o navegador encolhe tudo — e
> encolher a página encolhe o passo entre colunas enquanto o adesivo não encolhe, produzindo um
> erro que **cresce a cada coluna** e imita perfeitamente medida errada no cadastro.

### Geometria final (validada na impressora, 3 colunas)

| campo | valor |
|---|---|
| Largura de Impressão | **110,00** |
| Número de Colunas | **3** |
| Largura da Etiqueta | 34,00 |
| Margem até a 1ª coluna | 3,00 |
| Espaço entre colunas | 2,50 |
| Espaço entre fileiras | 2,20 |
| *(colunas nascem em)* | *3 / 39 / 75* |

As duas últimas colunas caem exatamente onde o legado as põe. A primeira fica 0,5 mm à esquerda
porque nosso modelo deriva as colunas de um passo **uniforme** e o template legado tem 35,5 e 36 —
provável digitação, não intenção; o rolo é regular.

No driver: papel **110 mm de largura**, mídia *etiquetas cortadas com molde*.

### Quantidade do teste travada em múltiplo do número de colunas

Pedido do dono do produto. Numa fileira incompleta o rolo avança igual: 40 etiquetas em 3 colunas
terminam com uma fileira de uma etiqueta só, e **os outros 2 adesivos passam pelo cabeçote em
branco e vão para o lixo**. `multiploDeColunas` arredonda **para cima** (quem pede 40 quer pelo
menos 40), no `onBlur` — para o usuário ver o número antes de confirmar, mesma convenção dos
campos decimais — e de novo no envio, porque o `onBlur` não dispara quando se digita e tecla Enter
direto.

### A régua saiu de junto das etiquetas

Com página do tamanho do passo, os 132 mm dela não cabem. E como 132 nunca foi múltiplo do passo,
ela deslocava todas as etiquetas — **escondendo justamente o defeito que se queria medir**. Virou
impressão exclusiva ("no lugar das etiquetas"), desmarcada por padrão. Calibrar escala e conferir
alinhamento são duas impressões.

### O Teste de Impressão grava antes de imprimir — o papel é sempre o do banco

Decisão do dono do produto, no fim do dia: *"ajuste para sempre seguir as medidas que estão no
banco"*. Até então o teste imprimia direto do **formulário**, o que permitia calibrar sem salvar —
conveniente, mas admite a pior divergência possível numa tela de calibragem: **o papel saindo com
uma medida e o cadastro guardando outra**. Como o papel é a única evidência de que a etiqueta está
certa, um teste que não corresponde ao que ficou gravado não prova nada.

Agora "Testar Impressão" **grava primeiro** (`salvarEImprimir`) e imprime **o retorno do
servidor** (`configImpressao`), não o form. Detalhes que a implementação precisou cobrir:

- **Não navega de volta para a lista** (diferente do botão Salvar): quem calibra continua na tela
  para o próximo ajuste.
- **A tela é reposta com o retorno** (`setForm(paraFormulario(config))`) — o servidor normaliza
  coisas como o nome em maiúsculas, e a tela tem de mostrar o que ficou gravado.
- ⚠️ **`idCriadoNoTeste`**: se a configuração ainda não existia, o teste faz INSERT — e sem
  guardar o id gerado, o "Salvar" seguinte criaria uma **segunda linha** em vez de atualizar.
  Duplicata silenciosa.
- **Gravação recusada não imprime nada**: `quantidadeImprimir` só é ligado no `onSuccess`.
- **Modo visualização** (`somenteLeitura`) não grava — imprime o `configExistente`, que já é o
  banco.
- **Toast explícito** ("Configuração salva…"): gravação é efeito, não intenção, e efeito silencioso
  vira surpresa na próxima abertura da tela.

Corrigido junto um bug de cache clássico deste projeto: salvar a configuração invalidava só
`['etiquetas-config']` e **não** `['etiquetas-config-emissao']`, então a Emissão listava os modelos
pelo cache antigo. (A geometria que ela imprime nunca esteve errada — o modelo escolhido é buscado
fresco do servidor —, mas nome e situação podiam estar velhos.)



### ⛔ `crispEdges` foi revertido — ele engrossou as barras a ponto de não ler

Introduzido no mesmo dia para resolver o "borrado", e **piorou**: a etiqueta impressa saiu com
barras gordas e espaços comidos. A foto do dono do produto encerrou a discussão.

**Por quê:** `shape-rendering: crispEdges` arredonda **cada borda** para a grade de pixels, de forma
independente. Com o módulo caindo em fração de pixel — que era o caso, porque o SVG era **esticado**
até caber na caixa — uma barra de 1 módulo vira 2 px enquanto o espaço vizinho some. A razão
barra/espaço, que é exatamente o que o leitor mede, deixa de existir.

**A causa real do "borrado" original não era o antialiasing: era o esticamento.** O jsbarcode
desenhava com `width: 2` fixo e o SVG era escalado por um fator arbitrário até preencher a caixa.
Agora o **módulo é derivado da caixa** (`larguraPx / 95`, os 95 módulos do EAN-13 sem zonas de
silêncio): o SVG nasce exatamente do tamanho do viewport e nada é escalado.

O antialiasing volta a valer. Ele parece "sujo" quando ampliado na tela, mas preserva as
proporções, e quem decide o ponto final é o rasterizador da impressora, a 203 dpi.

⚠️ **O que ainda não é possível:** com a caixa de 32 mm, o módulo fica em 0,337 mm — 102% do nominal
do EAN-13, ótimo tamanho, mas **2,7 dots** a 203 dpi. A impressora vai alternar entre 2 e 3 dots por
módulo, e não há largura de caixa que resolva isso nesta etiqueta (3 dots exigiriam 35,6 mm, que não
cabem; 2 dots dariam 23,75 mm = 76% do nominal, abaixo do mínimo de 80% da norma). O sistema legado
convive com o mesmo compromisso (`mmBarWidth` 0,31 mm = 2,48 dots) e lê.

**Se ainda sair grosso, o próximo suspeito é o DRIVER, não o código:** densidade (*darkness*) alta
demais é a causa clássica de barra engordada em térmica direta — o calor espalha o ponto. Baixar a
densidade e/ou reduzir a velocidade de impressão costuma resolver. Só depois disso vale mexer em
*bar width reduction* (desenhar a barra ~1 dot mais fina para compensar o espalhamento).

### ⛔ E o que impedia a leitura era a ZONA DE SILÊNCIO ausente

Barras finas e proporcionais, e mesmo assim *"ainda não tá lendo"*. A causa não estava nas barras:
o jsbarcode era chamado com **`margin: 0`**, o que remove as **zonas de silêncio** — o branco
obrigatório antes e depois do símbolo (**11 módulos à esquerda, 7 à direita**, GS1 General
Specifications).

Elas não são margem estética: **é por elas que o leitor sabe onde o símbolo começa e termina**. Sem
elas o código pode estar impresso com precisão perfeita e o leitor simplesmente **recusa**. O
`margin: 0` estava ali para o desenho "não desperdiçar espaço" da caixa — sem perceber que aquele
espaço tem função.

**Correção:** o módulo passou a ser derivado de **113 módulos** (95 do símbolo + 11 + 7), não de 95,
e as margens são declaradas em `marginLeft`/`marginRight`. A caixa continua sendo preenchida por
inteiro; o que muda é que parte dela é branco obrigatório.

| | antes | agora |
|---|---|---|
| módulo | 0,337 mm (102% do nominal) | **0,283 mm (86%)** |
| zona de silêncio | **0 mm** ❌ | **3,1 mm** esq · **2,0 mm** dir ✅ |
| símbolo | 32,0 mm | 26,9 mm |

O símbolo encolheu e continua **dentro da norma** (mínimo 80%) — e agora tem o que faltava para ser
reconhecido. Fundo branco explícito no SVG, porque zona de silêncio precisa ser branca e o papel
sozinho não garante isso se o campo cair sobre outro.

### ⚠️ Se ainda não ler, nesta ordem

1. **Altura das barras: 9,0 mm** (campo de 12 mm menos os 3 mm dos dígitos). O proporcional ao
   módulo seria **19,6 mm**. A norma permite truncar, mas leitor de mão em ângulo sofre com barra
   curta — é o candidato mais provável depois da zona de silêncio. Dá para ganhar altura movendo o
   preço para cima e usando a sobra de 1,7 mm no pé da etiqueta.
2. **Densidade (*darkness*) do driver.** Calor demais espalha o ponto e engorda a barra; é a causa
   clássica em térmica direta, e está no driver, não no código.
3. **O bloco preto do preço encostado no topo das barras** (termina em y=18, as barras começam em
   y=18). Não viola a zona de silêncio, que é lateral, mas qualquer deslocamento de impressão o faz
   invadir o símbolo. 1–2 mm de folga ali é barato.
### Dívida que continua aberta

"Largura da Etiqueta" segue significando duas coisas — o adesivo **e** a caixa de conteúdo. Com o
papel do driver correto elas voltaram a coincidir e a dívida parou de doer, mas um rolo mais largo
que o cabeçote reabre o assunto na hora. Quando reabrir, são dois campos, não um.

---

## ✅ 2026-08-21 (fim do dia) — sete ajustes da tela, depois de ela finalmente imprimir

Com a etiqueta saindo certa, o dono do produto usou a tela de verdade e listou o que atrapalhava.
Sete itens, todos da Configuração de Etiqueta (dois deles vazando para a Emissão).

### 1. O campo "Nome da Empresa" imprimia um literal

⚠️ **Era o defeito mais grave do lote, e não estava na prévia: estava no papel.** O front passava
`nomeEmpresaExemplo="NOME DA LOJA"` em **quatro** lugares — incluindo `EtiquetaEmissaoForm`, a
impressão de verdade. Ou seja, **toda etiqueta já emitida saiu com o texto "NOME DA LOJA"**.

Agora vem de `GET /api/v1/eu` (`empresa.nomeEtiqueta`), da empresa da **sessão** — a empresa
escolhida no popup da Emissão é filtro de estoque, não emitente da etiqueta.

⚠️ **E não sai de `empresa.cfg_nome_etiqueta`, apesar do nome da coluna.** Aquilo é herança do ERP
legado, onde a etiqueta era um **modelo de texto com marcadores**, e `SignupService` ainda semeia
assim: no banco de dev o valor é literalmente `{sku}\n{descricao}\n{preco_venda}`. Nosso editor
posiciona campos, não interpreta marcador — imprimir aquela coluna colocaria `{sku}` no adesivo. O
nome impresso é o **nome fantasia** (ou a razão social). `nomeEtiqueta` fica como campo próprio no
contrato, separado de `nome`, para o dia em que a loja quiser um nome comercial curto só para a
etiqueta: o front já lê de lá, então tornar isso editável não mexe em tela nenhuma.

### 2. O painel de propriedades cobria a etiqueta

Era `.modal-overlay` — que por definição cobre a tela inteira e captura todo clique. Resultado: **o
painel que existe para ajustar o campo impedia arrastar o campo**. Virou janela flutuante
(`position: fixed`), **arrastável pelo cabeçalho**, sem camada por trás, nascendo no canto inferior
direito (o canvas fica à esquerda). As duas formas de ajuste servem ao mesmo fim por caminhos
diferentes — o arraste posiciona rápido, o número posiciona exato — e precisam conviver.

### 3. Redimensionar por eixo

A alça era uma só, no canto, mexendo nas duas dimensões juntas. Agora são **três**: canto (ambas),
borda direita (largura) e borda inferior (altura). Num campo de código de barras a altura é
justamente o que não se quer mexer sem querer.

### 4. Código de barras: dois defeitos diferentes com a mesma aparência

- **Barras "borradas"** — ⛔ **o diagnóstico abaixo estava ERRADO e a correção foi revertida no
  mesmo dia**; fica registrado porque o raciocínio é plausível e alguém vai repeti-lo. Eu atribuí o
  defeito ao antialiasing e acrescentei `shape-rendering="crispEdges"`: a etiqueta impressa saiu com
  **barras gordas e ilegíveis**. A causa real era o **esticamento** do SVG, e a correção é derivar o
  módulo da largura da caixa. Ver *"⛔ `crispEdges` foi revertido"*, acima.
  <br>~~faltava `shape-rendering="crispEdges"` no SVG. Com antialiasing, uma borda que cai em
  meio-pixel é pintada em cinza; térmica é 1 bit e converte esse cinza em pontos irregulares.~~
- **Dígitos espremidos** — eram desenhados pelo próprio jsbarcode, **dentro** do SVG que é esticado
  para preencher a caixa (`preserveAspectRatio="none"`); esticar o desenho espremia o texto junto.
  Saíram do SVG e viraram HTML, com espaçamento proporcional ao corpo da letra e o agrupamento
  **1+6+6** do padrão EAN-13 (`7 891234 567895`), que é como o olho confere um código sem se perder
  entre treze algarismos.

⚠️ **O que NÃO dá para resolver por software:** deixar cada módulo com largura inteira em *dots*
(o que eliminaria de vez a irregularidade) exigiria, nesta caixa de 34 mm a 203 dpi, um módulo de
3 dots = 0,375 mm → 35,6 mm de símbolo, que não cabe; ou 2 dots = 0,25 mm, que é 76% do nominal,
**abaixo do mínimo de 80%** da norma. O sistema legado convive com o mesmo compromisso
(`mmBarWidth` 0,31 mm = 2,48 dots).

### 5. Zoom: abre no maior que couber, com teto de 250%

Uma etiqueta de 34 × 29,5 mm em 100% dá ~200 × 177 px, e nesse tamanho não se posiciona campo com
precisão de meio milímetro — todo mundo subia o zoom antes de começar. "Redefinir" volta para 250%,
não para 100%: o botão tem de devolver a tela ao estado em que ela nasce, senão leva para um lugar
onde ninguém trabalha.

### 6. A tela não rola mais na vertical

O corpo do shell é `overflow-y: auto`, então o editor — que cresce com o zoom — empurrava o
formulário para fora da janela e obrigava a rolar a **página** para ver o campo sendo ajustado. A
página ficou fixa e a rolagem foi para onde o conteúdo realmente varia de tamanho: a área do
canvas. É o mesmo princípio do shell de lista do projeto (cabeçalho e rodapé fixos, só o miolo
rola) — a tela é que não seguia.

### 7. O layout: duas colunas, e o zoom que se ajusta à tela

A primeira versão do "sem rolagem vertical" saiu pior que o problema: com tudo empilhado, os dois
cards de medidas comem ~780 px de altura, então a seção do editor era espremida no que sobrava e
**desenhava por cima das Informações do Registro**. O desenho da etiqueta — a razão de a tela
existir — virou uma tira de 100 px. O dono do produto mandou a captura: *"não consigo ver o design
da etiqueta"*.

**Duas colunas.** Os cards de medida são estreitos e sobrava metade da largura vazia à direita.
Medidas à esquerda, editor à direita com a altura inteira; nome e Informações do Registro
atravessam as duas. Quem rola, quando precisa, é cada coluna por dentro.

⚠️ **Fechar a cadeia de alturas é obrigatório, elo por elo.** Item de flex tem `min-height: auto`,
isto é, **se recusa a encolher abaixo do próprio conteúdo**. Sem `min-height: 0` em cada nível (e
`overflow: hidden` no corpo do editor), o canvas de 476 px simplesmente vaza para fora do container
de 99 px — medido no DOM, não deduzido. Esse vazamento era a sobreposição relatada.

**Espaço recuperado** (cada pixel aqui é pixel de etiqueta visível): a dica de uso da barra de
ferramentas quebrava em duas linhas e fazia a barra ocupar **71 px** — virou uma linha só com
reticências e o texto completo no `title` (**40 px**). Padding das seções e `gap` do editor
reduzidos. E a **prévia do rolo virou `<details>` recolhido**: ela responde uma pergunta pontual
("as colunas encaixam?") e estava custando ~190 px permanentes do desenho.

**Zoom que se ajusta.** Mesmo com tudo isso, 250% de uma etiqueta de 31,7 mm pede 476 px e a seção
do editor tem ~460 px numa janela de 945 px — **não cabe, com ou sem ajuste**. Então o editor abre
no maior zoom que mostra a etiqueta **inteira**, com 250% de teto e **150% de piso**. Duas lições
que só apareceram medindo:

- **Sem piso**, uma janela baixa abria a tela em **50%** — e etiqueta minúscula é tão inútil quanto
  etiqueta cortada. Abaixo do piso é melhor abrir grande e rolar.
- **Com `ResizeObserver` vivo**, qualquer redimensionamento da janela **descartava o zoom que o
  usuário tinha escolhido** — pior que abrir no valor errado. A medição é uma só, na abertura,
  depois de dois `requestAnimationFrame` (medir antes lê a altura provisória do primeiro quadro).

### 8. O Nome desceu para a coluna esquerda, e o editor subiu

Pedido do dono do produto, e é o mesmo raciocínio do item 7 levado até o fim: o campo **Nome**
atravessava a tela inteira e empurrava o editor uma linha inteira para baixo — e era exatamente
essa faixa de altura que faltava para a etiqueta caber sem rolagem.

Agora o Nome fica na **coluna esquerda**, com a mesma largura dos campos de medida logo abaixo
(a largura da coluna, não a da tela), e o editor ocupa as **duas linhas** do grid na coluna
direita: ele começa lá em cima, alinhado com o Nome. A caixa **Ativa** subiu para a linha do
rótulo, porque sozinha ela custava a altura de um campo inteiro — e essa altura agora pertence ao
desenho da etiqueta.

---

## Revisão 2026-08-22 — a busca de produto de exemplo avisa quando corta (item 33, estendido)

`EtiquetaConfigService.buscarProdutos` (o seletor de "produto de exemplo" do editor) cortava em
**10** resultados e a tela **não dizia nada** — mesmo defeito do item 33 da auditoria, numa tela que
a auditoria não tinha listado, encontrada ao revisar a documentação no fim do dia.

Limite subiu para **20** e o modal passou a avisar *"Mostrando os primeiros 20 — refine a busca para
ver mais."*. Mesma correção em `PesquisaVariacaoModal` (Relatório de Movimentação/Kardex).

⚠️ Aqui o impacto é menor que no seletor de fornecedor — não há botão de cadastro rápido ao lado
criando duplicata —, mas o operador ainda conclui "não está cadastrado" para um produto que existe.

---

## ✅ 2026-08-24 — o código de barras não lia, e a causa não estava no desenho

Fecha a pendência aberta em 2026-08-21 ("o código de barras impresso ainda não lê"), que tinha
resistido a **três** tentativas de correção no mesmo dia — todas no desenho do SVG, todas
inevitavelmente inúteis, porque **o defeito nunca esteve no desenho**.

### Como a causa apareceu: medindo a etiqueta, não relendo o código

A foto da etiqueta impressa foi recortada e teve o **perfil de intensidade** extraído (média de 60
linhas horizontais, para eliminar ruído de papel e da câmera). O resultado não deixou margem:

| Medida | Esperado | Impresso |
|---|---|---|
| Transições (barras + espaços) | **59** | **31** |
| Barra mais fina | 1,00 módulo | **2,25 módulos** |
| Espaço mais fino | 1,00 módulo | 0,82 módulo |
| Largura do símbolo | 25,2 mm | ≈26 mm |

Vinte e oito transições **desapareceram**: barras vizinhas se fundiram porque o espaço branco
entre elas foi comido. Nenhuma barra de 1 módulo sobreviveu.

⚠️ **A última linha é a que fecha o diagnóstico:** o símbolo mede o que deveria medir. Geometria,
módulo, zonas de silêncio e escala estavam **certos** — as três correções anteriores tinham
consertado o que já não estava quebrado. O que mudou foi a repartição entre preto e branco: cada
barra engordou ~0,33 mm e cada espaço encolheu na mesma medida. Isso tem nome — *bar width growth*
— e uma causa dominante em térmica: **intensidade alta demais**, o ponto queima maior que o dot
nominal e invade o branco vizinho.

### A confirmação independente, no mesmo papel

A tarja preta do topo (`NOME_EMPRESA`, `fundo_preto = true`) tem o texto **vazado em branco**, e
ele saiu **comido pela metade**. É o mesmo fenômeno visto do avesso: preto invadindo branco, num
campo que não tem nada a ver com o SVG do código de barras. Se a causa fosse o nosso desenho, não
havia por que o texto vazado sumir; se fosse geometria, o símbolo mediria errado — e não mede.

### A correção: intensidade 10 → 6 (medido, não teórico)

Argox OS-2140 PPLA, escala 0–20. Em **10** (padrão de fábrica) o leitor recusava; em **6** lê.

⚠️ **A pegadinha que trava qualquer um** está antes do slider: a caixa **"Usar configuração atual
de intensidade da impressora"** vem marcada, e enquanto estiver assim o driver usa a intensidade
gravada no *firmware* e deixa o slider **cinza**. Quem vai direto no slider conclui que não há o
que ajustar. Caminho completo: *Preferências de impressão* (**não** "Propriedades da impressora",
que é outra janela e não tem o ajuste) → aba **Opções** → desmarcar a caixa → **Nível de
intensidade** → 6.

### Por que isto virou aviso na tela e não código

**A intensidade é inalcançável a partir do navegador.** Mora no DEVMODE privado do driver, e não
existe API web que chegue lá — nem CSS, nem JS, nem `window.print()`. WebUSB também não serve: no
Windows a impressora já está capturada pelo driver de classe de impressora e o Chrome não consegue
reivindicar a interface.

**E compensar no desenho não resolveria.** A OS-2140 é 203 dpi = 8 dots/mm, então 1 dot = 0,125 mm
e o módulo atual (30 mm ÷ 113) tem **2,12 dots**. A impressora só liga ou desliga dots inteiros:
não há como desenhar "meio ponto mais fino", que é o que o *bar width reduction* exigiria.
Decompondo o engrossamento medido:

| Causa | Quanto | Corrigível no código? |
|---|---|---|
| Módulo em fração de dot (2,12) | ~0,9 dot | sim |
| **Espalhamento térmico** | **~1,8 dot** | **não** |

Mesmo zerando a parte corrigível, sobraria o engrossamento maior numa barra de 2 dots. **Só a
intensidade resolve** — daí o aviso.

Implementação: `AvisoIntensidadeImpressora.tsx`, exibido no popup do **Testar Impressão** (o
momento em que o lojista vai imprimir e conferir), mais um passo e um erro comum na `AjudaDaTela`.

### ⏭️ O que fica registrado para depois

O módulo em **número inteiro de dots** continua valendo a pena (elimina a fração de 0,9 dot e dá
margem), mas exige um parâmetro novo — a **resolução da impressora** (203/300 dpi) —, porque 2
dots são 0,25 mm a 203 dpi e 0,254 mm a 300. Conta que vale registrar: numa etiqueta de 34 mm a
203 dpi, **2 dots é a única opção** — 3 dots dariam 42,4 mm e não caberiam. É por isso que temos
menos folga que o sistema legado, cujo módulo é 0,31 mm.

Caminho definitivo, se algum dia o volume justificar: **agente local falando PPLA**, que define a
densidade por comando e faz a **própria impressora** desenhar o código de barras, alinhado a dots
por construção. Custo: executável instalado em cada loja, com atualização e suporte — empurra
contra o P6.

---

## ✅ 2026-08-24, rodada 2 — o quadro de corte do Teste mudava o layout que ele deveria provar

Depois de a intensidade resolver as barras, sobrou um defeito que só aparecia **no Teste de
Impressão**: os **dígitos legíveis** do código de barras não saíam no papel. Na Emissão de
Etiqueta, com a mesma impressora e o mesmo modelo, saíam.

⭐ **Quem matou a hipótese errada foi o dono do produto**, não a medição: eu estava convencido de
que era a intensidade baixa apagando a haste fina do Courier New. Ele observou que a Emissão
imprimia certo — e se fosse calor, as duas falhariam igual. Uma frase encerrou a linha de
investigação. Ver [[feedback_evidencia_do_usuario_vence_inferencia]].

### O que foi descartado, com evidência

| Suspeita | Como caiu |
|---|---|
| `exibir_texto_legivel` desligado | está `t` no banco |
| API não devolve o campo | `GET` devolve `exibirTextoLegivel: true` |
| O `PUT` (que o Teste usa) perde o campo | ele faz `return buscar(id)` — o mesmo DTO do `GET` |
| Os dígitos não são renderizados | **existem no DOM**, com o texto certo |
| `visibility: hidden` da impressão | `.etiqueta-rolo-imprimir *` vence por especificidade |
| `@page` pequeno demais | `size: 110mm 33.9mm`, correto |
| O navegador não desenha | **o PDF da mesma impressão traz os dígitos** |

E a foto do papel, calibrada pelo passo de 33,9 mm entre duas tarjas pretas (434 px → 12,8 px/mm),
mostrou as barras começando em **18,0 mm** (previsto 17,5) e terminando em **27,1 mm** — a altura
**reduzida**, de quando os dígitos existem. Se `exibirTexto` estivesse desligado, o SVG teria
esticado até 31,5 mm. Ou seja: o espaço dos dígitos foi reservado e ficou **vazio** no papel.

### A causa

O Teste desenhava o quadro de corte com **`border: 1px solid #000`**. Com o
`* { box-sizing: border-box }` global, a borda **encolhe a área interna e empurra todo o conteúdo
1 px (0,265 mm) para baixo**. O campo de barras termina em 31,5 mm numa etiqueta de 31,70 — folga
de **0,2 mm**, menor que o deslocamento. Os dígitos ocupam os últimos 3 mm do campo, e eram os
únicos a atravessar o limite do adesivo.

Medido no navegador, antes e depois:

| | Dígitos terminam | Fim do adesivo | |
|---|---|---|---|
| `border` (Teste) | 120,05 px | 119,80 px | passavam 0,066 mm |
| **`outline`** | 1051,04 px | 1051,80 px | **0,76 px dentro** ✅ |

### A correção e o princípio

`outline: 1px solid #000` + `outlineOffset: -1px` — desenha por fora do fluxo, não ocupa espaço.
O guia de corte continua existindo e o Teste passa a imprimir a **mesma geometria** da Emissão.

⚠️ **O defeito de fundo vale mais que a linha corrigida:** um enfeite da tela de calibragem estava
alterando a geometria que ela deveria estar provando, e chegou a divergir da rotina real num item
visível. É o mesmo princípio que este código já defende ao imprimir sempre a versão **gravada** em
vez do formulário em edição — um teste que não corresponde ao que sai de verdade não prova nada.

### 💾 A calibragem agora sobrevive a reset de banco

`db/scripts/seed_etiqueta_calibrada.sql` recria o modelo inteiro (geometria do rolo + os 4 campos).
O banco de dev é recriado com frequência e estes números custaram duas sessões de etiqueta
impressa. Idempotente, resolve o `id_tenant` pelo slug e seta `app.id_tenant` (sem isso o
`FORCE RLS` barra o INSERT e esconde o SELECT). **Testado nos dois caminhos** — atualizando o
existente e criando do zero com um nome temporário, que foi removido depois.

---

## 🔴 2026-08-24, rodada 3 — a tela de CRIAR modelo era inutilizável (e a de editar, perfeita)

Encontrado pelo dono do produto ao cadastrar um segundo modelo (34 × 60): *"a tela está estranha,
não aparecem os campos pra configurar"*. O formulário aparecia espremido numa coluna estreita com
rolagem interna, o editor visual caía **abaixo** dele reduzido a uma caixa de ~30 px, e metade
direita da tela ficava vazia.

⚠️ **Só acontecia em `/etiqueta-configuracao/novo`.** Editando um modelo existente o layout estava
correto — e é por isso que passou despercebido desde a reforma de 2026-08-21: toda a calibragem foi
feita **editando** o único modelo que existia.

### A causa: seletor posicional + componente que devolve `null`

O grid de duas colunas posicionava a faixa de baixo assim:

```css
.etiqueta-config-corpo .form-fieldset > .section:last-of-type { grid-column: 1 / -1; grid-row: 3; }
```

A intenção era mirar **Informações do Registro**. Mas `InfoRegistro` começa com
`if (!codigo) return null` — e no modo CRIAR não há registro, então ele **não renderiza**. O
"último `.section`" passa a ser a **seção do editor**.

E aí a especificidade decide: `.etiqueta-config-corpo .form-fieldset > .section:last-of-type` é
**0,4,0**, contra **0,2,0** de `.etiqueta-config-corpo .secao-editor-etiqueta { grid-column: 2;
grid-row: 1 / 3 }`. A regra da faixa de baixo vence, e o desenho da etiqueta — a razão de a tela
existir — vai para a linha 3 esmagado, ocupando as duas colunas.

Confirmado lendo o `grid-column` computado no DOM, nas duas rotas:

| | `/novo` (antes) | `/novo` (depois) | `/1` (editar) |
|---|---|---|---|
| `secao-editor-etiqueta` | **col 1/-1, row 3** 🔴 | col 2, row 1/3 ✅ | col 2, row 1/3 ✅ |
| `secao-info-registro` | (não existe) | (não existe) | col 1/-1, row 3 ✅ |

### A correção

`InfoRegistro` ganhou a classe **`secao-info-registro`** e o CSS passou a mirá-la pelo nome. Classe
própria não depende de um irmão existir; `:last-of-type`, sim.

⚠️ `InfoRegistro` é usado em **15 telas** — acrescentar uma classe é seguro (nenhuma regra existente
a referencia), e foi conferido no navegador que em Clientes a seção segue com `border-bottom: 0`
(a regra global `.section:last-of-type`, intocada) e `grid-column: auto`.

**Lição:** seletor posicional (`:last-of-type`, `:last-child`, `:nth-*`) sobre uma lista onde
**algum item é condicional** aponta para o elemento errado exatamente no caso em que o item some —
e, por ser mais específico, ainda vence a regra correta. Se o CSS precisa de um alvo, dê um nome a
ele.

---

## ✅ 2026-08-24, rodada 4 — o mesmo campo pode aparecer mais de uma vez (etiqueta destacável)

Pedido do dono do produto: *"hoje podemos colocar apenas uma vez cada campo, preciso ter a opção
de poder colocar 2 vezes cada campo, pois às vezes a etiqueta é destacável"*.

**O caso.** Numa etiqueta alta (34 × 60 mm) o adesivo é picotado ao meio: uma parte fica no produto
e a outra é destacada no caixa. As duas metades precisam do **mesmo** conteúdo — descrição, preço e
principalmente o código de barras. Até aqui cada campo só podia ser posicionado uma vez, a paleta
esvaziava com *"Todos os campos já estão na etiqueta"* e a segunda metade saía em branco.

### Havia DUAS travas, e a segunda só apareceu testando

| Trava | Onde | Como caiu |
|---|---|---|
| `UNIQUE (id_config_etiqueta, campo)` | banco | **V060** |
| `"Campo repetido na etiqueta: X"` | `EtiquetaConfigService.validar` | removida |

⚠️ A validação Java **não estava no radar** ao ler o schema. Foi o `POST` de teste respondendo
**400** que a revelou — se a mudança tivesse sido conferida só pela tela, o editor deixaria montar
o layout e a falha apareceria no **Salvar**, com o trabalho já feito.

### O modelo relacional já suportava; só as travas impediam

Nada mais precisou mudar no backend, e vale registrar por quê:

* `cfg_etiqueta_campo` tem **PK própria** (`id_config_etiqueta_campo`, identity) — cada linha já
  tinha identidade;
* `salvarCampos` **apaga e reinsere a lista inteira, em ordem** (padrão do projeto, igual a
  `ProdutoService.salvarCategorias`) — não faz diff por campo;
* `buscarCampos` lê com **`ORDER BY id_config_etiqueta_campo`**, que preserva a ordem em que a tela
  mandou.

A V060 não recria índice nenhum no lugar: `cfg_etiqueta_campo_config_ix (id_tenant,
id_config_etiqueta)` já atende a leitura por configuração, o único acesso que existe.

### No front, a identidade deixou de ser o NOME do campo

Era `CampoEtiqueta` (o enum) em todo lugar — o que, com repetição, faria as duas instâncias serem
a mesma coisa: **arrastar uma moveria a outra**, o painel abriria sem dizer qual, e as `key` do
React duplicariam. Passou a ser o **índice** na lista:

* `indiceSelecionado` (era `campoSelecionado`), `atualizarCampo/Posicao/Tamanho`,
  `moverCampoRelativo`, `removerCampo`, os refs de arraste e de redimensionamento;
* `camposQueTransbordam` virou `Set<number>` — por nome, uma instância cabendo e a outra não
  dariam um aviso só, apontando as duas;
* `key` por índice em **quatro** renders: editor, prévia do rolo, Teste de Impressão e **Emissão de
  Etiqueta** (esta última fora da tela, e passaria despercebida).

⚠️ **Remover limpa a seleção**, sempre: remover por índice desloca os seguintes, e manter o índice
antigo passaria a apontar para o campo vizinho — o painel abriria no lugar errado.

### O que a tela mostra agora

* A paleta **não esconde mais** o que já está na etiqueta, e traz a contagem: `Código de Barras
  (SKU) (2)`.
* O painel de propriedades diz qual instância está aberta: **"Código de Barras (SKU) (2ª)"** — o
  ordinal só aparece quando há repetição.
* O aviso de transbordo também distingue (`Código de Barras (SKU) (2ª)`).

### Decisão: sem limite de repetições

O pedido falava em "2 vezes", mas travar em 2 exigiria contagem e mensagem de erro para impedir
algo que não faz mal. Sem limite atende o pedido e é mais simples.

### Verificado

* **Navegador:** com dois códigos de barras, mudar a posição de um moveu **1 de 5** campos.
* **API:** `POST` 201 com dois `SKU_BARRAS` (y=5 e y=40), voltando na ordem certa e com
  `exibirTextoLegivel` **independente** por instância; `PUT` 200; `DELETE` 200 sem deixar campo
  órfão.
* **Suíte:** 913 testes verdes. O teste `campoRepetidoEhRejeitado` prendia o comportamento antigo e
  virou `mesmoCampoPodeAparecerMaisDeUmaVez` — que confere **as duas instâncias de volta**, não só
  o 201: um teste que olhasse o status passaria mesmo se a leitura devolvesse uma linha só.
