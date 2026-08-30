/**
 * Rotina de Contagem de Estoque (2026-08-04) — balanço físico por leitura de código de barras,
 * sempre escopado à empresa ativa da sessão (claim {@code eid} do JWT), aberto a ADMIN e
 * OPERADOR (mesma decisão de {@code estoque.transferencia}).
 *
 * <p>Quatro operações sobre a mesma tabela {@code produto_balanco} (V019, já existia como
 * placeholder — ganhou a coluna {@code id_movimento} nesta feature):
 * <ul>
 *   <li><b>Contagem</b> — cada leitura de código de barras insere uma linha nova em
 *       {@code produto_balanco} (ledger, não upsert); "quantidade contada" de um produto é a
 *       soma das linhas ativas dele ({@code id_movimento IS NULL}). Corrigível por linha
 *       (ajustar pra um valor exato ou remover) sem afetar os demais produtos contados.</li>
 *   <li><b>Zerar</b> — apaga todas as linhas ativas da empresa (some o trabalho de contagem;
 *       tela pede confirmação com o total antes).</li>
 *   <li><b>Diferenças</b> — compara a soma contada de cada variação com {@code produto_estoque}
 *       da empresa; mostra qualquer diferença, inclusive produto em estoque nunca escaneado
 *       (contagem tratada como 0).</li>
 *   <li><b>Efetivar</b> — grava um {@code produto_movimento_mestre} (tipo {@code AJUSTE}, valor
 *       já existia no enum sem uso) com uma linha de {@code produto_movimento_detalhe} por
 *       produto com diferença (a trigger {@code fn_atualiza_estoque_movimento} materializa em
 *       {@code produto_estoque}); em seguida marca TODAS as linhas ativas do balanço (com ou sem
 *       diferença) com o {@code id_movimento} gerado — é isso que "zera" a contagem sem apagar
 *       fisicamente nada, viabilizando o desfazer.</li>
 * </ul>
 *
 * <p><b>Desfazer última efetivação</b>: encontra o {@code produto_movimento_mestre} tipo
 * {@code AJUSTE} mais recente da empresa que ainda tenha ao menos uma linha de detalhe (ou seja,
 * ainda não desfeito) <b>e que tenha sido produzido por um balanço</b> — isto é, que apareça em
 * {@code produto_balanco.id_movimento}. ⚠️ O tipo {@code AJUSTE} sozinho <b>não</b> identifica o
 * balanço: a Importação de Dados grava o mesmo tipo, e enquanto o filtro era só ele o "desfazer"
 * apagava o estoque recém-importado (corrigido em 2026-08-30). Apaga as linhas de detalhe daquele
 * movimento (a trigger reverte
 * {@code produto_estoque} sozinha, mesmo mecanismo de exclusão de
 * {@link com.vetor.niner.estoque.transferencia.TransferenciaService#excluir}) e libera de volta
 * as linhas de {@code produto_balanco} marcadas com aquele {@code id_movimento}
 * ({@code id_movimento = NULL} de novo) — o balanço volta a ficar exatamente como estava antes
 * da efetivação, pronto pra corrigir e efetivar de novo. Como cada desfazer sempre olha pro
 * "mais recente que ainda tem detalhe", desfazer em sequência naturalmente vai voltando pras
 * efetivações mais antigas uma de cada vez (semântica de undo comum).
 */
package com.vetor.niner.estoque.balanco;
