/**
 * Módulo <b>pedidos</b> — fila unificada de pedidos dos canais. Idempotência por
 * chave natural {@code (canal, id_externo)} (P2). Ver spec §3.3.5.
 * Classes entram com as migrations de domínio (V013+).
 */
package com.vetor.niner.pedidos;
