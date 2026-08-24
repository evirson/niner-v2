# Tela — Relatório de Lucratividade

**Rota:** `/lucratividade` · **Menu:** Relatórios → Lucratividade · **Perfil:** ADMIN-only
**Status:** especificado em 2026-08-25 (pedido do dono do produto), saindo de "Implementações Futuras".

## Para que serve

Responder, num período, **"a loja deu lucro?"** — e mostrar de onde o lucro veio e por onde ele foi
embora. É o relatório que o lojista abre no fim do mês, antes de decidir preço, corte de despesa ou
pró-labore.

⚠️ **Não é a DRE, e não substitui a DRE.** A DRE (`relatorio-dre.md`) é a demonstração gerencial
completa, com regime escolhível, comparação entre períodos e a estrutura contábil de grupos
(deduções, custos variáveis, despesas fixas, depreciação, resultado financeiro, tributo sobre o
lucro). A Lucratividade é **uma página só, sem regime, sem comparação**: venda, custo, lucro bruto,
despesas, lucro líquido. Quem quer a leitura contábil vai na DRE; quem quer saber se sobrou
dinheiro vem aqui.

## Filtros

| Filtro | Obrigatório | Observação |
|---|---|---|
| Empresa | Não | Multisseleção. Vazio = todas as empresas do tenant. |
| Data Inicial | **Sim** | |
| Data Final | **Sim** | |

Popup de filtros ao entrar na tela, como em todo relatório do produto (`relatorio-vendas.md`).
Período máximo de **400 dias**, o mesmo limite da DRE.

### ⚠️ O período tem TRÊS naturezas, e isso é decisão de produto

| Lado | Qual data conta |
|---|---|
| **Crédito** (venda, devolução, custo) | a data da **venda** |
| **Débito lançado** (contas pagas) | a data de **pagamento** do Contas a Pagar |
| **Débito derivado** (comissão, taxa de cartão) | a data da **venda** — é a única que elas têm |

É um **regime misto** — competência de um lado, caixa do outro — e foi pedido assim: o lojista quer
casar *"o que vendi neste mês"* com *"o que saiu da minha conta neste mês"*.

⚠️ **A consequência precisa estar clara para quem lê o relatório:** uma conta de janeiro paga em
fevereiro pesa em **fevereiro**. Um mês em que se acertou muita coisa atrasada aparece pior do que
foi, e o mês em que se atrasou aparece melhor. Isso **não** é defeito: é o que "contas pagas no
período" significa. Quem quiser o resultado pelo fato gerador usa a **DRE em competência** — e a
tela diz isso, com link, em vez de deixar o lojista descobrir sozinho.

## O relatório

### 1. Valor total da venda

```
valor total da venda = vendas do período − devoluções do período
```

- **Vendas**: `venda` **não cancelada**, `data_venda` no período. Valor da linha:
  `qtd × preço de venda + acréscimo − desconto` — o que o cliente efetivamente pagou, o mesmo
  número que sai na papeleta.
- **Devoluções**: movimento `DEVOLUCAO` no período, com `venda_devolucao.cancelada = false`.
  ⚠️ **Devolução cancelada não deduz** — cancelar não apaga as linhas do ledger, só marca a
  devolução e lança o movimento compensatório. É o mesmo cuidado que a auditoria de 2026-08-21
  encontrou faltando na DRE.
- ⚠️ **A devolução é abatida pela data em que ela aconteceu, não pela data da venda de origem.**
  Devolver em março uma venda de fevereiro reduz o total de **março**. O contrário exigiria
  recalcular meses já fechados a cada devolução.

Este número é a **venda líquida** do item 6.

### 2. Custo das mercadorias vendidas (CMV)

```
CMV = custo das vendas do período − custo das devoluções do período
```

Custo é o `preco_custo` **gravado na própria linha da venda** — o custo praticado no dia, não o
custo de hoje. Devolução reverte o custo junto com a receita, senão a margem do mês subiria a cada
devolução.

### 3. Lucro bruto

```
lucro bruto = (1) valor total da venda − (2) CMV
```

### 4. % de lucro bruto

```
% lucro bruto = lucro bruto ÷ valor total da venda × 100
```

Vazio (`—`) quando o valor total da venda é zero — não existe base para o percentual, e imprimir
`0%` afirmaria margem zero onde não houve venda nenhuma.

### 5. Despesas do período, por plano de contas

Uma linha por conta analítica, com três colunas:

| Coluna | Cálculo |
|---|---|
| Valor | conta paga: `SUM(valor pago)`. Derivada: o valor calculado do movimento |
| % sobre a venda | `valor ÷ (1) valor total da venda × 100` |
| % sobre o lucro bruto | `valor ÷ (3) lucro bruto × 100` |

