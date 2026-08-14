# Spec: Estorno de Recebimento de Crediário       Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-29 · Módulo(s): `financeiro` (recebimentocrediario) · Fase: 2 — Crediário/Caixa (Q5/ADR-010)

## Problema

Um recebimento de crediário já efetivado (`docs/telas/recebimento-crediario.md`) não podia ser
desfeito — se o operador recebesse por engano (parcela errada, valor errado, cliente errado),
não havia como reabrir a(s) parcela(s) nem reverter o lançamento de caixa. A única saída era uma
correção manual direto no banco.

## Solução proposta

Tela irmã de Recebimento de Crediário, no mesmo módulo backend. Fluxo: busca recebimentos já
feitos por nome do cliente (obrigatório) + intervalo de data (opcional) → lista um por um, cada
linha sendo um **lote inteiro** (não uma parcela) → visualizar as parcelas daquele lote antes de
decidir → estornar, se for o caso. O estorno reabre todas as parcelas do lote, apaga os
lançamentos de caixa e apaga o cabeçalho do lote — sempre a operação inteira, nunca uma parcela
isolada.

## Regra de negócio central

Como o Recebimento de Crediário permite selecionar parcelas de vendas/contratos diferentes numa
única operação (`docs/telas/recebimento-crediario.md`, seleção múltipla), um lote pode cobrir,
por exemplo, uma parcela da venda #4 e outra da venda #14 recebidas juntas. **Estornar uma
parcela desse lote exige estornar todas as outras também** — não existe estorno parcial de um
lote. Isso é garantido por desenho: a listagem e a ação de estorno operam sempre no nível do
lote (`contas_receber_lote`), nunca no nível da parcela individual.

## User stories

- Como operador de caixa, quero localizar um recebimento já feito buscando pelo nome do cliente,
  e opcionalmente por um intervalo de datas.
- Como operador, antes de decidir estornar, quero visualizar quais parcelas fazem parte daquele
  recebimento (de quais vendas, vencimento, valor).
- Como operador, quero estornar um recebimento inteiro e ter certeza de que todas as parcelas
  envolvidas voltam a ficar em aberto, e que o(s) lançamento(s) de caixa correspondentes somem.

## Decisões (confirmadas com o dono do produto via `AskUserQuestion` antes de implementar)

1. **Granularidade da listagem — por lote, não por parcela** (recomendado, escolhido). Cada linha
   já é a unidade de estorno; a regra "estornou uma, estorna todas" fica automática por
   construção, sem precisar detectar/selecionar parcelas-irmãs na tela.
2. **Quem pode estornar — ADMIN e OPERADOR** (não a opção recomendada, que era só ADMIN). Mesmo
   nível de acesso da tela de Recebimento — operação do dia a dia do caixa.
3. **O que sobra do lote depois do estorno — é apagado fisicamente** (não a opção recomendada,
   que era manter o registro com uma marca de estornado). Mesmo padrão já usado na exclusão de
   Transferência de Produtos (`docs/telas/transferencia-estoque.md`).

## Campos da tela

### Filtros

| Campo | Tipo | Obrigatório |
|---|---|---|
| Nome do Cliente | texto | **Sim** — 400 se vazio |
| Data Inicial (do recebimento) | data (`dd/mm/aaaa`) | Não |
| Data Final (do recebimento) | data (`dd/mm/aaaa`) | Não |

### Listagem (uma linha por lote)

| Coluna | Origem |
|---|---|
| Data do Recebimento | `contas_receber_lote.data_recebimento` |
| Cliente | `cliente.nome` (via `contas_receber_lote.id_cliente`) |
| Qtd. Parcelas | `count(contas_receber)` com aquele `id_lote_recebimento` |
| Valor Total | `contas_receber_lote.valor_total` |
| Forma(s) de Pagamento | nomes distintos de `tipo_carteira` usados em `caixa_detalhe` daquele lote, concatenados por vírgula — só contexto, não afeta o estorno |

Duas ações por linha:
- **Visualizar** (ícone verde, olho) — abre um popup só-leitura com as parcelas do lote (Nº
  Venda, Nº PC no formato `parcela/totalParcelas` do mesmo plano venda+carteira, Vencimento,
  Valor Recebido). Não trava nada no banco (`readOnly`).
- **Estornar** (ícone vermelho, seta de retorno) — abre modal de confirmação explicando que
  todas as parcelas do lote voltam a ficar em aberto e os lançamentos de caixa serão apagados;
  ação irreversível.

