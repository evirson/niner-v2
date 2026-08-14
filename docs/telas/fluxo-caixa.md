# Fluxo de Caixa (DFC realizado + projeção) — `relatorios.fluxocaixa`

> Status: **implementado em 2026-08-14** (Partes 1 e 2), aprovado pelo dono do produto. Dois
> pontos mudaram durante a implementação, marcados com **[revisto na implementação]**.

## Problema

O lojista tem duas perguntas de dinheiro que o sistema não responde:

- *"Para onde foi o dinheiro no mês passado?"* — DFC realizado, por atividade.
- *"Vou ter dinheiro no dia 20 para pagar o fornecedor?"* — projeção. Para pequeno varejo esta é a
  que muda decisão: com crediário na rua e duplicatas a vencer, o dono decide compra, promoção e
  parcelamento por intuição.

`cfg_plano_contas` já tem `grupo_dfc` (`OPERACIONAL`/`INVESTIMENTO`/`FINANCIAMENTO`),
`inclui_fluxo_caixa` e `sinal` — a classificação padrão de DFC, modelada desde 2026-07-31 e **sem
nenhum consumidor** até aqui (era o último item do estudo de BI ainda parado).

## Parte 1 — fechar o buraco do saldo (pré-requisito, não opcional)

Um fluxo de caixa promete `saldo inicial + entradas − saídas = saldo final`, batendo com o
dinheiro real. Hoje essa conta **não fecha**, e a causa é estrutural:

| Evento | Onde é gravado hoje |
|---|---|
| Venda à vista / recebimento de crediário | `caixa_detalhe` ✅ |
| Movimento bancário lançado à mão | `conta_corrente_movimento` ✅ |
| **Baixa de conta a pagar** | **só `contas_pagar.data_pagamento`** ❌ |

As saídas de dinheiro não passam por caixa nem por banco. Montar o DFC pelas tabelas de dinheiro
daria um relatório quase sem saídas; montar por lançamento daria um relatório que não bate com o
extrato — e fluxo de caixa que não bate com o extrato o lojista abandona na segunda semana.

**Mudança:** ao registrar o pagamento de uma conta a pagar, o operador passa a informar **de onde
saiu o dinheiro**, e o sistema grava o movimento correspondente na mesma transação:

- **Conta corrente** → `conta_corrente_movimento` (`credito_debito = 'D'`, `id_plano_contas` da
  própria conta a pagar, `data_movimento = data_pagamento`, `valor = valor_pago`).
- **Caixa** → `caixa_detalhe` (`tipo_operacao = 'DEBITO_CAIXA'`, `credito_debito = 'D'`,
  `id_plano_contas` da conta, no caixa aberto do usuário). Exige caixa aberto, mesma convenção de
  PDV/Recebimento de Crediário (popup `AberturaCaixaModal` quando não houver).

**Rastreabilidade e desfazer:** as duas tabelas ganham `id_conta_pagar` (nullable, **sem FK — de
propósito**: a conta a pagar pode ser excluída e isso não deve travar por causa de um movimento de
dinheiro já realizado; ver `db/migration/V025__financeiro_caixa_crediario.sql:162`,
`db/migration/V028__financeiro_conta_corrente.sql:90` e `db/migration/README.md:57`). É o vínculo
que permite (a) não duplicar se a baixa for reeditada e (b) **apagar o movimento** quando a baixa
é desfeita (limpar `data_pagamento`). Sem ele, desfazer uma baixa deixaria dinheiro fantasma
saindo do caixa.

**[revisto na implementação] Conta já paga antes da mudança continua editável.** A validação
"informou pagamento, tem de informar a origem" vale só para **baixa nova**. Sem essa exceção,
qualquer edição de uma conta paga em julho (mudar uma observação, corrigir a nota fiscal) devolveria
400 para sempre, sem saída — a conta não tem movimento vinculado e nunca teria. A regra final está
em `ContaPagarService.sincronizarMovimentoNaEdicao`: tirou o pagamento → apaga o movimento;
informou origem → regrava; baixa nova sem origem → 400; conta já paga sem mexer no pagamento → não
toca em nada. Descoberto por dois testes existentes quebrando.

**Dado histórico:** contas pagas **antes** desta mudança não têm movimento vinculado e, portanto,
não aparecem no DFC realizado. É consciente — inventar o movimento retroativo exigiria adivinhar
de onde o dinheiro saiu. Em dev isso é irrelevante (volume mínimo); se um dia houver tenant real
com histórico, o caminho é uma rotina de regularização, fora do escopo daqui.

**Efeito na DRE (verificado):** a regra da DRE em regime de caixa — despesa só de `contas_pagar`,
`conta_corrente_movimento` só para contas de receita — **continua correta e sem dupla contagem**,
porque a DRE nunca soma `caixa_detalhe`/`conta_corrente_movimento` no lado da despesa. A questão
aberta nº 1 da DRE (`docs/telas/relatorio-dre.md`) fica **resolvida na origem**: o pagamento passa
a ter um lugar único e canônico.

## Parte 2 — a tela, com duas abas

