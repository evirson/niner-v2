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
 * CRUD de plano de contas (docs/telas/plano-contas.md), revisado 2026-07-31 — DRE/DFC por
 * conta, hierarquia de 4 níveis via máscara {@code 9.99.999.999} (conta.subconta.item.subitem),
 * {@code sinal}/{@code aceitaLancamento} sempre derivados no servidor. Particularidades ainda
 * válidas: PK de negócio {@code text} (código contábil), código imutável na atualização. A
 * tabela ganhou {@code ativo} nesta revisão — a exclusão passou a cair no mesmo fallback de
 * inativar que Cliente/Funcionário/Fornecedor já tinham (antes era exceção, só excluía de
 * verdade). Contas de nível 1 (subconta "00") não exigem pai — a maioria dos testes usa esse
 * nível pra não precisar montar hierarquia; os testes que exercitam hierarquia de verdade
 * constroem grupo→subconta→item→subitem explicitamente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlanoContasCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Plano %s","email":"dono%s@lojaplano.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    /** Cria uma conta simples de nível 1 (subconta "00") — não exige pai. */
    private void criarPlanoSimples(String token, String codigo, String descricao) throws Exception {
        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"%s","descricao":"%s","tipoMovimento":"NEUTRO","natureza":"ANALITICA",
                                 "incluiDre":false,"incluiFluxoCaixa":false}
                                """.formatted(codigo, descricao)))
                .andExpect(status().isCreated());
    }

    @Test
    void criaPlanoDeContasComDadosCompletos() throws Exception {
        String token = assinarNovoTenant("completo");

        String plano = """
                {"codigo":"1.00.000.000","descricao":"receita de vendas","tipoMovimento":"CREDITO",
                 "natureza":"ANALITICA","incluiDre":true,"grupoDre":"RECEITA_BRUTA",
                 "incluiFluxoCaixa":true,"grupoDfc":"OPERACIONAL"}
                """;

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(plano))
                .andExpect(status().isCreated())
                // Descrição normalizada para MAIÚSCULAS no servidor, convenção do projeto.
                .andExpect(jsonPath("$.idPlanoContas").value("1.00.000.000"))
                .andExpect(jsonPath("$.descricao").value("RECEITA DE VENDAS"))
                .andExpect(jsonPath("$.tipoMovimento").value("CREDITO"))
                .andExpect(jsonPath("$.natureza").value("ANALITICA"))
                .andExpect(jsonPath("$.nivel").value(1))
                .andExpect(jsonPath("$.idPlanoContasPai").doesNotExist())
                .andExpect(jsonPath("$.sinal").value(1))
                .andExpect(jsonPath("$.aceitaLancamento").value(true))
                .andExpect(jsonPath("$.incluiDre").value(true))
                .andExpect(jsonPath("$.grupoDre").value("RECEITA_BRUTA"))
                .andExpect(jsonPath("$.grupoDfc").value("OPERACIONAL"))
                .andExpect(jsonPath("$.padraoSistema").value(false))
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andExpect(jsonPath("$.atualizadoEm").exists());

        mvc.perform(get("/api/v1/planos-contas").param("busca", "1.00").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].idPlanoContas").value("1.00.000.000"));
    }

    @Test
    void codigoDuplicadoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("duplicado");
        criarPlanoSimples(token, "1.00.000.000", "CAIXA GERAL");

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"1.00.000.000","descricao":"Outra descricao","tipoMovimento":"DEBITO",
                                 "natureza":"ANALITICA","incluiDre":false,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void tipoMovimentoInvalidoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("tipo-invalido");

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"9.00.000.000","descricao":"Tipo errado","tipoMovimento":"CRÉDITO",
                                 "natureza":"ANALITICA","incluiDre":false,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void naturezaInvalidaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("natureza-invalida");

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"9.00.000.000","descricao":"Natureza errada","tipoMovimento":"NEUTRO",
                                 "natureza":"OUTRA","incluiDre":false,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void codigoForaDaMascaraEhRejeitado() throws Exception {
        // Formato antigo ("3.1.001", livre) não existe mais — máscara fixa 9.99.999.999.
        String token = assinarNovoTenant("mascara-invalida");

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"3.1.001","descricao":"Formato antigo","tipoMovimento":"NEUTRO",
                                 "natureza":"ANALITICA","incluiDre":false,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contaNeutraNaoPodeComporADre() throws Exception {
        String token = assinarNovoTenant("neutro-dre");

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"9.00.000.000","descricao":"Neutro com DRE","tipoMovimento":"NEUTRO",
                                 "natureza":"ANALITICA","incluiDre":true,"grupoDre":"RECEITA_BRUTA",
                                 "incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void incluiDreSemGrupoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("dre-sem-grupo");

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"9.00.000.000","descricao":"Sem grupo","tipoMovimento":"CREDITO",
                                 "natureza":"ANALITICA","incluiDre":true,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void criarContaSemPaiExistenteRespondeErroAmigavel() throws Exception {
        // "9.01.001.001" é nível 4 — exige que "9.01.001.000"/"9.01.000.000"/"9.00.000.000" já existam.
        String token = assinarNovoTenant("sem-pai");

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"9.01.001.001","descricao":"Folha orfa","tipoMovimento":"NEUTRO",
                                 "natureza":"ANALITICA","incluiDre":false,"incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("9.01.001.000")));
    }

    @Test
    void hierarquiaCompletaDeQuatroNiveisFuncionaDeCimaParaBaixo() throws Exception {
        String token = assinarNovoTenant("hierarquia");

        criarPlanoSimples(token, "9.00.000.000", "GRUPO TESTE");
        criarPlanoSimples(token, "9.01.000.000", "SUBCONTA TESTE");
        criarPlanoSimples(token, "9.01.001.000", "ITEM TESTE");

        mvc.perform(post("/api/v1/planos-contas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"9.01.001.001","descricao":"Subitem folha","tipoMovimento":"DEBITO",
                                 "natureza":"ANALITICA","incluiDre":true,"grupoDre":"DESPESA_FIXA",
                                 "incluiFluxoCaixa":true,"grupoDfc":"OPERACIONAL"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nivel").value(4))
                .andExpect(jsonPath("$.idPlanoContasPai").value("9.01.001.000"))
                .andExpect(jsonPath("$.sinal").value(-1));
    }

    @Test
    void atualizarMudaDescricaoMasNaoOCodigo() throws Exception {
        String token = assinarNovoTenant("atualiza");
        criarPlanoSimples(token, "2.00.000.000", "DESPESA ORIGINAL");

        // O corpo tenta trocar o código — o do path prevalece e o registro continua o mesmo.
        mvc.perform(put("/api/v1/planos-contas/2.00.000.000").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"codigo":"9.99.999.999","descricao":"despesa corrigida","tipoMovimento":"DEBITO",
                                 "natureza":"ANALITICA","incluiDre":true,"grupoDre":"DESPESA_FIXA",
                                 "incluiFluxoCaixa":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPlanoContas").value("2.00.000.000"))
                .andExpect(jsonPath("$.descricao").value("DESPESA CORRIGIDA"))
                .andExpect(jsonPath("$.incluiDre").value(true));

        mvc.perform(get("/api/v1/planos-contas/9.99.999.999").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirPlanoSemVinculoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        criarPlanoSimples(token, "4.00.000.000", "SEM VINCULO");

        mvc.perform(delete("/api/v1/planos-contas/4.00.000.000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/planos-contas/4.00.000.000").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirPlanoComFornecedorVinculadoInativaEmVezDeExcluir() throws Exception {
        // 2026-07-31: cfg_plano_contas ganhou `ativo` — mesmo fallback de Cliente/Funcionário/Fornecedor.
        String token = assinarNovoTenant("exclusao-vinculo");
        criarPlanoSimples(token, "5.00.000.000", "COM FORNECEDOR");
        long idTenant = extrairIdTenant(token);

        criarFornecedorComPlano(idTenant, "5.00.000.000");

        mvc.perform(delete("/api/v1/planos-contas/5.00.000.000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        mvc.perform(get("/api/v1/planos-contas/5.00.000.000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descricao").value("COM FORNECEDOR"))
                .andExpect(jsonPath("$.ativo").value(false));
    }

    @Test
    void excluirPlanoComCaixaVinculadoInativaEmVezDeExcluir() throws Exception {
        String token = assinarNovoTenant("exclusao-caixa");
        criarPlanoSimples(token, "8.00.000.000", "COM CAIXA");
        long idTenant = extrairIdTenant(token);

        criarCaixaComPlano(idTenant, "8.00.000.000");

        mvc.perform(delete("/api/v1/planos-contas/8.00.000.000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        mvc.perform(get("/api/v1/planos-contas/8.00.000.000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
    }

    @Test
    void excluirContaComFilhosInativaEmVezDeExcluir() throws Exception {
        String token = assinarNovoTenant("exclusao-filhos");
        criarPlanoSimples(token, "6.00.000.000", "GRUPO COM FILHO");
        criarPlanoSimples(token, "6.01.000.000", "SUBCONTA FILHA");

        mvc.perform(delete("/api/v1/planos-contas/6.00.000.000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        mvc.perform(get("/api/v1/planos-contas/6.00.000.000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
    }

    @Test
    void excluirContaPadraoDoSistemaInativaEmVezDeExcluir() throws Exception {
        // padrao_sistema nunca é setável via API (só o seed padrão grava true) — insere direto
        // via SQL pra simular uma conta do template, mesmo padrão de setup já usado neste arquivo.
        String token = assinarNovoTenant("exclusao-padrao");
        long idTenant = extrairIdTenant(token);
        criarPlanoSimples(token, "7.00.000.000", "CONTA PADRAO");
        marcarComoPadraoSistema(idTenant, "7.00.000.000");

        mvc.perform(delete("/api/v1/planos-contas/7.00.000.000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"))
                .andExpect(jsonPath("$.motivo").value(org.hamcrest.Matchers.containsString("padrão")));
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        String token = assinarNovoTenant("ordenacao");
        criarPlanoSimples(token, "1.00.000.000", "ORDPLANO BETA");
        criarPlanoSimples(token, "2.00.000.000", "ORDPLANO ALFA");
        // 4.00.000.000, não 3.00.000.000: esse já nasce seedado (V032, "CUSTOS VARIÁVEIS") pra
        // a Entrada de Produtos por Compra.
        criarPlanoSimples(token, "4.00.000.000", "ORDPLANO GAMA");

        mvc.perform(get("/api/v1/planos-contas").param("busca", "ORDPLANO")
                        .param("ordenarPor", "descricao").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].descricao").value("ORDPLANO GAMA"));
    }

    @Test
    void buscaEncontraPorDescricao() throws Exception {
        String token = assinarNovoTenant("busca");
        criarPlanoSimples(token, "7.00.000.000", "ALUGUEL DA LOJA");

        mvc.perform(get("/api/v1/planos-contas").param("busca", "ALUGUEL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].idPlanoContas").value("7.00.000.000"));
    }

    @Test
    void filtroDeStatusRespeitaAtivosInativosETodos() throws Exception {
        // A segunda conta precisa de um vínculo pra exclusão cair no fallback de inativar —
        // sem vínculo, excluir() apaga de verdade e ela some da tabela (não fica "inativa").
        String token = assinarNovoTenant("status");
        long idTenant = extrairIdTenant(token);
        criarPlanoSimples(token, "1.00.000.000", "STATUSPLANO ATIVA");
        criarPlanoSimples(token, "2.00.000.000", "STATUSPLANO INATIVA");
        criarFornecedorComPlano(idTenant, "2.00.000.000");
        mvc.perform(delete("/api/v1/planos-contas/2.00.000.000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        mvc.perform(get("/api/v1/planos-contas").param("busca", "STATUSPLANO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1));

        mvc.perform(get("/api/v1/planos-contas").param("busca", "STATUSPLANO").param("status", "TODOS")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(2));
    }

    @Test
    void isolamentoEntreTenantsNoMesmoCodigo() throws Exception {
        // PK composta (id_tenant, id_plano_contas) — o MESMO código em tenants diferentes.
        String tokenA = assinarNovoTenant("isolamento-a");
        String tokenB = assinarNovoTenant("isolamento-b");
        criarPlanoSimples(tokenA, "1.00.000.000", "CONTA DO TENANT A");

        mvc.perform(get("/api/v1/planos-contas/1.00.000.000").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    /** Insere um fornecedor mínimo referenciando o plano — vínculo que passa a inativar a exclusão. */
    private void criarFornecedorComPlano(long idTenant, String idPlanoContas) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            st.executeUpdate(
                    "INSERT INTO fornecedor (id_tenant, id_plano_contas, razao_social) VALUES ("
                            + idTenant + ", '" + idPlanoContas + "', 'FORNECEDOR TESTE')");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Insere um caixa (mestre + detalhe) referenciando o plano — vínculo que passa a inativar a
     * exclusão. Reaproveita empresa/usuário admin e um tipo de carteira semeados pelo signup.
     */
    private void criarCaixaComPlano(long idTenant, String idPlanoContas) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            long idEmpresa;
            long idUsuario;
            long idCarteira;
            try (ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
                rs.next();
                idEmpresa = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT id_usuario FROM usuario LIMIT 1")) {
                rs.next();
                idUsuario = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT id_carteira FROM tipo_carteira LIMIT 1")) {
                rs.next();
                idCarteira = rs.getLong(1);
            }
            long idCaixa;
            try (ResultSet rs = st.executeQuery(
                    "INSERT INTO caixa_mestre (id_tenant, id_empresa, id_usuario, id_carteira) VALUES ("
                            + idTenant + ", " + idEmpresa + ", " + idUsuario + ", " + idCarteira + ") RETURNING id_caixa")) {
                rs.next();
                idCaixa = rs.getLong(1);
            }
            st.executeUpdate("""
                    INSERT INTO caixa_detalhe
                        (id_tenant, id_caixa, id_carteira, id_plano_contas, valor, tipo_operacao, credito_debito)
                    VALUES (%d, %d, %d, '%s', 10.00, 'DEBITO_CAIXA', 'D')
                    """.formatted(idTenant, idCaixa, idCarteira, idPlanoContas));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** {@code padrao_sistema} só é gravado pelo script de seed — simula isso pra testar a
     *  proteção contra exclusão sem depender do seed real (que roda fora das migrations). */
    private void marcarComoPadraoSistema(long idTenant, String idPlanoContas) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            st.executeUpdate(
                    "UPDATE cfg_plano_contas SET padrao_sistema = true WHERE id_tenant = " + idTenant
                            + " AND id_plano_contas = '" + idPlanoContas + "'");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
