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
