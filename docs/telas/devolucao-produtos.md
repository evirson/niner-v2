# Spec: Devolução de Produtos                              Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-03 · Módulo(s): `vendas` (devolução) · Fase: 2 — Vendas/Financeiro

## Problema

Não existia nenhuma forma de dar entrada de volta no estoque de produtos que o cliente devolve
(sem ser uma troca completa nem o cancelamento da venda inteira). O PDV reservava o atalho **F5
"Devolver Produto"** desde 2026-07-28 sem nenhuma lógica; o schema já antecipava a feature: a
tabela `venda_devolucao` existe desde a migration original de vendas (`V018__vendas.sql`) e o
enum `tipo_movimento` já tinha o valor `DEVOLUCAO` (`V013__dominio_tipos_enum.sql`) — nenhum dos
dois era usado por código Java. O Relatório de Vendas já calculava "Valor Devolução"/"%
Devolução" esperando esse dado (`RelatorioVendasService.buscarValorDevolucao`).

## Solução implementada

Tela nova (`/devolucao-produto`, menu "Frente de Loja", ADMIN e OPERADOR) que:

1. Pede opcionalmente o **número da venda** — só para localizar o vendedor daquela venda (não é
   obrigatório e não fica gravado na devolução; serve unicamente para, no futuro, calcular a
   comissão de quem vendeu — ver RN-01).
2. Deixa digitar/ler **código de barras** repetidamente, empilhando uma grid de itens (mesmo
   padrão de leitura do PDV/Transferência de Estoque — `lib/pdv.ts:interpretarCodigoBarras` +
   `buscarProdutoPorCodigo`, mesmo `PesquisaProdutoModal`), cada linha com produto + quantidade
   editável.
3. O botão **"Gravar Devolução"** efetiva tudo numa transação: devolve a quantidade de cada item
   ao estoque **e emite um vale-mercadoria**, exibido num comprovante para impressão imediata.

## Decisões de escopo (fechadas)

**Sem vínculo persistido entre a devolução e a venda original.** O número da venda informado
serve só para resolver o vendedor (RN-01) — não fica gravado em `venda_devolucao` nem em
nenhuma outra tabela. Consequência aceita conscientemente: não há como reconciliar depois "esta
devolução veio desta venda", nem validar que a quantidade devolvida bate com o que foi vendido
naquela venda — o operador pode devolver qualquer código de barras, de qualquer venda ou
nenhuma, em qualquer quantidade (equivalente a uma entrada de estoque manual rotulada
"devolução"). Não existe limite/alerta de quantidade.

**Comissão: só o dado fica pronto, nenhum cálculo existe ainda.** `funcionario.perc_comissao` é
puramente cadastral em todo o sistema — nenhuma venda "credita" comissão de verdade hoje. A
devolução grava o vendedor resolvido em `produto_movimento_detalhe.id_funcionario` de cada linha
(mesmo campo que a venda já usa), para que um futuro relatório de comissão calcule `perc_comissao
× (Σ vendas do vendedor − Σ devoluções do vendedor no período)` sem precisar de tabela nova.
Nenhum cálculo de comissão é feito por esta feature.

**Toda devolução gera um vale-mercadoria** (revisão de escopo, 2026-08-03 — a primeira versão
deste documento previa "sem efeito financeiro"; o dono do produto pediu geração de vale como
parte central da feature). Ver "Vale-mercadoria" abaixo. Não é opcional por devolução — sempre
acontece.

**Permissão: ADMIN e OPERADOR.** Diferente do Cancelamento de Venda (ADMIN-only, reverte uma
venda inteira com caixa/parcelas), a Devolução de Produtos mexe só em estoque + emite um vale —
mesmo nível de acesso de Transferência de Estoque.

## Vale-mercadoria

Reaproveita a tabela `venda_devolucao` (existente desde `V018`, nunca usada até esta feature):

- `id_devolucao` (a própria PK) **é o número do vale**, impresso no comprovante.
- `id_venda_credito` grava o número da venda opcional informado na tela (redefinição desta
  coluna — o design original de `V018` previa um cenário de troca, não usado aqui).
- **O valor do vale nunca é gravado como coluna** — é sempre derivado somando os itens do
  movimento `DEVOLUCAO` vinculado (`produto_movimento_mestre.id_devolucao`, FK que já existia
  em `V019`, até então nunca preenchida).
- `vale_usado`/`id_venda_debito` (colunas que já existiam) são preenchidos quando o vale é
  resgatado numa venda futura (ver "Resgate no PDV" abaixo) — `false`/`NULL` enquanto não usado.

