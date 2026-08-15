/**
 * Módulo <b>financeiro</b> — crediário, caixa e contas do lojista (Q5/ADR-010/ADR-012,
 * spec §3.3.7). <b>Q5 foi encerrada em 2026-07-31 (V028): nada do financeiro do lojista ficou
 * fora do v1.</b>
 *
 * <p>Subpacotes, todos com {@code package-info} próprio:
 * <ul>
 *   <li>{@link com.vetor.niner.financeiro.caixa} — abertura, fechamento "às cegas" e reabertura;
 *       é o pacote transversal, do qual quase todo o resto depende.</li>
 *   <li>{@link com.vetor.niner.financeiro.recebimentocrediario} — recebimento de parcelas e
 *       estorno do lote.</li>
 *   <li>{@link com.vetor.niner.financeiro.contaspagar} — duplicatas de fornecedor e baixa.</li>
 *   <li>{@code contacorrente} — conta corrente da loja e seus movimentos.</li>
 *   <li>{@code dre} e {@code fluxocaixa} — os dois relatórios gerenciais, ADMIN-only.</li>
 * </ul>
 *
 * <p>Neste pacote raiz mora a tela {@code Tipo de Carteira} — forma de pagamento completa (categoria,
 * prazo/parcelas/taxa administradora, desconto/acréscimo), já semeada por tenant no signup
 * ({@code SignupService}). Absorveu o cadastro de {@code Moeda} em 2026-07-28 (motivo completo
 * no comentário de topo de {@code V025__financeiro_caixa_crediario.sql}): a mesma bandeira
 * podia estar vinculada a mais de uma categoria via {@code moeda_detalhe} (N:N, removida), mas
 * prazo/parcelas/taxa diferem entre categorias — cada combinação categoria×nome agora é sua
 * própria linha.
 *
 * <p>Dados sujeitos ao RLS de tenant (V025/V024, P8).
 */
package com.vetor.niner.financeiro;
