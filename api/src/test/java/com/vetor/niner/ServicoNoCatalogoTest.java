package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bloco <b>S1</b> do módulo de serviços (V085/V086) — <i>o item que não é mercadoria</i>.
 * Spec: {@code docs/MODULOSERVICOS.md} §3.4 (DS1), §3.5 (DS2).
 *
 * <p>⚠️ <b>O que estes testes protegem, e por que conferem o BANCO e não o HTTP.</b> A decisão de
 * modelagem (serviço mora em {@code produto}, sem estoque) só é segura enquanto o efeito colateral
 * <i>não</i> acontecer. Um teste que valide só o status da resposta passaria com serviço criando
 * saldo em {@code produto_estoque} — que é exatamente o defeito que não daria erro em lugar nenhum
 * e apareceria meses depois, num Relatório de Estoque convidando o lojista a contar banhos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ServicoNoCatalogoTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Servico %s","email":"dono%s@lojaservico.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private long idTenantDoToken(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.id_tenant")).longValue();
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    /** Liga o módulo — desligado por padrão (decisão do dono do produto, 2026-08-28). */
    private void ligarServicos(String token) throws Exception {
        String atual = mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String body = atual.replaceFirst("\"cfgUsaServicos\":\\s*false", "\"cfgUsaServicos\":true");
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private long criarProduto(String token, String descricao, String tipoItem) throws Exception {
        String servico = tipoItem == null ? "" : ",\"tipoItem\":\"%s\"".formatted(tipoItem);
        String body = """
                {"descricao":"%s","precoCusto":10.00,"percentualVenda":0,"precoVenda":80.00%s}
                """.formatted(descricao, servico);
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    /**
     * ⚠️ <b>Obter-ou-criar, não criar</b> (2026-08-31). Desde que o serviço passou a nascer com a
     * própria variação — sem ela ficava invisível na OS e no PDV —, o INSERT cego batia em
     * {@code produto_barra_variacao_uk} e derrubava <b>nove</b> testes de uma vez. Eles não estavam
     * errados: criavam a variação à mão porque o cadastro não criava, e o que precisam é
     * <b>ter uma</b> para exercitar outra coisa. Quem prende a criação é
     * {@code servicoNasceComVariacaoEApareceNaBuscaDeItens}; aqui o nome passou a dizer a verdade.
     */
    private long criarVariacao(Connection c, long idTenant, long idProduto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id_variacao FROM produto_barra WHERE id_tenant = ? AND id_produto = ? LIMIT 1")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_barra (id_tenant, id_produto, sku) VALUES (?, ?, gerar_ean13_interno())"
                        + " RETURNING id_variacao")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** A empresa do tenant, pelo caminho da API — a Conformidade Fiscal pede o id no path. */
    private long idEmpresaDoTenant(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/empresas").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$[0].idEmpresa")).longValue();
    }

    private long buscarIdEmpresa(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Lança uma saída (venda) da variação e devolve o id do movimento, para poder apagar depois. */
    private long lancarSaida(Connection c, long idTenant, long idEmpresa, long idVariacao) throws SQLException {
        long idMovimento;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_movimento_mestre (id_tenant, tipo_movimento, data_movimento, id_empresa)"
                        + " VALUES (?, 'VENDA', now(), ?) RETURNING id_movimento")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                idMovimento = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_movimento_detalhe
                    (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto, preco_venda)
                VALUES (?, ?, ?, ?, 'D', 1, 80.00)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idMovimento);
            ps.setLong(3, idEmpresa);
            ps.setLong(4, idVariacao);
            ps.executeUpdate();
        }
        return idMovimento;
    }

    private int linhasDeEstoque(Connection c, long idVariacao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM produto_estoque WHERE id_variacao = ?")) {
            ps.setLong(1, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private BigDecimal saldo(Connection c, long idVariacao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT qtd_estoque FROM produto_estoque WHERE id_variacao = ?")) {
            ps.setLong(1, idVariacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    // ---------------------------------------------------------------- o caso que a feature existe para

    @Test
    void servicoVendidoNaoCriaSaldoDeEstoque() throws Exception {
        String token = assinarNovoTenant("a");
        long idTenant = idTenantDoToken(token);
        ligarServicos(token);
        long idServico = criarProduto(token, "BANHO E TOSA", "SERVICO");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idServico);
            lancarSaida(c, idTenant, idEmpresa, idVariacao);

            // ⭐ Zero LINHAS, não saldo zero: a trigger sai antes do UPSERT, então produto_estoque
            // nem chega a conhecer o serviço. Saldo 0 e ausência de linha são coisas diferentes —
            // a primeira apareceria no Relatório de Estoque, a segunda não.
            assertThat(linhasDeEstoque(c, idVariacao)).isZero();
        }
    }

    /**
     * ⭐ O par negativo — sem ele, um curto-circuito grande demais (que desligasse o estoque para
     * todo mundo) passaria verde. É a lição de {@code feedback_caso_negativo_pega_guarda_que_expulsa_todos}.
     */
    @Test
    void mercadoriaVendidaContinuaBaixandoEstoqueNormalmente() throws Exception {
        String token = assinarNovoTenant("b");
        long idTenant = idTenantDoToken(token);
        long idProduto = criarProduto(token, "COLEIRA", null);   // sem tipoItem = MERCADORIA

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            lancarSaida(c, idTenant, idEmpresa, idVariacao);

            assertThat(linhasDeEstoque(c, idVariacao)).isEqualTo(1);
            assertThat(saldo(c, idVariacao)).isEqualByComparingTo("-1.000");
        }
    }

    /**
     * O ramo do DELETE. Sem o curto-circuito ali, apagar o item de serviço de uma venda cancelada
     * <b>creditaria</b> estoque — criando do nada a linha que a venda nunca criou.
     */
    @Test
    void apagarMovimentoDeServicoNaoCreditaEstoque() throws Exception {
        String token = assinarNovoTenant("c");
        long idTenant = idTenantDoToken(token);
        ligarServicos(token);
        long idServico = criarProduto(token, "CONSULTA VETERINARIA", "SERVICO");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idServico);
            long idMovimento = lancarSaida(c, idTenant, idEmpresa, idVariacao);

            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM produto_movimento_detalhe WHERE id_movimento = ?")) {
                ps.setLong(1, idMovimento);
                ps.executeUpdate();
            }
            assertThat(linhasDeEstoque(c, idVariacao)).isZero();
        }
    }

    /**
     * ⭐ O item <b>continua no ledger</b> — é isso que faz serviço existir para a DRE, a
     * Lucratividade, as Comissões, o Relatório de Vendas e a papeleta (§2.3 do estudo: 33 leitores).
     * Se alguém "otimizar" deixando de gravar o movimento, o serviço some de todos eles em silêncio.
     */
    @Test
    void servicoAparaceNoLedgerDeMovimento() throws Exception {
        String token = assinarNovoTenant("d");
        long idTenant = idTenantDoToken(token);
        ligarServicos(token);
        long idServico = criarProduto(token, "TOSA HIGIENICA", "SERVICO");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idServico);
            lancarSaida(c, idTenant, idEmpresa, idVariacao);

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM produto_movimento_detalhe WHERE id_variacao = ?")) {
                ps.setLong(1, idVariacao);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    // ---------------------------------------------------------------- cadastro

    @Test
    void aVariacaoHerdaOTipoDoProduto() throws Exception {
        String token = assinarNovoTenant("e");
        long idTenant = idTenantDoToken(token);
        ligarServicos(token);
        long idServico = criarProduto(token, "HIDRATACAO", "SERVICO");

        try (Connection c = abrirConexao(idTenant)) {
            long idVariacao = criarVariacao(c, idTenant, idServico);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT tipo_item::text FROM produto_barra WHERE id_variacao = ?")) {
                ps.setLong(1, idVariacao);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("SERVICO");
                }
            }
        }
    }

    @Test
    void servicoGuardaDuracaoEComissaoPropria() throws Exception {
        String token = assinarNovoTenant("f");
        ligarServicos(token);
        String body = """
                {"descricao":"BANHO GRANDE PORTE","precoCusto":0,"percentualVenda":0,"precoVenda":120.00,
                 "tipoItem":"SERVICO","duracaoMinutos":90,"percComissaoServico":15.00}
                """;
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipoItem").value("SERVICO"))
                .andExpect(jsonPath("$.duracaoMinutos").value(90))
                .andExpect(jsonPath("$.percComissaoServico").value(15.00))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(resp, "$.idProduto")).longValue();

        mvc.perform(get("/api/v1/produtos/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duracaoMinutos").value(90));
    }

    /** Mercadoria não ganha linha em {@code produto_servico} — a extensão é 1:1 com serviço. */
    @Test
    void mercadoriaNaoGanhaLinhaDeServico() throws Exception {
        String token = assinarNovoTenant("g");
        long idTenant = idTenantDoToken(token);
        long idProduto = criarProduto(token, "RACAO PREMIUM", "MERCADORIA");

        try (Connection c = abrirConexao(idTenant)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM produto_servico WHERE id_produto = ?")) {
                ps.setLong(1, idProduto);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
        }
    }

    // ---------------------------------------------------------------- as travas

    /**
     * ⛔ O módulo é <b>opt-in</b>: <i>"por padrão o módulo de serviço vai precisar ligar ele pra
     * funcionar, pois as empresas de serviço são menos que as de comércio"</i> (dono do produto,
     * 2026-08-28). Sem esta trava, a API aceitaria criar serviço enquanto a tela não oferece o
     * campo — e o item viraria uma linha que nenhuma tela explica.
     */
    @Test
    void naoCadastraServicoComOModuloDesligado() throws Exception {
        String token = assinarNovoTenant("h");
        String body = """
                {"descricao":"BANHO","precoCusto":0,"percentualVenda":0,"precoVenda":50.00,
                 "tipoItem":"SERVICO"}
                """;
        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    /** O par: com o módulo desligado, o cadastro de mercadoria segue exatamente como sempre foi. */
    @Test
    void mercadoriaContinuaSendoCadastradaComOModuloDesligado() throws Exception {
        String token = assinarNovoTenant("i");
        mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.cfgUsaServicos").value(false));
        criarProduto(token, "OSSO DE COURO", null);
    }

    /**
     * O tipo é imutável: virar um produto com estoque e histórico em serviço deixaria
     * {@code produto_estoque} com saldo de algo que não tem saldo, e a NFC-e de ontem descrevendo
     * mercadoria que hoje é mão de obra — tudo sem erro.
     */
    @Test
    void naoTrocaMercadoriaPorServicoDepoisDeCriado() throws Exception {
        String token = assinarNovoTenant("j");
        long idTenant = idTenantDoToken(token);
        ligarServicos(token);
        long idProduto = criarProduto(token, "SHAMPOO", "MERCADORIA");

        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE produto SET tipo_item = 'SERVICO' WHERE id_produto = ?")) {
            ps.setLong(1, idProduto);
            try {
                ps.executeUpdate();
                throw new AssertionError("a troca de tipo deveria ter sido recusada pela trigger");
            } catch (SQLException e) {
                assertThat(e.getMessage()).contains("TIPO_ITEM_IMUTAVEL");
            }
        }
    }

    /**
     * ⭐ Achado testando ao vivo em 2026-08-28, não previsto na spec: num tenant com
     * {@code cfg_usa_cor_grade} ligado — o caso da petshop que também vende ração em tamanhos —
     * cadastrar um serviço era recusado com <i>"Grade é obrigatória para este tenant"</i>, mandando
     * o operador procurar uma curva de tamanhos para um banho de cachorro.
     *
     * <p>Serviço não tem grade, nem cor, nem tamanho. A exigência vale para mercadoria.
     */
    @Test
    void servicoNaoExigeGradeMesmoNoTenantQueUsaCorEGrade() throws Exception {
        String token = assinarNovoTenant("l");
        ligarServicos(token);

        // liga cor/grade, como a petshop que vende ração em tamanhos
        String atual = mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(atual.replaceFirst("\"cfgUsaCorGrade\":\\s*false", "\"cfgUsaCorGrade\":true")))
                .andExpect(status().isOk());

        // serviço passa sem grade...
        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                                {"descricao":"BANHO E TOSA","precoCusto":0,"percentualVenda":0,
                                 "precoVenda":80.00,"tipoItem":"SERVICO"}
                                """))
                .andExpect(status().isCreated());

        // ...e a mercadoria continua exigindo, que é de onde a regra veio (o par negativo).
        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("""
                                {"descricao":"RACAO","precoCusto":0,"percentualVenda":0,"precoVenda":50.00}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tipoInvalidoEhRecusadoComMensagemLegivel() throws Exception {
        String token = assinarNovoTenant("k");
        ligarServicos(token);
        String body = """
                {"descricao":"ALGO","precoCusto":0,"percentualVenda":0,"precoVenda":10.00,
                 "tipoItem":"OUTRA_COISA"}
                """;
        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- os filtros (§3.6 do estudo)

    /**
     * ⛔ O filtro mais importante dos oito. Mão de obra é fato gerador de <b>ISS</b> e sai em NFS-e,
     * que é municipal — não em documento de ICMS. Sem o filtro, "BANHO E TOSA" iria dentro da NFC-e
     * com NCM e CFOP de mercadoria, e <b>o pior caso não é a SEFAZ rejeitar: é AUTORIZAR</b>, com o
     * erro aparecendo só numa fiscalização.
     *
     * <p>O teste confere a <b>consulta</b> que monta os itens da nota, e não a emissão inteira (que
     * exigiria certificado e SEFAZ): a venda mista tem 2 itens no ledger e a nota tem de enxergar 1.
     */
    @Test
    void servicoNaoEntraNosItensDaNotaFiscal() throws Exception {
        String token = assinarNovoTenant("m");
        long idTenant = idTenantDoToken(token);
        ligarServicos(token);
        long idMercadoria = criarProduto(token, "RACAO 15KG", "MERCADORIA");
        long idServico = criarProduto(token, "BANHO E TOSA", "SERVICO");

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long vMerc = criarVariacao(c, idTenant, idMercadoria);
            long vServ = criarVariacao(c, idTenant, idServico);

            // uma venda com os dois, como a petshop faz todo dia
            long idVenda;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO venda (id_tenant, id_empresa, data_venda, tipo_operacao)"
                            + " VALUES (?, ?, now(), 'VENDA') RETURNING id_venda")) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idEmpresa);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    idVenda = rs.getLong(1);
                }
            }
            long idMovimento;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO produto_movimento_mestre (id_tenant, tipo_movimento, data_movimento,"
                            + " id_empresa, id_venda) VALUES (?, 'VENDA', now(), ?, ?) RETURNING id_movimento")) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idEmpresa);
                ps.setLong(3, idVenda);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    idMovimento = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO produto_movimento_detalhe
                        (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto, preco_venda)
                    VALUES (?, ?, ?, ?, 'D', 1, 50.00), (?, ?, ?, ?, 'D', 1, 80.00)
                    """)) {
                ps.setLong(1, idTenant); ps.setLong(2, idMovimento); ps.setLong(3, idEmpresa); ps.setLong(4, vMerc);
                ps.setLong(5, idTenant); ps.setLong(6, idMovimento); ps.setLong(7, idEmpresa); ps.setLong(8, vServ);
                ps.executeUpdate();
            }

            // o ledger tem os DOIS (é o que faz o serviço aparecer na DRE e nas comissões)...
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM produto_movimento_detalhe WHERE id_movimento = ?")) {
                ps.setLong(1, idMovimento);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
            }
            // ...e a nota enxerga só a mercadoria (mesmo predicado do VendaFiscalAssembler).
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT p.descricao
                      FROM produto_movimento_detalhe pmd
                      JOIN produto_movimento_mestre pmm
                        ON pmm.id_tenant = pmd.id_tenant AND pmm.id_movimento = pmd.id_movimento
                      JOIN produto_barra pb
                        ON pb.id_tenant = pmd.id_tenant AND pb.id_variacao = pmd.id_variacao
                      JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                     WHERE pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                       AND pb.tipo_item = 'MERCADORIA'
                    """)) {
                ps.setLong(1, idVenda);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("RACAO 15KG");
                    assertThat(rs.next()).isFalse();
                }
            }

            // ⭐ E o que a nota DEIXA DE FORA precisa ser dito ao operador (2026-08-31, relato do
            // dono do produto: *"a venda também tinha serviços, por que não emitiu a nota de
            // serviço?"*). A NFC-e estava certa; faltava avisar que ela cobre metade da venda.
            // Mesma soma de `DocumentoFiscalRepositorio.somarServicosDaVenda`.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT SUM(d.qtd_produto * d.preco_venda - d.valor_desconto + d.valor_acrescimo)
                      FROM produto_movimento_mestre m
                      JOIN produto_movimento_detalhe d
                        ON d.id_tenant = m.id_tenant AND d.id_movimento = m.id_movimento
                      JOIN produto_barra pb
                        ON pb.id_tenant = d.id_tenant AND pb.id_variacao = d.id_variacao
                      JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                     WHERE m.id_venda = ? AND m.tipo_movimento = 'VENDA' AND p.tipo_item = 'SERVICO'
                    """)) {
                ps.setLong(1, idVenda);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBigDecimal(1))
                            .as("o BANHO E TOSA de 80,00 é o que ficou fora da nota — é este valor "
                                    + "que o aviso cita, e ele vem do LEDGER (preço praticado), não do cadastro")
                            .isEqualByComparingTo("80.00");
                }
            }
        }
    }

    /**
     * ⛔ O par negativo, e é ele que impede o aviso de virar ruído: venda <b>só de mercadoria</b> —
     * a esmagadora maioria — não pode gerar aviso nenhum. Um aviso que aparece em toda venda é um
     * aviso que ninguém lê, e aí ele deixa de proteger justamente a venda mista.
     */
    @Test
    void vendaSoDeMercadoriaNaoTemValorDeServicoParaAvisar() throws Exception {
        String token = assinarNovoTenant("so-mercadoria");
        long idTenant = idTenantDoToken(token);
        long idProduto = criarProduto(token, "RACAO 15KG", null);   // sem tipoItem = MERCADORIA

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = buscarIdEmpresa(c);
            long idVariacao = criarVariacao(c, idTenant, idProduto);
            long idVenda;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO venda (id_tenant, id_empresa, data_venda, tipo_operacao)"
                            + " VALUES (?, ?, now(), 'VENDA') RETURNING id_venda")) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idEmpresa);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    idVenda = rs.getLong(1);
                }
            }
            long idMovimento;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO produto_movimento_mestre (id_tenant, tipo_movimento, data_movimento,"
                            + " id_empresa, id_venda) VALUES (?, 'VENDA', now(), ?, ?) RETURNING id_movimento")) {
                ps.setLong(1, idTenant);
                ps.setLong(2, idEmpresa);
                ps.setLong(3, idVenda);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    idMovimento = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO produto_movimento_detalhe (id_tenant, id_movimento, id_empresa,"
                            + " id_variacao, credito_debito, qtd_produto, preco_venda)"
                            + " VALUES (?, ?, ?, ?, 'D', 1, 50.00)")) {
                ps.setLong(1, idTenant); ps.setLong(2, idMovimento);
                ps.setLong(3, idEmpresa); ps.setLong(4, idVariacao);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT SUM(d.qtd_produto * d.preco_venda - d.valor_desconto + d.valor_acrescimo)
                      FROM produto_movimento_mestre m
                      JOIN produto_movimento_detalhe d
                        ON d.id_tenant = m.id_tenant AND d.id_movimento = m.id_movimento
                      JOIN produto_barra pb
                        ON pb.id_tenant = d.id_tenant AND pb.id_variacao = d.id_variacao
                      JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                     WHERE m.id_venda = ? AND m.tipo_movimento = 'VENDA' AND p.tipo_item = 'SERVICO'
                    """)) {
                ps.setLong(1, idVenda);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBigDecimal(1))
                            .as("sem serviço não há o que avisar — nulo, e a tela não mostra nada")
                            .isNull();
                }
            }
        }
    }

    /**
     * Serviço não tem NCM nem perfil fiscal de ICMS. Sem o filtro, todo serviço viraria pendência
     * <b>permanente</b> na tela que existe para dizer que está tudo pronto para emitir — e pendência
     * que não se resolve treina o operador a ignorar a tela inteira.
     */
    @Test
    void servicoNaoViraPendenciaNaConformidadeFiscal() throws Exception {
        String token = assinarNovoTenant("n");
        ligarServicos(token);
        criarProduto(token, "CONSULTA VETERINARIA", "SERVICO");

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresaDoTenant(token)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..itens[?(@.descricao == 'CONSULTA VETERINARIA')]").isEmpty());
    }

    /** Etiqueta de código de barras é de mercadoria — serviço não vai na prateleira. */
    @Test
    void servicoNaoApareceNaEmissaoDeEtiqueta() throws Exception {
        String token = assinarNovoTenant("o");
        ligarServicos(token);
        criarProduto(token, "TOSA BEBE", "SERVICO");
        criarProduto(token, "TAPETE HIGIENICO", "MERCADORIA");

        mvc.perform(get("/api/v1/etiqueta-emissao/produtos?busca=T").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.descricao == 'TOSA BEBE')]").isEmpty())
                .andExpect(jsonPath("$[?(@.descricao == 'TAPETE HIGIENICO')]").isNotEmpty());
    }

    /**
     * ⭐ Aqui o filtro é o <b>contrário</b>: o serviço PRECISA aparecer na busca do PDV — é assim que
     * a petshop lança banho e ração na mesma venda. O que ele não pode é ser confundido com
     * mercadoria sem saldo, e por isso o tipo vai no DTO.
     */
    @Test
    void servicoAparaceNaBuscaDoPdvComOTipoNoRetorno() throws Exception {
        String token = assinarNovoTenant("p");
        long idTenant = idTenantDoToken(token);
        ligarServicos(token);
        long idServico = criarProduto(token, "BANHO SIMPLES", "SERVICO");

        try (Connection c = abrirConexao(idTenant)) {
            criarVariacao(c, idTenant, idServico);
        }
        mvc.perform(get("/api/v1/pdv/produtos?busca=BANHO").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].descricaoProduto").value("BANHO SIMPLES"))
                .andExpect(jsonPath("$[0].tipoItem").value("SERVICO"));
    }

    /** P8 — o de sempre: o que é de um tenant não aparece no outro. */
    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("x");
        ligarServicos(tokenA);
        long idServicoA = criarProduto(tokenA, "BANHO EXCLUSIVO A", "SERVICO");

        String tokenB = assinarNovoTenant("y");
        mvc.perform(get("/api/v1/produtos/" + idServicoA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------- os campos de mercadoria que somem
    // Pedido do dono do produto (2026-08-31): no cadastro de serviço não há Marca, Referência,
    // NCM, os três campos de oferta, nem Categorias.
    // ⏭️ O classificador do serviço (NBS) fica para a NFS-e, que está sendo construída em paralelo
    // — decisão dele no mesmo dia, para o campo não nascer definido em dois lugares.

    /**
     * ⭐ O teste que mais importa deste bloco, e o menos óbvio: num tenant que marcou "Marca" como
     * <b>obrigatória</b> em `cfg_tela_campo`, o serviço tem de cadastrar do mesmo jeito.
     *
     * <p>Sem a regra, o servidor recusaria com <i>"Campo obrigatório"</i> apontando para um campo
     * que a tela do serviço <b>não mostra</b> — o operador leria a mensagem, procuraria o campo,
     * não acharia, e ficaria sem caminho. A configuração por tenant é da tela de mercadoria.
     */
    @Test
    void servicoCadastraMesmoComMarcaObrigatoriaNoTenant() throws Exception {
        String token = assinarNovoTenant("marca-obrigatoria-servico");
        ligarServicos(token);

        mvc.perform(put("/api/v1/config-tela/catalogo.produto.form")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                [{"campo":"marca","visivel":true,"obrigatorio":true}]
                                """))
                .andExpect(status().isOk());

        // Mercadoria sem marca continua sendo recusada — é o par que prova que a configuração
        // está mesmo valendo, e que o serviço passa pela REGRA e não porque a config não pegou.
        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"COLEIRA","precoCusto":10,"percentualVenda":0,"precoVenda":10}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"BANHO SIMPLES","precoCusto":0,"percentualVenda":0,
                                 "precoVenda":40.00,"tipoItem":"SERVICO"}
                                """))
                .andExpect(status().isCreated());
    }

    /** Os campos de mercadoria são RECUSADOS num serviço, e a mensagem nomeia quais vieram. */
    @Test
    void servicoRecusaCamposDeMercadoriaNomeandoOsQueVieram() throws Exception {
        String token = assinarNovoTenant("recusa-campos-mercadoria");
        ligarServicos(token);

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"BANHO","precoCusto":0,"percentualVenda":0,"precoVenda":50.00,
                                 "tipoItem":"SERVICO","marca":"ACME","codigoNcm":"85171231"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Marca")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("NCM")));
    }

    /**
     * ⭐ **Serviço nasce com variação, e sem ela é invisível na OS e no PDV** (defeito relatado pelo
     * dono do produto em 2026-08-31: *"cadastrei CORTE DE CABELO, mas na Ordem de Serviço este
     * serviço não aparece"*).
     *
     * <p>O cadastro de Produto nunca criou variação, e **tudo** que procura item para lançar parte
     * de {@code produto_barra}. Para MERCADORIA isso ficava escondido — a Entrada de Estoque cria a
     * variação quando a compra chega. **Serviço não tem entrada de estoque**: sem esta correção ele
     * ficaria invisível para sempre.
     */
    @Test
    void servicoNasceComVariacaoEApareceNaBuscaDeItens() throws Exception {
        String token = assinarNovoTenant("servico-variacao");
        long idTenant = idTenantDoToken(token);
        ligarServicos(token);

        long idServico = criarProduto(token, "CORTE DE CABELO", "SERVICO");

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(variacoesDe(c, idServico))
                    .as("serviço sem variação é invisível na OS e no PDV — a busca dos dois parte de produto_barra")
                    .isEqualTo(1);
        }

        // A prova que interessa ao lojista: ele APARECE na busca que a OS usa (o mesmo popup do PDV).
        String achados = mvc.perform(get("/api/v1/pdv/produtos")
                        .header("Authorization", "Bearer " + token).param("busca", "CORTE DE CABELO"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(achados)
                .as("o serviço recém-cadastrado tem de aparecer na pesquisa de itens")
                .contains("CORTE DE CABELO");
    }

    /**
     * ⛔ O par negativo, e ele é a metade que define o escopo: **mercadoria continua SEM** variação
     * no cadastro. Criar uma aqui mudaria o fluxo de compra de centenas de cadastros e gastaria o
     * sequencial global do gerador de EAN (V017) com produto que talvez nunca entre — quem cria a
     * variação da mercadoria é a Entrada de Estoque, e isso não foi pedido.
     */
    @Test
    void mercadoriaContinuaSemVariacaoNoCadastro() throws Exception {
        String token = assinarNovoTenant("mercadoria-sem-variacao");
        long idTenant = idTenantDoToken(token);

        long idProduto = criarProduto(token, "COLEIRA AZUL", null);   // sem tipoItem = MERCADORIA

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(variacoesDe(c, idProduto))
                    .as("mercadoria ganha variação na Entrada de Estoque, não no cadastro")
                    .isZero();
        }
    }

    /**
     * Um serviço cadastrado ANTES desta correção ganha a variação na primeira edição — é por isso
     * que a garantia roda também no {@code atualizar}, e é o que dispensa migration de dados.
     */
    @Test
    void servicoAntigoSemVariacaoGanhaUmaAoSerEditado() throws Exception {
        String token = assinarNovoTenant("servico-antigo");
        long idTenant = idTenantDoToken(token);
        ligarServicos(token);

        long idServico = criarProduto(token, "TOSA COMPLETA", "SERVICO");
        // Recria o estado anterior à correção: o serviço existe e não tem variação nenhuma.
        try (Connection c = abrirConexao(idTenant);
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM produto_barra WHERE id_tenant = ? AND id_produto = ?")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idServico);
            ps.executeUpdate();
        }
        try (Connection c = abrirConexao(idTenant)) {
            assertThat(variacoesDe(c, idServico)).as("cenário montado: sem variação").isZero();
        }

        mvc.perform(put("/api/v1/produtos/" + idServico).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"TOSA COMPLETA\",\"precoCusto\":10.00,"
                                + "\"percentualVenda\":0,\"precoVenda\":80.00}"))
                .andExpect(status().isOk());

        try (Connection c = abrirConexao(idTenant)) {
            assertThat(variacoesDe(c, idServico))
                    .as("editar o serviço antigo tem de criar a variação que faltava")
                    .isEqualTo(1);
        }
    }

    private int variacoesDe(Connection c, long idProduto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM produto_barra WHERE id_produto = ?")) {
            ps.setLong(1, idProduto);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
