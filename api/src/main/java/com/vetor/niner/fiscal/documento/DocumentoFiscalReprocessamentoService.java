package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService.CertificadoParaAssinatura;
import com.vetor.niner.fiscal.documento.DocumentoFiscalListaDtos.ReprocessamentoResponse;
import com.vetor.niner.fiscal.documento.DocumentoFiscalRepositorio.ContextoReprocessamento;
import com.vetor.niner.fiscal.sefaz.SefazAutorizadorService;
import com.vetor.niner.fiscal.sefaz.SefazDtos.FalhaDeComunicacaoException;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazDtos.ServicoSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

/**
 * Reprocessamento de documento fiscal preso (§12, {@code POST /documentos/{id}/reprocessar},
 * bloco B8) — destrava um documento em {@code TRANSMITINDO}/{@code ASSINADO} depois de uma falha
 * de comunicação com a SEFAZ.
 *
 * <h2>F5 — nunca reemitir cego</h2>
 *
 * <p>A nota pode ter sido autorizada e só a resposta ter se perdido. Por isso o primeiro passo é
 * <b>sempre</b> consultar a chave ({@code NFeConsultaProtocolo4}) antes de decidir qualquer coisa
 * — funciona tanto para um documento que já foi transmitido (a consulta confirma o que aconteceu)
 * quanto para um que nunca chegou a sair (a consulta responde {@code cStat 217}, "não consta", e
 * só então este serviço retransmite o XML já assinado).
 *
 * <h2>F2 — nenhuma chamada de rede dentro de transação de banco</h2>
 *
 * <p>Roda inteiro fora de transação; leitura e gravação usam suas próprias transações curtas em
 * {@link DocumentoFiscalRepositorio}.
 */
@Service
public class DocumentoFiscalReprocessamentoService {

    /** cStat da consulta que significa "nunca chegou na SEFAZ" — só aí vale retransmitir. */
    private static final String CSTAT_NAO_CONSTA = "217";

    /** Denegação também aparece na consulta com códigos próprios, além dos da autorização. */
    private static final Set<String> CSTAT_DENEGACAO_CONSULTA =
            concat(EmissaoNfceService.CSTAT_DENEGACAO, Set.of("110", "205"));

    private final DocumentoFiscalRepositorio repositorio;
    private final SefazTransporte transporte;
    private final SefazAutorizadorService autorizadores;
    private final FiscalCertificadoService certificados;
    private final ArquivamentoXmlService arquivamento;

    public DocumentoFiscalReprocessamentoService(DocumentoFiscalRepositorio repositorio, SefazTransporte transporte,
                                                  SefazAutorizadorService autorizadores,
                                                  FiscalCertificadoService certificados,
                                                  ArquivamentoXmlService arquivamento) {
        this.repositorio = repositorio;
        this.transporte = transporte;
        this.autorizadores = autorizadores;
        this.certificados = certificados;
        this.arquivamento = arquivamento;
    }

