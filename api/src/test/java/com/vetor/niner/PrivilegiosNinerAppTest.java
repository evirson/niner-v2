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

    /** Conexão do container (superusuário) — usada só para INSPECIONAR o catálogo do Postgres,
     *  nunca para exercitar privilégio (para isso vale só {@link #conexaoApp()}). */
    private Connection conexaoDeInspecao() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
    /**
     * A sangria de caixa é <b>rastro de dinheiro saindo da gaveta</b>: a V094 concede só
     * {@code SELECT, INSERT} de propósito, para que um DELETE acidental falhe no banco em vez de
     * sumir com o rastro (P3).
     *
     * <p>⚠️ <b>Essa decisão não estava travada por nada</b> (auditoria 2026-08-29). A suíte conecta
     * como superusuário do container, então {@code SangriaCaixaCrudTest} fica verde qualquer que
     * seja o GRANT — e bastaria uma migration futura repetir o laço genérico da V024/V025
     * ({@code GRANT SELECT, INSERT, UPDATE, DELETE ON %I}) incluindo {@code caixa_sangria} para a
     * proteção sumir sem nenhum vermelho. É o roteiro do incidente do Cancelamento de Entrada, ao
     * contrário.
     */
    @Test
    void sangriaDeCaixaNaoPodeSerApagadaNemEditadaPorNinerApp() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            permissaoNegada(st, "DELETE FROM caixa_sangria");
            permissaoNegada(st, "UPDATE caixa_sangria SET valor = 1 WHERE false");
        }
    }

    /**
     * P8 vale para <b>toda</b> tabela de tenant, inclusive a que ainda vai ser criada.
     *
     * <p>⚠️ O guarda-corpo que checava isso era repetido dentro das migrations (V024–V031) e
     * <b>deixou de ser copiado a partir da V033</b> (auditoria 2026-08-29). Hoje nenhuma tabela
     * está descoberta — conferido uma a uma —, mas o guarda só valia no dia em que cada migration
     * rodou. Aqui ele passa a valer para sempre: uma tabela nova com {@code id_tenant} e sem
     * RLS nasceria legível por <b>todos os tenants</b>, e nem o Flyway nem a suíte reclamariam.
     *
     * <p>⛔ {@code plataforma.*} é a exceção documentada (control plane, fora do RLS de tenant).
     */
    /**
     * V071 — o diretório de login é <b>escrito só pela trigger</b>, nunca pela aplicação.
     *
     * <p>É o índice global de e-mail → conta que fez o login deixar de pedir o identificador da
     * loja. Se {@code niner_app} pudesse escrever nele, um caminho com defeito apontaria um e-mail
     * para a conta errada — e o login passaria a <b>entregar a loja de outra pessoa</b>. O
     * {@code GRANT SELECT} é o que a aplicação precisa; o {@code REVOKE} é o que sustenta a
     * afirmação "só a trigger escreve", e até 2026-08-31 nada na suíte a checava.
     */
    @Test
    void diretorioDeLoginSoEhLidoPorNinerApp() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM plataforma.diretorio_login WHERE false");   // leitura: permitida
            permissaoNegada(st, "INSERT INTO plataforma.diretorio_login (email) VALUES ('x@y.z')");
            permissaoNegada(st, "UPDATE plataforma.diretorio_login SET email = 'x@y.z' WHERE false");
            permissaoNegada(st, "DELETE FROM plataforma.diretorio_login");
        }
    }

    /**
     * V079 — o código do login em duas etapas <b>não se apaga</b>.
     *
     * <p>⭐ O que protege um código de 4 dígitos (10.000 combinações) não é o tamanho, é o teto de
     * tentativas — e o teto vive na própria linha. Com {@code DELETE}, apagar o desafio zeraria o
     * contador: o mesmo bypass que o rollback da transação já causou uma vez (2026-08-27), agora
     * pela porta do privilégio. {@code INSERT}/{@code UPDATE} continuam permitidos porque é assim
     * que o desafio nasce e conta tentativa.
     */
    @Test
    void codigoDeLoginEmDuasEtapasNaoPodeSerApagadoPorNinerApp() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM plataforma.codigo_login WHERE false");
            permissaoNegada(st, "DELETE FROM plataforma.codigo_login");
        }
    }

    @Test
    void todaTabelaComIdTenantTemRlsHabilitadoEForcado() throws Exception {
        try (Connection c = conexaoDeInspecao(); Statement st = c.createStatement();
             var rs = st.executeQuery("""
                     SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
                       FROM pg_class c
                       JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = 'public' AND c.relkind = 'r'
                        AND EXISTS (SELECT 1 FROM pg_attribute a
                                     WHERE a.attrelid = c.oid AND a.attname = 'id_tenant'
                                       AND NOT a.attisdropped)
                        AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
                     """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                    .as("tabelas com id_tenant sem ROW LEVEL SECURITY habilitado E forçado (P8)")
                    .isNull();
        }
    }

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
     * V046: {@code cfg_csrt_resptec} é a <b>exceção</b> entre as tabelas {@code cfg_*} — as outras
     * são carga por script do dono, esta é mantida pelo backoffice (/api/admin), que roda como
     * {@code niner_app} igual ao resto da API. Sem estes quatro grants, cadastrar o CSRT de um
     * estado novo falharia só em produção: o teste de integração conecta como superusuário do
     * container e não enxerga GRANT nenhum ([[feedback_testcontainers_nao_usa_niner_app]]).
     */
    @Test
    void csrtPorUfEhEscritoPelaAplicacaoDiferenteDasOutrasTabelasDeReferencia() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            for (String sql : new String[] {
                    "SELECT count(*) FROM cfg_csrt_resptec",
                    "INSERT INTO cfg_csrt_resptec (uf, ambiente, id_csrt, csrt_cifrado) "
                            + "VALUES ('SP', 2, '09', 'x')",
                    "UPDATE cfg_csrt_resptec SET id_csrt = '10' WHERE uf = 'SP' AND ambiente = 2",
                    "DELETE FROM cfg_csrt_resptec WHERE uf = 'SP' AND ambiente = 2"}) {
                st.execute(sql);
            }
        }
    }

    /**
     * F6/F7 (V035): documento fiscal, seus itens, seus eventos, a inutilização de numeração e o
     * log de uso do certificado NUNCA são apagados por {@code niner_app} — nem um RASCUNHO que
     * falhou é trilha perdida (documento) nem um acesso ao certificado deixa de constar (uso).
     */
    @Test
    void documentosFiscaisELogDeUsoDoCertificadoNaoPodemSerApagadosPorNinerApp() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            permissaoNegada(st, "DELETE FROM documento_fiscal");
            permissaoNegada(st, "DELETE FROM documento_fiscal_item");
            permissaoNegada(st, "DELETE FROM documento_fiscal_evento");
            permissaoNegada(st, "DELETE FROM fiscal_certificado_uso");
            permissaoNegada(st, "DELETE FROM fiscal_inutilizacao");
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
     * V058 — orçamento se cancela, não se apaga (P3).
     *
     * <p>⚠️ Este caso existe porque o `GRANT` é <b>invisível para o resto da suíte</b>: o
     * Testcontainers conecta como superusuário, então uma permissão faltando passa em todos os
     * outros testes e só quebra ao vivo (foi assim com o Cancelamento de Entrada, que passou com
     * 7 testes verdes e falhou na primeira tentativa real).
     *
     * <p>`orcamento` recebe UPDATE porque a situação muda (efetivar/cancelar/vencer);
     * `orcamento_item` não, porque o orçamento é imutável — item emitido nunca muda.
     */
    @Test
    void orcamentoSeCancelaNaoSeApaga() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            permissaoNegada(st, "DELETE FROM orcamento");
            permissaoNegada(st, "DELETE FROM orcamento_item");
            permissaoNegada(st, "UPDATE orcamento_item SET qtd_produto = 1 WHERE false");
            // O que a aplicação PRECISA poder fazer:
            st.executeUpdate("UPDATE orcamento SET situacao = 'VENCIDO' WHERE false");
        }
    }

    /**
     * Devolução ao fornecedor (V051–V054): os privilégios que a rotina inteira depende, e que
     * nenhum caso cobria (achado de auditoria, 2026-08-21).
     *
     * <p>⚠️ O mais exposto é a <b>view</b>: {@code vw_entrada_saldo_devolucao} é a fonte única do
     * teto de devolução (saldo ∧ estoque). Se aquele {@code GRANT SELECT} sumir numa recriação de
     * banco, a rotina responde <i>permission denied for view</i> em runtime — e a suíte não vê
     * nada, porque o Testcontainers conecta como superusuário. É o roteiro exato do incidente do
     * Cancelamento de Entrada.
     *
     * <p>{@code entrada_nfe_item} não pode ser apagada (P3 — é o XML da entrada que fundamenta o
     * teto), e as duas funções da trigger de estoque precisam de {@code EXECUTE}: sem ele, TODA
     * movimentação de estoque falha.
     */
    @Test
    void devolucaoAoFornecedorTemOsPrivilegiosQuePrecisa() throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            permissaoNegada(st, "DELETE FROM entrada_nfe_item");
            // O que a aplicação PRECISA poder fazer:
            st.executeQuery("SELECT 1 FROM entrada_nfe_item WHERE false").close();
            st.executeQuery("SELECT 1 FROM vw_entrada_saldo_devolucao WHERE false").close();
            // Assinatura COMPLETA: `has_function_privilege` resolve pela assinatura, e um nome sem
            // os tipos estoura com "function does not exist" — um falso vermelho que parece
            // privilégio faltando e não é. As duas assinaturas são as da V054:191-192.
            afirmaPodeExecutar(st, "fn_aplica_delta_estoque(integer, integer, integer, numeric)");
            afirmaPodeExecutar(st, "fn_exige_estoque_nao_negativo(integer, integer, integer, numeric, numeric)");
            // ⚠️ `fn_item_e_servico` roda na PRIMEIRA linha dos três ramos da mesma trigger (V086):
            // sem o GRANT, TODA movimentação de estoque falha com "permission denied for function",
            // exatamente o roteiro do incidente do Cancelamento de Entrada. Faltava caso aqui
            // (achado de auditoria, 2026-08-29).
            afirmaPodeExecutar(st, "fn_item_e_servico(smallint, integer)");
            // As três tabelas do módulo de serviços — o Testcontainers conecta como superusuário,
            // então sem estes SELECT a suíte ficaria verde mesmo com o GRANT sumindo numa
            // recriação do bootstrap, e o erro só apareceria em produção.
            st.executeQuery("SELECT 1 FROM produto_servico WHERE false").close();
            st.executeQuery("SELECT 1 FROM ordem_servico WHERE false").close();
            st.executeQuery("SELECT 1 FROM ordem_servico_item WHERE false").close();
        }
    }

    /**
     * P8 em job sem JWT (achado ao vivo em 2026-08-19): uma consulta em tabela de <b>domínio</b>
     * sem {@code SET app.id_tenant} não "varre todos os tenants" — o RLS {@code FORCE} devolve
     * <b>zero linhas de qualquer tenant</b>, em silêncio, sem erro. Era exatamente o que
     * {@code FiscalContingenciaDrenoJob}/{@code ArquivamentoXmlJob} faziam antes da correção: a
     * consulta "quem tem pendência?" rodava sem contexto e sempre voltava vazia — os jobs nunca
     * encontravam nada para processar. A correção usa {@code plataforma.tenant} (GLOBAL, sem RLS,
     * P9) para descobrir os tenants e entra em {@code TenantContext.comTenant} por tenant antes de
     * qualquer consulta de domínio — este teste prova as duas metades desse contrato.
     */
    @Test
    void semTenantNoContextoDominioNaoAparecePorNenhumTenantMasPlataformaTenantContinuaGlobal()
            throws Exception {
        try (Connection c = conexaoApp(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM empresa")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1))
                        .as("RLS sem app.id_tenant bloqueia TODAS as linhas de TODOS os tenants, "
                                + "não filtra 'nenhum tenant específico'")
                        .isZero();
            }
            // plataforma.tenant é GLOBAL (P9) — não tem RLS, por isso é o único jeito correto de um
            // job sem JWT descobrir quais tenants existem. A consulta não lançar já é a prova.
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM plataforma.tenant")) {
                assertThat(rs.next()).isTrue();
            }
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

    /**
     * Afirma que {@code niner_app} tem EXECUTE na função.
     *
     * <p>⚠️ A assinatura tem de vir COMPLETA, com os tipos: {@code has_function_privilege} resolve
     * a função pela assinatura e um nome sem os parâmetros estoura com <i>"function does not
     * exist"</i> — um vermelho que parece privilégio faltando e é só sintaxe.
     */
    private static void afirmaPodeExecutar(Statement st, String assinatura) throws SQLException {
        try (var rs = st.executeQuery(
                "SELECT has_function_privilege('niner_app', '" + assinatura + "', 'EXECUTE')")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).as("EXECUTE de niner_app em %s", assinatura).isTrue();
        }
    }
}
