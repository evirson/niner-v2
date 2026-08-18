package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.EmissaoNfceService.PedidoDeEmissao;
import com.vetor.niner.fiscal.documento.FiscalNumeracaoService.NumeroReservado;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Persistência do documento fiscal ao longo da máquina de estados (§9.1).
 *
 * <p><b>Bean separado do {@link EmissaoNfceService} de propósito, não por gosto de camada.</b>
 * O {@code @Transactional} do Spring funciona por proxy: um método anotado <b>chamado de dentro
 * da própria classe</b> não passa pelo proxy e roda <b>sem transação</b>. Aqui isso não seria um
 * detalhe de performance — o {@code TenantAwareTransactionManager} define
 * {@code app.id_tenant} no {@code doBegin} da transação, e é esse setting que as políticas RLS
 * leem (P8). Sem transação não há tenant, e sem tenant o RLS bloqueia tudo.
 *
 * <p>Toda query filtra {@code id_tenant} explicitamente no texto do SQL além do RLS
 * ([[feedback_isolamento_tenant_explicito]]).
 */
@Repository
public class DocumentoFiscalRepositorio {

    private static final int MODELO_NFCE = 65;

    private final JdbcClient jdbc;

    public DocumentoFiscalRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Estado terminal para a operação que o v1 reconhece mas não sabe emitir (§9.1). <b>Nenhum
     * número é alocado</b> — a venda existe, o motivo fica gravado, e o lojista vê na tela de
     * Documentos Fiscais em vez de descobrir na contabilidade.
     */
    @Transactional
    public long gravarNaoEmitido(PedidoDeEmissao pedido, String motivo) {
        return jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, tipo_operacao, situacao, ambiente,
                            id_venda, id_cliente, valor_total, motivo_nao_emissao, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, ?, 'VENDA_CONSUMIDOR', 'NAO_EMITIDO',
                                ?::ambiente_fiscal, ?, ?, ?, ?, ?)
                        RETURNING id_documento_fiscal
                        """)
                .params(pedido.idEmpresa(), MODELO_NFCE, pedido.ambiente().name(),
                        pedido.idVenda(), pedido.idCliente(), pedido.totais().valorNota(),
                        motivo, pedido.idUsuario())
                .query(Long.class).single();
    }

    @Transactional
    public long gravarAssinado(PedidoDeEmissao pedido, NumeroReservado numero,
                               String chave, String xmlAssinado, int tipoEmissao) {
        var t = pedido.totais();
        return jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, serie, numero, chave_acesso,
                            codigo_numerico, digito_verificador, tipo_operacao, situacao, ambiente,
                            tipo_emissao, id_venda, id_cliente, data_emissao,
                            valor_produtos, valor_desconto, valor_outros, valor_total, valor_troco,
                            valor_icms, valor_fcp, valor_pis, valor_cofins,
                            valor_ibs_uf, valor_ibs_mun, valor_cbs, valor_total_tributos,
                            xml_assinado, xml_hash, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?,
                                'VENDA_CONSUMIDOR', 'ASSINADO', ?::ambiente_fiscal, ?, ?, ?, ?,
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id_documento_fiscal
                        """)
                .params(pedido.idEmpresa(), MODELO_NFCE, numero.serie(), numero.numero(), chave,
                        "%08d".formatted(numero.codigoNumerico()),
                        Integer.parseInt(chave.substring(43)),
                        pedido.ambiente().name(), tipoEmissao,
                        pedido.idVenda(), pedido.idCliente(), pedido.emissao(),
                        t.valorProdutos(), t.valorDesconto(), t.valorAcrescimo(), t.valorNota(),
                        nz(pedido.troco()), t.valorIcms(), t.valorFcp(), t.valorPis(), t.valorCofins(),
                        t.valorIbsUf(), t.valorIbsMun(), t.valorCbs(), t.valorTotalTributos(),
                        xmlAssinado, sha256(xmlAssinado), pedido.idUsuario())
                .query(Long.class).single();
    }

    /**
     * Nota emitida em contingência, à espera da SEFAZ voltar (§9.7). Estado distinto de
     * {@code TRANSMITINDO}: aqui nada foi enviado ainda, e o cupom <b>já está</b> com o
     * consumidor — é o que o job de drenagem procura, e o que faz o prazo de 24 h correr.
     */
    @Transactional
    public void marcarEmContingencia(long idDocumento) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET situacao = 'CONTINGENCIA', atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .param(idDocumento).update();
    }

    @Transactional
    public void marcarTransmitindo(long idDocumento) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET situacao = 'TRANSMITINDO', tentativas = tentativas + 1,
                               ultima_tentativa_em = now(), atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .param(idDocumento).update();
    }

    @Transactional
    public void marcarAutorizado(long idDocumento, RespostaSefaz resposta) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET situacao = 'AUTORIZADO', protocolo = ?, data_autorizacao = now(),
                               status_sefaz = ?, motivo_sefaz = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .params(resposta.protocolo(), resposta.corpoXml(), resposta.xMotivo(), idDocumento)
                .update();
    }

    /**
     * Rejeição e denegação. O número <b>continua alocado</b> (§9.1): corrigir e retransmitir usa o
     * MESMO número; abandonar exige inutilização formal. Denegado é fim de linha — não se
     * cancela, não se reaproveita.
     */
    @Transactional
    public void marcarRecusado(long idDocumento, RespostaSefaz resposta, boolean denegado) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET situacao = ?::situacao_documento_fiscal, status_sefaz = ?,
                               motivo_sefaz = ?, ultimo_erro = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .params(denegado ? "DENEGADO" : "REJEITADO", resposta.corpoXml(),
                        resposta.xMotivo(), resposta.cStat() + " — " + resposta.xMotivo(), idDocumento)
                .update();
    }

    /** Lote aceito, resultado ainda não disponível: segue {@code TRANSMITINDO}, sem erro. */
    @Transactional
    public void registrarProcessamento(long idDocumento, RespostaSefaz resposta) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET status_sefaz = ?, motivo_sefaz = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .params(resposta.corpoXml(), resposta.xMotivo(), idDocumento).update();
    }

    /**
     * Falha de comunicação — o documento <b>permanece</b> {@code TRANSMITINDO}, nunca vira
     * {@code REJEITADO}. A nota pode ter sido autorizada e só a resposta ter se perdido; quem
     * retransmitir sem consultar a chave antes emite a mesma venda duas vezes (F5).
     */
    @Transactional
    public void registrarFalhaDeComunicacao(long idDocumento, String erro) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET ultimo_erro = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .params(erro, idDocumento).update();
    }

    /** F7: todo uso do certificado deixa rastro — é o que permite investigar um vazamento. */
    @Transactional
    public void registrarUsoDoCertificado(long idCertificado, long idDocumento, String finalidade) {
        jdbc.sql("""
                        INSERT INTO fiscal_certificado_uso
                            (id_tenant, id_certificado, id_documento_fiscal, finalidade)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?)
                        """)
                .params(idCertificado, idDocumento, finalidade).update();
    }

    // ---------------------------------------------------------------- fila de contingência (§9.7)

    /**
     * Empresas com nota emitida em contingência ainda não autorizada.
     *
     * <p>Varre <b>todos</b> os tenants de propósito: é consulta de infraestrutura para o job, que
     * não tem JWT. Devolve só o necessário para o chamador entrar no {@code TenantContext} certo
     * antes de tocar em dado de domínio — e por isso não seleciona nada da nota em si.
     *
     * <p>O {@code JOIN} com {@code fiscal_certificado} é filtro de existência, não fonte da
     * impressão digital — essa vem de {@link com.vetor.niner.fiscal.certificado.FiscalCertificadoService
     * #carregarAtivoParaAssinatura}, a única fonte confiável para a chave do cache mTLS (o cache
     * é por certificado; um valor de outra origem poderia divergir do certificado que de fato
     * assina, e é exatamente essa divergência que o cache existe para impedir).
     */
    @Transactional(readOnly = true)
    public List<EmpresaEmContingencia> empresasComFilaPendente() {
        return jdbc.sql("""
                        SELECT DISTINCT c.id_tenant, c.id_empresa, e.estado AS uf,
                               CASE c.ambiente WHEN 'PRODUCAO' THEN 1 ELSE 2 END AS ambiente_codigo,
                               u.codigo_uf_ibge
                          FROM documento_fiscal d
                          JOIN fiscal_config_empresa c
                            ON c.id_tenant = d.id_tenant AND c.id_empresa = d.id_empresa
                          JOIN empresa e
                            ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                          JOIN cfg_uf_autorizador u
                            ON u.uf = e.estado AND u.modelo = 65 AND u.ambiente = c.ambiente
                          JOIN fiscal_certificado cert
                            ON cert.id_tenant = d.id_tenant AND cert.id_empresa = d.id_empresa
                           AND cert.ativo = true
                         WHERE d.tipo_emissao = 9
                           AND d.situacao IN ('CONTINGENCIA', 'ASSINADO')
                        """)
                .query((rs, n) -> new EmpresaEmContingencia(
                        rs.getLong("id_tenant"), rs.getLong("id_empresa"), rs.getString("uf"),
                        rs.getInt("ambiente_codigo"), rs.getInt("codigo_uf_ibge")))
                .list();
    }

    /**
     * Fila de uma empresa, <b>em ordem de emissão</b>. A ordem não é estética: transmitir a nota
     * 12 antes da 10 cria buraco aparente na numeração e complica a conferência.
     *
     * <p>Dentro do {@code TenantContext}, o filtro explícito de {@code id_tenant} volta a ser
     * obrigatório (P8) — o job não é exceção.
     */
    @Transactional(readOnly = true)
    public List<NotaPendente> pendentesDeContingencia(long idEmpresa, int limite) {
        return jdbc.sql("""
                        SELECT id_documento_fiscal, chave_acesso, xml_assinado
                          FROM documento_fiscal
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_empresa = ?
                           AND tipo_emissao = 9
                           AND situacao IN ('CONTINGENCIA', 'ASSINADO')
                           AND xml_assinado IS NOT NULL
                         ORDER BY data_emissao, numero
                         LIMIT ?
                        """)
                .params(idEmpresa, limite)
                .query((rs, n) -> new NotaPendente(
                        rs.getLong("id_documento_fiscal"), rs.getString("chave_acesso"),
                        rs.getString("xml_assinado")))
                .list();
    }

    public record EmpresaEmContingencia(long idTenant, long idEmpresa, String uf, int ambienteCodigo,
                                        int codigoUfIbge) {
    }

    public record NotaPendente(long id, String chaveAcesso, String xmlAssinado) {
    }

    // ---------------------------------------------------------------- cancelamento (§10.1, B8)

    /**
     * A nota AUTORIZADA mais recente de uma venda, ou vazia quando não há nenhuma — o chamador
     * (F12/DF13) trata "vazio" como "sem nota fiscal, cancelamento não passa pela SEFAZ". Pega a
     * mais recente por {@code criado_em}: uma venda com nota rejeitada e reemitida com sucesso
     * tem duas linhas em {@code documento_fiscal}, e é a AUTORIZADA que importa.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<DocumentoParaCancelar> buscarAutorizadoParaCancelamento(long idVenda) {
        return jdbc.sql("""
                        SELECT d.id_documento_fiscal, d.chave_acesso, d.protocolo, d.data_autorizacao,
                               d.ambiente::text AS ambiente, e.estado AS uf, e.cnpj
                          FROM documento_fiscal d
                          JOIN empresa e ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                         WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_venda = ?
                           AND d.situacao = 'AUTORIZADO'
                         ORDER BY d.criado_em DESC
                         LIMIT 1
                        """)
                .param(idVenda)
                .query((rs, n) -> new DocumentoParaCancelar(
                        rs.getLong("id_documento_fiscal"), rs.getString("chave_acesso"),
                        rs.getString("protocolo"),
                        rs.getObject("data_autorizacao", java.time.OffsetDateTime.class),
                        MontagemNfceDtos.AmbienteSefaz.valueOf(rs.getString("ambiente")),
                        rs.getString("uf"), rs.getString("cnpj")))
                .optional();
    }

    /**
     * Grava a <b>tentativa</b> de cancelamento — sempre, autorizada ou recusada (P3, F11: nunca
     * esconder uma recusa). Só isso; quem marca {@code documento_fiscal.situacao = CANCELADO} é
     * {@link #marcarCancelado}, chamado à parte.
     *
     * <p>⚠️ {@code REQUIRES_NEW}, não o padrão — achado testando o caminho de recusa (B8):
     * {@code CancelamentoVendaService.cancelar()} chama {@link CancelamentoNfceService
     * #cancelarSeAplicavel} de <b>dentro</b> da própria transação (desvio deliberado do F2,
     * documentado lá). Com propagação padrão, este {@code INSERT} entraria nessa MESMA
     * transação — e como a recusa termina lançando {@code ResponseStatusException}, o Spring
     * reverte a transação inteira, apagando junto o registro de auditoria que existe
     * <b>exatamente</b> para sobreviver à recusa. {@code REQUIRES_NEW} garante que o evento
     * commita sozinho, aconteça o que acontecer depois no chamador.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public long registrarTentativaCancelamento(long idDocumentoFiscal, String justificativa,
                                               boolean autorizado, String protocoloEvento,
                                               String statusSefaz, String motivoSefaz, String xmlEvento,
                                               Integer idUsuario) {
        return jdbc.sql("""
                        INSERT INTO documento_fiscal_evento
                            (id_tenant, id_documento_fiscal, tipo_evento, justificativa, autorizado,
                             protocolo, status_sefaz, motivo_sefaz, xml_evento, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, '110111', ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id_evento
                        """)
                .params(idDocumentoFiscal, justificativa, autorizado, protocoloEvento, statusSefaz,
                        motivoSefaz, xmlEvento, idUsuario)
                .query(Long.class).single();
    }

    /**
     * Marca o documento como {@code CANCELADO} — chamado só quando a SEFAZ autorizou.
     * <b>Propagação padrão, de propósito</b> (diferente de {@link #registrarTentativaCancelamento}):
     * este {@code UPDATE} precisa entrar na <b>mesma</b> transação que reverte a venda em
     * {@code CancelamentoVendaService.cancelar()} — se a reversão falhar por qualquer motivo
     * depois deste ponto, o rollback tem que desfazer o {@code CANCELADO} junto, senão o fiscal
     * fica cancelado com a venda intacta ("estoque/caixa e fiscal nunca divergem", §10.1).
     */
    @Transactional
    public void marcarCancelado(long idDocumentoFiscal) {
        jdbc.sql("""
                        UPDATE documento_fiscal SET situacao = 'CANCELADO', atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .param(idDocumentoFiscal).update();
    }

    public record DocumentoParaCancelar(long idDocumentoFiscal, String chaveAcesso, String protocolo,
                                        java.time.OffsetDateTime dataAutorizacao,
                                        MontagemNfceDtos.AmbienteSefaz ambiente, String uf, String cnpjEmitente) {
    }

    // ---------------------------------------------------------------- consulta pontual (§12, B8)

    /**
     * Contexto mínimo pra montar/enviar uma consulta {@code NFeConsultaProtocolo4} a partir de um
     * {@code id_documento_fiscal} — usado por {@link DocumentoFiscalConsultaService#consultarNaSefaz}.
     *
     * <p>⚠️ Vive aqui, não como método privado do serviço, pela mesma razão de sempre
     * ([[feedback_transactional_chamada_interna_rls]]): {@code consultarNaSefaz} não pode ser
     * {@code @Transactional} (faria a chamada de rede acontecer dentro de uma transação de banco,
     * F2) — e sem transação ativa, uma leitura chamada por {@code this.} nunca teria
     * {@code app.id_tenant} definido. Achado testando ao vivo: a consulta devolvia 404 "documento
     * não encontrado" para um documento que existia, porque o RLS via tenant nenhum.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ContextoConsulta> buscarContextoParaConsulta(long idDocumentoFiscal) {
        return jdbc.sql("""
                        SELECT d.id_empresa, d.modelo, d.chave_acesso, d.ambiente::text AS ambiente, e.estado AS uf
                          FROM documento_fiscal d
                          JOIN empresa e ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                         WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_documento_fiscal = ?
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new ContextoConsulta(
                        rs.getLong("id_empresa"), rs.getInt("modelo"), rs.getString("chave_acesso"),
                        MontagemNfceDtos.AmbienteSefaz.valueOf(rs.getString("ambiente")), rs.getString("uf")))
                .optional();
    }

    public record ContextoConsulta(long idEmpresa, int modelo, String chaveAcesso,
                                   MontagemNfceDtos.AmbienteSefaz ambiente, String uf) {
    }

    // ---------------------------------------------------------------- reprocessamento (§12, B8)

    /**
     * Contexto completo pra reprocessar um documento preso (F5): igual a
     * {@link #buscarContextoParaConsulta}, mais {@code situacao} (precondição — só faz sentido
     * reprocessar {@code TRANSMITINDO}/{@code ASSINADO}) e {@code xmlAssinado} (pra retransmitir
     * quando a SEFAZ disser que a nota não consta).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ContextoReprocessamento> buscarParaReprocessar(long idDocumentoFiscal) {
        return jdbc.sql("""
                        SELECT d.id_empresa, d.modelo, d.situacao::text AS situacao, d.chave_acesso,
                               d.xml_assinado, d.ambiente::text AS ambiente, e.estado AS uf
                          FROM documento_fiscal d
                          JOIN empresa e ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                         WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_documento_fiscal = ?
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new ContextoReprocessamento(
                        rs.getLong("id_empresa"), rs.getInt("modelo"), rs.getString("situacao"),
                        rs.getString("chave_acesso"), rs.getString("xml_assinado"),
                        MontagemNfceDtos.AmbienteSefaz.valueOf(rs.getString("ambiente")), rs.getString("uf")))
                .optional();
    }

    public record ContextoReprocessamento(long idEmpresa, int modelo, String situacao, String chaveAcesso,
                                          String xmlAssinado, MontagemNfceDtos.AmbienteSefaz ambiente,
                                          String uf) {
    }

    // ---------------------------------------------------------------- arquivamento (docs/HANDOFF-ARQUIVAMENTO-XML.md)

    /**
     * Contexto pra montar o {@code nfeProc} de um documento AUTORIZADO. {@code xmlObjetoBucketAtual}
     * vem junto pra o chamador decidir "já arquivado" sem uma segunda query (P2 — idempotência).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<DocumentoParaArquivar> buscarParaArquivar(long idDocumentoFiscal) {
        return jdbc.sql("""
                        SELECT modelo, chave_acesso, data_emissao, xml_assinado, status_sefaz, xml_objeto_bucket
                          FROM documento_fiscal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                           AND situacao = 'AUTORIZADO'
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new DocumentoParaArquivar(
                        rs.getInt("modelo"), rs.getString("chave_acesso"),
                        rs.getObject("data_emissao", java.time.OffsetDateTime.class),
                        rs.getString("xml_assinado"), rs.getString("status_sefaz"),
                        rs.getString("xml_objeto_bucket")))
                .optional();
    }

    public record DocumentoParaArquivar(int modelo, String chaveAcesso, java.time.OffsetDateTime dataEmissao,
                                        String xmlAssinado, String statusSefaz, String xmlObjetoBucketAtual) {
    }

    /** F6: {@code xml_objeto_bucket}/{@code xml_hash} só são gravados UMA vez — o trigger de
     *  imutabilidade (V035) recusa um segundo UPDATE depois que o bucket deixou de ser NULL. */
    @Transactional
    public void marcarXmlArquivado(long idDocumentoFiscal, String chave, String hash) {
        jdbc.sql("""
                        UPDATE documento_fiscal SET xml_objeto_bucket = ?, xml_hash = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .params(chave, hash, idDocumentoFiscal)
                .update();
    }

    /** Tenants com pelo menos um documento OU evento (cancelamento) autorizado ainda não
     *  arquivado — consulta GLOBAL, sem JWT (job), mesmo padrão de {@link #empresasComFilaPendente}. */
    @Transactional(readOnly = true)
    public List<Long> tenantsComPendenciaDeArquivamento() {
        return jdbc.sql("""
                        SELECT id_tenant FROM documento_fiscal
                         WHERE situacao = 'AUTORIZADO' AND xml_objeto_bucket IS NULL
                        UNION
                        SELECT id_tenant FROM documento_fiscal_evento
                         WHERE autorizado = true AND xml_objeto_bucket IS NULL
                        """)
                .query(Long.class).list();
    }

    /** Dentro do {@code TenantContext} (P8) — ids pendentes de arquivamento desse tenant, mais
     *  antigo primeiro. */
    @Transactional(readOnly = true)
    public List<Long> pendentesDeArquivamento(int limite) {
        return jdbc.sql("""
                        SELECT id_documento_fiscal FROM documento_fiscal
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND situacao = 'AUTORIZADO' AND xml_objeto_bucket IS NULL
                         ORDER BY data_autorizacao
                         LIMIT ?
                        """)
                .param(limite).query(Long.class).list();
    }

    /** Contexto pra arquivar o XML de um EVENTO autorizado (cancelamento 110111). */
    @Transactional(readOnly = true)
    public java.util.Optional<EventoParaArquivar> buscarEventoParaArquivar(long idEvento) {
        return jdbc.sql("""
                        SELECT e.tipo_evento, e.sequencia, e.xml_evento, e.criado_em, e.xml_objeto_bucket,
                               d.chave_acesso, d.modelo
                          FROM documento_fiscal_evento e
                          JOIN documento_fiscal d
                            ON d.id_tenant = e.id_tenant AND d.id_documento_fiscal = e.id_documento_fiscal
                         WHERE e.id_tenant = plataforma.tenant_atual() AND e.id_evento = ? AND e.autorizado = true
                        """)
                .param(idEvento)
                .query((rs, n) -> new EventoParaArquivar(
                        rs.getString("tipo_evento"), rs.getInt("sequencia"), rs.getString("xml_evento"),
                        rs.getObject("criado_em", java.time.OffsetDateTime.class),
                        rs.getString("xml_objeto_bucket"), rs.getString("chave_acesso"), rs.getInt("modelo")))
                .optional();
    }

    public record EventoParaArquivar(String tipoEvento, int sequencia, String xmlEvento,
                                     java.time.OffsetDateTime criadoEm, String xmlObjetoBucketAtual,
                                     String chaveAcesso, int modelo) {
    }

    /** {@code documento_fiscal_evento} não tem {@code xml_hash} (V035) — só o ponteiro. */
    @Transactional
    public void marcarEventoArquivado(long idEvento, String chave) {
        jdbc.sql("""
                        UPDATE documento_fiscal_evento SET xml_objeto_bucket = ?
                         WHERE id_tenant = plataforma.tenant_atual() AND id_evento = ?
                        """)
                .params(chave, idEvento).update();
    }

    @Transactional(readOnly = true)
    public List<Long> eventosPendentesDeArquivamento(int limite) {
        return jdbc.sql("""
                        SELECT id_evento FROM documento_fiscal_evento
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND autorizado = true AND xml_objeto_bucket IS NULL
                         ORDER BY criado_em
                         LIMIT ?
                        """)
                .param(limite).query(Long.class).list();
    }

    /** {@code valor_troco} é {@code NOT NULL} — troco não informado é zero, nunca ausência. */
    private static java.math.BigDecimal nz(java.math.BigDecimal valor) {
        return valor == null ? java.math.BigDecimal.ZERO : valor;
    }

    private static String sha256(String xml) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao calcular o hash do XML.", e);
        }
    }
}
