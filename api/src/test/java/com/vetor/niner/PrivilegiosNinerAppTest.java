package com.vetor.niner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Privilégios reais da role de aplicação ({@code niner_app}) — o ponto cego do resto da suíte.
 *
 * <p>⚠️ {@link TestcontainersConfiguration} cria o container <b>sem</b> {@code withUsername},
 * então o {@code @ServiceConnection} liga o datasource da aplicação (o que
 * {@code Service}/{@code Controller} usam, inclusive via MockMvc) ao <b>superusuário</b> do
 * Testcontainers, não a {@code niner_app}. Consequência: todo bug de {@code GRANT}/{@code REVOKE}
 * — e todo bug que dependa de {@code niner_app} não ter {@code BYPASSRLS} — é invisível para os
 * demais testes. Foi exatamente assim que o Cancelamento de Entrada (2026-08-12) passou com 7
 * testes verdes sem o {@code GRANT UPDATE} de coluna de que precisava: o
 * {@code permission denied for table produto_movimento_mestre} só apareceu testando ao vivo
 * contra o container real.
 *
 * <p>Este teste fecha a lacuna pelo caminho barato: conecta cru como {@code niner_app} (mesmo
 * padrão de {@link RlsIsolamentoTest}) e afirma os invariantes de privilégio direto no banco com
 * as migrations aplicadas. <b>Não substitui</b> separar as credenciais do datasource (Flyway como
 * owner, app como {@code niner_app}) — essa correção de fundo segue em aberto, por risco de
 * quebrar a suíte existente.
 *
 * <p>Nenhum teste aqui semeia dado: no Postgres o privilégio é checado <b>antes</b> das linhas,
 * então um {@code DELETE} em tabela vazia (ou um {@code UPDATE ... WHERE false}) já basta para
 * exercitar a permissão, sem efeito colateral nenhum.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PrivilegiosNinerAppTest {

    /** {@code insufficient_privilege}. Comparado pelo SQLState porque a mensagem do Postgres é
     *  traduzível (depende do locale da imagem) — o código, não. */
    private static final String PRIVILEGIO_INSUFICIENTE = "42501";

    @Autowired
    PostgreSQLContainer postgres;

    private Connection conexaoApp() throws SQLException {
        // Role da aplicação, SEM BYPASSRLS (criada em bootstrap-test.sql).
        return DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
    }

    /** P8: a role da app nunca pode escapar do RLS nem ser dona do banco. */
    @Test
    void ninerAppNaoTemBypassRlsNemSuperusuario() throws Exception {
        try (Connection c = conexaoApp();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT rolbypassrls, rolsuper FROM pg_roles WHERE rolname = 'niner_app'")) {
            assertThat(rs.next()).as("role niner_app existe").isTrue();
            assertThat(rs.getBoolean("rolbypassrls")).as("BYPASSRLS").isFalse();
            assertThat(rs.getBoolean("rolsuper")).as("SUPERUSER").isFalse();
        }
    }

    /**
     * P3 / V024: {@code produto_movimento_mestre} é imutável — correção é sempre um novo
     * movimento compensatório, nunca edição do original.
     */
    @Test
    void ledgerDeEstoqueEhImutavelParaNinerApp() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            permissaoNegada(st, "DELETE FROM produto_movimento_mestre");
            permissaoNegada(st, "UPDATE produto_movimento_mestre SET nota_fiscal = 1 WHERE false");
        }
    }

    /**
     * Exceção estreita e deliberada à imutabilidade acima (V024, Cancelamento de Entrada): GRANT
     * <b>de coluna</b> nas 4 colunas da capa de auditoria do cancelamento. É este grant que
     * faltava em 2026-08-12 e que nenhum teste pegava.
     */
    @Test
    void ninerAppMarcaCancelamentoNoLedgerMasNaoEditaOMovimento() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            // WHERE false: exercita o privilégio sem tocar em nenhuma linha.
            st.executeUpdate("""
                    UPDATE produto_movimento_mestre
                       SET cancelado = true,
                           data_cancelamento = now(),
                           id_usuario_cancelamento = 1,
                           motivo_cancelamento = 'TESTE DE PRIVILEGIO'
                     WHERE false""");
        }
    }

    /**
     * V017: {@code cfg_ean_gerador} é global e {@code niner_app} não tem grant nenhum nela — o
     * acesso é só pela função {@code SECURITY DEFINER}, para não haver escrita no contador fora
     * da rotina de geração de SKU.
     */
    @Test
    void geradorDeEanSoEhAcessivelPelaFuncao() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            permissaoNegada(st, "SELECT proximo_sequencial FROM cfg_ean_gerador");

            try (ResultSet rs = st.executeQuery("SELECT gerar_ean13_interno()")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1))
                        .as("EAN-13 interno gerado apesar de a tabela ser inacessível")
                        .matches("9\\d{12}");
            }
        }
    }

    /** V017: NCM é referência global mantida por script como {@code niner_owner} — a app só lê. */
    @Test
    void ncmEhSomenteLeituraParaNinerApp() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM cfg_produto_ncm")) {
                assertThat(rs.next()).isTrue();
            }
            permissaoNegada(st,
                    "INSERT INTO cfg_produto_ncm (codigo_ncm, descricao_ncm) VALUES ('00000000', 'X')");
        }
    }

    /**
     * F6/F7 (V035): documento fiscal, seus itens, seus eventos e o log de uso do certificado
     * NUNCA são apagados por {@code niner_app} — nem um RASCUNHO que falhou é trilha perdida
     * (documento) nem um acesso ao certificado deixa de constar (uso).
     */
    @Test
    void documentosFiscaisELogDeUsoDoCertificadoNaoPodemSerApagadosPorNinerApp() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            permissaoNegada(st, "DELETE FROM documento_fiscal");
            permissaoNegada(st, "DELETE FROM documento_fiscal_item");
            permissaoNegada(st, "DELETE FROM documento_fiscal_evento");
            permissaoNegada(st, "DELETE FROM fiscal_certificado_uso");
        }
    }

    /** V011 / R21 / P3: trilha de impersonação só pode ser encerrada (UPDATE), nunca apagada. */
    @Test
    void trilhaDeImpersonacaoPodeSerEncerradaMasNaoApagada() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            permissaoNegada(st, "DELETE FROM plataforma.impersonacao_log");
            st.executeUpdate(
                    "UPDATE plataforma.impersonacao_log SET encerrado_em = now() WHERE false");
        }
    }

    /**
     * Executa e exige que o Postgres recuse por privilégio. Autocommit está ligado (conexão JDBC
     * nova), então cada instrução é sua própria transação — uma falha aqui não contamina as
     * seguintes.
     */
    private static void permissaoNegada(Statement st, String sql) throws SQLException {
        try {
            st.execute(sql);   // execute (não executeUpdate): serve para DML e SELECT
        } catch (SQLException e) {
            assertThat(e.getSQLState()).as("SQLState de: %s", sql).isEqualTo(PRIVILEGIO_INSUFICIENTE);
            return;
        }
        fail("Esperava permissão negada (SQLState " + PRIVILEGIO_INSUFICIENTE + ") em: " + sql);
    }
}
