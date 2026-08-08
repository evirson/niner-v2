package com.vetor.niner.estoque.balanco;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.DiferencasResponse;
import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.EfetivacaoResponse;
import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.LinhaContagem;
import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.LinhaDiferenca;
import com.vetor.niner.estoque.balanco.BalancoEstoqueDtos.UltimaEfetivacaoResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Rotina de Contagem de Estoque — ver {@code package-info.java} pro resumo de escopo. Toda
 * operação é sempre sobre a empresa ativa da sessão (claim {@code eid}); não existe parâmetro
 * de empresa em nenhum endpoint — nem pra ADMIN (decisão explícita, sem exceção).
 */
@Service
public class BalancoEstoqueService {

    private final JdbcClient jdbc;
    private final ConfiguracaoGeralService configuracaoGeralService;

    public BalancoEstoqueService(JdbcClient jdbc, ConfiguracaoGeralService configuracaoGeralService) {
        this.jdbc = jdbc;
        this.configuracaoGeralService = configuracaoGeralService;
    }

    @Transactional
    public void registrarContagem(Jwt jwt, long idVariacao, BigDecimal qtd) {
        if (qtd.signum() <= 0) {
            throw new IllegalArgumentException("Quantidade contada deve ser maior que zero.");
        }
        long idEmpresa = idEmpresaSessao(jwt);
        validarQtd(qtd);
        exigirVariacaoValida(idVariacao);

        jdbc.sql("""
                        INSERT INTO produto_balanco (id_tenant, id_empresa, id_variacao, qtd_contagem)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?)
                        """)
                .params(idEmpresa, idVariacao, qtd)
                .update();
    }

    /** Ajusta a contagem de uma variação pra um valor exato — some as leituras antigas daquela
     *  variação e insere uma única linha nova com o valor informado (0 = equivalente a remover). */
    @Transactional
    public void ajustarContagem(Jwt jwt, long idVariacao, BigDecimal qtdContada) {
        if (qtdContada.signum() < 0) {
            throw new IllegalArgumentException("Quantidade contada não pode ser negativa.");
        }
        long idEmpresa = idEmpresaSessao(jwt);
        validarQtd(qtdContada);

        jdbc.sql("""
                        DELETE FROM produto_balanco
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND id_variacao = ?
                              AND id_movimento IS NULL
                        """)
                .params(idEmpresa, idVariacao)
                .update();

        if (qtdContada.signum() > 0) {
            jdbc.sql("""
                            INSERT INTO produto_balanco (id_tenant, id_empresa, id_variacao, qtd_contagem)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?)
                            """)
                    .params(idEmpresa, idVariacao, qtdContada)
                    .update();
        }
    }

    @Transactional
    public void removerContagem(Jwt jwt, long idVariacao) {
        long idEmpresa = idEmpresaSessao(jwt);
        jdbc.sql("""
                        DELETE FROM produto_balanco
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND id_variacao = ?
                              AND id_movimento IS NULL
                        """)
                .params(idEmpresa, idVariacao)
                .update();
    }

    @Transactional
    public void zerarContagem(Jwt jwt) {
        long idEmpresa = idEmpresaSessao(jwt);
        jdbc.sql("""
                        DELETE FROM produto_balanco
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND id_movimento IS NULL
                        """)
                .param(idEmpresa)
                .update();
    }

    @Transactional(readOnly = true)
    public List<LinhaContagem> listarContagemAtiva(Jwt jwt) {
        long idEmpresa = idEmpresaSessao(jwt);
        return jdbc.sql("""
                        SELECT pb.id_variacao, p.descricao AS descricao_produto, co.descricao AS variacao_cor,
                               ta.descricao AS variacao_tamanho, pbr.sku, SUM(pb.qtd_contagem) AS qtd_contada
                        FROM produto_balanco pb
                        JOIN produto_barra pbr ON pbr.id_variacao = pb.id_variacao AND pbr.id_tenant = pb.id_tenant
                        JOIN produto p ON p.id_produto = pbr.id_produto AND p.id_tenant = pbr.id_tenant
                        LEFT JOIN cfg_cor co ON co.id_cor = pbr.id_cor AND co.id_tenant = pbr.id_tenant
                        LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pbr.id_tamanho AND ta.id_tenant = pbr.id_tenant
                        WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_empresa = ? AND pb.id_movimento IS NULL
                        GROUP BY pb.id_variacao, p.descricao, co.descricao, ta.descricao, pbr.sku
                        HAVING SUM(pb.qtd_contagem) <> 0
                        ORDER BY p.descricao, co.descricao, ta.descricao
                        """)
                .param(idEmpresa)
                .query(BalancoEstoqueService::mapearLinhaContagem)
                .list();
    }

