package com.vetor.niner;

import com.vetor.niner.configuracao.importacao.ImportacaoProgressoRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O progresso "ao vivo" da Importação de Dados é isolado por tenant (P8, 2026-09-01).
 *
 * <h2>O gap que isto fecha</h2>
 * O {@code idProgresso} é gerado pelo <b>navegador</b> e chega por path. O registro em memória era
 * chaveado <b>só por ele</b>, e {@code ImportacaoService.progresso} conferia apenas
 * {@code exigirAdmin} — nunca o tenant. Um admin de um tenant que conhecesse o id de outro lia o
 * progresso da importação alheia.
 *
 * <p>⚠️ <b>O risco prático era baixo e vale dizer isso</b>: o id é um UUID efêmero que só existe
 * durante a requisição, e o que vazava eram três campos (etapa, atual, total). Mas P8 não tem
 * exceção por tamanho — <i>nenhuma consulta cruza a fronteira do tenant</i> — e fechar custou uma
 * linha. Achado lendo `configuracao/importacao/`, o maior bloco de código que nenhuma das dez
 * varreduras de auditoria tinha aberto (`docs/PENDENCIAS.md` #68).
 *
 * <h2>⚠️ Por que este teste NÃO passa pelo endpoint</h2>
 * O progresso só existe <b>enquanto a requisição de importação está em voo</b>: o {@code finally}
 * remove a entrada ao terminar. Um teste que chamasse {@code GET /progresso/{id}} depois receberia
 * {@code "desconhecido"} para os dois tenants e <b>passaria sem provar nada</b> — verde por
 * ausência, que é a forma de teste inútil que este projeto já catalogou. Exercitar o registro
 * direto é o único jeito de a asserção significar alguma coisa.
 */
class ImportacaoProgressoIsoladoTest {

    @Test
    void oProgressoDeUmTenantNaoEhVisivelPorOutro() {
        var registry = new ImportacaoProgressoRegistry();
        String mesmoId = "11111111-2222-3333-4444-555555555555";

        registry.iniciar(1L, mesmoId);

        assertThat(registry.obter(1L, mesmoId))
                .as("o dono enxerga o próprio progresso")
                .isPresent();
        assertThat(registry.obter(2L, mesmoId))
                .as("outro tenant NÃO pode enxergar, mesmo sabendo o id exato")
                .isEmpty();
    }

    /**
     * ⭐ O caso negativo do mesmo mecanismo: o mesmo id em dois tenants são duas entradas
     * independentes, e encerrar uma não apaga a outra.
     *
     * <p>Sem ele, uma implementação que simplesmente <i>ignorasse</i> o segundo {@code iniciar}
     * (ou que o sobrescrevesse) passaria pelo teste de cima.
     */
    @Test
    void encerrarOProgressoDeUmTenantNaoApagaODoOutro() {
        var registry = new ImportacaoProgressoRegistry();
        String mesmoId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

        registry.iniciar(1L, mesmoId);
        registry.iniciar(2L, mesmoId);
        registry.finalizar(1L, mesmoId);

        assertThat(registry.obter(1L, mesmoId)).isEmpty();
        assertThat(registry.obter(2L, mesmoId))
                .as("o progresso do tenant 2 continua vivo")
                .isPresent();
    }
}
