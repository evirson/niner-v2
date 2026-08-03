/**
 * Relatório de Estoque (2026-08-04) — uma tela só, 3 modelos alternativos escolhidos por
 * {@code modelo} (mesmo padrão do {@code Totalizador} do Relatório de Vendas: um campo
 * discriminador na resposta + listas alternativas, só uma populada por vez):
 * <ul>
 *   <li><b>INVENTARIO</b> — uma linha por produto, quantidade somada de TODAS as variações e de
 *       TODAS as empresas selecionadas num único total, mais custo unitário ({@code
 *       produto.preco_custo}, dado tenant-wide) e custo total ({@code qtdTotal × custoUnitario}).
 *       Totaliza quantidade e custo no rodapé.</li>
 *   <li><b>SINTETICO</b> — uma linha por produto (soma as variações), mas com a quantidade
 *       aberta por empresa (uma coluna por empresa selecionada) mais uma coluna de total. Cada
 *       coluna de quantidade é totalizada no rodapé.</li>
 *   <li><b>ANALITICO</b> — uma linha por VARIAÇÃO (não por produto), com as mesmas colunas de
 *       empresa do Sintético mais Variação de Linha/Coluna. Sem totalização (não pedido).</li>
 * </ul>
 *
 * <p><b>Colunas dinâmicas por empresa</b> (Sintético/Analítico): a resposta traz {@code
 * colunasEmpresa} (id + nome, numa ordem fixa) e cada linha traz {@code qtdPorEmpresa} como lista
 * NA MESMA ORDEM (posicional, não por chave/mapa) — o front usa o índice da coluna pra montar o
 * `<th>`/`<td>` correspondente. Essa lista de colunas é resolvida ANTES da consulta principal
 * (não inferida dos dados): ADMIN pode restringir a qualquer subconjunto de empresas do tenant
 * (nenhuma marcada = todas as ATIVAS); OPERADOR sempre só a própria empresa (claim {@code eid}),
 * sem seletor — mesmo padrão de {@code RelatorioContasReceberService.resolverIdsEmpresa}, mas
 * aqui vira a própria DEFINIÇÃO das colunas, não só um filtro de linha (uma empresa sem nenhuma
 * movimentação em lugar nenhum ainda precisa aparecer como coluna zerada).
 *
 * <p>Filtros comuns aos 3 modelos: Empresas (ver acima), Marca (multi-seleção, valores exatos de
 * {@code produto.marca}), Categorias (multi-seleção, {@code cfg_categoria_produto} via EXISTS em
 * {@code produto_categoria}), Situação do Produto (ATIVOS padrão / INATIVOS / TODOS, mesmo
 * critério de {@code ProdutoService.listar}) e Tipo de Quantidade (TODOS padrão / DIFERENTE_DE_
 * ZERO / ZERADA) — aplicado sobre a quantidade TOTAL já agregada de cada linha (não por empresa
 * individual), depois da soma. Ordenação sempre por descrição do produto (pedido explícito) — a
 * consulta SQL já sai ordenada assim e o agrupamento em Java (LinkedHashMap) preserva essa ordem
 * sem precisar reordenar depois; a tela ainda permite reordenar clicando nas colunas (client-side,
 * mesmo padrão de Diferenças de Estoque), mas o carregamento inicial já respeita a regra.
 */
package com.vetor.niner.estoque.relatorioestoque;