    public ReprocessamentoResponse reprocessar(Jwt jwt, long idDocumentoFiscal) {
        exigirAdmin(jwt);
        ContextoReprocessamento ctx = repositorio.buscarParaReprocessar(idDocumentoFiscal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento fiscal não encontrado."));

        if (!"TRANSMITINDO".equals(ctx.situacao()) && !"ASSINADO".equals(ctx.situacao())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ("Só é possível reprocessar documentos pendentes de transmissão — este está "
                            + "%s.").formatted(ctx.situacao()));
        }

        CertificadoParaAssinatura certificado = certificados.carregarAtivoParaAssinatura(ctx.idEmpresa());

        // ---------- 1. consultar antes de qualquer coisa (F5) ----------
        String urlConsulta = autorizadores.urlDe(ctx.uf(), ctx.modelo(), ctx.ambiente().codigo(), ServicoSefaz.CONSULTA_PROTOCOLO);
        String consSitNFe = "<consSitNFe xmlns=\"%s\" versao=\"4.00\"><tpAmb>%d</tpAmb><xServ>CONSULTAR</xServ><chNFe>%s</chNFe></consSitNFe>"
                .formatted(MontadorXmlNfce.NS, ctx.ambiente().codigo(), ctx.chaveAcesso());
        RespostaSefaz consulta = transporte.enviar(urlConsulta, "NFeConsultaProtocolo4", consSitNFe,
                certificado.pkcs12(), certificado.senha(), certificado.impressaoDigital());

        if (consulta.autorizado()) {
            repositorio.marcarAutorizado(idDocumentoFiscal, consulta);
            arquivamento.arquivarDocumentoSeAplicavel(idDocumentoFiscal);
            return new ReprocessamentoResponse("AUTORIZADO", consulta.protocolo(), consulta.cStat(),
                    "A SEFAZ confirmou: esta nota já estava autorizada (%s).".formatted(consulta.cStat()));
        }
        if (CSTAT_DENEGACAO_CONSULTA.contains(consulta.cStat())) {
            repositorio.marcarRecusado(idDocumentoFiscal, consulta, true);
            return new ReprocessamentoResponse("DENEGADO", null, consulta.cStat(),
                    "A SEFAZ denegou esta nota: %s (%s). Este número não pode ser reaproveitado."
                            .formatted(consulta.xMotivo(), consulta.cStat()));
        }
        if (!CSTAT_NAO_CONSTA.equals(consulta.cStat())) {
            // Resposta que não sabemos decidir sozinhos (F11) — grava o que a SEFAZ disse agora
            // (F9) mas não muda o estado; o ADMIN decide com a informação na mão.
            repositorio.registrarProcessamento(idDocumentoFiscal, consulta);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A SEFAZ respondeu algo que este reprocessamento não sabe decidir sozinho: %s (%s). "
                            + "Consulte o suporte antes de tentar de novo.".formatted(consulta.xMotivo(), consulta.cStat()));
        }

        // ---------- 2. "não consta" — nunca chegou lá, retransmite o que já foi assinado ----------
        String urlAutorizacao = autorizadores.urlDe(ctx.uf(), ctx.modelo(), ctx.ambiente().codigo(), ServicoSefaz.AUTORIZACAO);
        String enviNFe = ("<enviNFe versao=\"4.00\" xmlns=\"%s\">"
                + "<idLote>%d</idLote><indSinc>1</indSinc>%s</enviNFe>")
                .formatted(MontadorXmlNfce.NS, idDocumentoFiscal, ctx.xmlAssinado());

        repositorio.marcarTransmitindo(idDocumentoFiscal);
        RespostaSefaz resposta;
        try {
            resposta = transporte.enviar(urlAutorizacao, "NFeAutorizacao4", enviNFe,
                    certificado.pkcs12(), certificado.senha(), certificado.impressaoDigital());
        } catch (FalhaDeComunicacaoException e) {
            repositorio.registrarFalhaDeComunicacao(idDocumentoFiscal, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não foi possível falar com a SEFAZ para retransmitir. Tente novamente em instantes.");
        }

        if (resposta.autorizado()) {
            repositorio.marcarAutorizado(idDocumentoFiscal, resposta);
            arquivamento.arquivarDocumentoSeAplicavel(idDocumentoFiscal);
            return new ReprocessamentoResponse("AUTORIZADO", resposta.protocolo(), resposta.cStat(),
                    "Nota retransmitida e autorizada agora.");
        }
        if (resposta.emProcessamento()) {
            repositorio.registrarProcessamento(idDocumentoFiscal, resposta);
            return new ReprocessamentoResponse("EM_PROCESSAMENTO", null, resposta.cStat(),
                    "A SEFAZ recebeu a retransmissão e ainda está processando (%s). Reprocesse de novo em instantes."
                            .formatted(resposta.xMotivo()));
        }
        boolean denegado = EmissaoNfceService.CSTAT_DENEGACAO.contains(resposta.cStat());
        repositorio.marcarRecusado(idDocumentoFiscal, resposta, denegado);
        return new ReprocessamentoResponse(denegado ? "DENEGADO" : "REJEITADO", null, resposta.cStat(),
                "A SEFAZ recusou a retransmissão: %s (%s).".formatted(resposta.xMotivo(), resposta.cStat()));
    }

    private static Set<String> concat(Set<String> a, Set<String> b) {
        var conjunto = new java.util.HashSet<>(a);
        conjunto.addAll(b);
        return Set.copyOf(conjunto);
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem reprocessar documentos fiscais.");
        }
    }
}
