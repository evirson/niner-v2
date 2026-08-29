package com.vetor.niner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nenhum {@code @Scheduled} pode disparar sozinho durante a suíte.
 *
 * <h2>⛔ O defeito que este teste existe para impedir</h2>
 *
 * <p>Até 2026-08-29 o {@code @EnableScheduling} vivia na classe da aplicação e valia também nos
 * testes. Os seis jobs do produto têm {@code initialDelay} de 15 s a 2 min e a suíte leva vários
 * minutos: eles <b>disparavam no meio dela</b>. O resultado é o pior tipo de falha — <b>o teste
 * passa sozinho e reprova na suíte</b>, apontando para código que ele não menciona.
 *
 * <p>Medido naquele dia: {@code FiscalInutilizacaoTest.naoDeixaInutilizarNumeroAindaNaoAlocado} usa
 * {@code verifyNoInteractions} num mock do transporte da SEFAZ, e o
 * {@code FiscalContingenciaDrenoJob} chamava {@code sefazRespondendo} nesse mesmo mock. Isolado:
 * verde. Na suíte: vermelho.
 *
 * <p>⚠️ E o dreno era o sintoma mais visível, não o mais grave: o {@code OrcamentoVencimentoJob}
 * <b>altera dados</b>. Um job que muda linha no meio de um teste produz falha impossível de
 * explicar lendo o teste.
 *
 * <h2>Por que ele mede o MECANISMO, e não a ausência de falha</h2>
 *
 * <p>"A suíte passou" não prova nada sobre um defeito de temporização — ele pode simplesmente não
 * ter disparado nesta rodada. Este teste pergunta ao próprio Spring quantas tarefas agendadas
 * existem: se alguém devolver o {@code @EnableScheduling} para a classe da aplicação, ou apagar a
 * linha do {@code src/test/resources/application.yml}, a contagem deixa de ser zero e o build
 * reprova <b>na hora</b>, não daqui a três meses num teste sem relação.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AgendadoresDesligadosNaSuiteTest {

    @Autowired
    private ApplicationContext contexto;

    @Test
    void nenhumJobAgendadoDisparaDuranteOsTestes() {
        // O post-processor é quem registra as tarefas de `@Scheduled`. Sem `@EnableScheduling`
        // ativo ele nem chega a ser criado — e essa ausência já é a prova.
        String[] processadores =
                contexto.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class);

        if (processadores.length > 0) {
            ScheduledAnnotationBeanPostProcessor p =
                    contexto.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(p.getScheduledTasks())
                    .as("""
                            Há tarefa agendada ativa no contexto de teste. Os seis @Scheduled do produto \
                            disparam no meio da suíte e produzem falha que aparece só quando a suíte \
                            inteira roda — foi assim que o FiscalContingenciaDrenoJob derrubou o \
                            FiscalInutilizacaoTest em 2026-08-29. Confira se `@EnableScheduling` voltou \
                            para NinerApiApplication e se `niner.agendadores.ativos: false` continua em \
                            src/test/resources/application.yml.""")
                    .isEmpty();
        }

        // O bean de configuração é condicional: nos testes ele não deve existir. Conferir os dois
        // lados evita que a asserção acima passe por um motivo errado (por exemplo, se o Spring
        // mudasse o momento em que registra as tarefas).
        assertThat(contexto.getBeanNamesForType(com.vetor.niner.comum.AgendadoresConfig.class))
                .as("AgendadoresConfig não pode ser criado na suíte — a propriedade que o desliga sumiu")
                .isEmpty();
    }
}
