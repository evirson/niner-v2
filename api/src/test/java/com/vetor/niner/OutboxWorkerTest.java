package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.canais.CanalDeVenda.CanalIndisponivelException;
import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.integracao.outbox.OutboxProcessador;
import com.vetor.niner.integracao.outbox.OutboxProcessador.ManipuladorDeEvento;
import com.vetor.niner.integracao.outbox.OutboxRepositorio;
import com.vetor.niner.integracao.outbox.OutboxRepositorio.EventoPendente;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Worker do outbox (P2, spec §3.3.6) contra Postgres de verdade.
 *
 * <p>O que precisa ficar preso aqui não é o caminho feliz — é o comportamento de <b>falha</b>:
 * transitório reagenda, definitivo não fica girando, e evento sem dono não some.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OutboxWorkerTest.ManipuladoresDeTeste.class})
class OutboxWorkerTest {

    /** Manipulador que o teste controla: decide na hora se falha, e como. */
    static class ManipuladorEspiao implements ManipuladorDeEvento {
        final List<Long> vistos = new ArrayList<>();
        final AtomicReference<RuntimeException> falhaAConfigurar = new AtomicReference<>();

        @Override
        public String tipo() {
            return "TESTE_SYNC";
        }

        @Override
        public void executar(EventoPendente evento) {
            vistos.add(evento.id());
            RuntimeException falha = falhaAConfigurar.get();
            if (falha != null) {
                throw falha;
            }
        }
    }

