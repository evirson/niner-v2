/**
 * Relatório de Movimentação de Produtos / Kardex (2026-08-04, docs/telas/relatorio-movimentacao-
 * produtos.md) — ledger transacional do estoque a partir de {@code produto_movimento_mestre}/
 * {@code produto_movimento_detalhe} (§3.3.4), diferente do Relatório de Estoque (que é uma
 * fotografia do saldo atual). 3 modelos alternativos (mesmo padrão do {@code Totalizador} do
 * Relatório de Vendas/Estoque — campo discriminador na resposta + objetos/listas alternativos):
 * <ul>
 *   <li><b>ANALITICO</b> — uma linha por movimento ({@code produto_movimento_detalhe}), com
 *       {@code documento} contextualizado por tipo (nº venda/transferência/devolução, fornecedor
 *       + NF pra compra, texto livre {@code origem} pros demais).</li>
 *   <li><b>KARDEX</b> — uma variação + uma empresa, saldo corrido calculado em Java (o schema não
 *       guarda saldo por linha de propósito, P3). Soma TODOS os tipos de movimento sem exceção —
 *       é o único jeito de bater com {@code produto_estoque.qtd_estoque} de verdade, já que a
 *       trigger {@code fn_atualiza_estoque_movimento} (V019) também não distingue tipo, só
 *       {@code credito_debito}. Traz saldo inicial (soma de tudo antes do período) e final.</li>
 *   <li><b>SINTETICO</b> — totais por tipo de movimento (quantidade e valor de entrada/saída).</li>
 * </ul>
 *
 * <p><b>Tipos "não físicos":</b> {@code RESERVA}/{@code LIBERACAO_RESERVA} não tiram/põem produto
 * físico do estoque (reserva de saldo — {@code produto_estoque.reservado}, coluna separada de
 * {@code qtd_estoque} — não uma movimentação real). {@code movimentoFisico = false} nessas duas
 * linhas; os KPIs "Entrada/Saída Física" (ANALITICO/SINTETICO) excluem esses tipos, mas o Kardex
 * inclui igual a qualquer outro (ver acima).
 *
 * <p><b>Gap conhecido, aceito de propósito:</b> {@code COMPRA} (entrada de mercadoria) e
 * {@code RESERVA}/{@code LIBERACAO_RESERVA} (integração de pedidos de canal) ainda não têm
 * nenhuma tela que grave esses tipos — o relatório já nasce preparado (filtro de tipo lista os 8
 * valores do ENUM, {@code montarDocumento} já sabe formatar fornecedor+NF pra Compra), só não vai
 * produzir linha nenhuma desses tipos até essas telas existirem.
 *
 * <p>Valorização sempre por CUSTO ({@code produto_movimento_detalhe.preco_custo}), nunca por
 * venda — diferente dos relatórios de Vendas/Comissões, aqui é contábil/Kardex e
 * {@code preco_venda} não faz sentido pra Transferência/Ajuste/Compra.
 *
 * <p><b>Gap descoberto pelos testes e corrigido no mesmo dia (2026-08-04):</b> os 5 services que
 * gravam o ledger (Pdv/Devolução/Cancelamento/Transferência/Balanço) não preenchiam
 * {@code produto_movimento_detalhe.preco_custo} — ficava sempre no DEFAULT 0 do schema. Corrigido
 * na raiz: cada um passou a ler {@code produto.preco_custo} (Cancelamento lê o custo gravado na
 * própria VENDA original, não uma nova leitura de produto — reversão exata daquela venda, preço
 * pode ter mudado desde então) e gravar no INSERT. O ANALITICO mantém
 * {@code COALESCE(NULLIF(pmd.preco_custo, 0), p.preco_custo)} só como fallback para linhas
 * antigas, gravadas antes da correção (custo ATUAL do cadastro como aproximação, já que não é
 * possível reconstruir o custo histórico de um movimento que nunca o gravou).
 *
 * <p><b>Top Ajustes Negativos</b> (gráfico): {@code AJUSTE} com {@code credito_debito = 'D'}
 * ranqueado por produto — indicador de quebra/perda/furto (shrinkage), o KPI de maior valor
 * prático que nenhum outro relatório do sistema isola.
 */
package com.vetor.niner.estoque.relatoriomovimentacao;
