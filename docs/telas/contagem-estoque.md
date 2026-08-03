# Spec: Rotina de Contagem de Estoque                Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-04 · Módulo(s): `estoque` · Fase: 2

## Problema

Não existe nenhuma forma de conferir o estoque físico da loja contra o que o sistema acha que
tem — a única maneira de corrigir uma divergência hoje seria uma Transferência de Produtos (que
exige uma empresa de origem/destino, não faz sentido pra "ajuste sem origem") ou mexer direto no
banco. `produto_balanco` (V019) e `tipo_movimento = 'AJUSTE'` já existiam desde a modelagem
original, como placeholder nunca usado.

## Solução proposta

Quatro telas novas, agrupadas num submenu "Contagem de Estoque" dentro do grupo **Estoque**,
abertas a **ADMIN e OPERADOR**, sempre escopadas à empresa ativa da sessão (claim `eid`) — sem
seletor de empresa em nenhuma delas, nem pra ADMIN:

1. **Contagem de Estoque** (`/estoque/contagem`) — só um campo de código de barras (mesma
   sintaxe `qtd*código` do PDV/Transferência); cada leitura soma na quantidade já contada daquele
   produto. Sem busca por nome — só o código.
2. **Diferenças de Estoque** (`/estoque/diferencas`) — compara a contagem ativa com
   `produto_estoque`, mostra só as exceções, segue o padrão visual de relatório (cabeçalho/rodapé
   fixos, colunas ordenáveis, PDF).
3. **Efetivar Balanço** (`/estoque/efetivar-balanco`) — grava as diferenças como ajuste de
   estoque de verdade e zera a contagem ativa.
4. **Zerar Contagem de Estoque** (`/estoque/zerar-contagem`) — apaga a contagem em andamento, ou
   desfaz a última efetivação.

## Modelo de dados

`produto_balanco` (V019, existia sem uso) ganhou a coluna `id_movimento` (nullable, FK pra
`produto_movimento_mestre`): **ledger, não upsert** — cada leitura de código de barras insere uma
linha nova; "quantidade contada" de uma variação é a SOMA das linhas ativas dela
(`id_movimento IS NULL`). Ao efetivar, em vez de apagar as linhas, elas são marcadas com o id do
`produto_movimento_mestre` (tipo `AJUSTE`, valor do enum que já existia sem uso) gerado na
efetivação — é isso que "zera" a contagem sem apagar nada fisicamente, e é o que viabiliza
desfazer depois (ver "Desfazer última efetivação"). Índice
`produto_balanco_empresa_movimento_ix (id_tenant, id_empresa, id_movimento)` cobre tanto "soma do
balanço ativo" (`id_movimento IS NULL`) quanto "todas as linhas de uma efetivação"
(`id_movimento = X`).

## Decisões de escopo

1. **Sempre a empresa ativa da sessão, sem exceção** — decisão explícita do dono do produto
   mesmo perguntando se ADMIN deveria poder escolher outra empresa: não. Simplifica o raciocínio
   (um balanço físico só faz sentido pra quem está fisicamente na loja).
2. **ADMIN e OPERADOR têm acesso completo** (não é ADMIN-only) — decisão explícita, contra a
   sugestão inicial de restringir.
3. **Mesma sintaxe de código de barras do PDV/Transferência** (`interpretarCodigoBarras()`,
   `web/src/lib/pdv.ts`) reaproveitada — "5*código" soma 5 de uma vez. **Quantidade máxima por
   leitura: 1000** (2026-08-04, pedido explícito após teste real) — só nesta tela, validação só
   no frontend (`ContagemEstoque.tsx`), não altera a função compartilhada nem afeta PDV/
   Transferência; acima disso a leitura é rejeitada com toast de erro, nada é registrado.
4. **Ajustar/remover uma linha da grid é permitido** (recomendado e aprovado) — corrige uma
   leitura errada sem precisar zerar a contagem inteira.
5. **Produto em estoque nunca escaneado aparece como diferença** (aprovado) — contagem tratada
   como 0, pra não passar batido. Mas só entra como "nunca contado" se a variação nunca tiver
   aparecido em `produto_balanco` (nem ativa, nem já efetivada em rodada anterior) — sem essa
   condição, todo produto já efetivado numa rodada voltaria a aparecer como diferença na rodada
   seguinte só por ter sido liberado do balanço anterior, mesmo sem ninguém tê-lo reescaneado.
   Uma variação **ativamente contada nesta rodada** sempre entra se diferente, independente do
   histórico.