    @TestConfiguration
    static class ManipuladoresDeTeste {
        @Bean
        ManipuladorEspiao manipuladorEspiao() {
            return new ManipuladorEspiao();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OutboxRepositorio repositorio;

    @Autowired
    OutboxProcessador processador;

    @Autowired
    ManipuladorEspiao espiao;

    @BeforeEach
    void limparEspiao() {
        espiao.vistos.clear();
        espiao.falhaAConfigurar.set(null);
    }

    private long novoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Outbox %s","email":"dono%s@lojaoutbox.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    private String situacao(long idEvento) {
        return jdbc.sql("SELECT status::text FROM outbox_evento WHERE id = ?")
                .param(idEvento).query(String.class).single();
    }

    private int tentativas(long idEvento) {
        return jdbc.sql("SELECT tentativas FROM outbox_evento WHERE id = ?")
                .param(idEvento).query(Integer.class).single();
    }

    // ------------------------------------------------------------------ caminho feliz

    @Test
    void eventoDespachadoViraProcessado() throws Exception {
        long idTenant = novoTenant("feliz");
        AtomicReference<Long> idEvento = new AtomicReference<>();

        TenantContext.comTenant(idTenant, () -> {
            idEvento.set(repositorio.enfileirar("TESTE_SYNC", "42", java.util.Map.of("qtd", 7)));
            processador.processarLoteDoTenantCorrente();
        });

        assertThat(espiao.vistos).contains(idEvento.get());
        assertThat(situacao(idEvento.get())).isEqualTo("PROCESSADO");
    }

    /** O payload tem de chegar ao manipulador desserializado, não como texto. */
    @Test
    void payloadChegaDesserializado() throws Exception {
        long idTenant = novoTenant("payload");
        AtomicReference<EventoPendente> capturado = new AtomicReference<>();

        TenantContext.comTenant(idTenant, () -> {
            repositorio.enfileirar("TESTE_SYNC", "99", java.util.Map.of("qtd", 7, "sku", "ABC"));
            List<EventoPendente> lote = repositorio.pegarLote(10);
            capturado.set(lote.getFirst());
        });

        assertThat(capturado.get().payload().get("qtd").asInt()).isEqualTo(7);
        assertThat(capturado.get().payload().get("sku").asText()).isEqualTo("ABC");
        assertThat(capturado.get().agregadoId()).isEqualTo("99");
    }

    // ------------------------------------------------------------------ falhas

    /**
     * Falha transitória (canal fora do ar) <b>reagenda</b> — não pode virar dead-letter na
     * primeira tentativa, senão uma queda de rede de 30 segundos deixa o lojista com o anúncio
     * dessincronizado até alguém reprocessar à mão.
     */
    @Test
    void falhaTransitoriaReagendaEmVezDeDesistir() throws Exception {
        long idTenant = novoTenant("transitoria");
        espiao.falhaAConfigurar.set(new CanalIndisponivelException("429 do Mercado Livre"));
        AtomicReference<Long> idEvento = new AtomicReference<>();

        TenantContext.comTenant(idTenant, () -> {
            idEvento.set(repositorio.enfileirar("TESTE_SYNC", "1", java.util.Map.of()));
            processador.processarLoteDoTenantCorrente();
        });

        assertThat(situacao(idEvento.get())).isEqualTo("ERRO");
        assertThat(tentativas(idEvento.get())).isEqualTo(1);

        // E o retry foi empurrado para o futuro — sem isso, a rodada seguinte pegaria o mesmo
        // evento em 30 s e o backoff não existiria na prática.
        Boolean noFuturo = jdbc.sql("SELECT proximo_retry > now() FROM outbox_evento WHERE id = ?")
                .param(idEvento.get()).query(Boolean.class).single();
        assertThat(noFuturo).as("o evento não pode voltar imediatamente").isTrue();
    }

    /** Evento reagendado não é pego de novo na mesma rodada nem na seguinte imediata. */
    @Test
    void eventoReagendadoNaoVoltaNoLoteSeguinte() throws Exception {
        long idTenant = novoTenant("reagendado");
        espiao.falhaAConfigurar.set(new CanalIndisponivelException("canal fora"));

        TenantContext.comTenant(idTenant, () -> {
            repositorio.enfileirar("TESTE_SYNC", "1", java.util.Map.of());
            processador.processarLoteDoTenantCorrente();
            espiao.vistos.clear();
            processador.processarLoteDoTenantCorrente();
        });

        assertThat(espiao.vistos).as("o backoff tem de segurar o evento").isEmpty();
    }

    /**
     * ⚠️ Tipo sem manipulador vai <b>direto</b> ao dead-letter: nenhuma espera vai fazer o
     * manipulador existir, e girar 10 vezes só esconderia a causa atrás das tentativas.
     */
    @Test
    void eventoSemManipuladorVaiDiretoAoDeadLetter() throws Exception {
        long idTenant = novoTenant("sem-dono");
        AtomicReference<Long> idEvento = new AtomicReference<>();

        TenantContext.comTenant(idTenant, () -> {
            idEvento.set(repositorio.enfileirar("TIPO_QUE_NINGUEM_ATENDE", "1", java.util.Map.of()));
            processador.processarLoteDoTenantCorrente();
        });

        assertThat(situacao(idEvento.get())).isEqualTo("DEAD_LETTER");
        // ⚠️ Dead-letter NÃO é perda — a linha continua lá, com o motivo, para o painel (R7).
        String erro = jdbc.sql("SELECT erro FROM outbox_evento WHERE id = ?")
                .param(idEvento.get()).query(String.class).single();
        assertThat(erro).contains("Nenhum manipulador");
    }

    /** Falha de um evento não pode derrubar os outros do mesmo lote. */
    @Test
    void falhaDeUmEventoNaoDerrubaOsOutros() throws Exception {
        long idTenant = novoTenant("lote-misto");
        AtomicReference<Long> semDono = new AtomicReference<>();
        AtomicReference<Long> comDono = new AtomicReference<>();

        TenantContext.comTenant(idTenant, () -> {
            semDono.set(repositorio.enfileirar("TIPO_ORFAO", "1", java.util.Map.of()));
            comDono.set(repositorio.enfileirar("TESTE_SYNC", "2", java.util.Map.of()));
            processador.processarLoteDoTenantCorrente();
        });

        assertThat(situacao(semDono.get())).isEqualTo("DEAD_LETTER");
        assertThat(situacao(comDono.get())).as("o evento bom seguiu").isEqualTo("PROCESSADO");
    }

    // ------------------------------------------------------------------ isolamento (P8)

    /**
     * ⛔ O worker roda fora de requisição, e {@code outbox_evento} tem RLS. Um tenant não pode
     * enxergar — muito menos despachar — o evento de outro.
     */
    @Test
    void loteDeUmTenantNaoEnxergaEventoDeOutro() throws Exception {
        long tenantA = novoTenant("iso-a");
        long tenantB = novoTenant("iso-b");
        AtomicReference<Long> eventoDeA = new AtomicReference<>();

        TenantContext.comTenant(tenantA, () ->
                eventoDeA.set(repositorio.enfileirar("TESTE_SYNC", "1", java.util.Map.of())));

        TenantContext.comTenant(tenantB, () -> processador.processarLoteDoTenantCorrente());

        assertThat(espiao.vistos).as("o worker do tenant B não pode ter visto o evento de A")
                .doesNotContain(eventoDeA.get());
        assertThat(situacao(eventoDeA.get())).isEqualTo("PENDENTE");
    }

    /**
     * ⚠️ O contraprovar do teste acima: sem {@code TenantContext} a fila sai <b>vazia, sem erro</b>
     * — que é exatamente o modo de falha silencioso que derrubou os jobs de fiscal em 2026-08-19.
     * Este teste existe para que a próxima pessoa veja o comportamento documentado em vez de
     * descobrir na produção.
     */
    @Test
    void semContextoDeTenantAFilaSaiVaziaSemErro() throws Exception {
        long idTenant = novoTenant("sem-contexto");
        TenantContext.comTenant(idTenant, () ->
                repositorio.enfileirar("TESTE_SYNC", "1", java.util.Map.of()));

        // Sem comTenant: nenhuma exceção, nenhum evento.
        assertThat(repositorio.pegarLote(10)).isEmpty();
    }
}
