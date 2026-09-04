# Spec: Relatório de Contas a Pagar / Pagas   Status: Aprovada

> Pedido do dono do produto em 2026-08-26. Irmão do Relatório de Contas a Receber
> (`relatorio-contas-receber.md`) — mesmo padrão de tela de relatório
> (`relatorio-vendas.md`): filtros em popup na abertura, KPIs, gráfico e PDF por captura visual.

## Problema

A tela de **Contas a Pagar / Pagas** (`contas-pagar.md`) é um CRUD: serve para lançar a duplicata
e dar baixa. Ela responde *"esta conta está paga?"*, mas não responde as perguntas que o lojista
faz quando senta para planejar:

- **quanto eu devo**, e quanto disso já venceu;
- **para quem** eu devo mais;
- **em que** o dinheiro está indo (aluguel? mercadoria? imposto?);
- quanto eu **paguei** num período.

O Fluxo de Caixa mostra a projeção, e a DRE mostra o resultado — nenhum dos dois lista a
**duplicata** com fornecedor, plano de contas e as três datas ao mesmo tempo.

## Solução proposta

Relatório de leitura sobre `contas_pagar`, uma linha por duplicata, com filtros em popup
obrigatório na abertura, KPIs no topo, um gráfico por plano de contas e outro por fornecedor,
subtotal por empresa e total geral.

## Decisões de escopo

1. ⭐ **"Paga" é `data_pagamento IS NOT NULL`, não `documento_pago`.** As duas colunas existem e
   **podem divergir** — o formulário do CRUD deixa preencher uma sem a outra. O critério aqui é o
   mesmo que o **Fluxo de Caixa** já usa (`FluxoCaixaService`: `cp.data_pagamento IS NULL` = a
   pagar). ⛔ Usar `documento_pago` faria duas telas financeiras darem **respostas opostas sobre o
   mesmo fato** — exatamente o defeito que a V059 documentou entre o DRE-caixa e o Fluxo de Caixa.
   `documento_pago` aparece como **coluna informativa** na grid, e uma divergência entre os dois é
   sinalizada (ver "Grid").

2. ⛔ **Este relatório NÃO respeita `cfg_plano_contas.inclui_dre`** — e isso é deliberado, ao
   contrário da Lucratividade e da DRE. Aqui a pergunta é *"quanto sai do caixa"*, não *"qual foi
   o lucro"*: compra de mercadoria (`3.03.x`), amortização de empréstimo (`5.02.x`) e compra de
   imobilizado (`6.01.x`) **são desembolso real** e precisam aparecer. ⚠️ Quem "consertar" isso
   aplicando o filtro do DRE vai fazer o relatório esconder justamente a maior saída de dinheiro
   da loja. Ver `relatorio-lucratividade.md`, que decidiu o oposto **pelo motivo oposto**.

3. **Três períodos independentes, todos opcionais, mas pelo menos um obrigatório** — lançamento,
   vencimento, pagamento. Combináveis (`AND`). Cada um exige início **e** fim quando preenchido.
   Mesmo desenho do irmão.

4. **Status da conta:** Todas (padrão) / Em Aberto / Pagas. ⚠️ **Não estava na lista de filtros
   pedida** — acrescentado porque sem ele é impossível isolar "a pagar" num relatório que se chama
   *"a Pagar / Pagas"*: as pagas só se isolam pelo período de pagamento, e as abertas não se
   isolam de jeito nenhum. Fácil de remover se o dono do produto não quiser.

5. **Empresa: ADMIN em multi-select** (nenhuma marcada = todas), **OPERADOR fixo na empresa ativa
   da sessão** — padrão de todos os relatórios.

6. **Fornecedor e Plano de Contas: seleção única e opcional.** Plano de contas casa por
   **prefixo** (`id_plano_contas LIKE '3.03.%'`), aproveitando a máscara de largura fixa da V016 —
   escolher a conta sintética `3.03.000` traz toda a subárvore, que é o que o lojista espera ao
   perguntar "quanto gastei com mercadoria".

7. **Valor em aberto = `valor_pagar − valor_pago`.** Pagamento parcial existe (`valor_pago` é
   coluna própria), e tratar a conta como binária esconderia o saldo devedor.

8. **Vencido é medido no fuso da loja**, `(data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date
   < (now() AT TIME ZONE 'America/Sao_Paulo')::date` — nunca `CURRENT_DATE` cru, que no banco
   (UTC) vira o dia seguinte às 21h de Brasília.

9. **Período máximo: 400 dias** por período informado, como no irmão.

## Filtros de pesquisa

| Campo | Tipo | Obrigatório | Comportamento |
|---|---|---|---|
| Período de Lançamento | Data inicial/final | Não* | *Pelo menos um dos três períodos completo |
| Período de Vencimento | Data inicial/final | Não* | Idem |
| Período de Pagamento | Data inicial/final | Não* | Idem |
| Empresas | Multi-select (`EmpresaMultiSelect`) | Não | Só ADMIN. Nenhuma marcada = todas |
| Fornecedor | Combo com busca | Não | Todos quando vazio |
| Plano de Contas | `SeletorPlanoContas` | Não | Casa por **prefixo** (traz a subárvore) |
| Situação | Combo | Não | Todas (padrão) / Em Aberto / Pagas |