**Duas fontes, marcadas na tela.** As linhas de **conta paga** vêm de `contas_pagar` com
`data_pagamento` no período. As linhas **derivadas** — **comissão sobre vendas** (`3.02.001`) e
**taxa de cartão e PIX** (`3.02.002`) — são calculadas do movimento, porque não existe lançamento
em Contas a Pagar para elas; a tela as marca com **(calculado)**.

⚠️ **Derivada conta pela data da VENDA**, não de pagamento — é a única data que ela tem. Sem a
marca na tela, a tabela misturaria duas bases de data em silêncio.

⚠️ **Zero não vira linha.** A loja típica não comissiona e vende no dinheiro; sem essa regra, ela
veria "Comissões — R$ 0,00" e "Taxas de Cartão — R$ 0,00" todo mês, sugerindo configuração faltando.

⚠️ **Devolução NÃO estorna comissão nem taxa**, igual à DRE: o vendedor vendeu, e a operadora já
cobrou sobre a transação original. Reverter seria regra de negócio nova e divergente da DRE.

O valor da conta paga é
`COALESCE(NULLIF(valor_pago, 0), valor_pagar)` — conta marcada como paga sem o valor preenchido
vale pelo valor original, como já faz a DRE em regime de caixa.

#### ⚠️ Pagamento que NÃO é despesa fica de fora (decisão do dono do produto, 2026-08-25)

Entram apenas contas com `cfg_plano_contas.inclui_dre = true`. Ficam de fora, hoje, 9 contas do
plano padrão:

| Família | Por que não entra |
|---|---|
| `3.03.x` Compra de mercadoria e frete sobre compra | **Já está contada no item 2.** Mercadoria comprada é estoque (ativo); ela vira despesa quando *sai vendida*, e é exatamente isso que o CMV mede. Incluir aqui contaria a mesma mercadoria **duas vezes** e transformaria em prejuízo um mês que deu lucro. |
| `5.02.x` Amortização de principal de empréstimo | Pagar principal troca dívida por dinheiro — não consome resultado. Só o **juro** é despesa, e ele tem conta própria. |
| `6.01.x` Compra de móveis, máquinas e equipamentos | Investimento (imobilizado). Vira despesa aos poucos, pela depreciação. |

⚠️ **É a mesma regra anti-dupla-contagem que a DRE aplica** (`inclui_dre = false` na conta de
compra), e pela mesma razão. Um relatório que some CMV **e** compra de mercadoria está errado de
um jeito que ninguém percebe: os dois números são plausíveis isolados.

⛔ **A regra é DADO, não código.** Quem decide é a marca `inclui_dre` do plano de contas, editável
na tela de Plano de Contas. Conta nova nasce com a marca que o lojista (ou o contador) escolher —
não há lista de códigos escrita no serviço.

### 6. Lucro líquido

```
lucro líquido = (3) lucro bruto − total das despesas do item 5
% sobre a venda bruta   = lucro líquido ÷ vendas do período (ANTES das devoluções) × 100
% sobre a venda líquida = lucro líquido ÷ (1) valor total da venda × 100
```

⚠️ **A diferença entre as duas bases é exatamente a devolução.** "Venda bruta" é tudo o que saiu
pelo caixa no período; "venda líquida" é o que **ficou** vendido. Quando não há devolução no
período, os dois percentuais são iguais — e isso é informação, não redundância: mostra que a
devolução não comeu margem naquele mês.

## Contrato de API

`GET /api/v1/relatorios/lucratividade` — **ADMIN-only** (403 para os demais papéis).

| Parâmetro | Tipo | Obrigatório |
|---|---|---|
| `dataInicial` | `date` (ISO) | sim |
| `dataFinal` | `date` (ISO) | sim |
| `idsEmpresa` | lista de `long` | não (vazio = todas) |

```jsonc
{
  "periodo": { "dataInicial": "2026-08-01", "dataFinal": "2026-08-31" },
  "vendaBruta": 100000.00,        // vendas do período, antes das devoluções
  "devolucoes": 2000.00,
  "vendaLiquida": 98000.00,       // item 1 — o "valor total da venda" do relatório
  "custoMercadoriaVendida": 55000.00,
  "lucroBruto": 43000.00,
  "percentualLucroBruto": 43.88,  // null quando vendaLiquida = 0
  "despesas": [
    { "idPlanoContas": "4.01.001", "descricao": "Aluguel",
      "valor": 6000.00, "derivada": false, "percentualSobreVenda": 6.12, "percentualSobreLucroBruto": 13.95 },
  ],
  "totalDespesas": 19200.00,
  "lucroLiquido": 23800.00,
  "percentualSobreVendaBruta": 23.80,
  "percentualSobreVendaLiquida": 24.29
}
```

Todo percentual é `null` quando a base é zero — nunca `0`. O front imprime `—`.

