/**
 * Staff da plataforma (Vetor) — a <b>segunda população de usuário</b> do produto (R18, ADR-009),
 * separada dos usuários do lojista em {@code identidade.usuario}.
 *
 * <p>O corte é sustentado por três coisas ao mesmo tempo: tabela própria
 * ({@code plataforma.staff}), {@code aud=plataforma} no token (decoder distinto por superfície) e
 * <b>ausência do claim {@code tid}</b> — sem ele não há {@code TenantContext}, então token de
 * staff não entra no RLS de nenhum lojista por acidente (P8/P9).
 *
 * <p>Acesso de staff a dado de tenant, quando existir, é por <b>impersonação auditada</b>
 * ({@code plataforma.impersonacao_log}, R21/P3) — nunca por leitura direta.
 */
package com.vetor.niner.plataforma.staff;
