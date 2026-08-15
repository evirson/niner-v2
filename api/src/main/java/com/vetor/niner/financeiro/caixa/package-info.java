/**
 * Caixa da loja — abertura, fechamento "às cegas" e reabertura de um {@code caixa_mestre}
 * (um por usuário/empresa/dia), mais o guard que protege o caixa já fechado.
 *
 * <p><b>Por que este pacote é transversal.</b> Ele não tem tela "principal" própria: quase todo o
 * resto do financeiro depende dele. PDV, Recebimento de Crediário e a baixa de conta a pagar em
 * dinheiro só efetivam com caixa aberto ({@code CaixaService.idCaixaAbertoObrigatorio}); estorno
 * de crediário e exclusão/reabertura de conta a pagar só desfazem dinheiro se o caixa ainda
 * estiver aberto ({@code CaixaService.exigirCaixaAbertoParaDesfazer}).
 *
 * <p><b>Abertura</b> (2026-07-30) — passo explícito, com saldo inicial e tipo de carteira. O
 * saldo inicial só pode ser em "Dinheiro": cartão/PIX/crediário não têm saldo inicial de verdade,
 * só recebem movimento durante o dia. Antes desta data o caixa era aberto em silêncio, com saldo
 * zero, pelo próprio Recebimento de Crediário.
 *
 * <p><b>Fechamento "às cegas"</b> (2026-07-30) — o operador informa quanto contou de cada carteira
 * <i>sem ver o esperado</i>; o caixa só fecha se todas baterem, e a conferência é gravada em
 * {@code caixa_fechamento_conferencia}. Divergência não fecha nada: devolve a diferença por
 * carteira, com drill-down lançamento a lançamento.
 *
 * <p><b>Reabertura</b> (2026-08-14) — <b>ADMIN-only e com motivo obrigatório</b>. Limpa o estado
 * de fechamento, <b>apaga</b> as linhas de {@code caixa_fechamento_conferencia} (foram calculadas
 * sobre um estado que vai mudar) e <b>acrescenta</b> — nunca sobrescreve — o rastro em
 * {@code caixa_mestre.observacoes}: {@code REABERTO EM dd/mm/aaaa hh:mm POR USUARIO <id>: <MOTIVO>}.
 * Coluna existente em vez de schema novo, de propósito (P3 sem migration). Recusa se o mesmo
 * operador já tiver outro caixa aberto — senão existiriam dois caixas abertos para a mesma pessoa
 * e o PDV não saberia em qual lançar.
 *
 * <p><b>O guard que dá sentido à reabertura.</b> {@code exigirCaixaAbertoParaDesfazer(VinculoCaixa, id)}
 * recusa (409) qualquer operação que fosse apagar lançamento de um caixa já fechado — sem ele, a
 * conferência gravada passaria a afirmar um total que não existe mais, <i>sem nenhum aviso</i>. O
 * SQL de cada vínculo é constante (nunca vem do cliente) e traz o filtro de {@code id_tenant}
 * escrito no texto da query, não só na policy de RLS (P8). Rotina nova que apague
 * {@code caixa_detalhe} deve acrescentar um valor ao enum, nunca fazer o DELETE direto.
 *
 * @see com.vetor.niner.financeiro.recebimentocrediario
 * @see com.vetor.niner.financeiro.contaspagar
 */
package com.vetor.niner.financeiro.caixa;
