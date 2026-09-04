# Relatório de Movimentação de Produtos (Kardex)

`relatorios.movimentacaoprodutos`, 5ª tela do grupo Relatórios. Qualquer papel (só consulta).
Rota `/relatorio-movimentacao-produtos`.

## Objetivo

Ledger transacional do estoque — "o que aconteceu", diferente do Relatório de Estoque (que é uma
fotografia do saldo atual). Fonte: `produto_movimento_mestre`/`produto_movimento_detalhe`
(§3.3.4), os mesmos **9** tipos do ENUM `tipo_movimento` (`COMPRA`, `TRANSFERENCIA`,
`DEVOLUCAO`, `AJUSTE`, `VENDA`, `RESERVA`, `LIBERACAO_RESERVA`, `CANCELAMENTO`,
`CANCELAMENTO_DEVOLUCAO`) — `db/migration/V013__dominio_tipos_enum.sql:25-27`. O 9º, `CANCELAMENTO_DEVOLUCAO`,
nasceu em 2026-08-10 com o Cancelamento de Devolução de Produtos: tem valor próprio (não
reaproveita `CANCELAMENTO`) justamente para o Kardex distinguir qual operação está sendo
revertida.

~~`COMPRA` e~~ `RESERVA`/`LIBERACAO_RESERVA` ainda não têm nenhuma tela que grave esses tipos
(integração de pedidos de canal fica pra depois) — o relatório já nasce preparado pra eles (filtro
de tipo já lista os 9, colunas já contextualizam cada um), só não vão produzir linha nenhuma até
essas telas existirem. **`COMPRA` saiu dessa lista em 2026-08-11**: a tela **Entrada de Produtos
por Compra** (`docs/telas/entrada-mercadoria.md`) grava o tipo
(`EntradaMercadoriaService.java:131`), então o relatório mostra dado real de compra.

## Modelos (mesmo padrão do Relatório de Vendas/Estoque — 1 seletor, não 3 rotas)

- **Analítico** — uma linha por movimento (`produto_movimento_detalhe`), a visão de exploração
  livre do período.
- **Kardex por Produto** — escolhe UMA variação + UMA empresa (estoque é por empresa) e mostra a
  ficha cronológica com saldo corrido calculado on-the-fly (o schema não guarda saldo por linha
  de propósito, P3 — corrigível). Traz Saldo Inicial (soma de tudo antes do período) e Saldo
  Final. Soma **todos** os tipos sem exceção — é o único jeito de bater com
  `produto_estoque.qtd_estoque` de verdade, já que a trigger `fn_atualiza_estoque_movimento`
  também não distingue tipo, só `credito_debito`.
- **Sintético** — totais agrupados por tipo de movimento (quantidade e valor de entrada/saída),
  visão gerencial rápida.

## Filtros (popup, mesmo padrão dos demais — abre sozinho, obrigatório antes da 1ª geração)

Comuns a Analítico/Sintético: Período (obrigatório, máx. ~400 dias), Empresas (ADMIN
multi-select / OPERADOR travado na própria), Tipo de Movimento (multi-select, vazio = todos),
Marca, Categorias (multi-select, reusa `MultiSelectGenerico`).

Kardex: Período + Empresa (single, obrigatória) + Produto/Variação (busca por descrição/SKU via
popup próprio, `PesquisaVariacaoModal`, mesmo padrão de `PesquisaVendedorModal`/
`PesquisaClienteModal` do PDV).

## KPIs e gráficos (Analítico/Sintético — Kardex não tem, tem seu próprio cabeçalho)

- **Entrada/Saída Física do Período** (quantidade e valor a custo) — exclui `RESERVA`/
  `LIBERACAO_RESERVA` de propósito (não são movimentação física real, são reserva de saldo —
  ver nota acima; entram no Kardex igual a qualquer outro tipo, só ficam fora desta lente).
- Gráfico **Movimentação por Tipo** (barra) — todos os 9 tipos, quantidade ou valor.
- Gráfico **Movimentação por Dia** (linha).
- **Top Ajustes Negativos por Produto** (barra horizontal) — `AJUSTE` com `credito_debito = 'D'`
  ranqueado por produto: é o indicador de quebra/perda/furto (shrinkage), o KPI de maior valor
  prático pra um lojista que nenhum relatório atual isola.

## Colunas do Analítico

Empresa | Data/Hora | Tipo | Produto (SKU + descrição + variação) | Entrada | Saída | Custo
Unitário | Valor Movimentado | Documento (contextual por tipo — "Venda #X" / "Transferência #X"
/ "Devolução #X" / fornecedor+NF pra Compra / texto livre `origem` pros demais) | Operador.

## Valorização: sempre por custo (`preco_custo`)

Diferente dos relatórios de Vendas/Comissões (que valorizam por venda), este é contábil/Kardex —
`preco_venda` não faz sentido pra Transferência/Ajuste/Compra, que não têm venda nenhuma
envolvida.

Os testes deste relatório descobriram, no dia da sua construção (2026-08-04), que nenhum dos 5
services que gravam o ledger (Pdv/Devolução/Cancelamento/Transferência/Balanço) preenchia
`produto_movimento_detalhe.preco_custo` — ficava sempre 0. Corrigido na raiz no mesmo dia: cada
service passou a gravar o custo no INSERT (Cancelamento repete o custo da VENDA original, não
uma nova leitura do cadastro — é a reversão exata daquela venda). O relatório mantém
`COALESCE(NULLIF(pmd.preco_custo, 0), p.preco_custo)` como fallback só pras linhas gravadas antes
da correção.

## Padrões herdados (iguais a todo relatório do grupo)

Popup de filtros obrigatório, PDF por captura visual (`html2canvas`+`jsPDF`), grid com cabeçalho/
rodapé fixos (`.grid-altura-fixa`, mesmo padrão do Relatório de Vendas/Contas a Receber — não o
antigo `.relatorio-corpo-fixo`, que colapsa com KPIs+gráfico acima da grid), ordenação
client-side por coluna, `AjudaDaTela` (R22).

---

## Revisão 2026-08-22 — a pesquisa de variação avisa quando corta (item 33, estendido)

`RelatorioMovimentacaoProdutosService.buscarVariacoes` cortava em **10** e o
`PesquisaVariacaoModal` **não dizia nada** — mesmo defeito do item 33, numa tela que a auditoria não
tinha listado, encontrada ao revisar a documentação no fim do dia.

Limite subiu para **20**, alinhado com os seletores do PDV, e o modal avisa quando o corte acontece.


### Também em 2026-08-22 — o popup de filtros fecha com `navigate(-1)` (item 17)

O botão de fechar usava `navigate('/')`, empilhando histórico. Passou a `navigate(-1)`, alinhado com
`RelatorioDre` e `FluxoCaixa`.

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
