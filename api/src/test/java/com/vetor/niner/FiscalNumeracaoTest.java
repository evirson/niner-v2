package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.fiscal.documento.FiscalNumeracaoService;
import com.vetor.niner.fiscal.documento.FiscalNumeracaoService.NumeroReservado;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Numeração de documento fiscal (F4 — docs/MODULOFISCAL.md §9.2).
 *
 * <p><b>Por que este teste existe e por que ele é concorrente de verdade.</b> Número repetido é
 * rejeição por duplicidade com cliente na frente do caixa; número pulado é obrigação acessória
 * (inutilização até o dia 10 do mês seguinte). Nenhuma das duas falhas aparece em teste
 * sequencial — o {@code INSERT … ON CONFLICT DO UPDATE … RETURNING} só se distingue de um
 * "lê, soma um, grava" quebrado quando duas threads disputam a mesma linha. Por isso os casos
 * abaixo usam threads de verdade soltas ao mesmo tempo por uma {@link CountDownLatch}, e não
 * chamadas em sequência.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FiscalNumeracaoTest {

    private static final int MODELO_NFCE = 65;

    @Autowired
    MockMvc mvc;

    @Autowired
    FiscalNumeracaoService numeracao;

    // ---------------------------------------------------------------- helpers

    private record Sessao(long idTenant, long idEmpresa) {
    }

    private Sessao assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Numeracao %s","email":"dono%s@lojanum.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return new Sessao(((Number) JsonPath.read(payload, "$.tid")).longValue(),
                ((Number) JsonPath.read(payload, "$.eid")).longValue());
    }

    // ---------------------------------------------------------------- casos

    @Test
    void primeiraNotaDaSerieComecaEmUm() throws Exception {
        Sessao s = assinarNovoTenant("um");

        TenantContext.comTenant(s.idTenant(), () -> {
            // A linha de fiscal_numeracao nem existe ainda: o ON CONFLICT precisa dar conta do
            // caso "primeira nota" sem que ninguém tenha semeado nada.
            assertThat(numeracao.ultimoNumeroUsado(s.idEmpresa(), MODELO_NFCE, 1, false)).isZero();

            NumeroReservado primeiro = numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, false);
            assertThat(primeiro.numero()).isEqualTo(1);
            assertThat(primeiro.serie()).isEqualTo(1);

            assertThat(numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, false).numero()).isEqualTo(2);
            assertThat(numeracao.ultimoNumeroUsado(s.idEmpresa(), MODELO_NFCE, 1, false)).isEqualTo(2);
        });
    }

    /**
     * O caso que justifica o desenho: 12 caixas emitindo ao mesmo tempo na mesma série.
     *
     * <p>Todas as threads ficam presas na latch e são soltas juntas, para que a disputa aconteça
     * de fato. O resultado exigido é forte: os 12 números formam exatamente {@code 1..12} — sem
     * repetido (rejeição por duplicidade) <b>e</b> sem buraco (inutilização).
     */
    @Test
    void doceCaixasSimultaneosNaoRepetemNemPulamNumero() throws Exception {
        Sessao s = assinarNovoTenant("concorrente");
        int caixas = 12;

        var largada = new CountDownLatch(1);
        var prontos = new CountDownLatch(caixas);
        var numeros = new ConcurrentLinkedQueue<Integer>();
        var falhas = new ConcurrentLinkedQueue<Throwable>();

        List<Thread> threads = IntStream.range(0, caixas)
                .mapToObj(i -> Thread.ofVirtual().unstarted(() -> {
                    try {
                        largada.await();
                        TenantContext.comTenant(s.idTenant(), () ->
                                numeros.add(numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, false).numero()));
                    } catch (Throwable e) {
                        falhas.add(e);
                    } finally {
                        prontos.countDown();
                    }
                }))
                .toList();

        threads.forEach(Thread::start);
        largada.countDown();
        assertThat(prontos.await(30, TimeUnit.SECONDS)).as("as 12 reservas terminaram").isTrue();

        assertThat(falhas).as("nenhuma reserva pode falhar — falhar é buraco na numeração").isEmpty();
        assertThat(numeros).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(1, caixas).boxed().toList());
    }

    /**
     * Séries e empresas são contadores independentes — a numeração é por
     * {@code (empresa, modelo, série)}. Misturar faria a série 2 nascer no número da série 1 e
     * levar rejeição por número já usado.
     */
    @Test
    void cadaSerieEmpresaEModeloTemContadorProprio() throws Exception {
        Sessao s = assinarNovoTenant("series");

        TenantContext.comTenant(s.idTenant(), () -> {
            numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, false);
            numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, false);

            assertThat(numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 2, false).numero())
                    .as("série 2 começa do 1, não continua a série 1").isEqualTo(1);
            assertThat(numeracao.reservar(s.idEmpresa(), 55, 1, false).numero())
                    .as("NF-e (55) tem numeração própria, independente da NFC-e (65)").isEqualTo(1);
            assertThat(numeracao.ultimoNumeroUsado(s.idEmpresa(), MODELO_NFCE, 1, false)).isEqualTo(2);
        });
    }

    /**
     * P8 — a numeração de um tenant não enxerga nem afeta a do outro, mesmo com o mesmo
     * {@code id_empresa}. Um vazamento aqui não seria só leitura indevida: faria a loja B começar
     * a numerar de onde a loja A parou.
     */
    @Test
    void numeracaoNaoAtravessaTenant() throws Exception {
        Sessao a = assinarNovoTenant("iso-a");
        Sessao b = assinarNovoTenant("iso-b");

        TenantContext.comTenant(a.idTenant(), () -> {
            for (int i = 0; i < 5; i++) {
                numeracao.reservar(a.idEmpresa(), MODELO_NFCE, 1, false);
            }
        });

        TenantContext.comTenant(b.idTenant(), () -> {
            assertThat(numeracao.ultimoNumeroUsado(b.idEmpresa(), MODELO_NFCE, 1, false))
                    .as("o tenant B não vê as 5 notas do tenant A").isZero();
            assertThat(numeracao.reservar(b.idEmpresa(), MODELO_NFCE, 1, false).numero()).isEqualTo(1);
        });

        TenantContext.comTenant(a.idTenant(), () ->
                assertThat(numeracao.ultimoNumeroUsado(a.idEmpresa(), MODELO_NFCE, 1, false))
                        .as("o tenant B não mexeu no contador do tenant A").isEqualTo(5));
    }

    /**
     * {@code cNF} é aleatório de propósito: é o que impede alguém de adivinhar chaves de acesso a
     * partir de uma conhecida. Se fosse sequencial (ou igual ao {@code nNF}, o que o MOC proíbe),
     * a chave inteira viraria previsível.
     */
    @Test
    void codigoNumericoTemOitoDigitosEnaoRepete() throws Exception {
        Sessao s = assinarNovoTenant("cnf");

        TenantContext.comTenant(s.idTenant(), () -> {
            Set<Integer> codigos = IntStream.range(0, 50)
                    .mapToObj(i -> numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, false))
                    .peek(r -> assertThat(r.codigoNumerico())
                            .as("cNF cabe em 8 dígitos e nunca é igual ao nNF")
                            .isBetween(10_000_000, 99_999_999)
                            .isNotEqualTo(r.numero()))
                    .map(NumeroReservado::codigoNumerico)
                    .collect(Collectors.toSet());

            // 50 sorteios em 90 milhões: colisão aqui denuncia gerador degenerado (constante,
            // semente fixa), não azar.
            assertThat(codigos).as("cNF é sorteado, não sequencial").hasSize(50);
        });
    }

    /**
     * ⭐ <b>Homologação e produção têm sequências SEPARADAS</b> (V106, 2026-08-31).
     *
     * <p>A SEFAZ mantém bases distintas para os dois ambientes, cada uma começando do 1. Até esta
     * data a numeração era uma só, e <b>cada nota de teste queimava um número de produção</b>:
     * medido no banco de dev, a NFC-e de homologação estava no 58, então a primeira nota real
     * sairia com <b>59</b> — e os números 1 a 58, que a SEFAZ de produção nunca viu, virariam
     * buraco de numeração e obrigação de inutilização formal.
     *
     * <p>⚠️ O defeito só aparece <b>ao trocar de ambiente</b>, que é exatamente o que o go-live é:
     * enquanto a loja fica em homologação, tudo parece certo.
     */
    @Test
    void homologacaoEProducaoTemSequenciasSeparadas() throws Exception {
        Sessao s = assinarNovoTenant("ambientes");

        TenantContext.comTenant(s.idTenant(), () -> {
            // Três notas de teste em homologação — é o que a loja faz antes de abrir.
            assertThat(numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, false).numero()).isEqualTo(1);
            assertThat(numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, false).numero()).isEqualTo(2);
            assertThat(numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, false).numero()).isEqualTo(3);

            // O go-live: vira a chave para produção. A primeira nota REAL tem de ser a número 1.
            assertThat(numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, true).numero())
                    .as("a primeira nota de PRODUÇÃO começa do 1 — as de teste não podem tê-la consumido")
                    .isEqualTo(1);

            // E os dois contadores seguem independentes, cada um no seu ritmo.
            assertThat(numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, true).numero()).isEqualTo(2);
            assertThat(numeracao.reservar(s.idEmpresa(), MODELO_NFCE, 1, false).numero())
                    .as("homologação continua de onde parou, sem saber da produção")
                    .isEqualTo(4);

            assertThat(numeracao.ultimoNumeroUsado(s.idEmpresa(), MODELO_NFCE, 1, true)).isEqualTo(2);
            assertThat(numeracao.ultimoNumeroUsado(s.idEmpresa(), MODELO_NFCE, 1, false)).isEqualTo(4);
        });
    }
}