    @Transactional(readOnly = true)
    public DiferencasResponse obterDiferencas(Jwt jwt) {
        long idEmpresa = idEmpresaSessao(jwt);
        boolean ativa = existeContagemAtiva(idEmpresa);
        return new DiferencasResponse(ativa, ativa ? calcularDiferencas(idEmpresa) : List.of());
    }

    /**
     * Grava um {@code produto_movimento_mestre} (tipo {@code AJUSTE}) com uma linha de detalhe
     * por produto com diferença — a trigger materializa em {@code produto_estoque}. Depois marca
     * TODAS as linhas ativas do balanço (com ou sem diferença) com o {@code id_movimento} gerado;
     * é isso que "zera" a contagem sem apagar nada fisicamente, viabilizando o desfazer (ver
     * {@link #desfazerUltimaEfetivacao}).
     */
    @Transactional
    public EfetivacaoResponse efetivar(Jwt jwt) {
        long idEmpresa = idEmpresaSessao(jwt);
        if (!existeContagemAtiva(idEmpresa)) {
            throw new IllegalArgumentException("Não há nenhuma contagem de estoque em andamento nesta empresa para efetivar.");
        }
        List<LinhaDiferenca> diferencas = calcularDiferencas(idEmpresa);
        if (diferencas.isEmpty()) {
            throw new IllegalArgumentException("Não há diferenças de estoque para efetivar — a contagem já bate com o estoque.");
        }

        long idMovimento = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento)
                        VALUES (plataforma.tenant_atual(), ?, 'AJUSTE')
                        RETURNING id_movimento
                        """)
                .param(idEmpresa)
                .query(Long.class).single();

        for (LinhaDiferenca linha : diferencas) {
            String creditoDebito = linha.diferenca().signum() > 0 ? "C" : "D";
            BigDecimal qtdAbsoluta = linha.diferenca().abs();
            // preco_custo (2026-08-04) vem de um SELECT/JOIN em vez de bind param — LinhaDiferenca
            // é DTO público (usado por DiferencasEstoque.tsx) e não precisa carregar custo só pra
            // isto; o custo ATUAL do cadastro é buscado na hora do INSERT.
            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto, preco_custo, origem)
                            SELECT plataforma.tenant_atual(), ?, ?, pb.id_variacao, ?::credito_debito, ?, p.preco_custo, 'contagem de estoque'
                            FROM produto_barra pb
                            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                            WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ?
                            """)
                    .params(idMovimento, idEmpresa, creditoDebito, qtdAbsoluta, linha.idVariacao())
                    .update();
        }

        jdbc.sql("""
                        UPDATE produto_balanco SET id_movimento = ?
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND id_movimento IS NULL
                        """)
                .params(idMovimento, idEmpresa)
                .update();

        return new EfetivacaoResponse(idMovimento, diferencas.size(), OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public UltimaEfetivacaoResponse obterUltimaEfetivacao(Jwt jwt) {
        long idEmpresa = idEmpresaSessao(jwt);
        Long idMovimento = buscarUltimaEfetivacaoAtiva(idEmpresa);
        if (idMovimento == null) {
            return new UltimaEfetivacaoResponse(false, null, null, null);
        }

        record Info(OffsetDateTime data, int total) {
        }
        Info info = jdbc.sql("""
                        SELECT pm.data_movimento, count(pmd.id_movimento_detalhe) AS total
                        FROM produto_movimento_mestre pm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pm.id_movimento AND pmd.id_tenant = pm.id_tenant
                        WHERE pm.id_tenant = plataforma.tenant_atual() AND pm.id_movimento = ?
                        GROUP BY pm.data_movimento
                        """)
                .param(idMovimento)
                .query((rs, n) -> new Info(rs.getObject("data_movimento", OffsetDateTime.class), rs.getInt("total")))
                .single();

        return new UltimaEfetivacaoResponse(true, idMovimento, info.data(), info.total());
    }

    /**
     * Desfaz a efetivação mais recente da empresa que ainda não tenha sido desfeita — apaga as
     * linhas de {@code produto_movimento_detalhe} daquele movimento (a trigger
     * {@code fn_atualiza_estoque_movimento} reverte {@code produto_estoque} sozinha, mesmo
     * mecanismo de {@code TransferenciaService.excluir}) e libera de volta as linhas de
     * {@code produto_balanco} marcadas com aquele movimento pro balanço ativo.
     */
    @Transactional
    public void desfazerUltimaEfetivacao(Jwt jwt) {
        long idEmpresa = idEmpresaSessao(jwt);
        Long idMovimento = buscarUltimaEfetivacaoAtiva(idEmpresa);
        if (idMovimento == null) {
            throw new IllegalArgumentException("Não há efetivação de balanço para desfazer.");
        }

        jdbc.sql("""
                        DELETE FROM produto_movimento_detalhe
                        WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                        """)
                .param(idMovimento)
                .update();

        jdbc.sql("""
                        UPDATE produto_balanco SET id_movimento = NULL
                        WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                        """)
                .param(idMovimento)
                .update();
    }

    private Long buscarUltimaEfetivacaoAtiva(long idEmpresa) {
        return jdbc.sql("""
                        SELECT pm.id_movimento
                        FROM produto_movimento_mestre pm
                        WHERE pm.id_tenant = plataforma.tenant_atual() AND pm.id_empresa = ? AND pm.tipo_movimento = 'AJUSTE'
                              AND EXISTS (
                                  SELECT 1 FROM produto_movimento_detalhe pmd
                                  WHERE pmd.id_tenant = pm.id_tenant AND pmd.id_movimento = pm.id_movimento
                              )
                        ORDER BY pm.data_movimento DESC, pm.id_movimento DESC
                        LIMIT 1
                        """)
                .param(idEmpresa)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    /** Existe alguma linha ativa de balanço (não efetivada) pra essa empresa? Usado tanto pra
     *  decidir se {@link #calcularDiferencas} deve rodar de verdade (sem isto, com o balanço
     *  ativo vazio — ninguém contou nada ainda, ou tudo já foi efetivado — TODO produto em
     *  estoque apareceria como "nunca contado", tornando o relatório inútil logo depois de
     *  efetivar) quanto pra dar uma mensagem diferente de "contagem bate com o estoque" quando
     *  na verdade não há contagem nenhuma pra comparar. */
    private boolean existeContagemAtiva(long idEmpresa) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM produto_balanco
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND id_movimento IS NULL)
                        """)
                .param(idEmpresa)
                .query(Boolean.class).single());
    }

    /** {@code contagem} trata leitura ausente como 0 (produto nunca escaneado NESTA rodada, mas
     *  em estoque, entra como diferença — SE nunca tiver participado de balanço nenhum, ver
     *  abaixo); {@code estoque} trata linha ausente em {@code produto_estoque} como 0 (produto
     *  que nunca teve movimento algum naquela empresa). Só entram linhas onde os dois valores são
     *  diferentes — o objetivo desta consulta é mostrar exceções, não o balanço inteiro.
     *
     *  <p>Um produto SEM contagem ativa só entra como "nunca contado" se ele nunca tiver
     *  aparecido em {@code produto_balanco} (nem ativo, nem já efetivado) — sem essa condição,
     *  todo produto já efetivado em uma rodada anterior voltaria a aparecer como "diferença"
     *  na rodada seguinte assim que sua linha fosse marcada/liberada (ela deixa de estar
     *  "ativa" nesse meio-tempo), mesmo já reconciliado e sem ninguém ter mexido nele de novo —
     *  inclusive fazendo uma efetivação nova ajustar de volta um produto que nem foi reescaneado
     *  nesta rodada. Um produto ATIVAMENTE contado nesta rodada sempre entra se diferente,
     *  não importa o histórico. */
    private List<LinhaDiferenca> calcularDiferencas(long idEmpresa) {
        return jdbc.sql("""
                        WITH contagem AS (
                            SELECT id_variacao, SUM(qtd_contagem) AS qtd_contada
                            FROM produto_balanco
                            WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND id_movimento IS NULL
                            GROUP BY id_variacao
                        ),
                        estoque AS (
                            SELECT id_variacao, qtd_estoque
                            FROM produto_estoque
                            WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                        )
                        SELECT COALESCE(c.id_variacao, e.id_variacao) AS id_variacao,
                               p.descricao AS descricao_produto, co.descricao AS variacao_cor,
                               ta.descricao AS variacao_tamanho, pbr.sku,
                               COALESCE(e.qtd_estoque, 0) AS qtd_estoque,
                               COALESCE(c.qtd_contada, 0) AS qtd_contada,
                               COALESCE(c.qtd_contada, 0) - COALESCE(e.qtd_estoque, 0) AS diferenca
                        FROM contagem c
                        FULL OUTER JOIN estoque e ON e.id_variacao = c.id_variacao
                        JOIN produto_barra pbr
                               ON pbr.id_variacao = COALESCE(c.id_variacao, e.id_variacao) AND pbr.id_tenant = plataforma.tenant_atual()
                        JOIN produto p ON p.id_produto = pbr.id_produto AND p.id_tenant = pbr.id_tenant
                        LEFT JOIN cfg_cor co ON co.id_cor = pbr.id_cor AND co.id_tenant = pbr.id_tenant
                        LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pbr.id_tamanho AND ta.id_tenant = pbr.id_tenant
                        WHERE COALESCE(c.qtd_contada, 0) <> COALESCE(e.qtd_estoque, 0)
                              AND (
                                  c.id_variacao IS NOT NULL
                                  OR NOT EXISTS (
                                      SELECT 1 FROM produto_balanco pb2
                                      WHERE pb2.id_tenant = plataforma.tenant_atual() AND pb2.id_empresa = ?
                                            AND pb2.id_variacao = e.id_variacao
                                  )
                              )
                        ORDER BY p.descricao, co.descricao, ta.descricao
                        """)
                .params(idEmpresa, idEmpresa, idEmpresa)
                .query(BalancoEstoqueService::mapearLinhaDiferenca)
                .list();
    }

    private void validarQtd(BigDecimal qtd) {
        if (!configuracaoGeralService.permiteQtdDecimalProduto() && temParteDecimal(qtd)) {
            throw new IllegalArgumentException(
                    "Quantidade deve ser um número inteiro — este tenant não permite quantidade decimal de produtos (Parâmetros do Sistema).");
        }
    }

    private void exigirVariacaoValida(long idVariacao) {
        boolean existe = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM produto_barra pb
                                       JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                                       WHERE pb.id_tenant = plataforma.tenant_atual()
                                             AND pb.id_variacao = ? AND p.ativo = true)
                        """)
                .param(idVariacao)
                .query(Boolean.class).single());
        if (!existe) {
            throw new ResponseStatusException(NOT_FOUND, "Produto não encontrado para a variação informada.");
        }
    }

    private static boolean temParteDecimal(BigDecimal valor) {
        return valor.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0;
    }

    private static LinhaContagem mapearLinhaContagem(ResultSet rs, int rowNum) throws SQLException {
        return new LinhaContagem(
                rs.getLong("id_variacao"), rs.getString("descricao_produto"), rs.getString("variacao_cor"),
                rs.getString("variacao_tamanho"), rs.getString("sku"), rs.getBigDecimal("qtd_contada"));
    }

    private static LinhaDiferenca mapearLinhaDiferenca(ResultSet rs, int rowNum) throws SQLException {
        return new LinhaDiferenca(
                rs.getLong("id_variacao"), rs.getString("descricao_produto"), rs.getString("variacao_cor"),
                rs.getString("variacao_tamanho"), rs.getString("sku"),
                rs.getBigDecimal("qtd_estoque"), rs.getBigDecimal("qtd_contada"), rs.getBigDecimal("diferenca"));
    }

    private static long idEmpresaSessao(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }
}