Uma tela só (`/fluxo-caixa`), duas abas compartilhando filtros e saldo inicial — escolha do dono
do produto, para não criar duas telas parecidas.

### Aba "Realizado" (o que aconteceu)

**Método direto, por atividade** — o formato que o contador reconhece e que o `grupo_dfc` já
suporta:

```
  SALDO INICIAL DO PERÍODO            (todo movimento anterior à data inicial)
+ ATIVIDADES OPERACIONAIS             (vendas recebidas, fornecedores, despesas do dia a dia)
+ ATIVIDADES DE INVESTIMENTO          (compra/venda de bem, reforma)
+ ATIVIDADES DE FINANCIAMENTO         (empréstimo, aporte, distribuição de lucro)
= SALDO FINAL DO PERÍODO
```

- **Fonte: só movimento de dinheiro** (`caixa_detalhe` + `conta_corrente_movimento`) — é o que
  garante a identidade com o saldo real. Depois da Parte 1, as saídas estão lá.
- **[revisto na implementação] A abertura de caixa conta como entrada operacional.**
  `caixa_mestre.saldo_inicial` é dinheiro que entra na gaveta **sem gerar linha em
  `caixa_detalhe`**. O saldo acumulado sempre o considerou, mas faltava a contrapartida dentro do
  período: o teste acusou `saldoFinal = −150,00` onde o dinheiro real era `850,00`, justamente no
  período em que o caixa foi aberto. Agora entra como a linha "Abertura de caixa (saldo inicial)",
  e a conciliação fecha em zero. Sem isso, a promessa central do relatório (bater com o saldo
  real) quebrava exatamente no caso mais comum — o primeiro dia de uso.
- **Classificação:** por `id_plano_contas` → `grupo_dfc` → `sinal`. Quando o movimento não tem
  plano de contas — caso do PDV e do Recebimento de Crediário, que gravam `caixa_detalhe` sem
  `id_plano_contas` —, **o fallback é por `tipo_operacao`**: `RECEBIMENTO_VENDA` e
  `RECEBIMENTO_PARCELA_CREDIARIO` são entrada operacional; `TROCO` é ajuste da própria venda.
- **Conciliação obrigatória na tela:** o saldo final calculado é confrontado com o saldo real de
  hoje (soma de caixa + conta corrente). Bateu, mostra confirmação discreta; não bateu, mostra a
  diferença — é o que dá confiança no número, e é a primeira coisa que o lojista confere.

### Aba "Projeção" (o que vem)

- **Saldo de partida:** saldo real de hoje (caixa + conta corrente).
- **Entradas previstas:** `contas_receber` em aberto (`data_recebimento IS NULL`) por
  `data_vencimento`.
- **Saídas previstas:** `contas_pagar` em aberto (`data_pagamento IS NULL`) por `data_vencimento`.
- **Agrupamento:** dia, semana ou mês (seletor), com **saldo acumulado** linha a linha.
- **Vencidos entram no primeiro balde**, marcados como "em atraso" — jogar vencido para a data
  original faria o saldo projetado mentir sobre o presente.
- **Alerta de saldo negativo:** a primeira data em que o acumulado fica negativo é destacada em
  `--danger`, com um resumo no topo ("saldo fica negativo em 20/09, faltam R$ 1.234,00"). É a
  informação que justifica a aba existir.
- **Non-goal explícito:** projeção não inventa venda futura — só usa compromissos já registrados.
  Previsão de faturamento é outra feature (e outro tipo de erro).

## Filtros (compartilhados pelas duas abas)

| Filtro | Componente | Regra |
|---|---|---|
| Período | datas mascaradas `dd/mm/aaaa` | obrigatório; padrão = mês corrente (Realizado) / próximos 90 dias (Projeção) |
| Empresa | `EmpresaMultiSelect` | ADMIN todas; OPERADOR só as suas |
| Origem do dinheiro | select | Todas · Só caixa · Só conta corrente |
| Agrupamento (Projeção) | select | Dia · Semana · Mês |

## Impacto no contrato de API

```
GET  /api/v1/relatorios/fluxo-caixa/realizado?dataInicial&dataFinal&idsEmpresa&origem
GET  /api/v1/relatorios/fluxo-caixa/projecao?dataInicial&dataFinal&idsEmpresa&agrupamento
PUT  /api/v1/contas-pagar/{id}            (existente) ganha origemPagamento + idContaCorrente
```

## Impacto no banco

- `conta_corrente_movimento.id_conta_pagar` — coluna nova, nullable, **sem FK** (V028:90).
- `caixa_detalhe.id_conta_pagar` — coluna nova, nullable, **sem FK** (V025:162).
- A ausência de FK nas duas é deliberada (`db/migration/README.md:57`): excluir a conta a pagar
  não pode travar por causa de um movimento de dinheiro já realizado.
- Ambas dentro das migrations existentes (banco em construção), aplicadas no dev com
  `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` + `flyway repair`.
- **Nenhuma tabela nova.** Saldo é sempre derivado — `conta_corrente` não tem coluna de saldo (é a
  soma dos movimentos) e `caixa_mestre.saldo_inicial` é por sessão de caixa.

