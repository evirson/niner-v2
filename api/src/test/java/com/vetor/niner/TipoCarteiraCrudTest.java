package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRUD de tipo de carteira — mesmo padrão de {@link PlanoContasCrudTest}. Absorveu o cadastro
 * de {@code moeda} em 2026-07-28 (motivo completo no topo de {@code
 * V025__financeiro_caixa_crediario.sql}): {@code percDesconto}/{@code percAcrescimo} vieram de
 * lá, e a chave única agora é {@code (nome_carteira, categoria_carteira)} — a mesma bandeira
 * pode existir uma vez por categoria (ex.: "HIPER" em débito e "HIPER" em crédito, prazo/taxa
 * independentes), mas não duas vezes na mesma categoria. Sem coluna {@code ativo}: exclusão
 * sem fallback de inativar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TipoCarteiraCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Carteira %s","email":"dono%s@lojacarteira.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void tenantNovoJaNasceComSeisTiposDeCarteiraSemeados() throws Exception {
        String token = assinarNovoTenant("seed");

        mvc.perform(get("/api/v1/tipos-carteira").param("limite", "20").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(6));
    }

    @Test
    void criaTipoCarteiraComDescontoEAcrescimo() throws Exception {
        String token = assinarNovoTenant("com-desconto");

        String carteira = """
                {"nomeCarteira":"crediario 30/60/90","categoriaCarteira":"CREDIARIO","prazoPagamento":30,
                 "pcMinima":1,"pcMaxima":3,"taxaAdministradora":2.5,"percDesconto":0,"percAcrescimo":0,
                 "permiteReceberCrediario":false}
                """;

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(carteira))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nomeCarteira").value("CREDIARIO 30/60/90"))
                .andExpect(jsonPath("$.categoriaCarteira").value("CREDIARIO"))
                .andExpect(jsonPath("$.pcMinima").value(1))
                .andExpect(jsonPath("$.pcMaxima").value(3))
                .andExpect(jsonPath("$.permiteReceberCrediario").value(false))
                .andExpect(jsonPath("$.criadoEm").exists());
    }

    @Test
    void carteiraPodeSerMarcadaPermiteReceberCrediario() throws Exception {
        String token = assinarNovoTenant("permite-receber");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"DINHEIRO CREDIARIO","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"permiteReceberCrediario":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permiteReceberCrediario").value(true));
    }

    /**
     * O motivo inteiro da junção com moeda (2026-07-28): a mesma bandeira em categorias
     * diferentes tem prazo/parcelas/taxa diferentes — precisa poder existir como duas linhas.
     */
    @Test
    void mesmoNomeEmCategoriasDiferentesEhPermitido() throws Exception {
        String token = assinarNovoTenant("mesma-bandeira");
        criarCarteira(token, "HIPER", "CARTAO_DEBITO", 1, 1, 1, "1.5");
        criarCarteira(token, "HIPER", "CARTAO_CREDITO", 30, 1, 6, "2.8");

        mvc.perform(get("/api/v1/tipos-carteira").param("busca", "HIPER").param("limite", "20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(2));
    }

    @Test
    void mesmoNomeNaMesmaCategoriaEhRejeitado() throws Exception {
        String token = assinarNovoTenant("nome-duplicado");
        criarCarteira(token, "CARTEIRA UNICA", "CREDIARIO", 30, 1, 1, "0");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"carteira unica","categoriaCarteira":"CREDIARIO","prazoPagamento":15,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":0,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void parcelaMaximaMenorQueMinimaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("parcela-invalida");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA PARCELA INVALIDA","categoriaCarteira":"CREDIARIO","prazoPagamento":30,
                                 "pcMinima":5,"pcMaxima":2,"taxaAdministradora":0,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taxaAdministradoraNegativaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("taxa-negativa");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA TAXA INVALIDA","categoriaCarteira":"CREDIARIO","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":-1,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** Opcional (2026-07-23) — nem todo tipo de carteira cobra taxa administradora. */
    @Test
    void taxaAdministradoraPodeFicarEmBrancoEPrazoPodeSerZero() throws Exception {
        String token = assinarNovoTenant("taxa-em-branco");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA SEM TAXA","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxaAdministradora").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.prazoPagamento").value(0));
    }

    /** Categoria (2026-07-23) é obrigatória — histórico do cliente depende dela pra isolar crediário. */
    @Test
    void categoriaCarteiraAusenteEhRejeitada() throws Exception {
        String token = assinarNovoTenant("categoria-ausente");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA SEM CATEGORIA","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":1,"taxaAdministradora":0,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void percentualDescontoNegativoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("percentual-negativo");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA PERCENTUAL INVALIDO","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"percDesconto":-1,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    /**
     * ⛔ <b>Percentual acima de 100 passou a ser recusado</b> (auditoria de segurança, 2026-08-27).
     *
     * <p>⚠️ Este teste é a <b>inversão</b> de {@code percentualAcimaDeCemEhAceito}, que prendia o
     * comportamento anterior ("sem limite superior, herdado de moeda"). Ele foi invertido, não
     * apagado: o que ele prende agora é a regra nova.
     *
     * <p>O furo: a coluna é {@code numeric(5,2)} (até 999,99) e só o negativo era barrado. O PDV
     * calcula a cobertura como {@code valorPago × (1 + perc/100)} — com <b>999,99%</b>, R$ 10
     * fechavam uma venda de R$ 109,99, <b>contornando inteiramente</b> o desconto máximo dos
     * Parâmetros do Sistema (que vigia outro campo). E a tela Tipos de Carteira não é exclusiva do
     * administrador.
     */
    @Test
    void percentualAcimaDeCemEhRejeitado() throws Exception {
        String token = assinarNovoTenant("percentual-alto");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA ACRESCIMO ALTO","categoriaCarteira":"CARTAO_CREDITO","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":6,"percAcrescimo":150,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());

        // O desconto é o caminho que vira dinheiro na venda — mesmo teto, teste próprio.
        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA DESCONTO ABSURDO","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"percDesconto":999.99,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** 100% continua valendo: é o limite, não o proibido. */
    @Test
    void percentualDeCemEhAceito() throws Exception {
        String token = assinarNovoTenant("percentual-cem");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA CEM","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"percDesconto":100,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.percDesconto").value(100));
    }

    /**
     * A checagem é por valor positivo, não por presença (2026-07-23, herdado de moeda) — 0/0 é
     * o estado neutro normal (toda carteira semeada no signup nasce assim) e não pode rejeitar.
     */
    @Test
    void descontoEAcrescimoZeradosJuntosSaoAceitos() throws Exception {
        String token = assinarNovoTenant("zerados-juntos");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA NEUTRA","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"percDesconto":0,"percAcrescimo":0,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void descontoEAcrescimoPositivosJuntosSaoRejeitados() throws Exception {
        String token = assinarNovoTenant("ambos-positivos");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA INVALIDA","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"percDesconto":5,"percAcrescimo":3,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizarMudaPercentuais() throws Exception {
        String token = assinarNovoTenant("atualiza-percentual");
        long id = criarCarteira(token, "CARTEIRA ATUALIZA PCT", "AVISTA", 0, 1, 1, "0");

        mvc.perform(put("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA ATUALIZA PCT","categoriaCarteira":"AVISTA","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"percDesconto":5,"permiteReceberCrediario":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percDesconto").value(5));
    }

    @Test
    void excluirCarteiraSemVinculoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        long id = criarCarteira(token, "CARTEIRA SEM VINCULO", "CREDIARIO", 30, 1, 1, "0");

        mvc.perform(delete("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirCarteiraComContaAReceberVinculadaRespondeConflito() throws Exception {
        String token = assinarNovoTenant("exclusao-vinculo-cr");
        long idTenant = extrairIdTenant(token);
        long id = criarCarteira(token, "CARTEIRA COM VINCULO CR", "CREDIARIO", 30, 1, 1, "0");

        criarContaReceberComCarteira(idTenant, id);

        mvc.perform(delete("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeCarteira").value("CARTEIRA COM VINCULO CR"));
    }

    /** Vínculo novo desde que caixa_detalhe passou a referenciar tipo_carteira (2026-07-28). */
    @Test
    void excluirCarteiraComLancamentoDeCaixaVinculadoRespondeConflito() throws Exception {
        String token = assinarNovoTenant("exclusao-vinculo-caixa");
        long idTenant = extrairIdTenant(token);
        long id = criarCarteira(token, "CARTEIRA COM VINCULO CAIXA", "AVISTA", 0, 1, 1, "0");

        criarLancamentoDeCaixaComCarteira(idTenant, id);

        mvc.perform(delete("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /**
     * ⚠️ 2026-08-31 — o guard conhecia 2 das 5 FKs, e este é o caso mais banal dos três que
     * faltavam: a carteira <b>DINHEIRO</b> é a carteira de <b>abertura</b> do caixa
     * ({@code caixa_mestre.id_carteira}), e num dia sem nenhum lançamento ela não aparece em
     * {@code caixa_detalhe}. Sem a correção, o DELETE ia ao banco, violava a FK, e o
     * {@code GlobalExceptionHandler} respondia o 409 genérico "Registro em uso por outro
     * cadastro" — sem dizer onde, e sem caminho de volta (esta tabela não tem "inativar").
     *
     * <p>⭐ O que separa este teste do irmão acima é o helper: ali o caixa nasce <b>com</b>
     * detalhe, então o guard antigo já pegava. Aqui só existe a abertura.
     */
    @Test
    void excluirCarteiraUsadaSoNaAberturaDeCaixaRespondeConflitoDizendoOnde() throws Exception {
        String token = assinarNovoTenant("exclusao-vinculo-abertura");
        long idTenant = extrairIdTenant(token);
        long id = criarCarteira(token, "CARTEIRA SO ABERTURA", "AVISTA", 0, 1, 1, "0");

        abrirCaixaComCarteira(idTenant, id);

        mvc.perform(delete("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("abertura de caixa")));

        // ⭐ e o 409 não pode ter apagado nada pelo caminho.
        mvc.perform(get("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /**
     * A terceira FK esquecida: a conferência gravada no fechamento do caixa. Uma carteira que só
     * apareceu numa conferência (o caixa fechou com ela zerada, sem lançamento) prendia a
     * exclusão pelo mesmo 409 mudo.
     */
    @Test
    void excluirCarteiraUsadaSoNaConferenciaDeFechamentoRespondeConflitoDizendoOnde() throws Exception {
        String token = assinarNovoTenant("exclusao-vinculo-conferencia");
        long idTenant = extrairIdTenant(token);
        long idAbertura = criarCarteira(token, "CARTEIRA ABERTURA CONF", "AVISTA", 0, 1, 1, "0");
        long idConferida = criarCarteira(token, "CARTEIRA SO CONFERENCIA", "CARTAO_DEBITO", 30, 1, 1, "0");

        long idCaixa = abrirCaixaComCarteira(idTenant, idAbertura);
        criarConferenciaDeFechamento(idTenant, idCaixa, idConferida);

        mvc.perform(delete("/api/v1/tipos-carteira/" + idConferida).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("conferência de fechamento")));
    }

    /** Fiscal (2026-08-18, `docs/MODULOFISCAL.md` §6.4) — o CRUD só ganhou os campos, sem
     *  torná-los obrigatórios: quem cobra o preenchimento é a Conformidade Fiscal. */
    @Test
    void criaTipoCarteiraComDadosFiscais() throws Exception {
        String token = assinarNovoTenant("dados-fiscais");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"AMEX","categoriaCarteira":"CARTAO_CREDITO","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":6,"permiteReceberCrediario":false,
                                 "codigoTpag":"03","codigoBandeira":"03","cnpjCredenciadora":"11.222.333/0001-81"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codigoTpag").value("03"))
                .andExpect(jsonPath("$.codigoBandeira").value("03"))
                .andExpect(jsonPath("$.cnpjCredenciadora").value("11222333000181"));
    }

    /** Sem preencher nada de fiscal, os 3 campos voltam {@code null} — nunca string vazia
     *  (a Conformidade Fiscal checa {@code IS NULL}). */
    @Test
    void dadosFiscaisFicamNulosQuandoNaoInformados() throws Exception {
        String token = assinarNovoTenant("sem-dados-fiscais");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA SEM FISCAL","categoriaCarteira":"CARTAO_DEBITO","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"permiteReceberCrediario":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codigoTpag").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.codigoBandeira").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.cnpjCredenciadora").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void cnpjCredenciadoraInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("cnpj-credenciadora-invalido");

        mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA CNPJ INVALIDO","categoriaCarteira":"CARTAO_CREDITO","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":1,"permiteReceberCrediario":false,
                                 "codigoTpag":"03","cnpjCredenciadora":"11.111.111/1111-11"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizarPreencheDadosFiscaisDeUmaCarteiraSemeadaSemEles() throws Exception {
        String token = assinarNovoTenant("atualiza-fiscal");
        long id = criarCarteira(token, "CARTEIRA ATUALIZA FISCAL", "CARTAO_DEBITO", 0, 1, 1, "0");

        mvc.perform(put("/api/v1/tipos-carteira/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"CARTEIRA ATUALIZA FISCAL","categoriaCarteira":"CARTAO_DEBITO","prazoPagamento":0,
                                 "pcMinima":1,"pcMaxima":1,"permiteReceberCrediario":false,
                                 "codigoTpag":"04","codigoBandeira":"01"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigoTpag").value("04"))
                .andExpect(jsonPath("$.codigoBandeira").value("01"));
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        String token = assinarNovoTenant("ordenacao");
        criarCarteira(token, "ORDCARTEIRA BETA", "CREDIARIO", 30, 1, 1, "0");
        criarCarteira(token, "ORDCARTEIRA ALFA", "CREDIARIO", 30, 1, 1, "0");
        criarCarteira(token, "ORDCARTEIRA GAMA", "CREDIARIO", 30, 1, 1, "0");

        mvc.perform(get("/api/v1/tipos-carteira").param("busca", "ORDCARTEIRA")
                        .param("ordenarPor", "nomeCarteira").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nomeCarteira").value("ORDCARTEIRA GAMA"));
    }

    private long criarCarteira(String token, String nome, String categoria, int prazoPagamento, int pcMinima,
                                int pcMaxima, String taxaAdministradora) throws Exception {
        String resp = mvc.perform(post("/api/v1/tipos-carteira").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"%s","categoriaCarteira":"%s","prazoPagamento":%d,"pcMinima":%d,"pcMaxima":%d,
                                 "taxaAdministradora":%s,"permiteReceberCrediario":false}
                                """.formatted(nome, categoria, prazoPagamento, pcMinima, pcMaxima, taxaAdministradora)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCarteira")).longValue();
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    /**
     * Insere uma venda + parcela mínimas referenciando a carteira — vínculo que bloqueia a
     * exclusão. Não existe API de escrita pra venda/contas_receber ainda (mesmo caso de
     * {@code ClienteHistoricoCrudTest}), então vai direto via JDBC.
     */
    private void criarContaReceberComCarteira(long idTenant, long idCarteira) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            long idEmpresa;
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                idEmpresa = rs.getLong(1);
            }
            long idVenda;
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO venda (id_tenant, id_empresa) VALUES (" + idTenant + ", " + idEmpresa
                            + ") RETURNING id_venda")) {
                rs.next();
                idVenda = rs.getLong(1);
            }
            st.executeUpdate("""
                    INSERT INTO contas_receber
                        (id_tenant, id_venda, id_carteira, numero_parcela, data_vencimento, valor_receber)
                    VALUES (%d, %d, %d, 1, now(), 100.00)
                    """.formatted(idTenant, idVenda, idCarteira));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * P8 na forma de pagamento (pendência 48, 2026-08-31). ⭐ Aqui a alteração cruzada valeria
     * <b>dinheiro direto</b>: quem conseguisse editar a carteira do vizinho mudaria o
     * {@code percDesconto} dela e faria toda venda daquela loja fechar mais barato.
     */
    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("isolamento-carteira-a");
        String tokenB = assinarNovoTenant("isolamento-carteira-b");
        long idA = criarCarteira(tokenA, "CARTEIRA EXCLUSIVA DO TENANT A", "CARTAO_CREDITO", 30, 1, 6, "2.50");

        mvc.perform(get("/api/v1/tipos-carteira/" + idA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/tipos-carteira/" + idA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/tipos-carteira?busca=EXCLUSIVA DO TENANT")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(0));

        mvc.perform(put("/api/v1/tipos-carteira/" + idA).header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"nomeCarteira":"SEQUESTRADA","categoriaCarteira":"CARTAO_CREDITO","prazoPagamento":30,
                                 "pcMinima":1,"pcMaxima":6,"percDesconto":99,"permiteReceberCrediario":true}
                                """))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/tipos-carteira/" + idA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // o desconto do dono continua o que ele configurou
        mvc.perform(get("/api/v1/tipos-carteira/" + idA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeCarteira").value("CARTEIRA EXCLUSIVA DO TENANT A"))
                .andExpect(jsonPath("$.taxaAdministradora").value(2.50));
    }

    /** Só a ABERTURA do caixa (sem nenhum lançamento), que é o vínculo mais fácil de esquecer. */
    private long abrirCaixaComCarteira(long idTenant, long idCarteira) {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            long idEmpresa;
            long idUsuario;
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                idEmpresa = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT id_usuario FROM usuario LIMIT 1")) {
                rs.next();
                idUsuario = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO caixa_mestre (id_tenant, id_empresa, id_usuario, id_carteira) VALUES ("
                            + idTenant + ", " + idEmpresa + ", " + idUsuario + ", " + idCarteira + ") RETURNING id_caixa")) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Uma linha de conferência do fechamento, apontando para outra carteira que não a de abertura. */
    private void criarConferenciaDeFechamento(long idTenant, long idCaixa, long idCarteira) {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            st.executeUpdate("""
                    INSERT INTO caixa_fechamento_conferencia
                        (id_tenant, id_caixa, id_carteira, valor_esperado, valor_contado)
                    VALUES (%d, %d, %d, 0.00, 0.00)
                    """.formatted(idTenant, idCaixa, idCarteira));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Insere um caixa (mestre + detalhe) referenciando a carteira — mesmo padrão de {@link PlanoContasCrudTest}. */
    private void criarLancamentoDeCaixaComCarteira(long idTenant, long idCarteira) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            long idEmpresa;
            long idUsuario;
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                idEmpresa = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT id_usuario FROM usuario LIMIT 1")) {
                rs.next();
                idUsuario = rs.getLong(1);
            }
            long idCaixa;
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO caixa_mestre (id_tenant, id_empresa, id_usuario, id_carteira) VALUES ("
                            + idTenant + ", " + idEmpresa + ", " + idUsuario + ", " + idCarteira + ") RETURNING id_caixa")) {
                rs.next();
                idCaixa = rs.getLong(1);
            }
            st.executeUpdate("""
                    INSERT INTO caixa_detalhe (id_tenant, id_caixa, id_carteira, valor, tipo_operacao, credito_debito)
                    VALUES (%d, %d, %d, 10.00, 'DEBITO_CAIXA', 'D')
                    """.formatted(idTenant, idCaixa, idCarteira));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
