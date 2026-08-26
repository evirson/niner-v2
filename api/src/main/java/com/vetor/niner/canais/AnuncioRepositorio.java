package com.vetor.niner.canais;

import com.vetor.niner.canais.AnuncioDtos.VariacaoDoErp;
import com.vetor.niner.canais.AnuncioDtos.VinculoGravado;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * As leituras e escritas do de-para anúncio ↔ variação (R6).
 *
 * <h2>⛔ Por que isto é um bean separado de {@link AnuncioService}</h2>
 *
 * Porque {@code @Transactional} <b>não vale em auto-invocação</b>: método anotado chamado de dentro
 * do próprio bean não passa pelo proxy do Spring e roda <b>sem transação</b>. Sem transação o
 * {@code TenantAwareTransactionManager} não executa o {@code SET LOCAL app.id_tenant},
 * {@code plataforma.tenant_atual()} vale NULL, e o {@code SELECT} casa <b>zero linha em
 * silêncio</b> — o pior desfecho possível, porque "vazio" costuma ser lido como "não existe".
 *
 * <p>E o serviço <b>precisa</b> chamar estes métodos de fora de uma transação: no meio dele há uma
 * chamada HTTP ao marketplace, e segurar uma conexão do pool esperando um terceiro é o defeito
 * apontado na auditoria de 2026-08-21. Mesmo desenho de {@code OutboxJob} × {@code OutboxProcessador}.
 *
 * <p>Toda consulta filtra {@code id_tenant} <b>no texto do SQL</b>, não só por RLS (P8).
 */
@Repository
public class AnuncioRepositorio {

    private final JdbcClient jdbc;

    public AnuncioRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** O canal, como o vínculo precisa conhecê-lo. */
    public record DadosDoCanal(String tipo, String nome, String status, BigDecimal percPreco) {
    }

    /** Um vínculo que já existe, para a tela mostrar o que está ligado a quê. */
    public record Vinculo(long idAnuncio, long idVariacao, String descricaoVariacao) {
    }

    @Transactional(readOnly = true)
    public Optional<DadosDoCanal> buscarCanal(long idCanal) {
        return jdbc.sql("""
                        SELECT tipo::text AS tipo, nome, status::text AS status, perc_preco
                          FROM canal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(idCanal)
                .query((rs, n) -> new DadosDoCanal(rs.getString("tipo"), rs.getString("nome"),
                        rs.getString("status"), rs.getBigDecimal("perc_preco")))
                .optional();
    }

    /**
     * Os vínculos do canal, indexados pela chave (anúncio, variação do canal).
     *
     * <p>⚠️ A chave é <b>texto com separador</b>. Concatenar sem separador faria
     * {@code ("MLB1","23")} e {@code ("MLB12","3")} colidirem — a mesma família de defeito de
     * chave que a Devolução de Produtos pagou em 2026-08-22.
     */
    @Transactional(readOnly = true)
    public Map<String, Vinculo> vinculosDoCanal(long idCanal) {
        Map<String, Vinculo> mapa = new HashMap<>();
        jdbc.sql("""
                        SELECT a.id_anuncio, a.id_externo, a.id_externo_variacao, a.id_variacao,
                               p.descricao || ' — ' || c.descricao || ' / ' || t.descricao AS descricao
                          FROM anuncio a
                          JOIN produto_barra pb ON pb.id_tenant = a.id_tenant AND pb.id_variacao = a.id_variacao
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          JOIN cfg_cor c ON c.id_tenant = pb.id_tenant AND c.id_cor = pb.id_cor
                          JOIN cfg_tamanho t ON t.id_tenant = pb.id_tenant AND t.id_tamanho = pb.id_tamanho
                         WHERE a.id_tenant = plataforma.tenant_atual() AND a.id_canal = ?
                        """)
                .params(idCanal)
                .query((rs, n) -> mapa.put(
                        chave(rs.getString("id_externo"), rs.getString("id_externo_variacao")),
                        new Vinculo(rs.getLong("id_anuncio"), rs.getLong("id_variacao"),
                                rs.getString("descricao"))))
                .list();
        return mapa;
    }

    /**
     * Variações do ERP cujo SKU (ou EAN) casa com algum dos códigos vindos do canal.
     *
     * <p>⚠️ Olha as <b>duas</b> colunas: o SKU do ERP é gerado ({@code gerar_ean13_interno}), mas o
     * lojista pode ter digitado o <b>EAN real</b> no campo de SKU do Mercado Livre. As duas
     * hipóteses apontam para a mesma variação, e checar só uma perderia metade das sugestões.
     *
     * <p>⚠️ Uma consulta para a página inteira, não uma por linha: a tela lista até 50 anúncios, e
     * cada anúncio com variações vira várias linhas.
     */
    @Transactional(readOnly = true)
    public List<VariacaoDoErp> buscarPorSkus(List<String> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT pb.id_variacao, pb.sku,
                               p.descricao || ' — ' || c.descricao || ' / ' || t.descricao AS descricao
                          FROM produto_barra pb
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          JOIN cfg_cor c ON c.id_tenant = pb.id_tenant AND c.id_cor = pb.id_cor
                          JOIN cfg_tamanho t ON t.id_tenant = pb.id_tenant AND t.id_tamanho = pb.id_tamanho
                         WHERE pb.id_tenant = plataforma.tenant_atual()
                           AND (upper(pb.sku) IN (:skus) OR upper(pb.ean) IN (:skus))
                        """)
                .param("skus", skus)
                .query((rs, n) -> new VariacaoDoErp(rs.getLong("id_variacao"), rs.getString("sku"),
                        rs.getString("descricao")))
                .list();
    }

