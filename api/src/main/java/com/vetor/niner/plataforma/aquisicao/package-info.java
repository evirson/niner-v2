/**
 * Aquisição: funil próprio do site público (ADR-017) — visita, evento de interesse e lead.
 *
 * <p>É control-plane (P9): o visitante existe <b>antes</b> de qualquer tenant, então nada aqui
 * tem {@code id_tenant} no RLS. O elo com a receita é {@code lead.id_tenant}, gravado no signup.
 *
 * <p><b>Escrita anônima de volume.</b> {@code POST /api/publico/eventos} é o único endpoint
 * público que grava em lote; por isso ele não dispara efeito de negócio nenhum, limita o
 * tamanho do lote e descarta o que não reconhece, em silêncio — beacon que devolve erro vira
 * ruído no console do visitante e não conserta nada.
 */
package com.vetor.niner.plataforma.aquisicao;
