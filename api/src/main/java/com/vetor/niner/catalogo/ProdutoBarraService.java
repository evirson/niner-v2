package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.ProdutoBarraDtos.ProdutoBarraResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * (gera o SKU, insere) — idempotente do ponto de vista de quem chama. Sempre exige que o
     * tamanho pertença à grade do produto (ver {@link #obterOuCriar(long, Long, Long, boolean)}
     * pra quando isso não deve valer). */
    @Transactional
    public ProdutoBarraResponse obterOuCriar(long idProduto, Long idCorPedido, Long idTamanhoPedido) {
        return obterOuCriar(idProduto, idCorPedido, idTamanhoPedido, true, null);
    }

    /**
     * {@code validarGrade=false}: usado só pela Rotina de Importação de Dados (Estoque,
     * 2026-08-11) — planilha migrada de outro sistema pode trazer um {@code NOME_TAMANHO} que
     * não está na sequência da grade do produto (ex. "UN1" numa grade que só tem "UN") sem que
     * isso seja, de fato, um erro; o dono do produto pediu que o tamanho seja aceito e a
     * variação criada mesmo assim. Emissão de Etiqueta (cadastro manual, ação deliberada de
     * quem está no balcão) continua sempre exigindo que o tamanho pertença à grade — chama o
     * overload de 3 parâmetros acima, sem mudança de comportamento.
     */
    @Transactional
    public ProdutoBarraResponse obterOuCriar(long idProduto, Long idCorPedido, Long idTamanhoPedido, boolean validarGrade) {
        return obterOuCriar(idProduto, idCorPedido, idTamanhoPedido, validarGrade, null);
    }

    /**
     * {@code ean} (2026-08-11, Entrada de Produtos por Compra) é o código de barras real do
     * fabricante — só é gravado se esta chamada de fato CRIA a variação; se já existe uma
     * variação pra essa combinação produto/cor/tamanho, o {@code ean} enviado é ignorado (nunca
     * sobrescreve o que já estava lá). Único ponto de entrada que grava {@code ean} — os outros
     * overloads chamam este com {@code ean = null}.
     */
    @Transactional
    public ProdutoBarraResponse obterOuCriar(long idProduto, Long idCorPedido, Long idTamanhoPedido, boolean validarGrade, String ean) {
        Long idGrade = buscarIdGrade(idProduto);
        Long idCor = idGrade == null ? null : idCorPedido;
        Long idTamanho = idGrade == null ? null : idTamanhoPedido;
        validarObrigatoriedade(idProduto, idGrade, idCor, idTamanho, validarGrade);
        return buscarPorCombinacao(idProduto, idCor, idTamanho)
                .orElseGet(() -> criar(idProduto, idCor, idTamanho, ean));
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

    private void validarObrigatoriedade(long idProduto, Long idGrade, Long idCor, Long idTamanho, boolean validarGrade) {
        if (idGrade == null) {
            return;
        }
        if (idCor == null) {
            throw new IllegalArgumentException("Este produto exige cor.");
        }
        if (idTamanho == null) {
            throw new IllegalArgumentException("Este produto exige tamanho.");
        }
        if (!validarGrade) {
            return;
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

    /** Resumo leve de uma variação (id_variacao, cor, tamanho, sku, ean) — usado só pelo
     *  pré-fetch em lote da Rotina de Importação de Dados (Estoque, 2026-08-11), pra evitar 1
     *  SELECT por combinação produto/cor/tamanho num arquivo com milhares de linhas (achado
     *  real: planilha de 22 mil linhas levando quase 8 minutos, dominado por ida-e-volta ao
     *  banco por linha, não por nenhuma consulta lenta em si). */
    public record VariacaoResumo(long idVariacao, Long idCor, Long idTamanho, String sku, String ean) {
    }

    /** Busca em lote o {@code id_grade} de vários produtos de uma vez — evita 1 SELECT por
     *  grupo quando o mesmo produto se repete em várias combinações cor/tamanho (o caso normal
     *  numa planilha de estoque). */
    public Map<Long, Long> buscarGradesEmLote(Collection<Long> idsProduto) {
        if (idsProduto.isEmpty()) {
            return Map.of();
        }
        List<Long> lista = new ArrayList<>(idsProduto);
        Map<Long, Long> resultado = new HashMap<>();
        jdbc.sql("""
                        SELECT id_produto, id_grade FROM produto
                        WHERE id_tenant = plataforma.tenant_atual() AND id_produto IN (%s)
                        """.formatted(placeholders(lista.size())))
                .params(lista)
                .query((rs, n) -> resultado.put(rs.getLong("id_produto"), getLongOuNulo(rs, "id_grade")))
                .list();
        return resultado;
    }

    /** Busca em lote as variações já cadastradas pra um conjunto de produtos — evita 1 SELECT
     *  por combinação produto/cor/tamanho, o que domina o tempo de uma importação de estoque
     *  grande. Chave do mapa: mesma convenção {@code idProduto+"|"+idCor+"|"+idTamanho} (vazio
     *  no lugar de {@code null}) já usada em {@code EstoqueImportador}. */
    public Map<String, VariacaoResumo> buscarVariacoesEmLote(Collection<Long> idsProduto) {
        if (idsProduto.isEmpty()) {
            return Map.of();
        }
        List<Long> lista = new ArrayList<>(idsProduto);
        Map<String, VariacaoResumo> resultado = new HashMap<>();
        jdbc.sql("""
                        SELECT id_variacao, id_produto, id_cor, id_tamanho, sku, ean FROM produto_barra
                        WHERE id_tenant = plataforma.tenant_atual() AND id_produto IN (%s)
                        """.formatted(placeholders(lista.size())))
                .params(lista)
                .query((rs, n) -> {
                    long idProduto = rs.getLong("id_produto");
                    Long idCor = getLongOuNulo(rs, "id_cor");
                    Long idTamanho = getLongOuNulo(rs, "id_tamanho");
                    String chave = idProduto + "|" + (idCor == null ? "" : idCor) + "|" + (idTamanho == null ? "" : idTamanho);
                    return resultado.put(chave, new VariacaoResumo(
                            rs.getLong("id_variacao"), idCor, idTamanho, rs.getString("sku"), rs.getString("ean")));
                })
                .list();
        return resultado;
    }

    /**
     * Cria uma variação pra importação em massa (2026-08-11) — recebe {@code idGrade} já
     * resolvido (quem chama fez um pré-fetch em lote via {@link #buscarGradesEmLote}) em vez de
     * buscar de novo, e não faz o SELECT de volta com os dados completos de produto/cor/tamanho
     * ({@link #criar} devolve isso pra uso interativo — a importação em massa não precisa). Só
     * deve ser chamada depois que o chamador já conferiu, via {@link #buscarVariacoesEmLote},
     * que a combinação ainda não existe. Continua exigindo cor/tamanho quando o produto tem
     * grade, mas — mesmo espírito do parâmetro {@code validarGrade} de
     * {@link #obterOuCriar(long, Long, Long, boolean)} — não valida que o tamanho pertence à
     * grade.
     */
    public VariacaoResumo criarParaImportacaoEmMassa(long idProduto, Long idGrade, Long idCorPedido, Long idTamanhoPedido) {
        Long idCor = idGrade == null ? null : idCorPedido;
        Long idTamanho = idGrade == null ? null : idTamanhoPedido;
        if (idGrade != null && idCor == null) {
            throw new IllegalArgumentException("Este produto exige cor.");
        }
        if (idGrade != null && idTamanho == null) {
            throw new IllegalArgumentException("Este produto exige tamanho.");
        }
        record NovaVariacao(long idVariacao, String sku) {
        }
        // SqlParameterValue explícito pra id_cor/id_tamanho — mesmo achado de `criar()` abaixo
        // (sem isso, uma variação sem grade, ambos null, falha no driver JDBC do Postgres).
        NovaVariacao nova = jdbc.sql("""
                        INSERT INTO produto_barra (id_tenant, id_produto, id_cor, id_tamanho, sku)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, gerar_ean13_interno())
                        RETURNING id_variacao, sku
                        """)
                .params(java.util.Arrays.asList(
                        idProduto,
                        new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.INTEGER, idCor),
                        new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.INTEGER, idTamanho)))
                .query((rs, n) -> new NovaVariacao(rs.getLong("id_variacao"), rs.getString("sku")))
                .single();
        return new VariacaoResumo(nova.idVariacao(), idCor, idTamanho, nova.sku(), null);
    }

    private static String placeholders(int quantidade) {
        return String.join(",", Collections.nCopies(quantidade, "?"));
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

    private ProdutoBarraResponse criar(long idProduto, Long idCor, Long idTamanho, String ean) {
        // SqlParameterValue explícito pra id_cor/id_tamanho: sem isso, uma variação sem grade
        // (ambos null) faz o driver JDBC do Postgres falhar ("conversion to class java.lang.Long
        // from int4 not supported") — mesmo achado do id_grade em ProdutoService, 2026-08-08.
        long idVariacao = jdbc.sql("""
                        INSERT INTO produto_barra (id_tenant, id_produto, id_cor, id_tamanho, sku, ean)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, gerar_ean13_interno(), ?)
                        RETURNING id_variacao
                        """)
                .params(java.util.Arrays.asList(
                        idProduto,
                        new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.INTEGER, idCor),
                        new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.INTEGER, idTamanho),
                        ean))
                .query(Long.class)
                .single();

        return jdbc.sql(SELECT_BASE + " WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ?")
                .param(idVariacao)
                .query(ProdutoBarraService::mapear)
                .single();
    }

    private static final String SELECT_BASE = """
            SELECT pb.id_variacao, pb.sku, pb.ean, p.descricao, p.marca, p.referencia, p.preco_venda,
                   co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho
            FROM produto_barra pb
            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
            LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant
            LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant
            """;

    private static ProdutoBarraResponse mapear(ResultSet rs, int n) throws SQLException {
        return new ProdutoBarraResponse(
                rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("ean"), rs.getString("descricao"),
                rs.getString("marca"), rs.getString("referencia"), rs.getBigDecimal("preco_venda"),
                rs.getString("variacao_cor"), rs.getString("variacao_tamanho"));
    }
}
