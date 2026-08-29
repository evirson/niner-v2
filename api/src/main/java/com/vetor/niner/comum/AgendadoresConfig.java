package com.vetor.niner.comum;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga os seis {@code @Scheduled} do produto — e permite <b>desligá-los na suíte de testes</b>.
 *
 * <h2>⛔ O problema que isto resolve (2026-08-29)</h2>
 *
 * <p>{@code @EnableScheduling} vivia na classe da aplicação, então o agendador rodava também nos
 * testes. Como a suíte leva vários minutos e os {@code initialDelay} vão de 15 s a 2 min, os jobs
 * <b>disparam no meio dela</b> — e o efeito é o pior tipo de falha: <b>o teste passa sozinho e
 * reprova na suíte</b>.
 *
 * <p>Medido: {@code FiscalInutilizacaoTest.naoDeixaInutilizarNumeroAindaNaoAlocado} usa
 * {@code verifyNoInteractions(transporte)}, e o {@code FiscalContingenciaDrenoJob} chamava
 * {@code sefazRespondendo} no mock compartilhado. Rodando o arquivo isolado: verde. Na suíte
 * inteira: vermelho, apontando para um job que o teste não menciona.
 *
 * <p>⚠️ E o dreno era o sintoma mais visível, não o mais grave: o {@code OrcamentoVencimentoJob}
 * <b>altera dados</b> (marca orçamento como vencido). Um job que muda linha no meio de um teste
 * produz falha que ninguém consegue explicar lendo o teste.
 *
 * <h2>Por que a chave é positiva e o padrão é LIGADO</h2>
 *
 * <p>{@code matchIfMissing = true}: sem a propriedade — que é o caso em produção e no
 * {@code application.yml} de verdade — os agendadores continuam ligados. Só
 * {@code src/test/resources/application.yml} declara {@code niner.agendadores.ativos: false}.
 * Uma chave que precisasse ser declarada para ligar deixaria produção sem job no dia em que alguém
 * esquecesse a linha, que é a direção errada de errar.
 *
 * <p>⚠️ Desligar o <b>agendador</b> não remove os beans: um teste que queira exercitar um job
 * continua injetando-o e chamando o método (é o que {@code OrcamentoCrudTest} faz). O que some é o
 * disparo automático — a fonte da não-determinação, não a funcionalidade.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "niner.agendadores.ativos", havingValue = "true", matchIfMissing = true)
public class AgendadoresConfig {
}
