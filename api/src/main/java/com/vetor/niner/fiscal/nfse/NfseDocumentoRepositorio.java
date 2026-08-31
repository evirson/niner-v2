package com.vetor.niner.fiscal.nfse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Leitura e gravação de {@code nfse_documento} e das suas filhas.
 *
 * <h2>⚠️ Duas armadilhas deste repositório, e as duas já morderam aqui</h2>
 *
 * <ol>
 *   <li><b>Todo método é {@code @Transactional}.</b> Método de repositório que roda FORA de
 *       transação vê {@code tenant_atual()} como NULL — e falha de dois jeitos, um deles em
 *       silêncio: num {@code INSERT} estoura o {@code NOT NULL} e o handler global traduz para
 *       "registro em uso por outro cadastro"; num {@code SELECT} devolve zero linhas e o
 *       {@code Optional.empty()} costuma significar "não existe, pode seguir". Foi assim que o
 *       cancelamento da devolução ao fornecedor devolveu estoque sem avisar a SEFAZ.</li>
 *   <li><b>Filtro de {@code id_tenant} escrito no SQL</b>, não só pela política de RLS (P8). Um
 *       teste reproduzível provou que confiar apenas na política pode vazar ou alterar linha de
 *       outro tenant — inclusive em código antigo e nunca tocado.</li>
 * </ol>
 */
@Repository
public class NfseDocumentoRepositorio {

    private final JdbcClient jdbc;

    public NfseDocumentoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Cria a linha em {@code RASCUNHO} ou devolve a que já existe para o par
     * (venda, código de serviço).
     *
     * <p>⭐ Reusar a linha é o que torna o reenvio depois de rejeição correto: a DPS recusada
     * <b>não queima número</b> no SEFIN (medido — a 2001000 tomou {@code E0712} e a mesma 2001000
     * foi aceita depois), então a segunda tentativa vai com o mesmo {@code nDPS} e o mesmo
     * {@code Id}. Alocar um número novo a cada tentativa abriria buracos sem necessidade.
     *
     * <p>⚠️ O {@code ON CONFLICT DO NOTHING} + {@code SELECT} é deliberado em vez de
     * {@code DO UPDATE … RETURNING}: aqui não há delta a aplicar, e a V102 registra o caso em que
     * {@code DO UPDATE} avalia os CHECK da tupla proposta antes de resolver o conflito.
     */
    @Transactional
    public long criarOuRecuperarRascunho(NovoDocumento novo) {
        jdbc.sql("""
                        INSERT INTO nfse_documento (
                            id_tenant, id_empresa, id_venda, serie, numero_dps, id_dps,
                            situacao, ambiente, codigo_municipio_ibge, competencia, data_emissao,
                            codigo_tributacao_nacional, codigo_tributacao_municipal,
                            descricao_servico, valor_servicos, valor_desconto, base_calculo,
                            aliquota_iss, iss_retido, opta_simples, aliquota_simples_efetiva,
                            id_certificado)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?,
                                'RASCUNHO', ?::ambiente_fiscal, ?, ?, ?,
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id_tenant, id_venda, codigo_tributacao_nacional) DO NOTHING
                        """)
                .params(novo.idEmpresa(), novo.idVenda(), novo.serie(), novo.numeroDps(),
                        novo.idDps(), novo.ambienteProducao() ? "PRODUCAO" : "HOMOLOGACAO",
                        novo.codigoMunicipioIbge(), novo.competencia(), novo.dataEmissao(),
                        novo.codigoTributacaoNacional(), novo.codigoTributacaoMunicipal(),
                        novo.descricaoServico(), novo.valorServicos(), novo.valorDesconto(),
                        novo.baseCalculo(), novo.aliquotaIss(), novo.issRetido(),
                        novo.optaSimples(), novo.aliquotaSimplesEfetiva(), novo.idCertificado())
                .update();

        return jdbc.sql("""
                        SELECT id_nfse FROM nfse_documento
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_venda = ?
                           AND codigo_tributacao_nacional = ?
                        """)
                .params(novo.idVenda(), novo.codigoTributacaoNacional())
                .query(Long.class)
                .single();
    }

