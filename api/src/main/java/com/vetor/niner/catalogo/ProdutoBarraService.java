package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.ProdutoBarraDtos.ProdutoBarraResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Variação de produto (`produto_barra`) — 1ª implementação de domínio real pra essa tabela
 * (schema pronto desde a V017, mas até 2026-08-05 nenhum service Java criava linha aqui; SKU só
 * existia via INSERT manual/JDBC em sessões de teste). Nasce como consumo direto da Emissão de
 * Etiqueta (item 1 do pedido, 2026-08-05: modo Individual pode escolher um produto/variação SEM
 * código de barras ainda cadastrado — o sistema cria na hora), mas fica em {@code catalogo}
 * (perto de {@code ProdutoService}) porque é lógica de domínio de produto, não de emissão de
 * etiqueta — qualquer feature futura que precise de variação reaproveita este service, não recria.
 *
 * <p>{@code sku} SEMPRE vem de {@code gerar_ean13_interno()} (V017, testada em
 * {@code EanGeradorTest}) — nunca aceito do cliente, mesmo estilo de {@code
 * plataforma.tenant_atual()} usado no resto do domínio (aviso explícito em {@code CLAUDE.md}).
 *
 * <p><b>Obrigatoriedade de linha/coluna é por PRODUTO</b>, não uma flag global: se
 * {@code produto.nome_variante_linha} está preenchido, ESTE produto usa a dimensão "linha" e
 * {@code idVarianteLinha} passa a ser obrigatório pra ele (mesmo raciocínio pro `nome_variante_coluna`/
 * coluna) — dois produtos do mesmo tenant podem ter combinações diferentes (cor+tamanho, só
 * tamanho, ou nenhuma), mesmo com as flags globais {@code cfg_usa_variante_linha}/{@code
 * cfg_usa_variante_coluna} (Parâmetros do Sistema) ligadas — essas só controlam se o CAMPO
 * aparece no cadastro do produto, o preenchimento em si é opcional por produto.
 */
@Service
public class ProdutoBarraService {

    private final JdbcClient jdbc;

    public ProdutoBarraService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Acha a variação já cadastrada pra essa combinação produto/linha/coluna, ou cria na hora
     * (gera o SKU, insere) — idempotente do ponto de vista de quem chama. */
    @Transactional
    public ProdutoBarraResponse obterOuCriar(long idProduto, Long idVarianteLinha, Long idVarianteColuna) {
        validarObrigatoriedade(idProduto, idVarianteLinha, idVarianteColuna);
        return buscarPorCombinacao(idProduto, idVarianteLinha, idVarianteColuna)
                .orElseGet(() -> criar(idProduto, idVarianteLinha, idVarianteColuna));
    }

    private void validarObrigatoriedade(long idProduto, Long idVarianteLinha, Long idVarianteColuna) {
        String[] nomes = jdbc.sql("""
                        SELECT nome_variante_linha, nome_variante_coluna FROM produto
                        WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?
                        """)
                .param(idProduto)
                .query((rs, n) -> new String[]{rs.getString("nome_variante_linha"), rs.getString("nome_variante_coluna")})
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Produto não encontrado."));

        if (nomes[0] != null && idVarianteLinha == null) {
            throw new IllegalArgumentException("Este produto exige a variação de \"" + nomes[0] + "\".");
        }
        if (nomes[1] != null && idVarianteColuna == null) {
            throw new IllegalArgumentException("Este produto exige a variação de \"" + nomes[1] + "\".");
        }
    }

    private Optional<ProdutoBarraResponse> buscarPorCombinacao(long idProduto, Long idVarianteLinha, Long idVarianteColuna) {
        StringBuilder sql = new StringBuilder(SELECT_BASE + " WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_produto = ?");
        List<Object> params = new ArrayList<>();
        params.add(idProduto);
        sql.append(idVarianteLinha == null ? " AND pb.id_variante_linha IS NULL" : " AND pb.id_variante_linha = ?");
        if (idVarianteLinha != null) params.add(idVarianteLinha);
        sql.append(idVarianteColuna == null ? " AND pb.id_variante_coluna IS NULL" : " AND pb.id_variante_coluna = ?");
        if (idVarianteColuna != null) params.add(idVarianteColuna);

        return jdbc.sql(sql.toString()).params(params).query(ProdutoBarraService::mapear).optional();
    }

    private ProdutoBarraResponse criar(long idProduto, Long idVarianteLinha, Long idVarianteColuna) {
        long idVariacao = jdbc.sql("""
                        INSERT INTO produto_barra (id_tenant, id_produto, id_variante_linha, id_variante_coluna, sku)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, gerar_ean13_interno())
                        RETURNING id_variacao
                        """)
                .params(java.util.Arrays.asList(idProduto, idVarianteLinha, idVarianteColuna))
                .query(Long.class)
                .single();

        return jdbc.sql(SELECT_BASE + " WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ?")
                .param(idVariacao)
                .query(ProdutoBarraService::mapear)
                .single();
    }

    private static final String SELECT_BASE = """
            SELECT pb.id_variacao, pb.sku, p.descricao, p.marca, p.referencia, p.preco_venda,
                   vl.descricao AS variacao_linha, vc.descricao AS variacao_coluna
            FROM produto_barra pb
            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
            LEFT JOIN cfg_variante_linha vl ON vl.id_variante_linha = pb.id_variante_linha AND vl.id_tenant = pb.id_tenant
            LEFT JOIN cfg_variante_coluna vc ON vc.id_variante_coluna = pb.id_variante_coluna AND vc.id_tenant = pb.id_tenant
            """;

    private static ProdutoBarraResponse mapear(ResultSet rs, int n) throws SQLException {
        return new ProdutoBarraResponse(
                rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("descricao"), rs.getString("marca"),
                rs.getString("referencia"), rs.getBigDecimal("preco_venda"), rs.getString("variacao_linha"),
                rs.getString("variacao_coluna"));
    }
}
