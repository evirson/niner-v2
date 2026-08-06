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

Uma linha por configuração nomeada: `nome`, `largura_rolo_mm`, `numero_colunas` (`CHECK` 1 a 4 —
era 1 a 6, reduzido em 2026-08-05 a pedido do dono do produto; ver seção de ajustes no fim),
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
   configurado pra testar numa impressora real, sem depender da futura tela de Emissão (que precisa
   de produto/estoque reais e ainda nem foi especificada). Fica no topo da tela
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

## Pendências explícitas, fora do escopo desta migration

- **Estilo "contorno" (borda preta, sem preenchimento)** — reparado no PDF de referência (a caixa
  do tamanho "M"), mas não pedido; `fundo_preto` cobre só o binário preto-cheio/sem-fundo pedido.
  Se vier a ser pedido, é um campo novo (`estilo_fundo` com 3 valores em vez de boolean).
- **Sobreposição visual de dois campos** (x/y colidindo) — não validada pelo banco.