## Critérios de aceitação (viram testes)

- Dada uma conta a pagar baixada com origem "conta corrente", quando consulto o DFC realizado do
  dia, então a saída aparece em atividades operacionais e o saldo final cai no mesmo valor.
- Dada a mesma baixa desfeita, então o movimento de conta corrente é apagado e o saldo volta.
- Dada uma baixa com origem "caixa" sem caixa aberto, então a API rejeita com 400.
- Dada uma venda à vista de R$ 100, quando consulto o DFC realizado, então entra como entrada
  operacional mesmo sem `id_plano_contas` (fallback por `tipo_operacao`).
- Dado um período sem movimento, então saldo inicial = saldo final.
- Dadas duas parcelas a receber (uma vencida, uma para o mês que vem), quando consulto a projeção
  agrupada por mês, então a vencida aparece no primeiro balde marcada como em atraso.
- Dado saldo atual de R$ 100 e uma conta a pagar de R$ 300 vencendo em 20/09, então a projeção
  aponta saldo negativo em 20/09 de −R$ 200.
- Dado outro tenant com movimento no mesmo período, então nenhum valor dele aparece (isolamento).

## Ajuda da tela (R22 / §3.7.1) — obrigatório

`chave_tela`: `relatorios.fluxocaixa`. Precisa explicar a diferença entre **lucro e caixa** (a DRE
pode dar lucro no mês em que falta dinheiro, por causa do crediário), o que significa cada
atividade, e que a projeção só considera compromissos já lançados — não prevê vendas futuras.

## Impacto nas integrações

Nenhum na leitura (as duas abas só consultam). **Mas a Parte 1 tem efeito colateral fora desta
tela:** a baixa de conta a pagar passou a escrever em `caixa_detalhe`/`conta_corrente_movimento`,
então qualquer tela que leia essas tabelas — Fechamento de Caixa, Movimentação de Conta Corrente,
DRE em regime de caixa — passa a enxergar as saídas de dinheiro que antes não existiam ali. Isso é
o efeito desejado, mas quem for mexer em uma dessas telas precisa saber que a origem do dado mudou.

## Métrica de sucesso

O lojista abre a aba **Projeção** antes de decidir uma compra grande ou um parcelamento, e a
conciliação do **Realizado** fecha em zero no dia a dia — é ela que decide se o relatório é
confiável. Conciliação com diferença recorrente é sinal de pagamento lançado em dois lugares, não
de erro do relatório.

## Non-goals desta versão

- **Método indireto** (partir do lucro e ajustar) — é linguagem de contador, não de lojista.
- **Previsão de vendas futuras** por sazonalidade/média.
- **Conciliação bancária** (importar OFX/extrato e casar lançamentos) — feature própria, grande.
- **Cenários** ("e se eu atrasar esse pagamento?").

## Arquivos

| Arquivo | Papel |
|---|---|
| `db/migration/V025__financeiro_caixa_crediario.sql` | `caixa_detalhe.id_conta_pagar` (coluna + índice) |
| `db/migration/V028__financeiro_conta_corrente.sql` | `conta_corrente_movimento.id_conta_pagar` |
| `api/.../financeiro/contaspagar/ContaPagarService.java` | baixa gera/apaga o movimento; regra da conta já paga |
| `api/.../financeiro/fluxocaixa/` | `FluxoCaixaDtos`/`Service`/`Controller` (realizado + projeção) |
| `api/src/test/.../FluxoCaixaCrudTest.java` | 5 testes (saída, período vazio, alerta negativo, vencido, isolamento) |
| `api/src/test/.../ContaPagarCrudTest.java` | +3 testes do ciclo baixa → movimento → desfazer |
| `web/src/lib/fluxoCaixa.ts`, `fluxoCaixaCaptura.ts` | tipos/chamadas e PDF (A4 retrato) |
| `web/src/pages/relatorios/FluxoCaixa.tsx` | tela com as duas abas |
| `web/src/pages/financeiro/contaspagar/ContasPagarForm.tsx` | campo "De onde saiu o dinheiro" |
| `web/src/components/AjudaDaTela.tsx` | ajuda de `relatorios.fluxocaixa` e da tela de Contas a Pagar |
| `web/src/styles.css` | `.abas-fluxo` / `.aba-fluxo` |

## Questões abertas

1. ~~Ao baixar pelo **caixa**, usar o caixa aberto do usuário (simples) ou deixar escolher a sessão
   de caixa? Proposta: usar o aberto, como PDV e Recebimento fazem.~~ — **decidido e implementado**
   pela proposta: a baixa usa o **caixa aberto do usuário/empresa/dia**
   (`ContaPagarService.java:191-196`), sem seletor de sessão, igual a PDV e Recebimento de
   Crediário — e rejeita com 400 quando não há caixa aberto (o front abre o `AberturaCaixaModal`).
2. Contas a pagar **já baixadas** no banco de dev ficam fora do realizado (sem movimento). Aceito
   para dev; confirmar que não vira problema quando houver tenant real.
