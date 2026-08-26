package com.vetor.niner.fiscal.exportacao;

import com.vetor.niner.fiscal.exportacao.ExportacaoXmlLoteDtos.DocumentoDoPacote;
import com.vetor.niner.fiscal.exportacao.ExportacaoXmlLoteDtos.EmpresaDoPacote;
import com.vetor.niner.fiscal.exportacao.ExportacaoXmlLoteDtos.EventoDoPacote;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Optional;

/**
 * Leituras da Exportação de XML em Lote.
 *
 * <p><b>Por que é um bean separado do serviço, e não métodos privados dele.</b> Duas razões, as
 * duas já documentadas em {@code CLAUDE.md} como armadilha vivida neste projeto:
 *
 * <ul>
 *   <li><b>{@code @Transactional} não vale em auto-invocação.</b> Método anotado chamado de dentro
 *       do próprio bean não passa pelo proxy do Spring e roda <b>sem transação</b>. Sem transação o
 *       {@code TenantAwareTransactionManager} nunca executa o {@code SET LOCAL app.id_tenant}, o
 *       {@code plataforma.tenant_atual()} vem {@code NULL} e o {@code SELECT} devolve <b>zero linha
 *       em silêncio</b> — o ZIP sairia vazio e a mensagem diria "nenhum XML no período".</li>
 *   <li><b>A leitura do bucket não pode acontecer dentro da transação.</b> O serviço lê dezenas de
 *       objetos no MinIO; segurar uma conexão do pool durante isso é o que
 *       {@code ArquivamentoXmlService} evita desde o começo (F2). Com o repositório separado, as
 *       transações são curtas e terminam antes do I/O de rede.</li>
 * </ul>
 *
 * <p><b>P8:</b> toda query traz {@code id_tenant = plataforma.tenant_atual()} escrito no texto do
 * SQL — inclusive dentro dos {@code JOIN} —, e não apenas confia na política RLS.
 */
@Repository
public class ExportacaoXmlLoteRepositorio {

    /**
     * Filtro comum às três consultas. A comparação de data converte para o fuso da loja <b>dos dois
     * lados</b> (a sessão do Postgres roda em {@code Etc/UTC}, então {@code ::date} cru viraria o
     * dia seguinte às 21h de Brasília) — mesma expressão que a lista de Documentos Fiscais usa,
     * de propósito: duas telas sobre a mesma tabela não podem discordar sobre o que é "agosto".
     */
    private static final String FILTRO_PERIODO =
            " AND (d.data_emissao AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?";

    /**
     * Só evento <b>autorizado</b> e <b>arquivado</b>. O {@code autorizado = false} é a tentativa que
     * a SEFAZ recusou: fica gravada por P3, mas colocá-la no pacote do contador afirmaria um
     * cancelamento que não aconteceu.
     */
    private static final String SQL_EVENTOS_BASE = """
            SELECT e.id_evento, d.chave_acesso, e.tipo_evento, e.sequencia,
                   d.data_emissao, e.xml_objeto_bucket
              FROM documento_fiscal_evento e
              JOIN documento_fiscal d
                    ON d.id_tenant = e.id_tenant AND d.id_tenant = plataforma.tenant_atual()
                   AND d.id_documento_fiscal = e.id_documento_fiscal
             WHERE e.id_tenant = plataforma.tenant_atual() AND d.id_empresa = ?
               AND e.autorizado = true AND e.xml_objeto_bucket IS NOT NULL
            """;

    /**
     * Mesma consulta de {@link #SQL_EVENTOS_BASE}, sem o filtro de empresa: quem restringe é a
     * lista de documentos da parte, que já veio filtrada por empresa e período.
     */
    private static final String SQL_EVENTOS_BASE_SEM_EMPRESA = """
            SELECT e.id_evento, d.chave_acesso, e.tipo_evento, e.sequencia,
                   d.data_emissao, e.xml_objeto_bucket
              FROM documento_fiscal_evento e
              JOIN documento_fiscal d
                    ON d.id_tenant = e.id_tenant AND d.id_tenant = plataforma.tenant_atual()
                   AND d.id_documento_fiscal = e.id_documento_fiscal
             WHERE e.id_tenant = plataforma.tenant_atual()
               AND e.autorizado = true AND e.xml_objeto_bucket IS NOT NULL
            """;

    private final JdbcClient jdbc;

    public ExportacaoXmlLoteRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<EmpresaDoPacote> buscarEmpresa(long idEmpresa) {
        return jdbc.sql("""
                        SELECT razao_social, nome_fantasia, estado
                          FROM empresa
                         WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                        """)
                .param(idEmpresa)
                .query((rs, n) -> new EmpresaDoPacote(
                        rs.getString("razao_social"), rs.getString("nome_fantasia"), rs.getString("estado")))
                .optional();
    }