6. **Efetivar grava um ajuste de estoque de verdade** — um `produto_movimento_mestre` (tipo
   `AJUSTE`) com uma linha de `produto_movimento_detalhe` por variação com diferença (crédito se
   sobra, débito se falta), que a trigger `fn_atualiza_estoque_movimento` materializa em
   `produto_estoque` (mesmo mecanismo de todo o sistema — nenhuma lógica de estoque em Java).
   Sem nenhuma contagem ativa, ou sem nenhuma diferença, o botão fica desabilitado e a tela avisa
   — não efetiva nada.
7. **Efetivar exige total contado/em estoque na confirmação + digitar "efetiva contagem"**
   (2026-08-04, pedido explícito) — o popup mostra os dois totais antes de confirmar, e o botão
   só habilita depois de digitar a frase exata (case-insensitive, espaços nas pontas ignorados) —
   mesmo padrão de "Zerar Contagem de Estoque" abaixo.
8. **Zerar exige digitar "zerar estoque"** (2026-08-04, pedido explícito, mesmo racional de #7:
   apagar contagem em andamento é irreversível e um clique duplo acidental perderia todo o
   trabalho de leitura).
9. **Desfazer última efetivação — desenho proposto pelo assistente e aprovado sem alterações**:
   apaga as linhas de `produto_movimento_detalhe` daquele movimento (a trigger reverte
   `produto_estoque` sozinha, mesmo mecanismo de exclusão de `TransferenciaService.excluir`) e
   libera de volta as linhas de `produto_balanco` daquele `id_movimento` pro balanço ativo
   (`id_movimento = NULL` de novo) — **reversão por delta, não por snapshot**: como a trigger
   sempre soma/subtrai o delta exato do movimento apagado a partir do estoque ATUAL, o resultado
   é correto mesmo que outras movimentações (venda, transferência) tenham acontecido no meio
   tempo entre a efetivação e o desfazer. Só a efetivação **mais recente que ainda tenha alguma
   linha de detalhe** pode ser desfeita (uma cujas linhas já foram todas apagadas conta como "já
   desfeita"); desfazer em sequência sem efetivar de novo no meio naturalmente cascateia pras
   efetivações mais antigas, uma de cada vez.
10. **Empty-body bug de status HTTP (2026-08-04, achado em teste real):** endpoints `void`
    (`registrarContagem`/`ajustarContagem`/`removerContagem`/`zerarContagem`/`desfazer`) sempre
    `@ResponseStatus(HttpStatus.NO_CONTENT)` (204) — qualquer outro status (200/201 default) com
    corpo vazio fazia o `api()` do frontend chamar `res.json()` num corpo vazio e lançar
    `SyntaxError`, fazendo uma leitura de código de barras bem-sucedida aparecer como "Não foi
    possível ler o código de barras" (o dado já tinha sido gravado no banco corretamente). Também
    corrigido defensivamente em `web/src/lib/api.ts` (lê como texto primeiro, só faz
    `JSON.parse` se não estiver vazio) — cobre qualquer status sem corpo, não só 204, protegendo
    contra a mesma classe de bug em endpoints futuros.

## Telas

### 1. Contagem de Estoque

Campo único de código de barras (foco automático ao abrir); grid abaixo mostra
Descrição/Variação Linha/Variação Coluna/Quantidade Contada de cada produto já lido — vem sempre
do servidor (React Query), não é um rascunho local. Quantidade editável direto na grid (corrige
sem reler); ícone de excluir remove a linha inteira sem afetar as demais.

### 2. Diferenças de Estoque

Sem popup de filtro (não existe filtro — sempre a contagem ativa da empresa logada). Colunas:
Descrição | Variação Linha | Variação Coluna | Qtd Estoque | Qtd Contada | Diferença (cor: verde
sobra, vermelho falta) — todas ordenáveis, cabeçalho/rodapé fixos. Duas mensagens de vazio
diferentes: **sem nenhuma contagem em andamento** (`existeContagemAtiva = false`) vs. **contagem
bate exatamente com o estoque** (`existeContagemAtiva = true`, `linhas` vazia) — 2026-08-04,
corrigido depois de reportado como bug (antes as duas apareciam com a mesma mensagem). PDF mostra
a empresa da contagem numa seção "Filtros Aplicados" acima da grid (2026-08-04, mesmo pedido).

### 3. Efetivar Balanço

Mostra a mesma grid de diferenças (pra revisar antes de confirmar) + botão "Efetivar Balanço",
desabilitado sem contagem ativa ou sem diferenças. Popup de confirmação mostra total contado e
total em estoque, com "Você pode desfazer esta efetivação depois" — só libera o botão de
confirmar depois de digitar "efetiva contagem".

### 4. Zerar Contagem de Estoque

Duas seções independentes na mesma tela (aprovado — botão de desfazer fica dentro desta tela, não
em outra): "Contagem Ativa" (total de produtos + quantidade total, botão Zerar com aviso de
irreversibilidade + campo de confirmação "zerar estoque") e "Desfazer Última Efetivação" (data,
total de produtos ajustados, botão que só aparece se há algo pra desfazer).

## Contrato de API

```
GET    /api/v1/estoque/balanco/contagem              → [{idVariacao,descricaoProduto,variacaoLinha,variacaoColuna,sku,qtdContada}]
POST   /api/v1/estoque/balanco/contagem   {idVariacao,qtd}         → 204
PUT    /api/v1/estoque/balanco/contagem/{idVariacao}  {qtdContada} → 204
DELETE /api/v1/estoque/balanco/contagem/{idVariacao}                → 204
DELETE /api/v1/estoque/balanco/contagem                             → 204  (zera tudo)
GET    /api/v1/estoque/balanco/diferencas             → {existeContagemAtiva, linhas:[{idVariacao,descricaoProduto,variacaoLinha,variacaoColuna,sku,qtdEstoque,qtdContada,diferenca}]}
POST   /api/v1/estoque/balanco/efetivar               → {idMovimento,totalProdutosAjustados,dataEfetivacao}
GET    /api/v1/estoque/balanco/ultima-efetivacao      → {existe,idMovimento,dataEfetivacao,totalProdutos}
POST   /api/v1/estoque/balanco/desfazer               → 204
```

Sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), ADMIN e OPERADOR. Erros em Problem Details: 400
(quantidade ≤ 0 no registro / negativa no ajuste, tenant sem quantidade decimal recebendo valor
quebrado, efetivar sem contagem ativa ou sem diferenças, desfazer sem nada pra desfazer); 404
(código de barras sem produto ativo correspondente, resolvido por `PesquisaProdutoModal`/
`buscarProdutoPorCodigo` no frontend antes de chamar `registrarContagem`).

