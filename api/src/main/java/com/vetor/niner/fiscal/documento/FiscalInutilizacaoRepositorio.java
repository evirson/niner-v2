package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistência da inutilização de numeração (§10.4, bloco B8).
 *
 * <p>Bean separado do serviço de orquestração pelo mesmo motivo de sempre neste módulo: um método
 * {@code @Transactional} chamado de dentro da própria classe não passa pelo proxy do Spring, e sem
 * transação não há {@code app.id_tenant} — o RLS bloqueia tudo em silêncio (P8,
 * [[feedback_transactional_chamada_interna_rls]]).
 *
 * <p>Toda query filtra {@code id_tenant} explicitamente no texto do SQL além do RLS
 * ([[feedback_isolamento_tenant_explicito]]).
 */
@Repository
public class FiscalInutilizacaoRepositorio {

    /** Uma nota com número queimado legitimamente perante a SEFAZ — não é buraco. */
    private static final String SITUACOES_NAO_SAO_BURACO = "'AUTORIZADO', 'CANCELADO', 'DENEGADO'";

    private final JdbcClient jdbc;

    public FiscalInutilizacaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<ContextoEmpresa> buscarContexto(long idEmpresa) {
        return jdbc.sql("""
                        SELECT e.cnpj, e.estado AS uf, c.ambiente::text AS ambiente
                          FROM empresa e
                          JOIN fiscal_config_empresa c
                            ON c.id_tenant = e.id_tenant AND c.id_empresa = e.id_empresa
                         WHERE e.id_tenant = plataforma.tenant_atual() AND e.id_empresa = ?
                        """)
                .param(idEmpresa)
                .query((rs, n) -> new ContextoEmpresa(
                        rs.getString("cnpj"), rs.getString("uf"),
                        AmbienteSefaz.valueOf(rs.getString("ambiente"))))
                .optional();
    }

    /** {@code proximo_numero} da série — 1 quando nunca houve emissão (linha nem existe). */
    @Transactional(readOnly = true)
    public int proximoNumero(long idEmpresa, int modelo, int serie) {
        return jdbc.sql("""
                        SELECT proximo_numero FROM fiscal_numeracao
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_empresa = ? AND modelo = ? AND serie = ?
                        """)
                .params(idEmpresa, modelo, serie)
                .query(Integer.class)
                .optional()
                .orElse(1);
    }

    /**
     * Números da faixa pedida que já foram consumidos por uma nota de verdade (autorizada,
     * cancelada ou denegada) — inutilizá-los seria pedir à SEFAZ para apagar uma nota que existe,
     * não tapar um buraco. Bloqueio preventivo (F11): mais barato recusar aqui do que esperar a
     * SEFAZ recusar.
     */
    @Transactional(readOnly = true)
    public List<Integer> numerosJaConsumidos(long idEmpresa, int modelo, int serie,
                                             int numeroInicial, int numeroFinal) {
        return jdbc.sql("""
                        SELECT numero FROM documento_fiscal
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_empresa = ? AND modelo = ? AND serie = ?
                           AND numero BETWEEN ? AND ?
                           AND situacao IN (%s)
                         ORDER BY numero
                        """.formatted(SITUACOES_NAO_SAO_BURACO))
                .params(idEmpresa, modelo, serie, numeroInicial, numeroFinal)
                .query(Integer.class)
                .list();
    }