    /**
     * Contagem da pré-conferência. {@code count(coluna)} ignora nulos, então {@code comXml} sai da
     * mesma varredura que {@code total} — sem uma segunda query e sem risco de as duas discordarem.
     */
    @Transactional(readOnly = true)
    public ContagemDocumentos contar(long idEmpresa, LocalDate dataInicial, LocalDate dataFinal, Integer modelo) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) AS total, count(d.xml_objeto_bucket) AS com_xml
                  FROM documento_fiscal d
                 WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_empresa = ?
                """);
        List<Object> params = new ArrayList<>(List.of(idEmpresa, dataInicial, dataFinal));
        sql.append(FILTRO_PERIODO);
        if (modelo != null) {
            sql.append(" AND d.modelo = ?");
            params.add(modelo);
        }
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new ContagemDocumentos(rs.getLong("total"), rs.getLong("com_xml")))
                .single();
    }

    @Transactional(readOnly = true)
    public long contarEventos(long idEmpresa, LocalDate dataInicial, LocalDate dataFinal, Integer modelo) {
        StringBuilder sql = new StringBuilder(SQL_EVENTOS_BASE);
        List<Object> params = new ArrayList<>(List.of(idEmpresa, dataInicial, dataFinal));
        sql.append(FILTRO_PERIODO);
        if (modelo != null) {
            sql.append(" AND d.modelo = ?");
            params.add(modelo);
        }
        return jdbc.sql("SELECT count(*) FROM (" + sql + ") evt").params(params).query(Long.class).single();
    }

    /**
     * Documentos do período — <b>todos</b>, inclusive os sem XML arquivado: são eles que o
     * {@code relatorio.csv} marca como {@code (nao arquivado)}, e omiti-los aqui repetiria no
     * índice o mesmo silêncio que o índice existe para quebrar.
     */
    @Transactional(readOnly = true)
    public List<DocumentoDoPacote> listarDocumentos(long idEmpresa, LocalDate dataInicial, LocalDate dataFinal,
                                                    Integer modelo) {
        return listarDocumentos(idEmpresa, dataInicial, dataFinal, modelo, null, null, 0);
    }

    /**
     * Uma <b>parte</b> do período, para a exportação particionada.
     *
     * @param ateIdDocumento teto de {@code id_documento_fiscal} congelado na pré-conferência, ou
     *                       {@code null} para não limitar. ⚠️ É ele que torna a partição estável:
     *                       sem teto, uma nota emitida <b>entre</b> a parte 1 e a parte 3 entraria
     *                       no meio da ordenação e o {@code OFFSET} passaria a pular um documento
     *                       que ninguém baixou — silenciosamente, e justamente no caso comum de
     *                       exportar o mês corrente durante o expediente.
     * @param limite         quantos documentos nesta parte, ou {@code null} para todos.
     */
    @Transactional(readOnly = true)
    public List<DocumentoDoPacote> listarDocumentos(long idEmpresa, LocalDate dataInicial, LocalDate dataFinal,
                                                    Integer modelo, Long ateIdDocumento,
                                                    Integer limite, int deslocamento) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.id_documento_fiscal, d.modelo, d.serie, d.numero, d.chave_acesso,
                       d.situacao::text AS situacao, d.data_emissao, d.valor_total, d.xml_objeto_bucket,
                       COALESCE(c.nome, f.razao_social) AS contraparte,
                       COALESCE(c.cpf_cnpj, f.cnpj) AS documento_contraparte
                  FROM documento_fiscal d
                  LEFT JOIN cliente c
                         ON c.id_tenant = d.id_tenant AND c.id_tenant = plataforma.tenant_atual()
                        AND c.id_cliente = d.id_cliente
                  LEFT JOIN fornecedor f
                         ON f.id_tenant = d.id_tenant AND f.id_tenant = plataforma.tenant_atual()
                        AND f.id_fornecedor = d.id_fornecedor
                 WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_empresa = ?
                """);
        List<Object> params = new ArrayList<>(List.of(idEmpresa, dataInicial, dataFinal));
        sql.append(FILTRO_PERIODO);
        if (modelo != null) {
            sql.append(" AND d.modelo = ?");
            params.add(modelo);
        }
        if (ateIdDocumento != null) {
            sql.append(" AND d.id_documento_fiscal <= ?");
            params.add(ateIdDocumento);
        }
        // A ordenação é a chave da partição: precisa ser total e estável, por isso o id entra
        // como desempate da data (duas notas do mesmo instante existem).
        sql.append(" ORDER BY d.data_emissao, d.id_documento_fiscal");
        if (limite != null) {
            sql.append(" LIMIT ? OFFSET ?");
            params.add(limite);
            params.add(deslocamento);
        }

        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new DocumentoDoPacote(
                        rs.getLong("id_documento_fiscal"),
                        rs.getInt("modelo"),
                        inteiroOuNulo(rs, "serie"),
                        longOuNulo(rs, "numero"),
                        rs.getString("chave_acesso"),
                        rs.getString("situacao"),
                        rs.getObject("data_emissao", OffsetDateTime.class),
                        rs.getBigDecimal("valor_total"),
                        rs.getString("xml_objeto_bucket"),
                        rs.getString("contraparte"),
                        rs.getString("documento_contraparte")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<EventoDoPacote> listarEventos(long idEmpresa, LocalDate dataInicial, LocalDate dataFinal,
                                              Integer modelo) {
        StringBuilder sql = new StringBuilder(SQL_EVENTOS_BASE);
        List<Object> params = new ArrayList<>(List.of(idEmpresa, dataInicial, dataFinal));
        sql.append(FILTRO_PERIODO);
        if (modelo != null) {
            sql.append(" AND d.modelo = ?");
            params.add(modelo);
        }
        sql.append(" ORDER BY d.data_emissao, e.id_evento");

        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new EventoDoPacote(
                        rs.getLong("id_evento"),
                        rs.getString("chave_acesso"),
                        rs.getString("tipo_evento"),
                        rs.getInt("sequencia"),
                        rs.getObject("data_emissao", OffsetDateTime.class),
                        rs.getString("xml_objeto_bucket")))
                .list();
    }

    /**
     * Maior {@code id_documento_fiscal} do período <b>agora</b> — o teto que congela a partição.
     * {@code null} quando o período está vazio.
     */
    @Transactional(readOnly = true)
    public Long maiorIdDocumento(long idEmpresa, LocalDate dataInicial, LocalDate dataFinal, Integer modelo) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(max(d.id_documento_fiscal), 0) AS teto
                  FROM documento_fiscal d
                 WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_empresa = ?
                """);
        List<Object> params = new ArrayList<>(List.of(idEmpresa, dataInicial, dataFinal));
        sql.append(FILTRO_PERIODO);
        if (modelo != null) {
            sql.append(" AND d.modelo = ?");
            params.add(modelo);
        }
        // ⚠️ O `COALESCE` não é enfeite: um mapper que devolve `null` quebra o JdbcClient dos dois
        // jeitos — `.single()` recusa com "Result value is null but no null value expected", e
        // `.list().stream().findFirst()` estoura NullPointerException ao ver o null no stream
        // (medido aqui em 2026-08-26, derrubou 3 testes). Como `id_documento_fiscal` começa em 1,
        // **0 significa "período sem nenhuma nota"** sem precisar de null em lugar nenhum.
        long teto = jdbc.sql(sql.toString()).params(params).query(Long.class).single();
        return teto == 0 ? null : teto;
    }

    /**
     * Eventos dos documentos <b>desta parte</b>, e não do período inteiro.
     *
     * ⚠️ Filtrar por período de novo colocaria <b>todos</b> os eventos do mês em <b>todas</b> as
     * partes: o comprovante de cancelamento apareceria repetido em cada ZIP, e o contador veria o
     * mesmo evento N vezes sem a nota correspondente ao lado.
     */
    @Transactional(readOnly = true)
    public List<EventoDoPacote> listarEventosDosDocumentos(List<Long> idsDocumento) {
        if (idsDocumento.isEmpty()) {
            return List.of();
        }
        // Os ids vêm de `long` lido do banco — concatenar é seguro aqui, e um `IN` com milhares de
        // parâmetros vinculados esbarraria no teto do driver.
        String lista = idsDocumento.stream().map(String::valueOf).collect(Collectors.joining(","));
        String sql = SQL_EVENTOS_BASE_SEM_EMPRESA
                + " AND e.id_documento_fiscal IN (" + lista + ")"
                + " ORDER BY d.data_emissao, e.id_evento";
        return jdbc.sql(sql)
                .query((rs, n) -> new EventoDoPacote(
                        rs.getLong("id_evento"),
                        rs.getString("chave_acesso"),
                        rs.getString("tipo_evento"),
                        rs.getInt("sequencia"),
                        rs.getObject("data_emissao", OffsetDateTime.class),
                        rs.getString("xml_objeto_bucket")))
                .list();
    }

    /** Total do período e quantos deles já têm XML no bucket. */
    public record ContagemDocumentos(long total, long comXml) {
    }

    /**
     * ⚠️ {@code rs.getObject(coluna, Integer.class)} numa coluna {@code smallint} recusa com
     * "conversion ... not supported", e o erro chega ao controller como 409 "registro em uso"
     * (2026-08-20). {@code getInt} + {@code wasNull} é o caminho que funciona.
     */
    private static Integer inteiroOuNulo(ResultSet rs, String coluna) throws SQLException {
        int valor = rs.getInt(coluna);
        return rs.wasNull() ? null : valor;
    }

    private static Long longOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }
}
