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
import java.util.stream.IntStream;

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
 * <p><b>Cor + tamanho (2026-08-08)</b> substituem o antigo par genérico linha/coluna: a
 * obrigatoriedade agora é por {@code produto.id_grade}, não mais por nome configurado em cada
 * produto — se o produto tem grade, cor e tamanho são **ambos** obrigatórios na variação (decisão
 * do dono do produto), e o tamanho escolhido precisa pertencer à grade do produto (não é
 * qualquer tamanho do tenant). Sem grade, cor/tamanho são forçados a {@code null} — mesmo
 * princípio de "campo oculto ⇒ servidor ignora, não rejeita" usado no resto do sistema. A cor em
 * si ainda não tem tela de cadastro própria (nasce embutida na Emissão de Etiqueta, válvula de
 * escape enquanto a Entrada de Produtos — onde cor nasceria na prática, na compra — não existe).
 */
@Service
public class ProdutoBarraService {

    private static final int MAX_TAMANHOS_GRADE = 20;
    private static final String COLUNAS_GRADE = String.join(", ",
            IntStream.rangeClosed(1, MAX_TAMANHOS_GRADE).mapToObj(i -> "g.id_tamanho" + i).toList());

    private final JdbcClient jdbc;

    public ProdutoBarraService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Acha a variação já cadastrada pra essa combinação produto/cor/tamanho, ou cria na hora
     * (gera o SKU, insere) — idempotente do ponto de vista de quem chama. */
    @Transactional
    public ProdutoBarraResponse obterOuCriar(long idProduto, Long idCorPedido, Long idTamanhoPedido) {
        Long idGrade = buscarIdGrade(idProduto);
        Long idCor = idGrade == null ? null : idCorPedido;
        Long idTamanho = idGrade == null ? null : idTamanhoPedido;
        validarObrigatoriedade(idProduto, idGrade, idCor, idTamanho);
        return buscarPorCombinacao(idProduto, idCor, idTamanho)
                .orElseGet(() -> criar(idProduto, idCor, idTamanho));
    }

    /** Envelope não-nulo em volta de {@code idGrade} — necessário porque tanto {@code
     * .query(rowMapper).optional()} (sobre o valor mapeado) quanto {@code Optional.map(fn)}
     * (quando {@code fn} devolve null) colapsam pra {@code Optional.empty()} — uma linha
     * existente com {@code id_grade IS NULL} (produto sem grade) virava indistinguível de
     * "produto não encontrado", disparando 404 por engano (achado real de teste, 2026-08-08).
     * Por isso extrai o campo DEPOIS de resolver a presença da linha, nunca via `.map`. */
    private record LinhaProduto(Long idGrade) {
    }

    private Long buscarIdGrade(long idProduto) {
        List<LinhaProduto> lista = jdbc.sql("""
                        SELECT id_grade FROM produto
                        WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?
                        """)
                .param(idProduto)
                .query((rs, n) -> new LinhaProduto(getLongOuNulo(rs, "id_grade")))
                .list();
        if (lista.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Produto não encontrado.");
        }
        return lista.get(0).idGrade();
    }

    /** {@code rs.getObject(coluna, Long.class)} não converte `integer` (o tipo real de
     * `produto.id_grade`) pra `Long` de forma confiável no driver — mesmo padrão já usado em
     * {@code CrmService.getLongOuNulo}. */
    private static Long getLongOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    private void validarObrigatoriedade(long idProduto, Long idGrade, Long idCor, Long idTamanho) {
        if (idGrade == null) {
            return;
        }
        if (idCor == null) {
            throw new IllegalArgumentException("Este produto exige cor.");
        }
        if (idTamanho == null) {
            throw new IllegalArgumentException("Este produto exige tamanho.");
        }
        boolean pertenceAGrade = Boolean.TRUE.equals(
                jdbc.sql("""
                                SELECT EXISTS (
                                    SELECT 1 FROM produto p
                                    JOIN cfg_grade g ON g.id_grade = p.id_grade AND g.id_tenant = p.id_tenant
                                    WHERE p.id_tenant = plataforma.tenant_atual() AND p.id_produto = ?
                                      AND ? IN (%s)
                                )
                                """.formatted(COLUNAS_GRADE))
                        .params(idProduto, idTamanho)
                        .query(Boolean.class).single());
        if (!pertenceAGrade) {
            throw new IllegalArgumentException("Tamanho informado não pertence à grade deste produto.");
        }
    }

    private Optional<ProdutoBarraResponse> buscarPorCombinacao(long idProduto, Long idCor, Long idTamanho) {
        StringBuilder sql = new StringBuilder(SELECT_BASE + " WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_produto = ?");
        List<Object> params = new ArrayList<>();
        params.add(idProduto);
        sql.append(idCor == null ? " AND pb.id_cor IS NULL" : " AND pb.id_cor = ?");
        if (idCor != null) params.add(idCor);
        sql.append(idTamanho == null ? " AND pb.id_tamanho IS NULL" : " AND pb.id_tamanho = ?");
        if (idTamanho != null) params.add(idTamanho);

        return jdbc.sql(sql.toString()).params(params).query(ProdutoBarraService::mapear).optional();
    }

    private ProdutoBarraResponse criar(long idProduto, Long idCor, Long idTamanho) {
        // SqlParameterValue explícito pra id_cor/id_tamanho: sem isso, uma variação sem grade
        // (ambos null) faz o driver JDBC do Postgres falhar ("conversion to class java.lang.Long
        // from int4 not supported") — mesmo achado do id_grade em ProdutoService, 2026-08-08.
        long idVariacao = jdbc.sql("""
                        INSERT INTO produto_barra (id_tenant, id_produto, id_cor, id_tamanho, sku)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, gerar_ean13_interno())
                        RETURNING id_variacao
                        """)
                .params(java.util.Arrays.asList(
                        idProduto,
                        new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.INTEGER, idCor),
                        new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.INTEGER, idTamanho)))
                .query(Long.class)
                .single();

        return jdbc.sql(SELECT_BASE + " WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ?")
                .param(idVariacao)
                .query(ProdutoBarraService::mapear)
                .single();
    }

    private static final String SELECT_BASE = """
            SELECT pb.id_variacao, pb.sku, p.descricao, p.marca, p.referencia, p.preco_venda,
                   co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho
            FROM produto_barra pb
            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
            LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant
            LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant
            """;

    private static ProdutoBarraResponse mapear(ResultSet rs, int n) throws SQLException {
        return new ProdutoBarraResponse(
                rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("descricao"), rs.getString("marca"),
                rs.getString("referencia"), rs.getBigDecimal("preco_venda"), rs.getString("variacao_cor"),
                rs.getString("variacao_tamanho"));
    }
}
