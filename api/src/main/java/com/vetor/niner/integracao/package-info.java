/**
 * Módulo <b>integracao</b> — outbox + workers de sincronização com marketplaces
 * (Mercado Livre, Shopee…). Toda integração é async e idempotente (P2), sobre Postgres
 * (outbox + {@code SELECT … FOR UPDATE SKIP LOCKED}, sem broker — P6). Caminhos sem
 * requisição estabelecem o {@code TenantContext} a partir de {@code evento.id_tenant}
 * (P8). Ver spec §3.1/§3.3.6. Sub-pacotes por marketplace entram nas fases seguintes.
 */
package com.vetor.niner.integracao;