### Validações

- Período só com início ou só com fim → *"Informe início e fim do período de
  [lançamento/vencimento/pagamento]."*
- Início > fim → *"Data inicial de [período] não pode ser maior que a final."*
- Nenhum período → *"Informe ao menos um período (lançamento, vencimento ou pagamento)."*
- Período > 400 dias → *"Período de [período] não pode exceder 400 dias."*

## KPIs (topo da tela)

| KPI | O que soma |
|---|---|
| **Total no período** | `valor_pagar` de todas as linhas do filtro |
| **Em aberto** | `valor_pagar − valor_pago` das não pagas |
| **Vencido** | idem, só das com vencimento anterior a hoje (fuso da loja) |
| **A vencer** | idem, vencimento hoje ou futuro |
| **Pago no período** | `valor_pago` das linhas com `data_pagamento` preenchida |

⚠️ **Vencido e A vencer somam "Em aberto", e nunca o Total** — misturá-los num só número faria o
lojista somar duas vezes. Os três aparecem separados, como os três contadores do painel de saúde
dos canais.

## Gráficos

1. **Por plano de contas** — onde o dinheiro está indo. Agrupado pela conta **analítica** que a
   duplicata carrega, ordenado por valor, com as demais somadas em "Outros" a partir da 8ª.
2. **Por fornecedor** — para quem se deve mais. Mesmo corte (top 7 + "Outros").

⚠️ **Os dois somam `valor_pagar`, não o valor em aberto** — a pergunta é "em que/para quem eu
comprometi dinheiro no período", e um gráfico que encolhesse conforme se paga responderia outra
coisa. Está escrito no rótulo de cada gráfico.

## Grid

Colunas: Empresa | Fornecedor | Plano de Contas | NF | Duplicata | Lançamento | Vencimento |
Pagamento | Valor | Valor Pago | Em Aberto | Situação — todas ordenáveis (allowlist no backend).

- **Situação**: `Paga` / `Vencida` / `A vencer`, derivada de `data_pagamento` + vencimento.
- ⚠️ **Divergência sinalizada:** linha com `documento_pago = true` e `data_pagamento` nula (ou o
  contrário) recebe um aviso na coluna Situação. As duas colunas existem e o CRUD deixa
  divergirem; o relatório **mostra** em vez de escolher em silêncio.
- Subtotal por empresa quando há mais de uma no resultado; total geral sempre no rodapé.
- ⚠️ O subtotal é **intercalado na quebra de empresa**, dentro do corpo da tabela — não empilhado no
  rodapé. Com três empresas, três subtotais soltos no fim não dizem a que linhas pertencem. Isso só
  funciona porque `e.razao_social ASC` é a ordenação **primária** no servidor, seja qual for a coluna
  que o usuário clicar: a quebra é sempre contígua. Mesmo desenho do Relatório de Contas a Receber.
- Cabeçalho/rodapé fixos (`.relatorio-corpo-fixo`).

## PDF

Mesmo mecanismo de captura visual do Relatório de Vendas — ver `relatorio-vendas.md`, seção "PDF".
⚠️ Sem `color-mix()` em nada que entre na captura (html2canvas não resolve).

## Contrato de API

```
GET /api/v1/relatorios/contas-pagar
    ?dataLancamentoInicial=&dataLancamentoFinal=
    &dataVencimentoInicial=&dataVencimentoFinal=
    &dataPagamentoInicial=&dataPagamentoFinal=
    &idsEmpresa=1&idsEmpresa=2&idFornecedor=&idPlanoContas=
    &situacao=ABERTA|PAGA&ordenarPor=&direcao=
  → { linhas: [{idContaPagar,idEmpresa,nomeEmpresa,idFornecedor,nomeFornecedor,
                idPlanoContas,descricaoPlanoContas,notaFiscal,numeroDuplicata,
                dataLancamento,dataVencimento,dataPagamento,
                valorPagar,valorPago,valorEmAberto,documentoPago,situacao,divergente}],
      subtotaisPorEmpresa: [{idEmpresa,nomeEmpresa,valorPagar,valorPago,valorEmAberto}],
      totalGeral: {valorPagar,valorPago,valorEmAberto},
      kpis: {totalPeriodo,emAberto,vencido,aVencer,pagoNoPeriodo},
      graficoPorPlanoContas: [{rotulo,valor}],
      graficoPorFornecedor: [{rotulo,valor}] }
```

Sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), qualquer papel. Erros em Problem Details: 400
(período incompleto/inválido/excessivo, nenhum período informado).

## Critérios de aceitação (viram testes)

- Dado nenhum período informado, responde **400**.
- Dado período incompleto (só início), responde **400**.
- Dado período maior que 400 dias, responde **400**.
- Dado filtro por período de pagamento, só contas pagas dentro da janela aparecem.
- ⭐ Dado uma conta com `documento_pago = true` mas **sem** `data_pagamento`, ela conta como **em
  aberto** (mesmo critério do Fluxo de Caixa) **e** é marcada como divergente.
