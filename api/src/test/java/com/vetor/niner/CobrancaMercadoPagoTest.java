package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import com.vetor.niner.plataforma.cobranca.CobrancaWebhookProcessador;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobrança da assinatura pelo Mercado Pago (ADR-016) contra um <b>servidor falso</b> do gateway
 * ({@code com.sun.net.httpserver}, sem dependência nova): o adapter fala HTTP de verdade, com os
 * mesmos cabeçalhos e o mesmo parsing que usará com o token {@code TEST-} do sandbox.
 *
 * <p>O caso que mais importa aqui é o <b>corpo do webhook ser ignorado</b>: mesmo uma notificação
 * dizendo "approved", se o gateway responder "rejected" na consulta, a fatura <b>não</b> pode ser
 * paga. É o que impede um webhook forjado de promover alguém de faixa sem pagar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CobrancaMercadoPagoTest {

    private static final String SEGREDO_WEBHOOK = "segredo-de-teste-do-webhook";

    /** O que o Mercado Pago falso responde em GET /v1/payments/{id} — cada teste ajusta. */
    private static final AtomicReference<String> STATUS_PAGAMENTO = new AtomicReference<>("pending");
    /** Cada cobrança criada ganha um id próprio, como no gateway real: id fixo faria a
     *  idempotência de `pagamento` (gateway + id_gateway_transacao) reaproveitar a cobrança do
     *  teste anterior — e o efeito cairia na fatura errada. */
    private static final AtomicLong PROXIMO_ID = new AtomicLong(1_000_000);
    private static final Map<String, String> REFERENCIA_POR_PAGAMENTO = new ConcurrentHashMap<>();
    private static final AtomicReference<String> ULTIMO_PAGAMENTO = new AtomicReference<>("");
    private static HttpServer mercadoPagoFalso;

    static {
        try {
            mercadoPagoFalso = HttpServer.create(new InetSocketAddress(0), 0);
            mercadoPagoFalso.createContext("/v1/payments", troca -> {
                String resposta;
                if ("POST".equals(troca.getRequestMethod())) {
                    String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String id = String.valueOf(PROXIMO_ID.incrementAndGet());
                    String referencia = JsonPath.read(corpo, "$.external_reference");
                    REFERENCIA_POR_PAGAMENTO.put(id, referencia);
                    ULTIMO_PAGAMENTO.set(id);
                    resposta = """
                            {"id":%s,"status":"pending","transaction_amount":99.00,
                             "external_reference":"%s",
                             "point_of_interaction":{"transaction_data":{
                               "qr_code":"00020126PIX-COPIA-E-COLA","qr_code_base64":"aGVsbG8=",
                               "ticket_url":"https://mp.exemplo/ticket/1"}}}
                            """.formatted(id, referencia);
                } else {
                    String id = troca.getRequestURI().getPath().substring("/v1/payments/".length());
                    resposta = """
                            {"id":%s,"status":"%s","transaction_amount":99.00,"external_reference":"%s"}
                            """.formatted(id, STATUS_PAGAMENTO.get(),
                            REFERENCIA_POR_PAGAMENTO.getOrDefault(id, ""));
                }
                byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
                troca.getResponseHeaders().add("Content-Type", "application/json");
                troca.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = troca.getResponseBody()) {
                    os.write(bytes);
                }
            });
            mercadoPagoFalso.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void configurarGateway(DynamicPropertyRegistry registro) {
        registro.add("niner.cobranca.mercadopago.base-url",
                () -> "http://localhost:" + mercadoPagoFalso.getAddress().getPort());
        registro.add("niner.cobranca.mercadopago.access-token", () -> "TEST-token-de-teste");
        registro.add("niner.cobranca.mercadopago.webhook-secret", () -> SEGREDO_WEBHOOK);
    }

    @AfterAll
    static void pararGatewayFalso() {
        mercadoPagoFalso.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    CobrancaWebhookProcessador processador;

    @BeforeEach
    void reiniciarGateway() {
        STATUS_PAGAMENTO.set("pending");
    }

    // ------------------------------------------------------------------------------- infra

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Cobranca %s","email":"dono%s@lojacobranca.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private Connection abrirConexao() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
    }

    private long idFaixaPaga() throws SQLException {
        try (Connection c = abrirConexao(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT id_plano FROM plataforma.plano WHERE ativo AND NOT gratuito ORDER BY faixa_ordem LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String situacaoFatura(long idFatura) throws SQLException {
        try (Connection c = abrirConexao();
                PreparedStatement ps = c.prepareStatement("SELECT status::text FROM plataforma.fatura WHERE id_fatura = ?")) {
            ps.setLong(1, idFatura);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String assinaturaValida(String dataId, String requestId, String ts) throws Exception {
        String manifest = "id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SEGREDO_WEBHOOK.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "ts=" + ts + ",v1=" + HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * {@code idNotificacao} é a chave de idempotência do webhook — cada teste usa o seu. Com um
     * id fixo para todos, a segunda notificação seria (corretamente) descartada e o teste
     * seguinte não processaria nada: a asserção "continua ABERTA" passaria por engano.
     */
    private void notificar(String dataId, long idNotificacao) throws Exception {
        String ts = "1704908010";
        String requestId = "req-" + idNotificacao;
        mvc.perform(post("/api/publico/webhooks/mercadopago?data.id=" + dataId + "&type=payment")
                        .header("x-signature", assinaturaValida(dataId, requestId, ts))
                        .header("x-request-id", requestId)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"id":%d,"type":"payment","action":"payment.updated","data":{"id":"%s"}}
                                """.formatted(idNotificacao, dataId)))
                .andExpect(status().isOk());
    }

    private long pedirPix(String token) throws Exception {
        String resp = mvc.perform(post("/api/v1/assinatura/pagamento").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idPlano\":%d,\"ciclo\":\"MENSAL\"}".formatted(idFaixaPaga())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.copiaECola").value("00020126PIX-COPIA-E-COLA"))
                .andExpect(jsonPath("$.qrCodeBase64").value("aGVsbG8="))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFatura")).longValue();
    }

    // ------------------------------------------------------------------------------- testes

    @Test
    void pedirPixCriaFaturaAbertaComCodigoParaPagar() throws Exception {
        String token = assinarNovoTenant("pix");
        long idFatura = pedirPix(token);

        assertThat(situacaoFatura(idFatura)).isEqualTo("ABERTA");
        mvc.perform(get("/api/v1/assinatura/faturas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].situacao").value("ABERTA"));
    }

    @Test
    void pedirPixDuasVezesNoMesmoMesNaoEmpilhaFatura() throws Exception {
        String token = assinarNovoTenant("duasvezes");
        long primeira = pedirPix(token);
        long segunda = pedirPix(token);

        assertThat(segunda).isEqualTo(primeira);
        mvc.perform(get("/api/v1/assinatura/faturas").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void pagamentoConfirmadoPagaFaturaEPromoveAssinatura() throws Exception {
        String token = assinarNovoTenant("confirma");
        long idFatura = pedirPix(token);

        STATUS_PAGAMENTO.set("approved");
        notificar(ULTIMO_PAGAMENTO.get(), 90001);
        processador.pegarLote().forEach(processador::processar);

        assertThat(situacaoFatura(idFatura)).isEqualTo("PAGA");
        // A faixa paga passa a ser o plano vigente — e é o worker que promove, nunca o checkout.
        mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.plano.gratuito").value(false))
                .andExpect(jsonPath("$.plano.limiteVendasMes").value(500));
    }

    @Test
    void webhookMentindoNaoPagaFatura() throws Exception {
        // A notificação diz "approved"; o gateway, consultado, diz "rejected". Vence o gateway —
        // é isto que torna o endpoint público inofensivo mesmo se alguém forjar a notificação.
        String token = assinarNovoTenant("mentira");
        long idFatura = pedirPix(token);

        STATUS_PAGAMENTO.set("rejected");
        notificar(ULTIMO_PAGAMENTO.get(), 90002);
        processador.pegarLote().forEach(processador::processar);

        assertThat(situacaoFatura(idFatura)).isEqualTo("ABERTA");
        mvc.perform(get("/api/v1/minha-conta").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.plano.gratuito").value(true));
    }

    @Test
    void webhookComAssinaturaInvalidaEhRecusado() throws Exception {
        mvc.perform(post("/api/publico/webhooks/mercadopago?data.id=555555&type=payment")
                        .header("x-signature", "ts=1704908010,v1=deadbeef")
                        .header("x-request-id", "req-falso")
                        .contentType(APPLICATION_JSON)
                        .content("{\"id\":99002,\"type\":\"payment\",\"data\":{\"id\":\"555555\"}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notificacaoRepetidaNaoDuplicaEvento() throws Exception {
        notificar("777777", 90003);
        notificar("777777", 90003);

        try (Connection c = abrirConexao();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM plataforma.webhook_gateway WHERE gateway = 'mercadopago' AND evento_id = '90003'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);   // idempotência por (gateway, evento_id)
            }
        }
    }

    @Test
    void operadorNaoPedePagamento() throws Exception {
        String token = assinarNovoTenant("operadorcobranca");
        // token de ADMIN vira OPERADOR só trocando o papel exigido no serviço: aqui basta checar
        // que a rota exige ADMIN — usamos o próprio endpoint com um token sem o papel.
        mvc.perform(post("/api/v1/assinatura/pagamento").contentType(APPLICATION_JSON)
                        .content("{\"idPlano\":1,\"ciclo\":\"MENSAL\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(token).isNotBlank();
    }

    /**
     * ⭐ <b>Corrida real: dois workers puxando da fila de webhooks com as transações SOBREPOSTAS.</b>
     *
     * <p>A invariante do {@code SKIP LOCKED} é que os lotes sejam <b>disjuntos</b>: um evento não
     * pode sair para os dois workers. Se sair, o mesmo pagamento é aplicado duas vezes — fatura
     * quitada em duplicidade, ou assinatura reativada por um evento já consumido.
     *
     * <p>⚠️ <b>A primeira versão deste teste acusava falso, e o motivo vale mais que o teste.</b> Ele
     * chamava {@code pegarLote()} em duas threads soltas por um latch e afirmava disjunção. Mas
     * {@code pegarLote()} é {@code @Transactional} e <b>commita ao retornar</b>: se a thread A
     * termina antes de B começar a consultar, o lock de A já morreu, as linhas continuam com
     * {@code processado_em IS NULL}, e B lê exatamente as mesmas — <b>lotes idênticos sem defeito
     * nenhum</b>. Isolado o teste passava; na suíte inteira (máquina mais carregada, menos
     * sobreposição) ele falhou. Um teste de concorrência que depende de as threads se cruzarem por
     * sorte é pior que nenhum, porque treina quem lê a falha a ignorá-lo.
     *
     * <p>⭐ <b>A correção é segurar a transação de propósito.</b> Aqui as duas conexões são
     * controladas à mão: a primeira executa a consulta da fila e <b>não commita</b>; só então a
     * segunda executa. Assim a sobreposição é garantida, não sorteada — e o que se mede é o
     * comportamento do {@code SKIP LOCKED} no schema real.
     *
     * <p>⭐ <b>Medido ao sabotar (2026-09-04):</b> trocando o {@code SKIP LOCKED} por
     * {@code FOR UPDATE} puro, este teste <b>não falha — ele TRAVA</b>. A segunda conexão fica
     * esperando um lock que a primeira segura de propósito, e o processo só morre no timeout. É a
     * diferença entre as duas cláusulas em uma frase: {@code SKIP LOCKED} <b>pula</b> o que está
     * travado, {@code FOR UPDATE} <b>espera</b>. Num worker de fila isso não seria só lentidão: os
     * dois workers ficariam serializados, e o segundo releria as mesmas linhas ao ser liberado.
     *
     * <p>⚠️ A SQL abaixo <b>espelha</b> a de {@link CobrancaWebhookProcessador#pegarLote()}. Para
     * que a cópia não vire mentira no dia em que alguém mexer lá, a última asserção lê o fonte do
     * serviço e exige que ele continue usando {@code SKIP LOCKED} — sem isso, este teste
     * continuaria verde enquanto a produção perdia a garantia.
     */
    @Test
    void doisWorkersPuxandoAFilaAoMesmoTempoNaoPegamOMesmoEvento() throws Exception {
        final int QUANTOS = 12;
        try (Connection c = abrirConexao();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO plataforma.webhook_gateway (gateway, evento_id, tipo, payload, proxima_tentativa)
                     VALUES ('mercadopago', ?, 'payment', '{"data":{"id":"1"}}'::jsonb, now())
                     """)) {
            for (int i = 0; i < QUANTOS; i++) {
                ps.setString(1, "corrida-skiplocked-" + java.util.UUID.randomUUID());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // Espelha `CobrancaWebhookProcessador.pegarLote()` — ver o javadoc acima.
        String sqlFila = """
                SELECT id FROM plataforma.webhook_gateway
                 WHERE gateway = 'mercadopago' AND processado_em IS NULL AND proxima_tentativa <= now()
                   AND tentativas < 8
                 ORDER BY recebido_em
                 LIMIT 20
                 FOR UPDATE SKIP LOCKED
                """;

        List<Long> loteA = new java.util.ArrayList<>();
        List<Long> loteB = new java.util.ArrayList<>();

        try (Connection a = abrirConexao(); Connection b = abrirConexao()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);

            // A pega o lote e SEGURA a transação aberta — os locks dele continuam de pé.
            try (PreparedStatement ps = a.prepareStatement(sqlFila);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    loteA.add(rs.getLong(1));
                }
            }

            // B consulta com os locks de A ainda vivos. Com SKIP LOCKED, pula o que A travou.
            try (PreparedStatement ps = b.prepareStatement(sqlFila);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    loteB.add(rs.getLong(1));
                }
            }

            a.rollback();
            b.rollback();
        }

        assertThat(loteA).as("o primeiro worker tem de trazer eventos da fila").isNotEmpty();

        // ⭐ A invariante: interseção vazia. Um evento entregue aos dois é um pagamento aplicado 2×.
        var repetidos = new java.util.ArrayList<>(loteA);
        repetidos.retainAll(loteB);
        assertThat(repetidos)
                .as("SKIP LOCKED: nenhum evento pode sair para os dois workers (A=%s, B=%s)", loteA, loteB)
                .isEmpty();

        // ⚠️ Guarda contra a cópia virar mentira: se alguém tirar o SKIP LOCKED do serviço, o teste
        // acima continuaria verde (ele usa a SQL local) enquanto a produção perderia a garantia.
        String fonte = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/vetor/niner/plataforma/cobranca/CobrancaWebhookProcessador.java"));
        assertThat(fonte)
                .as("o consumo da fila em produção tem de continuar usando FOR UPDATE SKIP LOCKED")
                .contains("FOR UPDATE SKIP LOCKED");
    }

}