    /**
     * Os vínculos gravados do canal — <b>sem</b> chamar o marketplace.
     *
     * <p>É o que mantém a tela útil no dia em que o ML estiver fora do ar: o lojista continua
     * vendo e podendo desfazer o que vinculou.
     */
    @Transactional(readOnly = true)
    public List<VinculoGravado> listarVinculos(long idCanal) {
        return jdbc.sql("""
                        SELECT a.id_anuncio, a.id_externo, a.id_externo_variacao, a.id_variacao,
                               pb.sku, a.preco, a.preco_manual, a.status_sync::text AS status_sync,
                               a.ultimo_erro,
                               p.descricao || ' — ' || c.descricao || ' / ' || t.descricao AS descricao
                          FROM anuncio a
                          JOIN produto_barra pb ON pb.id_tenant = a.id_tenant AND pb.id_variacao = a.id_variacao
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          JOIN cfg_cor c ON c.id_tenant = pb.id_tenant AND c.id_cor = pb.id_cor
                          JOIN cfg_tamanho t ON t.id_tenant = pb.id_tenant AND t.id_tamanho = pb.id_tamanho
                         WHERE a.id_tenant = plataforma.tenant_atual() AND a.id_canal = ?
                         ORDER BY p.descricao, a.id_anuncio
                        """)
                .params(idCanal)
                .query((rs, n) -> new VinculoGravado(
                        rs.getLong("id_anuncio"), rs.getString("id_externo"),
                        rs.getString("id_externo_variacao"), rs.getLong("id_variacao"),
                        rs.getString("sku"), rs.getString("descricao"),
                        rs.getBigDecimal("preco"), rs.getBoolean("preco_manual"),
                        rs.getString("status_sync"), rs.getString("ultimo_erro")))
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<BigDecimal> precoDeVendaDaVariacao(long idVariacao) {
        return jdbc.sql("""
                        SELECT p.preco_venda
                          FROM produto_barra pb
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                         WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ?
                        """)
                .params(idVariacao)
                .query(BigDecimal.class)
                .optional();
    }

    /**
     * Grava o vínculo.
     *
     * <p>⚠️ {@code preco_manual = false}: o preço nasce <b>derivado</b> e acompanha o reajuste da
     * loja até o dia em que o lojista o digitar (§8.4).
     */
    @Transactional
    public void inserir(long idCanal, long idVariacao, String idExterno, String idExternoVariacao,
                        BigDecimal preco) {
        jdbc.sql("""
                        INSERT INTO anuncio (id_tenant, id_canal, id_variacao, id_externo,
                                             id_externo_variacao, preco, preco_manual, status_sync)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, false, 'PENDENTE')
                        """)
                .params(idCanal, idVariacao, idExterno, idExternoVariacao, preco)
                .update();

        // ⭐ Publica o saldo AGORA, na mesma transação do vínculo.
        //
        // Sem isto, um anúncio recém-vinculado ficaria com o saldo que estiver no Mercado Livre
        // até a próxima venda daquele produto — que pode levar semanas. E o pior caso é o comum:
        // o lojista vincula justamente porque o número lá está errado.
        //
        // ⚠️ O gatilho da V067 não cobre este caso: ele reage a mudança de `produto_estoque`, e
        // vincular não mexe em estoque nenhum. É o único ponto em que enfileirar por Java é certo.
        jdbc.sql("""
                        INSERT INTO outbox_evento (id_tenant, tipo, agregado_id, payload)
                        SELECT plataforma.tenant_atual(), 'ESTOQUE_ATUALIZADO',
                               ? || ':' || c.id_empresa,
                               jsonb_build_object('idVariacao', CAST(? AS bigint),
                                                  'idEmpresa', c.id_empresa)
                          FROM canal c
                         WHERE c.id_tenant = plataforma.tenant_atual() AND c.id_canal = ?
                        """)
                .params(idVariacao, idVariacao, idCanal)
                .update();
    }

    /** Já existe vínculo desta variação do ERP neste canal? Decide QUAL mensagem de conflito dar. */
    @Transactional(readOnly = true)
    public boolean variacaoDoErpJaVinculada(long idCanal, long idVariacao) {
        Integer quantos = jdbc.sql("""
                        SELECT count(*) FROM anuncio
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_canal = ? AND id_variacao = ?
                        """)
                .params(idCanal, idVariacao)
                .query(Integer.class)
                .single();
        return quantos != null && quantos > 0;
    }

    @Transactional
    public int excluir(long idCanal, long idAnuncio) {
        return jdbc.sql("""
                        DELETE FROM anuncio
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_canal = ? AND id_anuncio = ?
                        """)
                .params(idCanal, idAnuncio)
                .update();
    }

    /** Ver a nota de {@link #vinculosDoCanal}: separador obrigatório. */
    public static String chave(String idExterno, String idExternoVariacao) {
        return idExterno + " " + (idExternoVariacao == null ? "" : idExternoVariacao);
    }

    public static String normalizar(String codigo) {
        return codigo.trim().toUpperCase(Locale.ROOT);
    }
}