## Critérios de aceitação (viram testes)

1. **Dado** uma venda de R$ 100,00 com custo R$ 60,00 no período, **quando** gero o relatório,
   **então** o valor total da venda é 100,00, o CMV é 60,00, o lucro bruto é 40,00 e o % de lucro
   bruto é 40,00.
2. **Dado** uma venda de R$ 100,00 (custo 60,00) e uma devolução de R$ 30,00 (custo 18,00) no mesmo
   período, **então** o valor total da venda é 70,00 e o CMV é 42,00 — a devolução abate **os dois**
   lados.
3. **Dado** uma venda **cancelada** no período, **então** ela não entra em nenhum número.
4. **Dado** uma devolução **cancelada** no período, **então** ela não deduz venda nem reverte CMV.
5. **Dado** uma conta paga de R$ 10,00 numa conta com `inclui_dre = true`, **quando** a data de
   pagamento está no período, **então** ela aparece no item 5 e reduz o lucro líquido.
6. ⭐ **Dado** uma compra de mercadoria paga no período (conta `3.03.001`, `inclui_dre = false`),
   **então** ela **não** aparece no item 5 e **não** reduz o lucro líquido — a mercadoria já está
   no CMV.
7. **Dado** uma conta **vencida no período mas paga depois**, **então** ela não entra; e uma conta
   **lançada antes e paga dentro** do período **entra** — o corte é a data de pagamento.
8. **Dado** um período sem venda nenhuma e com contas pagas, **então** todos os percentuais são
   `null` e o lucro líquido é negativo pelo total pago.
9. **Dado** um usuário OPERADOR, **quando** chama o endpoint, **então** recebe **403**.
10. **Dado** dois tenants com dados no mesmo período, **então** cada um vê só os próprios números
    (P8 — filtro `id_tenant` explícito em toda query, não só RLS).
11. **Dado** o filtro de empresa preenchido, **então** venda, devolução e contas pagas respeitam a
    mesma seleção.
12. **Dado** um vendedor com 10% de comissão e uma venda de R$ 100,00, **então** aparece a linha
    derivada `3.02.001` com R$ 10,00, marcada como `derivada: true`, e o lucro líquido cai 10,00.
13. **Dado** uma carteira com 3% de taxa e uma venda de R$ 100,00 nela, **então** aparece a linha
    derivada `3.02.002` com R$ 3,00.
14. ⚠️ **Dado** uma loja sem comissionamento que vende no dinheiro, **então** as duas linhas
    derivadas **não aparecem** — zero não vira linha.

## Ajuda da tela (R22 / §3.7.1) — obrigatório

Chave `relatorios.lucratividade`. Precisa explicar, em linguagem de lojista:

- que o crédito conta pela **data da venda** e o débito pela **data de pagamento**, e o efeito disso;
- que **compra de mercadoria não aparece na lista de contas pagas** porque já está no custo — é a
  dúvida nº 1 esperada, e sem resposta o lojista acha que o relatório "esqueceu" a maior despesa dele;
- a diferença entre % sobre venda bruta e sobre venda líquida.

## Impacto no banco

**Nenhum.** O relatório é consulta pura sobre `venda`, `produto_movimento_mestre/_detalhe`,
`venda_devolucao`, `contas_pagar` e `cfg_plano_contas`. Sem migration, sem coluna nova.

## Non-goals desta versão

- **Margem por produto, categoria ou vendedor.** A descrição antiga do item de menu prometia isso;
  o pedido do dono do produto é o relatório de resultado descrito aqui. Fica para depois.
- **Comparação entre períodos** e **gráfico** — a DRE já compara; aqui a página é uma só.
- **Regime escolhível** — o regime misto é a definição deste relatório. Quem quer escolher usa a DRE.
- **Rateio de despesa entre empresas** — cada conta pertence à empresa em que foi lançada.

## Questões abertas

- ✅ **Resolvida em 2026-08-25:** comissão e taxa de cartão **entram** como despesa, derivadas do
  movimento igual à DRE (decisão do dono do produto). O item 5 deixou de ser "contas pagas" e virou
  "despesas do período", com as linhas derivadas marcadas **(calculado)** na tela.
- ⏭️ **Devolução não estorna comissão nem taxa.** Segue a DRE, e é defensável (o vendedor vendeu; a
  operadora cobrou sobre a transação original). Se o lojista descontar comissão de venda devolvida
  na prática, vira regra nova — e teria de mudar na DRE junto, senão os dois relatórios divergem.

## Ver também

`relatorio-dre.md` (o irmão contábil, com regime e comparação) · `fluxo-caixa.md` (dinheiro, não
resultado) · `contas-pagar.md` (a origem do item 5) · `plano-contas.md` (onde mora `inclui_dre`).
