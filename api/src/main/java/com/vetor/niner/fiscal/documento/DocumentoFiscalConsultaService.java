package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService.CertificadoParaAssinatura;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.ConsultaSefazResponse;
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

    public DocumentoFiscalConsultaService(JdbcClient jdbc, DocumentoFiscalRepositorio repositorio,
                                          SefazTransporte transporte, SefazAutorizadorService autorizadores,
                                          FiscalCertificadoService certificados) {
        this.jdbc = jdbc;
        this.repositorio = repositorio;
        this.transporte = transporte;
        this.autorizadores = autorizadores;
        this.certificados = certificados;
    }

    @Transactional(readOnly = true)
    public PaginaDocumentosFiscais listar(Jwt jwt, long idEmpresa, LocalDate dataInicial, LocalDate dataFinal,
                                          Integer modelo, String situacao, Integer pagina, Integer limite) {
        exigirAdmin(jwt);
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
                   AND d.data_emissao::date BETWEEN ? AND ?
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
                        rs.getLong("id_documento_fiscal"), rs.getInt("modelo"), rs.getInt("serie"),
                        rs.getLong("numero"), rs.getString("chave_acesso"), rs.getString("tipo_operacao"),
                        rs.getString("situacao"), rs.getInt("tipo_emissao"), rs.getString("ambiente"),
                        rs.getObject("data_emissao", OffsetDateTime.class),
                        rs.getObject("data_autorizacao", OffsetDateTime.class),
                        rs.getString("protocolo"), rs.getBigDecimal("valor_total"),
                        getLongOuNulo(rs, "id_venda"), rs.getString("nome_cliente"),
                        extrairTagCdata(rs.getString("xml_assinado"), "qrCode")))
                .list();

        return new PaginaDocumentosFiscais(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public XmlDocumentoFiscalResponse buscarXml(Jwt jwt, long idDocumentoFiscal) {
        exigirAdmin(jwt);
        return jdbc.sql("""
                        SELECT chave_acesso, xml_assinado FROM documento_fiscal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new XmlDocumentoFiscalResponse(
                        idDocumentoFiscal, rs.getString("chave_acesso"), rs.getString("xml_assinado")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento fiscal não encontrado."));
    }

    /**
     * Pergunta à SEFAZ a situação <b>atual</b> da nota (F5, `NFeConsultaProtocolo4`) — útil
     * quando o documento ficou {@code TRANSMITINDO} por falha de comunicação (F5: a nota pode ter
     * sido autorizada sem a resposta ter chegado) ou pra conferir um cancelamento feito por outro
     * canal. Não muda o banco sozinho — só devolve o que a SEFAZ respondeu agora; quem decide
     * gravar é uma rotina separada (fora do escopo desta consulta pontual).
     */
    public ConsultaSefazResponse consultarNaSefaz(Jwt jwt, long idDocumentoFiscal) {
        exigirAdmin(jwt);
        var ctx = repositorio.buscarContextoParaConsulta(idDocumentoFiscal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento fiscal não encontrado."));

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

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem acessar os documentos fiscais.");
        }
    }
}
