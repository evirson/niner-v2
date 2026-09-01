package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sangria de Caixa (V094) — dinheiro que sai da gaveta e entra numa conta corrente.
 *
 * <h2>⭐ O que estes testes medem, e por que</h2>
 *
 * <p>Sangria é <b>transferência</b>, não saída (decisão do dono do produto, 2026-08-29:
 * <i>"esta sangria tem que ter um destino: sempre será depositada numa conta bancária"</i>). Um
 * teste que conferisse só o 201, ou só o débito no caixa, passaria com metade do mecanismo — e
 * metade é exatamente o defeito: dinheiro que sai sem destino desaparece do fluxo.
 *
 * <p>Por isso o teste principal confere <b>os três lados no banco</b>: o mestre, o débito em
 * {@code caixa_detalhe} e o crédito em {@code conta_corrente_movimento}, ligados pelo
 * {@code id_sangria}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SangriaCaixaCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private record TenantNovo(String token, long idEmpresa) {
    }

    private TenantNovo assinar(String sufixo) throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Sangria %s","email":"dono%s@lojasangria.com",
                         "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                        """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        String empresas = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        long idEmpresa = ((Number) JsonPath.read(empresas, "$[0].idEmpresa")).longValue();
        return new TenantNovo(token, idEmpresa);
    }

    private static long extrairIdTenant(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return ((Number) JsonPath.read(payload, "$.tid")).longValue();
    }

    /** ⚠️ `SET app.id_tenant` é obrigatório: com `FORCE ROW LEVEL SECURITY` nem o dono enxerga. */
    private Connection abrirConexao(long idTenant) throws Exception {
        Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.id_tenant', ?, false)")) {
            ps.setString(1, String.valueOf(idTenant));
            ps.execute();
        }
        return c;
    }

    private void abrirCaixaComFundo(String token, String fundo) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        long idCarteira = carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":%s}".formatted(idCarteira, fundo)))
                .andExpect(status().isOk());
    }

    private void criarConta(String token, long idEmpresa, String codigo) throws Exception {
        mvc.perform(post("/api/v1/contas-corrente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"%s","idEmpresa":%d,"idBanco":"341","idAgencia":"1234",
                                 "descricao":"Conta do deposito","ativo":true}
                                """.formatted(codigo, idEmpresa)))
                .andExpect(status().isCreated());
    }

    /** Um plano de contas qualquer do tenant — a coluna é NOT NULL nos dois lados. */
    private String primeiroPlanoDeContas(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/planos-contas?pagina=1&limite=1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.itens[0].idPlanoContas");
    }

    /**
     * P8 na rotina de dinheiro mais nova do produto (pendência 48, 2026-08-31).
     *
     * <p>⭐ <b>Aqui o vetor não é ler dado alheio — é ESCREVER nele.</b> A sangria pega o caixa do
     * servidor (nunca do corpo, justamente para um operador não sangrar o caixa de outro), mas a
     * <b>conta corrente de destino vem do corpo</b>. Como {@code id_conta_corrente} é chave de
     * negócio por tenant (texto), nada impede o vizinho de mandar o código de uma conta que não é
     * dele — e sangrar é uma <b>transferência</b>: o dinheiro sairia da gaveta dele e entraria no
     * extrato da loja alheia.
     *
     * <p>⚠️ E o teste confere o BANCO, não só o status: um 4xx que já tivesse gravado metade
     * passaria por uma asserção de status.
     */
    @Test
    void isolamentoEntreTenants() throws Exception {
        TenantNovo a = assinar("isolamento-a");
        TenantNovo b = assinar("isolamento-b");
        long idTenantA = extrairIdTenant(a.token());

        criarConta(a.token(), a.idEmpresa(), "SODOA1");
        abrirCaixaComFundo(b.token(), "500.00");
        String planoB = primeiroPlanoDeContas(b.token());

        // B tenta depositar na conta de A — o código existe, mas não para ele.
        mvc.perform(post("/api/v1/caixa/sangrias").header("Authorization", "Bearer " + b.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"SODOA1","valor":100.00,"idPlanoContas":"%s",
                                 "observacao":"Tentativa cross-tenant"}
                                """.formatted(planoB)))
                .andExpect(status().isNotFound());

        // ⭐ e nada nasceu do lado de A — nem o crédito no extrato, nem a sangria.
        // ⚠️ O `id_tenant` explícito aqui NÃO é redundância: `abrirConexao` conecta com o
        // superusuário do container (Testcontainers), que ignora RLS mesmo com FORCE — o
        // `set_config` só serve às funções que o leem. Sem o filtro, este `count(*)` mede o banco
        // INTEIRO: a primeira versão deste teste passava sozinha e reprovava na suíte, contando a
        // sangria legítima que outro teste da mesma classe tinha acabado de criar.
        try (Connection c = abrirConexao(idTenantA)) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT count(*) FROM conta_corrente_movimento
                     WHERE id_tenant = ? AND id_conta_corrente = 'SODOA1'
                    """)) {
                ps.setLong(1, idTenantA);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("nenhum movimento pode ter entrado na conta do tenant A").isZero();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM caixa_sangria WHERE id_tenant = ?")) {
                ps.setLong(1, idTenantA);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("nenhuma sangria pode ter nascido no tenant A").isZero();
                }
            }
        }
    }

    @Test
    void sangriaEscreveOsTresLadosNaMesmaTransacao() throws Exception {
        TenantNovo t = assinar("tres-lados");
        long idTenant = extrairIdTenant(t.token());
        abrirCaixaComFundo(t.token(), "500.00");
        criarConta(t.token(), t.idEmpresa(), "BB001");
        String plano = primeiroPlanoDeContas(t.token());

        String resp = mvc.perform(post("/api/v1/caixa/sangrias").header("Authorization", "Bearer " + t.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"BB001","valor":300.00,"idPlanoContas":"%s",
                                 "observacao":"Deposito do meio-dia"}
                                """.formatted(plano)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valor").value(300.00))
                .andExpect(jsonPath("$.idContaCorrente").value("BB001"))
                .andReturn().getResponse().getContentAsString();
        long idSangria = ((Number) JsonPath.read(resp, "$.idSangria")).longValue();

        try (Connection c = abrirConexao(idTenant)) {
            // (1) o débito no caixa — o dinheiro saiu da gaveta
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT valor, credito_debito::text, tipo_operacao::text
                      FROM caixa_detalhe WHERE id_sangria = ?
                    """)) {
                ps.setLong(1, idSangria);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("o débito em caixa_detalhe tem de existir").isTrue();
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("300.00");
                    assertThat(rs.getString(2)).isEqualTo("D");
                    assertThat(rs.getString(3)).isEqualTo("DEBITO_CAIXA");
                }
            }
            // (2) o crédito no banco — e é este lado que a rotina existe para criar. Sem ele o
            // dinheiro sairia do fluxo sem chegar a lugar nenhum.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT valor, credito_debito::text, id_conta_corrente
                      FROM conta_corrente_movimento WHERE id_sangria = ?
                    """)) {
                ps.setLong(1, idSangria);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("o crédito na conta corrente tem de existir").isTrue();
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("300.00");
                    assertThat(rs.getString(2)).isEqualTo("C");
                    assertThat(rs.getString(3)).isEqualTo("BB001");
                }
            }
        }

        // (3) e o disponível caiu exatamente o que saiu: 500 − 300.
        mvc.perform(get("/api/v1/caixa/sangrias/contexto").header("Authorization", "Bearer " + t.token()))
                .andExpect(jsonPath("$.disponivel").value(200.00));
    }

    /**
     * Não se sangra mais do que há na gaveta.
     *
     * <p>⚠️ E o teste confere o <b>banco</b> depois do 409, não só o status: uma recusa que
     * acontecesse <i>depois</i> de gravar deixaria a sangria lá e o teste passaria mesmo assim —
     * foi exatamente esse o erro que a devolução de venda cancelada ensinou em 2026-08-27.
     */
    @Test
    void naoSangraMaisDoQueTemNaGaveta() throws Exception {
        TenantNovo t = assinar("acima-do-saldo");
        long idTenant = extrairIdTenant(t.token());
        abrirCaixaComFundo(t.token(), "100.00");
        criarConta(t.token(), t.idEmpresa(), "BB002");
        String plano = primeiroPlanoDeContas(t.token());

        mvc.perform(post("/api/v1/caixa/sangrias").header("Authorization", "Bearer " + t.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"BB002","valor":250.00,"idPlanoContas":"%s"}
                                """.formatted(plano)))
                .andExpect(status().isConflict());

        // ⚠️ `id_tenant` explícito, e não é preciosismo: a conexão do Testcontainers usa o
        // SUPERUSUÁRIO do container, que ignora RLS (ver
        // feedback_testcontainers_nao_usa_niner_app). Sem o filtro, este `count(*)` conta o banco
        // INTEIRO — a asserção passava só enquanto nenhum outro teste da classe gravava sangria, e
        // quebrou no dia em que um passou a gravar. Contagem sem tenant aqui não afirma nada sobre
        // este tenant.
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM caixa_sangria WHERE id_tenant = ?")) {
            ps.setLong(1, idTenant);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).as("nenhuma sangria pode ter nascido").isZero();
            }
        }
    }

    /**
     * ⭐ <b>CONCORRÊNCIA REAL — duas sangrias ao MESMO tempo da mesma gaveta.</b>
     *
     * <p>Segunda das corridas escritas em 2026-09-01 (a outra é o limite de crédito, em
     * {@code PdvCrudTest}). Até aqui os {@code FOR UPDATE} de caixa e sangria tinham sido
     * <b>lidos</b> e considerados corretos, nunca <b>exercitados</b> — era o limite declarado em
     * `docs/PENDENCIAS.md` #68.
     *
     * <p>Com R$ 100 na gaveta, duas sangrias de R$ 80 disparadas juntas: uma passa, a outra tem de
     * bater no saldo. Sem a trava, as duas leem "R$ 100 disponíveis" ao mesmo tempo e o caixa fecha
     * o dia devendo R$ 60 que nunca existiram — e o dinheiro do outro lado já foi para a conta
     * corrente, então não é um número errado numa tela: é depósito registrado sem lastro.
     *
     * <p>⚠️ Não mede tempo nem dorme (ver {@code feedback_testes_frageis_por_relogio}): as duas
     * threads saem juntas de um {@link java.util.concurrent.CountDownLatch} e o que se afirma é a
     * <b>invariante</b> — uma sangria gravada, e a soma sangrada nunca acima do que havia na
     * gaveta. Se as threads não se cruzarem numa execução, o teste continua verdadeiro; ele nunca
     * acusa falso.
     */
    @Test
    void duasSangriasSimultaneasNaoTiramMaisDoQueTemNaGaveta() throws Exception {
        TenantNovo t = assinar("sangria-corrida");
        long idTenant = extrairIdTenant(t.token());
        abrirCaixaComFundo(t.token(), "100.00");
        criarConta(t.token(), t.idEmpresa(), "BB003");
        String plano = primeiroPlanoDeContas(t.token());

        String corpo = """
                {"idContaCorrente":"BB003","valor":80.00,"idPlanoContas":"%s"}
                """.formatted(plano);

        var largada = new java.util.concurrent.CountDownLatch(1);
        var prontas = new java.util.concurrent.CountDownLatch(2);
        var aceitas = new java.util.concurrent.atomic.AtomicInteger();
        var falhas = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

        Runnable sangrar = () -> {
            try {
                prontas.countDown();
                largada.await();
                int status = mvc.perform(post("/api/v1/caixa/sangrias")
                                .header("Authorization", "Bearer " + t.token())
                                .contentType(APPLICATION_JSON).content(corpo))
                        .andReturn().getResponse().getStatus();
                if (status == 200 || status == 201) {
                    aceitas.incrementAndGet();
                }
            } catch (Exception e) {
                falhas.add(e.toString());
            }
        };

        Thread a = new Thread(sangrar, "sangria-1");
        Thread b = new Thread(sangrar, "sangria-2");
        a.start();
        b.start();
        prontas.await();
        largada.countDown();
        a.join();
        b.join();

        assertThat(falhas).as("nenhuma thread pode explodir por outro motivo").isEmpty();
        assertThat(aceitas.get())
                .as("R$ 80 + R$ 80 numa gaveta com R$ 100: só uma pode passar")
                .isEqualTo(1);

        // ⭐ A prova vem do BANCO: uma linha, R$ 80,00. Contar só o status HTTP deixaria passar um
        // servidor que respondesse 409 e gravasse assim mesmo.
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*), COALESCE(SUM(valor), 0) FROM caixa_sangria WHERE id_tenant = ?")) {
            ps.setLong(1, idTenant);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).as("uma sangria, não duas").isEqualTo(1);
                assertThat(rs.getBigDecimal(2))
                        .as("não pode sair da gaveta mais do que havia nela")
                        .isEqualByComparingTo(new java.math.BigDecimal("80.00"));
            }
        }
    }

    @Test
    void semCaixaAbertoNaoHaSangria() throws Exception {
        TenantNovo t = assinar("sem-caixa");
        criarConta(t.token(), t.idEmpresa(), "BB003");
        String plano = primeiroPlanoDeContas(t.token());

        mvc.perform(get("/api/v1/caixa/sangrias/contexto").header("Authorization", "Bearer " + t.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caixaAberto").value(false));

        mvc.perform(post("/api/v1/caixa/sangrias").header("Authorization", "Bearer " + t.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"BB003","valor":10.00,"idPlanoContas":"%s"}
                                """.formatted(plano)))
                .andExpect(status().isConflict());
    }

    /**
     * O depósito da sangria <b>não</b> se edita nem se apaga pela tela de extrato.
     *
     * <p>É a lição de 2026-08-15, aplicada no mesmo dia em que a rotina nasceu: tabela de CRUD
     * manual que passa a receber lançamento automático precisa recusar a edição já — senão o
     * extrato descasa do caixa <b>em silêncio</b>, e o banco não impede porque é uma linha comum.
     */
    @Test
    void extratoRecusaEditarOuExcluirODepositoDaSangria() throws Exception {
        TenantNovo t = assinar("extrato-recusa");
        long idTenant = extrairIdTenant(t.token());
        abrirCaixaComFundo(t.token(), "400.00");
        criarConta(t.token(), t.idEmpresa(), "BB004");
        String plano = primeiroPlanoDeContas(t.token());

        mvc.perform(post("/api/v1/caixa/sangrias").header("Authorization", "Bearer " + t.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idContaCorrente":"BB004","valor":150.00,"idPlanoContas":"%s"}
                                """.formatted(plano)))
                .andExpect(status().isCreated());

        long localizador;
        try (Connection c = abrirConexao(idTenant);
             // ⚠️ Sem `id_tenant` isto pegava o PRIMEIRO movimento de sangria do banco inteiro
             // (o superusuário do Testcontainers ignora RLS), e o DELETE seguinte respondia 404
             // por procurar, no tenant deste teste, uma linha que é de outro.
             PreparedStatement ps = c.prepareStatement(
                     "SELECT localizador FROM conta_corrente_movimento "
                             + "WHERE id_tenant = ? AND id_sangria IS NOT NULL")) {
            ps.setLong(1, idTenant);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                localizador = rs.getLong(1);
            }
        }

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/contas-corrente-movimento/" + localizador)
                        .header("Authorization", "Bearer " + t.token()))
                .andExpect(status().isConflict());

        // E continua lá — o 409 não pode ter apagado nada pelo caminho.
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT valor FROM conta_corrente_movimento WHERE id_tenant = ? AND localizador = ?")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, localizador);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("150.00"));
            }
        }
    }
}