- Dado pagamento parcial (`valor_pago` < `valor_pagar`), o valor em aberto é a diferença.
- ⭐ Dado uma conta de **compra de mercadoria** (plano `3.03.x`, que a DRE exclui), ela **aparece**
  no relatório — este é um relatório de desembolso, não de resultado.
- Dado filtro de plano de contas sintético, as contas da subárvore aparecem (casamento por prefixo).
- Dado vencimento no passado e conta não paga, a situação é **Vencida** e ela soma no KPI Vencido.
- Dado dados de outro tenant, eles não aparecem (RLS).
- Dado ordenação por valor, ASC e DESC respeitam a direção.

## Ajuda da tela (R22 / §3.7.1) — obrigatório

Chave `relatorio.contas-pagar.tela`, com objetivo, passos e erros comuns — incluindo a explicação
de por que compra de mercadoria aparece aqui e não na Lucratividade.

## Impacto no banco

**Nenhum.** Relatório de leitura sobre `contas_pagar`, `fornecedor`, `cfg_plano_contas` e
`empresa` — todas já existentes. Sem migration.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Não dá baixa** — pagar continua sendo editar a conta na tela de Contas a Pagar / Pagas.
- **Não projeta** — projeção de saída é o Fluxo de Caixa.
- **Não calcula resultado** — margem e lucro são DRE e Lucratividade.
- **Não mostra juros/multa por atraso** — não existem no modelo de `contas_pagar` hoje.

⚠️ *Non-goals apodrecem* (ver a memória de mesmo nome): conferir no código antes de repetir.

## Questões abertas

✅ **Nenhuma** — as duas foram confirmadas pelo dono do produto em 2026-08-26:

1. ✅ O filtro de **Situação** (Todas / Em Aberto / Pagas), acrescentado sem ter sido pedido
   (decisão 4), foi **mantido**. Justificativa aceita: é a pergunta mais comum de quem abre um
   relatório de contas a pagar, e sem ele o lojista teria de ler a coluna linha a linha.
2. ✅ O corte dos gráficos em **top 7 + "Outros"** foi **mantido**, igual aos demais relatórios do
   sistema. ⚠️ O "Outros" não é enfeite: sem ele o total do gráfico deixaria de bater com o total do
   relatório, e nada avisaria.

---

## ⚠️ 2026-08-29 (auditoria, 2ª leva) — os 5 KPIs saíam EMPILHADOS, na tela e no PDF

`className="relatorio-kpis"` — classe que **não existe** em `styles.css`. A do projeto é
`relatorio-kpis-grid` (`display: grid; grid-template-columns: repeat(4, 1fr)`), usada pelo Relatório
de Vendas e pelo de Movimentação.

O lojista recebia "Total no período", "Em aberto", "Vencido", "A vencer" e "Pago no período" como
**cinco cartões de largura total, um embaixo do outro**, empurrando a grade para fora da primeira
dobra — enquanto os relatórios irmãos mostram a mesma faixa lado a lado. ⚠️ E como o **PDF é captura
visual**, o arquivo entregue ao contador saía igual: quase uma folha inteira só de KPIs.

⭐ Classe CSS inexistente **não dá erro em build, `tsc -b` nem teste** — só aparece abrindo a tela.
Foi achada por script comparando classes usadas × declaradas, junto com as da Sangria de Caixa e da
Minha Conta. ⚠️ São 5 cards numa grade de 4 colunas: o 5º fica sozinho na segunda linha, e vale
conferir no navegador se isso é aceitável ou se a grade precisa de variante.

### ⚠️ E o filtro de fornecedor empurrava o "Gerar Relatório" para fora
A lista de sugestões usava `lista-sugestoes`, que também não existe: com 20 resultados, os botões
empurravam "Plano de Contas", "Situação" e o rodapé para baixo — num modal de altura fixa, o
operador ficava **sem como gerar** até apagar a busca.

---

**Revisão 2026-09-04 — PDF preto no branco e cabeçalho de coluna repetido.**
O mecanismo é comum aos 11 relatórios e está descrito em `docs/telas/relatorio-vendas.md`
(arquivo-padrão de tela de relatório): a captura deixou de reproduzir o **tema claro do
produto** e passou a declarar uma **paleta de impressão própria** (fundo `#ffffff`, texto
`#000000`, cabeçalho de tabela `#f2f2f2`), mantendo coloridas só as cores de série, que são
informação do gráfico. O módulo virou `lib/paletaDeImpressaoParaCaptura.ts`.

**Nesta tela:** o cabeçalho das colunas **se repete** no topo de todas as páginas — a tela tem
uma tabela só, e ela é o corpo do relatório. ⚠️ **Não teve PDF gerado na entrega de 09-04**:
a mudança foi aplicada e verificada por script e por `tsc`, mas só Estoque e Lucratividade
foram exercitados de ponta a ponta.