Ao gravar a devolução, um popup automático (`ComprovanteValeModal.tsx`) mostra o número e o
valor do vale, com "Salvar PDF", "Imprimir" e "Enviar por WhatsApp". Até 2026-08-07 usava um
layout próprio de 42 colunas (fonte Courier, `.comprovante-preview`), mesmo padrão do
Comprovante de Recebimento de Crediário; **desde 2026-08-07** foi padronizado com o layout de
**64 colunas / fonte Lucida Console da Papeleta de Venda** (`.papeleta-preview`/
`.papeleta-imprimir`, `ComprovantePapeletaModal.tsx`) — pedido do dono do produto pra uniformizar
a impressão dos itens entre os dois comprovantes que saem na mesma bobina térmica física.
"Enviar por WhatsApp" reaproveita o mesmo mecanismo da Papeleta de Venda/Comprovante de
Crediário (`comum.arquivocompartilhado`, ver `docs/infra/compartilhamento-arquivo-temporario.md`)
— como `venda_devolucao` não tem vínculo com cliente (devolução é anônima), não há telefone pra
pré-preencher, o operador digita na hora. Backend: `ItemDevolucaoResponse` ganhou `sku` e
`valorTotal` (mesmas colunas de `ItemComprovanteVenda`, PDV) pra reaproveitar a mesma tabela de
itens da papeleta em vez de duplicar a montagem do layout.

### Resgate no PDV

Em vez de um mecanismo de pagamento novo, o resgate usa o tipo de carteira **"VALE MERCADORIA"**,
que já era seedado em todo tenant desde o signup. Só a **categoria** dela mudou: de `AVISTA`
para um novo valor do enum `categoria_carteira`, **`VALE_MERCADORIA`** (`V025`, editado direto —
banco em construção). Consequência: "VALE PRESENTE" (outro rótulo seedado desde sempre, também
`AVISTA`, sem nenhuma lógica) foi **removido do seed** — conviver ao lado do vale de verdade
ficaria confuso.

No split-tender do PDV, a categoria "Vale-Mercadoria" pede o **número do vale**, não um valor
digitado — o servidor busca o valor de verdade e ignora qualquer valor mandado pelo cliente
(mesmo princípio de todo o PDV: preço nunca vem do front). Paga na hora, como À Vista. Bloqueios:
vale já usado → 409; vale maior que o saldo a pagar → 400 (decisão do dono do produto: vale é
sempre consumido por inteiro — "sem troco em vale" — por isso é bloqueado em vez de aceitar e
perder a diferença; o schema só guarda usado/não usado, nunca saldo remanescente). Ao efetivar a
venda, marca `vale_usado = true` + `id_venda_debito` **atomicamente** (`UPDATE ... WHERE
vale_usado = false`, trava otimista contra resgate concorrente do mesmo vale em duas vendas
simultâneas). Como "VALE MERCADORIA" já é uma carteira normal, o **Fechamento de Caixa já
totaliza sozinho** — nenhuma mudança lá.

### Cancelamento de Venda reabre o vale

Se a venda que resgatou um vale for cancelada (`CancelamentoVendaService`), o vale volta a valer
(`vale_usado = false`, `id_venda_debito = NULL`), dentro da mesma transação do cancelamento —
senão o cliente perderia o crédito de um vale cuja venda de resgate foi desfeita. Sem rastro de
que já tinha sido usado uma vez, mesma filosofia da exclusão física já usada ali para
`caixa_detalhe`/`contas_receber`.

## User stories

- Como operador de caixa, quero opcionalmente informar o número de uma venda pra que o sistema
  já saiba qual vendedor atender, sem precisar procurar isso manualmente.
- Como operador de caixa, quero ler o código de barras de cada produto devolvido e ver uma grid
  se formando, igual já faço no PDV, até terminar de separar tudo que o cliente está devolvendo.
- Como operador de caixa, quero gravar a devolução e já sair com um vale-mercadoria impresso
  pra entregar ao cliente.
- Como operador de caixa, quero que o cliente possa usar esse vale numa compra futura,
  escolhendo "Vale-Mercadoria" como forma de pagamento e digitando o número do vale.
- Como ADMIN, se eu cancelar uma venda que usou um vale, quero que esse vale volte a valer, sem
  o cliente perder o crédito.

## Regras de negócio

### RN-01 — Identificação do vendedor via número da venda (opcional)

Se informado, resolve o vendedor buscando `produto_movimento_detalhe.id_funcionario` dos itens
de `produto_movimento_mestre` com `tipo_movimento = 'VENDA'` e `id_venda` = informado (mesmo
dado que o PDV grava — um vendedor por venda, não por item). Se a venda não existir (ou for de
outro tenant — RLS), retorna 404 no lookup (`GET /vendedor`); no `POST` de efetivação, se não
resolver, a devolução segue sem vendedor (não bloqueia).

### RN-02 — Grid de leitura de código de barras

Reaproveita `web/src/lib/pdv.ts` (`interpretarCodigoBarras` — inclusive a sintaxe `qtd*código` —
e `buscarProdutoPorCodigo`) e `PesquisaProdutoModal.tsx`. Grid em tabela simples (`table
table-compacta`, estilo `TransferenciaForm.tsx`).

### RN-03 — Efetivação gera estoque + vale

Dentro de uma única transação: `venda_devolucao` (vale) → `produto_movimento_mestre`
(`tipo_movimento = 'DEVOLUCAO'`, `id_devolucao` apontando pro vale) → um
`produto_movimento_detalhe` (`credito_debito = 'C'`) por linha, com `id_funcionario` = vendedor
resolvido em RN-01. A trigger `fn_atualiza_estoque_movimento` soma a quantidade de volta em
`produto_estoque` sozinha.