## O que o estorno faz, em ordem (transação única)

1. Trava o lote (`SELECT ... FOR UPDATE`) — 409 se não existir ou já tiver sido estornado.
2. Trava as parcelas do lote (`FOR UPDATE`), protegendo contra estorno duplicado em corrida.
3. Apaga `contas_receber_detalhe` das parcelas do lote (detalhe de cartão, RN010 da tela de
   Recebimento) — **antes** de qualquer outra mudança, porque a consulta que localiza essas
   parcelas depende de `id_lote_recebimento` ainda estar preenchido.
4. Reabre todas as parcelas do lote numa única `UPDATE` (`data_recebimento`/`valor_recebido`/
   `id_empresa_pagamento`/`id_lote_recebimento` voltam a `NULL`/`0`) — a contagem de linhas
   afetadas alimenta o `qtdParcelas` da resposta.
5. Apaga os lançamentos de `caixa_detalhe` daquele lote.
6. Apaga o cabeçalho `contas_receber_lote`.

**Nunca mexe em `caixa_mestre`** — o caixa do dia pode ter lançamentos de outros lotes recebidos
na mesma sessão de trabalho (mesmo usuário/empresa/dia).

## Critérios de aceitação (viram testes)

- Dado uma busca sem nome de cliente, quando executada, então 400.
- Dado um recebimento já feito, quando listado por nome+intervalo de data, então aparece com a
  qtd. de parcelas e as formas de pagamento corretas.
- Dado um lote cobrindo parcelas de duas vendas diferentes, quando estornado, então **as duas**
  parcelas voltam a ficar em aberto (não só uma).
- Dado um lote com pagamento em carteira de cartão, quando estornado, então
  `contas_receber_detalhe` daquelas parcelas é apagado.
- Dado um lote estornado, quando os lançamentos de caixa são conferidos, então não existem mais.
- Dado um lote inexistente, quando tenta estornar, então 409.
- Dado um lote já estornado, quando tenta estornar de novo, então 409 na segunda tentativa.
- Dado um lote de outro tenant, então não aparece na listagem nem pode ser estornado (RLS).
- Dado um lote, quando solicita visualizar as parcelas, então recebe a lista sem travar nada no
  banco nem alterar qualquer dado.

Cobertos por `RecebimentoCrediarioCrudTest` (7 testes novos, 23 no arquivo). Suíte completa do
projeto: 492/492 verdes (2026-08-24).

## Impacto no contrato de API

```
GET  /api/v1/recebimento-crediario/estornos?nomeCliente=&dataInicial=&dataFinal=   lista lotes (nome obrigatório)
GET  /api/v1/recebimento-crediario/estornos/{idLoteRecebimento}/parcelas           parcelas do lote (só leitura)
POST /api/v1/recebimento-crediario/estornos/{idLoteRecebimento}                    estorna o lote inteiro
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), abertos a ADMIN e OPERADOR. Erros em
Problem Details (RFC 9457): 400 (sem nome de cliente), 409 (lote inexistente ou já estornado).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `financeiro.estornorecebimentocrediario.tela`** — ver
  `web/src/components/AjudaDaTela.tsx`. `url_video`: `NULL` por ora.

## Impacto no banco

Nenhum — reaproveita `contas_receber`/`contas_receber_detalhe`/`contas_receber_lote`/
`caixa_detalhe`, todas já existentes desde a tela de Recebimento (V025).

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Estorno parcial de um lote** — nunca uma parcela isolada, sempre o lote inteiro (regra de
  negócio central, ver acima).
- **Manter rastro do lote estornado** — o cabeçalho é apagado fisicamente, não marcado; não há
  como listar "recebimentos já estornados" depois do fato.
- **Estorno de parcela de cartão de crédito recebida fora desta tela** — mesmo escopo da tela de
  Recebimento (só categoria `CREDIARIO`).
- **Reabertura de caixa fechado** — se o `caixa_mestre` daquele dia já tiver sido fechado (a
  rotina de **Fechamento de Caixa** existe desde 2026-07-30 —
  `web/src/pages/caixa/FechamentoCaixa.tsx`, `CaixaController.java:56`,
  `docs/telas/fechamento-caixa.md`), o estorno dos lançamentos de `caixa_detalhe` ainda funciona,
  mas não há tela de reabertura de caixa.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Tempo de localização e estorno de um recebimento em menos de 30 segundos.
