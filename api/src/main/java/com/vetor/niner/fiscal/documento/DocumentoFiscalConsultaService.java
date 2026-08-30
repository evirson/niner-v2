package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.armazenamento.AreaPrivada;
import com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService.CertificadoParaAssinatura;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.ConsultaSefazResponse;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.DanfeItem;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.DanfeParticipante;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.DanfeResponse;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.DanfeTotais;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.DocumentoFiscalItem;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.PaginaDocumentosFiscais;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.XmlDocumentoFiscalResponse;
import com.vetor.niner.fiscal.sefaz.SefazAutorizadorService;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazDtos.ServicoSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Documentos Fiscais (§12, tela {@code fiscal.documentos}, bloco B8) — lista com filtros, ver
 * XML, consultar na SEFAZ. ADMIN-only, mesmo padrão do resto do módulo fiscal.
 */
@Service
public class DocumentoFiscalConsultaService {

    private static final int TAMANHO_PAGINA_PADRAO = 50;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;
    private static final int PERIODO_MAXIMO_DIAS = 365;

    private final JdbcClient jdbc;
    private final DocumentoFiscalRepositorio repositorio;
    private final SefazTransporte transporte;
    private final SefazAutorizadorService autorizadores;
    private final FiscalCertificadoService certificados;
    private final ArmazenamentoPrivado armazenamento;

    public DocumentoFiscalConsultaService(JdbcClient jdbc, DocumentoFiscalRepositorio repositorio,
                                          SefazTransporte transporte, SefazAutorizadorService autorizadores,
                                          FiscalCertificadoService certificados, ArmazenamentoPrivado armazenamento) {
        this.jdbc = jdbc;
        this.repositorio = repositorio;
        this.transporte = transporte;
        this.autorizadores = autorizadores;
        this.certificados = certificados;
        this.armazenamento = armazenamento;
    }

    @Transactional(readOnly = true)
    public PaginaDocumentosFiscais listar(Jwt jwt, long idEmpresa, LocalDate dataInicial, LocalDate dataFinal,
                                          Integer modelo, String situacao, Integer pagina, Integer limite) {
        if (dataInicial == null || dataFinal == null) {
            throw new IllegalArgumentException("Informe a data inicial e a data final.");
        }
        if (dataInicial.isAfter(dataFinal)) {
            throw new IllegalArgumentException("Data inicial não pode ser maior que a data final.");
        }
        if (dataInicial.plusDays(PERIODO_MAXIMO_DIAS).isBefore(dataFinal)) {
            throw new IllegalArgumentException("Período de consulta não pode exceder " + PERIODO_MAXIMO_DIAS + " dias.");
        }

        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);

        StringBuilder filtro = new StringBuilder("""
                 WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_empresa = ?
                   AND (d.data_emissao AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                """);
        List<Object> params = new ArrayList<>(List.of(idEmpresa, dataInicial, dataFinal));

        if (modelo != null) {
            filtro.append(" AND d.modelo = ?");
            params.add(modelo);
        }
        if (situacao != null && !situacao.isBlank() && !"TODAS".equalsIgnoreCase(situacao)) {
            filtro.append(" AND d.situacao = ?::situacao_documento_fiscal");
            params.add(situacao);
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM documento_fiscal d" + filtro)
                .params(params).query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);

        List<DocumentoFiscalItem> itens = jdbc.sql("""
                        SELECT d.id_documento_fiscal, d.modelo, d.serie, d.numero, d.chave_acesso,
                               d.tipo_operacao::text AS tipo_operacao, d.situacao::text AS situacao,
                               d.tipo_emissao, d.ambiente::text AS ambiente, d.data_emissao,
                               d.data_autorizacao, d.protocolo, d.valor_total, d.id_venda,
                               c.nome AS nome_cliente, d.xml_assinado
                          FROM documento_fiscal d
                          LEFT JOIN cliente c ON c.id_tenant = d.id_tenant AND c.id_cliente = d.id_cliente
                        """ + filtro + " ORDER BY d.data_emissao DESC, d.id_documento_fiscal DESC LIMIT ? OFFSET ?")
                .params(paramsPagina)
                .query((rs, n) -> new DocumentoFiscalItem(
                        rs.getLong("id_documento_fiscal"), rs.getInt("modelo"), getIntOuNulo(rs, "serie"),
                        getLongOuNulo(rs, "numero"), rs.getString("chave_acesso"), rs.getString("tipo_operacao"),
                        rs.getString("situacao"), rs.getInt("tipo_emissao"), rs.getString("ambiente"),
                        rs.getObject("data_emissao", OffsetDateTime.class),
                        rs.getObject("data_autorizacao", OffsetDateTime.class),
                        rs.getString("protocolo"), rs.getBigDecimal("valor_total"),
                        getLongOuNulo(rs, "id_venda"), rs.getString("nome_cliente"),
                        extrairTagCdata(rs.getString("xml_assinado"), "qrCode")))
                .list();

