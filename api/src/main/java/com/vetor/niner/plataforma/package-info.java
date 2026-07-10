/**
 * Módulo <b>plataforma</b> — Plano de Controle (control-plane) do SaaS Niner (P9).
 *
 * <p>Negócio da Vetor: tenants, planos, assinaturas, faturas, pagamentos, uso e
 * staff. É o <b>único</b> módulo que enxerga cross-tenant; suas tabelas são
 * <b>globais</b> e ficam fora do RLS de tenant (schema {@code plataforma}, migrations
 * V001–V012). Não confundir com o financeiro do lojista. Ver spec §3.3.11.
 */
package com.vetor.niner.plataforma;
