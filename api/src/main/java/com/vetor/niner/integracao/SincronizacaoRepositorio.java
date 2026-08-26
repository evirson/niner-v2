package com.vetor.niner.integracao;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * O que os manipuladores do outbox precisam ler e escrever para publicar estoque e preço (M3).
 *
 * <p>⚠️ Bean à parte dos manipuladores pelo motivo de sempre: {@code @Transactional} não vale em
 * auto-invocação, e sem transação não há {@code SET LOCAL app.id_tenant} — o {@code SELECT}
 * voltaria <b>vazio em silêncio</b> e o worker publicaria "nada mudou" para sempre.
 *
 * <p>Toda consulta filtra {@code id_tenant} <b>no texto do SQL</b> (P8).
 */
@Repository
public class SincronizacaoRepositorio {

    private final JdbcClient jdbc;

    public SincronizacaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Uma linha a publicar: o anúncio, a variação dele no canal, e o saldo do ERP.
     *
     * @param disponivel {@code qtd_estoque - reservado} — o saldo <b>disponível</b>, nunca o
     *                   bruto. Publicar o bruto prometeria o que já está reservado para outro
     *                   pedido (ADR-004)
     */
    public record LinhaParaPublicar(long idCanal, String tipoCanal, long idAnuncio,
                                    String idExterno, String idExternoVariacao, long idVariacao,
                                    BigDecimal disponivel, BigDecimal preco, boolean precoManual,
                                    BigDecimal precoVendaDaLoja, BigDecimal percPrecoDoCanal) {
    }

    /**
     * Tudo o que precisa ir junto num {@code PUT} do anúncio que contém esta variação.
     *
     * <p>⛔ <b>Traz o anúncio INTEIRO, não só a variação que mudou</b> — e é por isso que a busca é
     * por {@code id_externo} e não por {@code id_variacao}. No Mercado Livre um {@code PUT} em
     * {@code variations} <b>apaga as que não forem enviadas</b> (§2.4). O adapter ainda lê o
     * anúncio antes de escrever como segunda defesa, mas mandar daqui só uma das doze variações
     * ligadas seria confiar a integridade do anúncio do lojista inteiramente a essa segunda linha.
     */
    @Transactional(readOnly = true)
    public List<LinhaParaPublicar> linhasDoItemDaVariacao(long idVariacao, long idEmpresa) {
        return jdbc.sql("""
                        SELECT a.id_canal, c.tipo::text AS tipo_canal, a.id_anuncio, a.id_externo,
                               a.id_externo_variacao, a.id_variacao, a.preco, a.preco_manual,
                               COALESCE(pe.qtd_estoque - pe.reservado, 0) AS disponivel,
                               p.preco_venda, c.perc_preco
                          FROM anuncio a
                          JOIN canal c  ON c.id_tenant = a.id_tenant AND c.id_canal = a.id_canal
                          JOIN produto_barra pb ON pb.id_tenant = a.id_tenant AND pb.id_variacao = a.id_variacao
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          LEFT JOIN produto_estoque pe
                                 ON pe.id_tenant = a.id_tenant
                                AND pe.id_variacao = a.id_variacao
                                AND pe.id_empresa = c.id_empresa
                         WHERE a.id_tenant = plataforma.tenant_atual()
                           AND c.status = 'CONECTADO'
                           AND c.id_empresa = ?
                           AND a.id_externo IN (
                                 SELECT a2.id_externo FROM anuncio a2
                                  JOIN canal c2 ON c2.id_tenant = a2.id_tenant AND c2.id_canal = a2.id_canal
                                  WHERE a2.id_tenant = plataforma.tenant_atual()
                                    AND a2.id_variacao = ?
                                    AND c2.id_empresa = ?)
                         ORDER BY a.id_canal, a.id_externo, a.id_anuncio
                        """)
                .params(idEmpresa, idVariacao, idEmpresa)
                .query(SincronizacaoRepositorio::mapear)
                .list();
    }

    /** As linhas derivadas (não manuais) de um produto, para o reajuste de preço. */
    @Transactional(readOnly = true)
    public List<LinhaParaPublicar> linhasDerivadasDoProduto(long idProduto) {
        return jdbc.sql("""
                        SELECT a.id_canal, c.tipo::text AS tipo_canal, a.id_anuncio, a.id_externo,
                               a.id_externo_variacao, a.id_variacao, a.preco, a.preco_manual,
                               COALESCE(pe.qtd_estoque - pe.reservado, 0) AS disponivel,
                               p.preco_venda, c.perc_preco
                          FROM anuncio a
                          JOIN canal c  ON c.id_tenant = a.id_tenant AND c.id_canal = a.id_canal
                          JOIN produto_barra pb ON pb.id_tenant = a.id_tenant AND pb.id_variacao = a.id_variacao
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          LEFT JOIN produto_estoque pe
                                 ON pe.id_tenant = a.id_tenant
                                AND pe.id_variacao = a.id_variacao
                                AND pe.id_empresa = c.id_empresa
                         WHERE a.id_tenant = plataforma.tenant_atual()
                           AND c.status = 'CONECTADO'
                           AND pb.id_produto = ?
                           AND a.preco_manual = false
                         ORDER BY a.id_canal, a.id_anuncio
                        """)
                .params(idProduto)
                .query(SincronizacaoRepositorio::mapear)
                .list();
    }

    /** Grava o preço recalculado. Só para linha derivada — a manual nunca chega aqui. */
    @Transactional
    public void gravarPreco(long idAnuncio, BigDecimal preco) {
        jdbc.sql("""
                        UPDATE anuncio SET preco = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_anuncio = ?
                           AND preco_manual = false
                        """)
                .params(preco, idAnuncio)
                .update();
    }

    /**
     * Marca o resultado da sincronização no anúncio.
     *
     * <p>⚠️ {@code ultimo_erro} guarda o texto <b>cru</b> do canal quando falha, e é apagado no
     * sucesso. Erro que não some depois de resolvido treina o lojista a ignorar a coluna.
     */
    @Transactional
    public void marcarSincronizacao(List<Long> idsAnuncio, String status, String erro) {
        if (idsAnuncio.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        UPDATE anuncio
                           SET status_sync = CAST(? AS status_sync), ultimo_erro = ?,
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_anuncio IN (:ids)
                        """.replace(":ids", marcadores(idsAnuncio.size())))
                .params(parametros(status, erro, idsAnuncio))
                .update();
    }

    private static String marcadores(int quantos) {
        return String.join(",", java.util.Collections.nCopies(quantos, "?"));
    }

    private static List<Object> parametros(String status, String erro, List<Long> ids) {
        List<Object> p = new java.util.ArrayList<>();
        p.add(status);
        p.add(erro);
        p.addAll(ids);
        return p;
    }

    private static LinhaParaPublicar mapear(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new LinhaParaPublicar(
                rs.getLong("id_canal"), rs.getString("tipo_canal"), rs.getLong("id_anuncio"),
                rs.getString("id_externo"), rs.getString("id_externo_variacao"),
                rs.getLong("id_variacao"), rs.getBigDecimal("disponivel"),
                rs.getBigDecimal("preco"), rs.getBoolean("preco_manual"),
                rs.getBigDecimal("preco_venda"), rs.getBigDecimal("perc_preco"));
    }
}