## Critérios de aceitação (viram testes)

- Dado duas leituras do mesmo código, a quantidade contada soma (não duplica linha).
- Dado um ajuste manual de quantidade, só aquela variação muda — as demais ficam intactas.
- Dado remover uma linha, ela some da grid sem afetar as outras.
- Dado zerar a contagem, todas as linhas ativas da empresa somem.
- Dado um produto contado com quantidade diferente do estoque, ele aparece em Diferenças.
- Dado um produto em estoque nunca escaneado nesta rodada (mas contagem ativa em andamento por
  causa de outro produto), ele aparece como diferença (contagem = 0).
- Dado nenhuma contagem ativa, Diferenças vem vazia com `existeContagemAtiva = false`.
- Dado contagem igual ao estoque, o produto não aparece em Diferenças.
- Dado efetivar com diferenças, o estoque é ajustado, o movimento é gravado e o balanço ativo
  zera.
- Dado efetivar sem diferenças (ou sem contagem), responde erro de validação.
- Dado desfazer a última efetivação, o estoque volta ao valor anterior e o balanço ativo é
  restaurado com as mesmas linhas.
- Dado desfazer sem nenhuma efetivação, responde erro de validação.
- Dado desfazer duas vezes seguidas, a segunda não tem mais nada pra desfazer.
- Dado duas efetivações em sequência e a mais recente já desfeita, um novo desfazer volta pra
  efetivação anterior a ela.
- Dado o balanço de um tenant, dados de outro tenant não aparecem (RLS).

Cobertos por `BalancoEstoqueCrudTest` (15 testes).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`s: `estoque.contagem`, `estoque.diferencas`, `estoque.efetivar-balanco`,
  `estoque.zerar-contagem`** — ver `web/src/components/AjudaDaTela.tsx`. `url_video`: `NULL` por
  ora, em todas.

## Impacto no banco

`produto_balanco` (V019, editada em vez de nova migration — banco ainda em construção) ganhou
`id_movimento integer` (FK pra `produto_movimento_mestre`) + índice
`produto_balanco_empresa_movimento_ix`. Nenhuma tabela nova.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Contagem por múltiplas empresas numa mesma sessão** — sempre uma empresa por vez (a ativa da
  sessão).
- **Histórico de efetivações anteriores à mais recente ainda não desfeita** — só existe
  "última efetivação ativa"; não há uma tela de histórico completo de balanços.

## Questões abertas

Nenhuma bloqueante — o desenho de "desfazer" (item 9 acima) foi proposto pelo assistente e
aprovado sem alterações; os 4 bugs relatados após uso real (leitura de código de barras, mensagem
de vazio em Diferenças, aviso em Efetivar sem contagem, confirmação por texto em Zerar) foram
corrigidos na mesma sessão.