### RN-04 — Resgate do vale (`categoria_carteira = VALE_MERCADORIA`)

Ver "Resgate no PDV" acima.

### RN-05 — Reabertura do vale no cancelamento

Ver "Cancelamento de Venda reabre o vale" acima.

## Contrato de API

```
GET  /api/v1/vendas/devolucao/vendedor?numeroVenda=123    → { idFuncionario, nomeFuncionario } | 404
GET  /api/v1/vendas/devolucao/vale/{idDevolucao}           → { valorVale, valeUsado, dataDevolucao, idVendaCredito, idVendaDebito }
GET  /api/v1/pdv/produtos/codigo/{codigo}                  → reaproveita o endpoint já existente do PDV
POST /api/v1/vendas/devolucao
     { numeroVenda?: number, itens: [{ idVariacao, qtd }] }
     → { idMovimento, idDevolucao, valorVale, dataMovimento, idFuncionario, nomeFuncionario, itens }
```

`POST /api/v1/pdv/vendas` (`PdvVendaService`) ganhou o campo opcional `idDevolucao` em cada linha
de `pagamentos[]` — obrigatório só quando `idCarteira` aponta pra uma carteira de categoria
`VALE_MERCADORIA`. Erros em Problem Details: 400 (grid vazia, quantidade inválida, número do
vale ausente numa linha `VALE_MERCADORIA`, vale maior que o saldo a pagar), 404 (venda/variação/
vale inexistente ou de outro tenant), 409 (vale já usado).

## Critérios de aceitação

- Dado um número de venda existente, quando informado, então o vendedor daquela venda é
  resolvido e exibido, sem gravar o número da venda em lugar nenhum.
- Dado nenhum número de venda informado, quando a devolução é gravada, então segue sem vendedor
  associado (não bloqueia).
- Dado um código de barras lido N vezes (ou `N*código`), quando a grid é montada, então soma a
  quantidade na mesma linha em vez de duplicar.
- Dado uma grid com itens, quando "Gravar Devolução" é confirmado, então cada item volta ao
  estoque na empresa correta, um `produto_movimento_mestre` com `tipo_movimento='DEVOLUCAO'`
  fica registrado, e um vale-mercadoria é emitido com o valor correto.
- Dado um vale emitido, quando usado como pagamento `VALE_MERCADORIA` numa venda cujo saldo a
  pagar é maior ou igual ao valor do vale, então a venda é efetivada, o vale é marcado como
  usado e vinculado à venda, e o Fechamento de Caixa totaliza esse valor na carteira.
- Dado um vale já usado, quando usado novamente, então 409.
- Dado um vale maior que o saldo a pagar de uma venda, quando usado, então 400.
- Dado um pagamento `VALE_MERCADORIA` sem número de vale informado, então 400.
- Dado uma venda que usou um vale, quando cancelada (Cancelamento de Venda), então o vale volta
  a `vale_usado = false`/`id_venda_debito = NULL` e pode ser usado numa venda futura.
- Dado um tenant, então nunca enxerga nem afeta estoque/venda/vale de outro tenant (RLS, P8).

Cobertos por `DevolucaoProdutoCrudTest` (5 testes) e `ValeMercadoriaCrudTest` (6 testes,
incluindo o cancelamento reabrindo o vale). Suíte completa do projeto: 295/295.

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `vendas.devolucaoproduto.form`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

- `categoria_carteira` (ENUM, `V025`) ganhou o valor `VALE_MERCADORIA`.
- `SignupService` (seed por tenant): a carteira "VALE MERCADORIA" nasce com categoria
  `VALE_MERCADORIA` em vez de `AVISTA`; "VALE PRESENTE" foi removida do seed.
- Nenhuma tabela nova — reaproveita `produto_movimento_mestre/detalhe` (`tipo_movimento =
  'DEVOLUCAO'`, já existia desde `V013`) e `venda_devolucao` (já existia desde `V018`, agora
  finalmente usada).

## Impacto nas integrações

Nenhum — comissão, documento fiscal e TEF ficam fora do v1 (nenhum dos três existe no sistema
hoje).

## Non-goals

- Vínculo persistido entre a devolução e a venda original (rastreabilidade venda↔devolução).
- Validação de quantidade devolvida contra quantidade vendida.
- Cálculo de comissão de fato — só o dado (`id_funcionario`) fica pronto.
- Uso do fluxo de troca de `venda_devolucao` (`id_venda_credito` como geradora de uma nova
  venda) — a coluna existe e é preenchida, mas não dispara nenhuma lógica de troca.
- Saldo parcial de vale (usar parte do valor e manter o resto disponível) — um vale é sempre
  consumido por inteiro, numa única venda.

## Métrica de sucesso

Devolução de um produto simples (leitura → gravar → vale impresso) em menos de 20 segundos; o
vale gerado pode ser resgatado numa venda futura sem nenhuma etapa manual além de digitar o
número.
