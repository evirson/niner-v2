/**
 * Cadastro de plano de contas (spec §3.3.7, tabela {@code cfg_plano_contas} de V016) —
 * terceira tela de domínio do módulo <b>cadastros</b>, no mesmo padrão de
 * {@code cadastros.cliente}/{@code cadastros.funcionario}, com duas particularidades
 * estruturais herdadas do schema:
 *
 * <ul>
 *   <li>A PK é a <b>chave de negócio</b> {@code (id_tenant, id_plano_contas)} — o código
 *       contábil {@code text} (ex.: "3.1.001") digitado pelo usuário, sem surrogate
 *       {@code integer}. O código não é editável depois de criado (é PK e é referenciado
 *       por {@code fornecedor}/{@code contas_pagar}).</li>
 *   <li>Não existe coluna {@code ativo} — a exclusão não tem fallback de inativar: com
 *       vínculo, responde 409 e o registro permanece.</li>
 * </ul>
 *
 * <p>Dados sujeitos ao RLS de tenant (V016/V024, P8).
 */
package com.vetor.niner.cadastros.planocontas;
