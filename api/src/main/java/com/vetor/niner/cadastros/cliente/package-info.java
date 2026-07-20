/**
 * Módulo <b>cadastros</b> — cadastros auxiliares do lojista referenciados pelo domínio
 * (cliente, fornecedor, funcionário — spec §3.3.9). Primeira tela implementada: cliente
 * (docs/telas/cliente.md), com a categoria de cliente ({@code cfg_categoria_cliente}) como
 * dependência obrigatória (campo NOT NULL sem categoria padrão pré-cadastrada).
 *
 * <p>Dados sujeitos ao RLS de tenant (V016/V024, P8).
 */
package com.vetor.niner.cadastros.cliente;
