/**
 * Transferência de produtos entre empresas do tenant (docs/telas/transferencia-estoque.md) —
 * a empresa de origem é sempre a empresa ativa da sessão (claim {@code eid} do JWT, ver
 * {@code docs/telas/login-empresa.md}); o usuário só escolhe a empresa de destino. Grava dois
 * {@code produto_movimento_mestre} (um por empresa, {@code tipo_movimento = 'TRANSFERENCIA'})
 * com um {@code produto_movimento_detalhe} 'D' (sai da origem) e 'C' (entra no destino) por
 * item, na mesma transação — a trigger {@code fn_atualiza_estoque_movimento} (V019) atualiza
 * {@code produto_estoque} dos dois lados sozinha.
 *
 * <p>Dados sujeitos ao RLS de tenant (V019/V024, P8).
 */
package com.vetor.niner.estoque.transferencia;
