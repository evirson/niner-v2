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
                        SELECT DISTINCT c.id_tenant, c.id_empresa, e.uf,
                               CASE c.ambiente WHEN 'PRODUCAO' THEN 1 ELSE 2 END AS ambiente_codigo,
                               u.codigo_uf_ibge
                          FROM documento_fiscal d
                          JOIN fiscal_config_empresa c
                            ON c.id_tenant = d.id_tenant AND c.id_empresa = d.id_empresa
                          JOIN empresa e
                            ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                          JOIN cfg_uf_autorizador u
                            ON u.uf = e.uf AND u.modelo = 65 AND u.ambiente = c.ambiente
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
    public void registrarTentativaCancelamento(long idDocumentoFiscal, String justificativa,
                                               boolean autorizado, String protocoloEvento,
                                               String statusSefaz, String motivoSefaz, String xmlEvento,
                                               Integer idUsuario) {
        jdbc.sql("""
                        INSERT INTO documento_fiscal_evento
                            (id_tenant, id_documento_fiscal, tipo_evento, justificativa, autorizado,
                             protocolo, status_sefaz, motivo_sefaz, xml_evento, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, '110111', ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(idDocumentoFiscal, justificativa, autorizado, protocoloEvento, statusSefaz,
                        motivoSefaz, xmlEvento, idUsuario)
                .update();
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