    @Transactional
    public void gravarItens(long idNfse, List<ItemGravavel> itens) {
        // Rascunho é remontado antes de existir para a prefeitura — por isso a V102 dá DELETE nos
        // itens (e só neles). Apaga e regrava é o padrão do projeto para coleção filha.
        jdbc.sql("""
                        DELETE FROM nfse_documento_item
                         WHERE id_tenant = plataforma.tenant_atual() AND id_nfse = ?
                        """)
                .param(idNfse)
                .update();

        int numero = 1;
        for (ItemGravavel item : itens) {
            jdbc.sql("""
                            INSERT INTO nfse_documento_item (
                                id_tenant, id_nfse, numero_item, id_variacao, descricao,
                                quantidade, valor_unitario, valor_desconto, valor_total)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(idNfse, numero++, item.idVariacao(), item.descricao(),
                            item.quantidade(), item.valorUnitario(), item.valorDesconto(),
                            item.valorTotal())
                    .update();
        }
    }

    @Transactional
    public void marcarAssinada(long idNfse) {
        atualizarSituacao(idNfse, "ASSINADA", null, null);
    }

    @Transactional
    public void marcarTransmitindo(long idNfse) {
        jdbc.sql("""
                        UPDATE nfse_documento
                           SET situacao = 'TRANSMITINDO',
                               tentativas = tentativas + 1,
                               ultima_tentativa_em = now(),
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_nfse = ?
                        """)
                .param(idNfse)
                .update();
    }

    @Transactional
    public void marcarAutorizada(long idNfse, String chaveAcesso, Long numeroNfse,
                                 String xmlChave, String codigoStatus) {
        jdbc.sql("""
                        UPDATE nfse_documento
                           SET situacao = 'AUTORIZADA',
                               chave_acesso = ?,
                               numero_nfse = ?,
                               xml_chave = ?,
                               codigo_status = ?,
                               motivo_status = NULL,
                               data_autorizacao = now(),
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_nfse = ?
                        """)
                .params(chaveAcesso, numeroNfse, xmlChave, codigoStatus, idNfse)
                .update();
    }

    /**
     * Recusa de negócio: a nota fica {@code REJEITADA}, com o código fiscal no início do motivo.
     *
     * <p>⚠️ Não confundir com indisponibilidade — ver {@link #marcarPendente}. A diferença decide
     * se o operador corrige um dado ou só espera, e errá-la nos dois sentidos custa caro.
     */
    @Transactional
    public void marcarRejeitada(long idNfse, String codigoStatus, String motivo) {
        atualizarSituacao(idNfse, "REJEITADA", codigoStatus, motivo);
    }

    /**
     * O serviço não respondeu, ou respondeu sem avaliar: a nota <b>volta para a fila</b>, não é
     * rejeitada. O número continua válido e o reenvio usa o mesmo.
     */
    @Transactional
    public void marcarPendente(long idNfse, String motivo) {
        atualizarSituacao(idNfse, "ASSINADA", null, motivo);
    }

    @Transactional
    public void marcarCancelada(long idNfse, String xmlChave) {
        jdbc.sql("""
                        UPDATE nfse_documento
                           SET situacao = 'CANCELADA',
                               xml_cancelamento_chave = ?,
                               data_cancelamento = now(),
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_nfse = ?
                        """)
                .params(xmlChave, idNfse)
                .update();
    }

    private void atualizarSituacao(long idNfse, String situacao, String codigo, String motivo) {
        jdbc.sql("""
                        UPDATE nfse_documento
                           SET situacao = ?::situacao_nfse,
                               codigo_status = ?,
                               motivo_status = ?,
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_nfse = ?
                        """)
                .params(situacao, codigo, motivo, idNfse)
                .update();
    }

    @Transactional(readOnly = true)
    public Optional<Documento> buscar(long idNfse) {
        return jdbc.sql("""
                        SELECT id_nfse, id_empresa, id_venda, serie, numero_dps, id_dps,
                               chave_acesso, numero_nfse, situacao::text AS situacao,
                               ambiente::text AS ambiente, codigo_municipio_ibge, competencia,
                               codigo_tributacao_nacional, descricao_servico, valor_servicos,
                               xml_chave, xml_cancelamento_chave, codigo_status, motivo_status,
                               tentativas, data_autorizacao, data_cancelamento
                          FROM nfse_documento
                         WHERE id_tenant = plataforma.tenant_atual() AND id_nfse = ?
                        """)
                .param(idNfse)
                .query(Documento.class)
                .optional();
    }

    /** As notas de uma venda — a tela do PDV mostra N, uma por código de serviço. */
    @Transactional(readOnly = true)
    public List<Documento> daVenda(long idVenda) {
        return jdbc.sql("""
                        SELECT id_nfse, id_empresa, id_venda, serie, numero_dps, id_dps,
                               chave_acesso, numero_nfse, situacao::text AS situacao,
                               ambiente::text AS ambiente, codigo_municipio_ibge, competencia,
                               codigo_tributacao_nacional, descricao_servico, valor_servicos,
                               xml_chave, xml_cancelamento_chave, codigo_status, motivo_status,
                               tentativas, data_autorizacao, data_cancelamento
                          FROM nfse_documento
                         WHERE id_tenant = plataforma.tenant_atual() AND id_venda = ?
                         ORDER BY id_nfse
                        """)
                .param(idVenda)
                .query(Documento.class)
                .list();
    }

    @Transactional
    public void registrarEvento(long idNfse, String tipoEvento, String idPedido, int motivoCodigo,
                                String motivoTexto, String situacao, String codigoStatus,
                                String motivoStatus, String xmlChave, Long idUsuario) {
        jdbc.sql("""
                        INSERT INTO nfse_documento_evento (
                            id_tenant, id_nfse, tipo_evento, sequencia, id_pedido, data_evento,
                            motivo_codigo, motivo_texto, situacao, codigo_status, motivo_status,
                            xml_chave, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, ?, 1, ?, now(),
                                ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id_tenant, id_nfse, tipo_evento, sequencia) DO UPDATE
                            SET situacao = EXCLUDED.situacao,
                                codigo_status = EXCLUDED.codigo_status,
                                motivo_status = EXCLUDED.motivo_status,
                                xml_chave = COALESCE(EXCLUDED.xml_chave,
                                                     nfse_documento_evento.xml_chave)
                        """)
                .params(idNfse, tipoEvento, idPedido, motivoCodigo, motivoTexto, situacao,
                        codigoStatus, motivoStatus, xmlChave, idUsuario)
                .update();
    }

    /** CNPJ do emitente — vai no {@code CNPJAutor} do evento de cancelamento. */
    @Transactional(readOnly = true)
    public String cnpjDaEmpresa(long idEmpresa) {
        return jdbc.sql("""
                        SELECT cnpj FROM empresa
                         WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                        """)
                .param(idEmpresa)
                .query(String.class)
                .single();
    }

    // ---- registros ---------------------------------------------------------------------------

    public record NovoDocumento(
            long idEmpresa, long idVenda, int serie, long numeroDps, String idDps,
            boolean ambienteProducao, int codigoMunicipioIbge, LocalDate competencia,
            OffsetDateTime dataEmissao, String codigoTributacaoNacional,
            String codigoTributacaoMunicipal, String descricaoServico, BigDecimal valorServicos,
            BigDecimal valorDesconto, BigDecimal baseCalculo, BigDecimal aliquotaIss,
            boolean issRetido, int optaSimples, BigDecimal aliquotaSimplesEfetiva,
            Long idCertificado) {
    }

    public record ItemGravavel(long idVariacao, String descricao, BigDecimal quantidade,
                               BigDecimal valorUnitario, BigDecimal valorDesconto,
                               BigDecimal valorTotal) {
    }

    public record Documento(
            long idNfse, long idEmpresa, long idVenda, int serie, long numeroDps, String idDps,
            String chaveAcesso, Long numeroNfse, String situacao, String ambiente,
            int codigoMunicipioIbge, LocalDate competencia, String codigoTributacaoNacional,
            String descricaoServico, BigDecimal valorServicos, String xmlChave,
            String xmlCancelamentoChave, String codigoStatus, String motivoStatus, int tentativas,
            OffsetDateTime dataAutorizacao, OffsetDateTime dataCancelamento) {
    }
}