        return new PaginaDocumentosFiscais(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    /**
     * Handoff §6.5: quando o documento já foi arquivado, devolve o {@code nfeProc} de verdade
     * (XML + protocolo, o que o contador precisa) lido do bucket — nunca o {@code xml_assinado}
     * puro, que não é a mesma coisa. Documento ainda não arquivado (job/caminho quente não
     * chegaram lá, ou nunca foi autorizado) cai no fallback de sempre.
     */
    @Transactional(readOnly = true)
    public XmlDocumentoFiscalResponse buscarXml(Jwt jwt, long idDocumentoFiscal) {
        var linha = jdbc.sql("""
                        SELECT chave_acesso, xml_assinado, xml_objeto_bucket FROM documento_fiscal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new LinhaXml(
                        rs.getString("chave_acesso"), rs.getString("xml_assinado"), rs.getString("xml_objeto_bucket")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento fiscal não encontrado."));

        if (linha.xmlObjetoBucket() != null) {
            String nfeProc = new String(armazenamento.ler(AreaPrivada.FISCAL_XML, linha.xmlObjetoBucket()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return new XmlDocumentoFiscalResponse(idDocumentoFiscal, linha.chaveAcesso(), nfeProc);
        }
        return new XmlDocumentoFiscalResponse(idDocumentoFiscal, linha.chaveAcesso(), linha.xmlAssinado());
    }

    private record LinhaXml(String chaveAcesso, String xmlAssinado, String xmlObjetoBucket) {
    }

    /**
     * Dados do <b>DANFE A4</b> (modelo 55) — §10.2/B9. Monta no servidor a partir do que está
     * gravado em {@code documento_fiscal} + {@code _item} + {@code _referencia}, nunca reparseando
     * o XML no navegador: o impresso tem que ser o documento, e reinterpretar XML no front abriria
     * espaço para divergir dele.
     *
     * <p>⚠️ Recusa modelo 65: a NFC-e imprime o <b>DANFCE térmico</b>, que é outro documento com
     * outra calibragem (80mm, `DanfceImprimir.tsx`). Pedir o A4 de uma NFC-e é erro de chamada,
     * não um caso a suportar em silêncio.
     */
    @Transactional(readOnly = true)
    public DanfeResponse buscarDanfe(Jwt jwt, long idDocumentoFiscal) {

        var cab = jdbc.sql("""
                        SELECT d.id_documento_fiscal, d.chave_acesso, d.modelo, d.serie, d.numero,
                               d.tipo_nf, d.situacao::text AS situacao, d.ambiente::text AS ambiente,
                               d.data_emissao, d.data_autorizacao, d.protocolo,
                               d.valor_produtos, d.valor_desconto, d.valor_frete, d.valor_seguro,
                               d.valor_outros, d.valor_icms, d.valor_icms_st, d.valor_pis, d.valor_cofins,
                               d.valor_total, d.valor_total_tributos, d.tipo_operacao::text AS tipo_operacao,
                               e.razao_social AS emit_nome, e.cnpj AS emit_cnpj,
                               e.inscricao_estadual AS emit_ie, e.endereco AS emit_endereco,
                               e.numero AS emit_numero, e.bairro AS emit_bairro, e.cidade AS emit_cidade,
                               e.estado AS emit_uf, e.cep AS emit_cep, e.telefone AS emit_fone,
                               c.nome AS dest_nome, c.cpf_cnpj AS dest_doc, c.rg_ie AS dest_ie,
                               c.endereco AS dest_endereco, c.numero AS dest_numero, c.bairro AS dest_bairro,
                               c.cidade AS dest_cidade, c.estado AS dest_uf, c.cep AS dest_cep,
                               c.telefone AS dest_fone,
                               (SELECT r.chave_referenciada FROM documento_fiscal_referencia r
                                 WHERE r.id_tenant = d.id_tenant AND r.id_documento_fiscal = d.id_documento_fiscal
                                 LIMIT 1) AS chave_referenciada
                          FROM documento_fiscal d
                          JOIN empresa e ON e.id_empresa = d.id_empresa AND e.id_tenant = d.id_tenant
                          LEFT JOIN cliente c ON c.id_cliente = d.id_cliente AND c.id_tenant = d.id_tenant
                         WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_documento_fiscal = ?
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new LinhaDanfe(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento fiscal não encontrado."));

        if (cab.modelo != 55) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O DANFE em A4 é do modelo 55. A NFC-e (modelo 65) imprime o DANFCE em bobina térmica.");
        }

        List<DanfeItem> itens = jdbc.sql("""
                        SELECT numero_item, codigo_produto, descricao, codigo_ncm, cfop, origem_mercadoria,
                               cst_icms, csosn, unidade_comercial, quantidade, valor_unitario, valor_produto,
                               base_calculo_icms, valor_icms, aliquota_icms
                          FROM documento_fiscal_item
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                         ORDER BY numero_item
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new DanfeItem(
                        rs.getInt("numero_item"), rs.getString("codigo_produto"), rs.getString("descricao"),
                        rs.getString("codigo_ncm"), rs.getString("cfop"), rs.getInt("origem_mercadoria"),
                        rs.getString("csosn") != null ? rs.getString("csosn") : rs.getString("cst_icms"),
                        rs.getString("unidade_comercial"), rs.getBigDecimal("quantidade"),
                        rs.getBigDecimal("valor_unitario"), rs.getBigDecimal("valor_produto"),
                        rs.getBigDecimal("base_calculo_icms"), rs.getBigDecimal("valor_icms"),
                        rs.getBigDecimal("aliquota_icms")))
                .list();

        // Destinatário: quando a nota é de devolução com consumidor não identificado na venda
        // original, `id_cliente` é nulo e o destinatário É a própria loja (ver
        // DevolucaoFiscalAssembler) — o DANFE tem que mostrar isso, não um bloco vazio.
        DanfeParticipante emitente = new DanfeParticipante(cab.emitNome, cab.emitCnpj, cab.emitIe,
                linhaEndereco(cab.emitEndereco, cab.emitNumero), cab.emitBairro, cab.emitCidade,
                cab.emitUf, cab.emitCep, cab.emitFone);
        DanfeParticipante destinatario = cab.destNome != null
                ? new DanfeParticipante(cab.destNome, cab.destDoc, cab.destIe,
                        linhaEndereco(cab.destEndereco, cab.destNumero), cab.destBairro, cab.destCidade,
                        cab.destUf, cab.destCep, cab.destFone)
                : emitente;

        return new DanfeResponse(idDocumentoFiscal, cab.chaveAcesso, cab.modelo, cab.serie, cab.numero,
                naturezaDe(cab.tipoOperacao), cab.tipoNf, cab.situacao,
                "HOMOLOGACAO".equals(cab.ambiente), cab.dataEmissao, cab.dataAutorizacao, cab.protocolo,
                emitente, destinatario, itens,
                new DanfeTotais(cab.valorProdutos, cab.valorIcms, BigDecimal.ZERO, cab.valorIcmsSt,
                        cab.valorProdutos, cab.valorFrete, cab.valorSeguro, cab.valorDesconto,
                        cab.valorOutros, cab.valorPis, cab.valorCofins, cab.valorTotalTributos, cab.valorTotal),
                cab.chaveReferenciada != null
                        ? "Devolucao referente a nota fiscal " + cab.chaveReferenciada
                        : null,
                cab.chaveReferenciada);
    }

    private static String naturezaDe(String tipoOperacao) {
        return "DEVOLUCAO_VENDA".equals(tipoOperacao) ? "DEVOLUCAO DE VENDA" : "VENDA";
    }

    private static String linhaEndereco(String logradouro, String numero) {
        if (logradouro == null || logradouro.isBlank()) {
            return "";
        }
        return numero == null || numero.isBlank() ? logradouro : logradouro + ", " + numero;
    }

    /** Leitura crua do cabeçalho do DANFE — campos demais para um record posicional legível. */
    private static final class LinhaDanfe {
        final String chaveAcesso;
        final int modelo;
        final int serie;
        final long numero;
        final int tipoNf;
        final String situacao;
        final String ambiente;
        final String tipoOperacao;
        final OffsetDateTime dataEmissao;
        final OffsetDateTime dataAutorizacao;
        final String protocolo;
        final BigDecimal valorProdutos;
        final BigDecimal valorDesconto;
        final BigDecimal valorFrete;
        final BigDecimal valorSeguro;
        final BigDecimal valorOutros;
        final BigDecimal valorIcms;
        final BigDecimal valorIcmsSt;
        final BigDecimal valorPis;
        final BigDecimal valorCofins;
        final BigDecimal valorTotal;
        final BigDecimal valorTotalTributos;
        final String emitNome, emitCnpj, emitIe, emitEndereco, emitNumero, emitBairro, emitCidade,
                emitUf, emitCep, emitFone;
        final String destNome, destDoc, destIe, destEndereco, destNumero, destBairro, destCidade,
                destUf, destCep, destFone;
        final String chaveReferenciada;

        LinhaDanfe(java.sql.ResultSet rs) throws java.sql.SQLException {
            chaveAcesso = rs.getString("chave_acesso");
            modelo = rs.getInt("modelo");
            serie = rs.getInt("serie");
            numero = rs.getLong("numero");
            tipoNf = rs.getInt("tipo_nf");
            situacao = rs.getString("situacao");
            ambiente = rs.getString("ambiente");
            tipoOperacao = rs.getString("tipo_operacao");
            dataEmissao = rs.getObject("data_emissao", OffsetDateTime.class);
            dataAutorizacao = rs.getObject("data_autorizacao", OffsetDateTime.class);
            protocolo = rs.getString("protocolo");
            valorProdutos = rs.getBigDecimal("valor_produtos");
            valorDesconto = rs.getBigDecimal("valor_desconto");
            valorFrete = rs.getBigDecimal("valor_frete");
            valorSeguro = rs.getBigDecimal("valor_seguro");
            valorOutros = rs.getBigDecimal("valor_outros");
            valorIcms = rs.getBigDecimal("valor_icms");
            valorIcmsSt = rs.getBigDecimal("valor_icms_st");
            valorPis = rs.getBigDecimal("valor_pis");
            valorCofins = rs.getBigDecimal("valor_cofins");
            valorTotal = rs.getBigDecimal("valor_total");
            valorTotalTributos = rs.getBigDecimal("valor_total_tributos");
            emitNome = rs.getString("emit_nome");
            emitCnpj = rs.getString("emit_cnpj");
            emitIe = rs.getString("emit_ie");
            emitEndereco = rs.getString("emit_endereco");
            emitNumero = rs.getString("emit_numero");
            emitBairro = rs.getString("emit_bairro");
            emitCidade = rs.getString("emit_cidade");
            emitUf = rs.getString("emit_uf");
            emitCep = rs.getString("emit_cep");
            emitFone = rs.getString("emit_fone");
            destNome = rs.getString("dest_nome");
            destDoc = rs.getString("dest_doc");
            destIe = rs.getString("dest_ie");
            destEndereco = rs.getString("dest_endereco");
            destNumero = rs.getString("dest_numero");
            destBairro = rs.getString("dest_bairro");
            destCidade = rs.getString("dest_cidade");
            destUf = rs.getString("dest_uf");
            destCep = rs.getString("dest_cep");
            destFone = rs.getString("dest_fone");
            chaveReferenciada = rs.getString("chave_referenciada");
        }
    }

    /**
     * Pergunta à SEFAZ a situação <b>atual</b> da nota (F5, `NFeConsultaProtocolo4`) — útil
     * quando o documento ficou {@code TRANSMITINDO} por falha de comunicação (F5: a nota pode ter
     * sido autorizada sem a resposta ter chegado) ou pra conferir um cancelamento feito por outro
     * canal. Não muda o banco sozinho — só devolve o que a SEFAZ respondeu agora; quem decide
     * gravar é uma rotina separada (fora do escopo desta consulta pontual).
     */
    public ConsultaSefazResponse consultarNaSefaz(Jwt jwt, long idDocumentoFiscal) {
        var ctx = repositorio.buscarContextoParaConsulta(idDocumentoFiscal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento fiscal não encontrado."));

        // ⚠️ Sem chave não há o que consultar: o documento parou no bloqueio preventivo (F11)
        // antes de a numeração ser tirada. Até 2026-08-25 este caminho seguia adiante e mandava
        // a string literal "null" no <chNFe> — chamada real à SEFAZ, que respondia cStat 215
        // ("Falha no schema XML"). Barrar aqui, e não só escondendo o botão, porque o front não é
        // o único cliente da API.
        if (ctx.chaveAcesso() == null || ctx.chaveAcesso().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este documento não chegou a ser transmitido — não tem chave de acesso para consultar na SEFAZ.");
        }

        CertificadoParaAssinatura certificado = certificados.carregarAtivoParaAssinatura(ctx.idEmpresa());
        String url = autorizadores.urlDe(ctx.uf(), ctx.modelo(), ctx.ambiente().codigo(), ServicoSefaz.CONSULTA_PROTOCOLO);
        String consulta = "<consSitNFe xmlns=\"%s\" versao=\"4.00\"><tpAmb>%d</tpAmb><xServ>CONSULTAR</xServ><chNFe>%s</chNFe></consSitNFe>"
                .formatted(MontadorXmlNfce.NS, ctx.ambiente().codigo(), ctx.chaveAcesso());

        RespostaSefaz resposta = transporte.enviar(url, "NFeConsultaProtocolo4", consulta,
                certificado.pkcs12(), certificado.senha(), certificado.impressaoDigital());

        return new ConsultaSefazResponse(resposta.cStat(), resposta.xMotivo(), resposta.protocolo());
    }

    /** {@code rs.getObject(coluna, Long.class)} não funciona pra colunas {@code integer} (o
     *  driver do Postgres só converte pro tipo exato) — {@code getLong}+{@code wasNull} é o
     *  jeito seguro de ler uma coluna {@code integer} nullable como {@code Long}. */
    private static Long getLongOuNulo(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    /** Irmã de {@link #getLongOuNulo} para coluna {@code smallint}/{@code integer} lida como
     *  {@code Integer}. Sem o {@code wasNull}, NULL vira {@code 0} <b>sem erro nenhum</b> — foi
     *  assim que a lista de Documentos Fiscais exibiu série/número "0/0" para os documentos que
     *  pararam no bloqueio preventivo (F11) e nunca receberam numeração. */
    private static Integer getIntOuNulo(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        int valor = rs.getInt(coluna);
        return rs.wasNull() ? null : valor;
    }

    /** Mesma extração de {@code PdvVendaService} (comprovante do PDV) — o {@code qrCode} do XML
     *  já assinado é uma URL v3.00 completa e autossuficiente: abrir no navegador faz exatamente
     *  o que escanear o QR faria (§11.4). {@code null} quando a nota não chegou a ser autorizada
     *  (o XML nem tem o elemento). */
    private static String extrairTagCdata(String xml, String tag) {
        if (xml == null) return null;
        String abre = "<" + tag + ">";
        String fecha = "</" + tag + ">";
        int inicio = xml.indexOf(abre);
        if (inicio < 0) return null;
        inicio += abre.length();
        int fim = xml.indexOf(fecha, inicio);
        if (fim < 0) return null;
        return xml.substring(inicio, fim).replace("<![CDATA[", "").replace("]]>", "");
    }

}