    /** {@code true} se a faixa pedida sobrepõe alguma inutilização já homologada pela SEFAZ. */
    @Transactional(readOnly = true)
    public boolean sobrepoeInutilizacaoExistente(long idEmpresa, int modelo, int serie,
                                                 int numeroInicial, int numeroFinal) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT exists(
                            SELECT 1 FROM fiscal_inutilizacao
                             WHERE id_tenant = plataforma.tenant_atual()
                               AND id_empresa = ? AND modelo = ? AND serie = ? AND autorizado
                               AND numero_inicial <= ? AND numero_final >= ?)
                        """)
                .params(idEmpresa, modelo, serie, numeroFinal, numeroInicial)
                .query(Boolean.class)
                .single());
    }

    /**
     * Números alocados (1..proximo_numero-1) que não viraram nota de verdade nem já foram
     * inutilizados — os buracos que a tela detecta sozinha (§10.4: "ninguém confere sequência de
     * nota manualmente").
     */
    @Transactional(readOnly = true)
    public List<Integer> buracos(long idEmpresa, int modelo, int serie) {
        int proximo = proximoNumero(idEmpresa, modelo, serie);
        if (proximo <= 1) {
            return List.of();
        }
        return jdbc.sql("""
                        WITH faixa AS (
                          SELECT generate_series(1, ? - 1) AS numero
                        ),
                        usados AS (
                          SELECT numero FROM documento_fiscal
                           WHERE id_tenant = plataforma.tenant_atual()
                             AND id_empresa = ? AND modelo = ? AND serie = ?
                             AND situacao IN (%s)
                        ),
                        inutilizados AS (
                          SELECT generate_series(numero_inicial, numero_final) AS numero
                            FROM fiscal_inutilizacao
                           WHERE id_tenant = plataforma.tenant_atual()
                             AND id_empresa = ? AND modelo = ? AND serie = ? AND autorizado
                        )
                        SELECT f.numero FROM faixa f
                         WHERE f.numero NOT IN (SELECT numero FROM usados)
                           AND f.numero NOT IN (SELECT numero FROM inutilizados)
                         ORDER BY f.numero
                        """.formatted(SITUACOES_NAO_SAO_BURACO))
                .params(proximo, idEmpresa, modelo, serie, idEmpresa, modelo, serie)
                .query(Integer.class)
                .list();
    }

    /**
     * Grava a <b>tentativa</b> de inutilização — sempre, homologada ou recusada (P3, F11: nunca
     * esconder uma recusa). Uma linha por tentativa, nunca apagada nem sobrescrita (F6/F7).
     */
    @Transactional
    public long registrarTentativa(long idEmpresa, int modelo, int serie, int ano,
                                   int numeroInicial, int numeroFinal, String justificativa,
                                   boolean autorizado, String protocolo, String statusSefaz,
                                   String motivoSefaz, String xmlInutilizacao, Integer idUsuario) {
        return jdbc.sql("""
                        INSERT INTO fiscal_inutilizacao (
                            id_tenant, id_empresa, modelo, serie, ano, numero_inicial, numero_final,
                            justificativa, autorizado, protocolo, status_sefaz, motivo_sefaz,
                            xml_inutilizacao, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id_inutilizacao
                        """)
                .params(idEmpresa, modelo, serie, ano, numeroInicial, numeroFinal, justificativa,
                        autorizado, protocolo, statusSefaz, motivoSefaz, xmlInutilizacao, idUsuario)
                .query(Long.class).single();
    }

    // ---------------------------------------------------------------- arquivamento (docs/HANDOFF-ARQUIVAMENTO-XML.md)

    @Transactional(readOnly = true)
    public Optional<InutilizacaoParaArquivar> buscarParaArquivar(long idInutilizacao) {
        return jdbc.sql("""
                        SELECT modelo, serie, numero_inicial, numero_final, xml_inutilizacao,
                               criado_em, xml_objeto_bucket
                          FROM fiscal_inutilizacao
                         WHERE id_tenant = plataforma.tenant_atual() AND id_inutilizacao = ?
                           AND autorizado = true
                        """)
                .param(idInutilizacao)
                .query((rs, n) -> new InutilizacaoParaArquivar(
                        rs.getInt("modelo"), rs.getInt("serie"), rs.getInt("numero_inicial"),
                        rs.getInt("numero_final"), rs.getString("xml_inutilizacao"),
                        rs.getObject("criado_em", OffsetDateTime.class), rs.getString("xml_objeto_bucket"),
                        rs.getString("uf")))
                .optional();
    }

    public record InutilizacaoParaArquivar(int modelo, int serie, int numeroInicial, int numeroFinal,
                                           String xmlInutilizacao, OffsetDateTime criadoEm,
                                           String xmlObjetoBucketAtual, String uf) {
    }

    /** {@code fiscal_inutilizacao} não tem {@code xml_hash} (V035) — só o ponteiro. */
    @Transactional
    public void marcarArquivado(long idInutilizacao, String chave) {
        jdbc.sql("""
                        UPDATE fiscal_inutilizacao SET xml_objeto_bucket = ?
                         WHERE id_tenant = plataforma.tenant_atual() AND id_inutilizacao = ?
                        """)
                .params(chave, idInutilizacao).update();
    }

    @Transactional(readOnly = true)
    public List<Long> pendentesDeArquivamento(int limite) {
        return jdbc.sql("""
                        SELECT id_inutilizacao FROM fiscal_inutilizacao
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND autorizado = true AND xml_objeto_bucket IS NULL
                         ORDER BY criado_em
                         LIMIT ?
                        """)
                .param(limite).query(Long.class).list();
    }

    /** Histórico de inutilizações da empresa, mais recente primeiro — a tela mostra tentativas. */
    @Transactional(readOnly = true)
    public List<InutilizacaoItem> listar(long idEmpresa) {
        return jdbc.sql("""
                        SELECT modelo, serie, ano, numero_inicial, numero_final, justificativa,
                               autorizado, protocolo, motivo_sefaz, criado_em
                          FROM fiscal_inutilizacao
                         WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                         ORDER BY criado_em DESC
                        """)
                .param(idEmpresa)
                .query((rs, n) -> new InutilizacaoItem(
                        rs.getInt("modelo"), rs.getInt("serie"), rs.getInt("ano"),
                        rs.getInt("numero_inicial"), rs.getInt("numero_final"),
                        rs.getString("justificativa"), rs.getBoolean("autorizado"),
                        rs.getString("protocolo"), rs.getString("motivo_sefaz"),
                        rs.getObject("criado_em", OffsetDateTime.class)))
                .list();
    }

    public record ContextoEmpresa(String cnpj, String uf, AmbienteSefaz ambiente) {
    }

    public record InutilizacaoItem(int modelo, int serie, int ano, int numeroInicial, int numeroFinal,
                                   String justificativa, boolean autorizado, String protocolo,
                                   String motivoSefaz, OffsetDateTime criadoEm) {
    }
}
