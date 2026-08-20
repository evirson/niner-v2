package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.comum.tempo.FusoDaUf;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService.CertificadoParaAssinatura;
import com.vetor.niner.fiscal.documento.DocumentoFiscalRepositorio.DocumentoParaCancelar;
import com.vetor.niner.fiscal.documento.MontagemEventoDtos.EventoCancelamento;
import com.vetor.niner.fiscal.documento.MontagemEventoDtos.XmlEventoMontado;
import com.vetor.niner.fiscal.sefaz.SefazAutorizadorService;
import com.vetor.niner.fiscal.sefaz.SefazDtos.Autorizador;
import com.vetor.niner.fiscal.sefaz.SefazDtos.FalhaDeComunicacaoException;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazDtos.ServicoSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Cancelamento de NFC-e — evento 110111 (§10.1, bloco B8).
 *
 * <p><b>Estoque/caixa e fiscal nunca divergem: ou os dois andam, ou nenhum anda</b> — é essa a
 * garantia que {@code CancelamentoVendaService} pede a este serviço <b>antes</b> de reverter
 * qualquer coisa: {@link #cancelarSeAplicavel} devolve normalmente quando pode prosseguir (sem
 * nota fiscal, ou a SEFAZ autorizou o cancelamento) e lança {@link ResponseStatusException}
 * (409) em qualquer caso que deveria impedir a reversão — SEFAZ recusou, falha de comunicação, ou
 * prazo legal vencido. O chamador não precisa saber qual dos três: só precisa saber que, se este
 * método voltar sem lançar, pode reverter.
 *
 * <p><b>F2 — nenhuma chamada de rede dentro de transação de banco:</b> este método roda inteiro
 * <b>fora</b> de qualquer transação (não é {@code @Transactional}); as leituras e a gravação do
 * evento usam suas próprias transações curtas em {@link DocumentoFiscalRepositorio}. Quem chama
 * precisa fazer o mesmo — chamar isto de dentro da transação que reverte a venda prenderia a
 * conexão e a trava da linha pelos até 10 s do timeout da SEFAZ.
 */
@Service
public class CancelamentoNfceService {

    private static final int MODELO_NFCE = 65;
    private static final int PRAZO_PADRAO_MINUTOS = 30;
    /** ⚠️ Sempre via {@link FusoDaLoja}: o driver devolve `timestamptz` em UTC, e formatar direto
     *  imprimia 3 h a mais (achado em 2026-08-20 — "autorizada às 19:44" para uma nota das 16:44). */
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm", Locale.of("pt", "BR"));

    private final DocumentoFiscalRepositorio repositorio;
    private final MontadorEventoCancelamento montador;
    private final AssinadorXmlNfe assinador;
    private final ValidadorXsd validador;
    private final SefazTransporte transporte;
    private final SefazAutorizadorService autorizadores;
    private final FiscalCertificadoService certificados;
    private final ArquivamentoXmlService arquivamento;

    public CancelamentoNfceService(DocumentoFiscalRepositorio repositorio, MontadorEventoCancelamento montador,
                                   AssinadorXmlNfe assinador, ValidadorXsd validador, SefazTransporte transporte,
                                   SefazAutorizadorService autorizadores, FiscalCertificadoService certificados,
                                   ArquivamentoXmlService arquivamento) {
        this.repositorio = repositorio;
        this.montador = montador;
        this.assinador = assinador;
        this.validador = validador;
        this.transporte = transporte;
        this.autorizadores = autorizadores;
        this.certificados = certificados;
        this.arquivamento = arquivamento;
    }

    /**
     * @return o protocolo do evento quando havia nota e a SEFAZ autorizou o cancelamento;
     *         {@code Optional.empty()} quando a venda não tem NFC-e autorizada (F12/DF13 — nada a
     *         cancelar na SEFAZ, o cancelamento de venda segue o fluxo de sempre)
     * @throws ResponseStatusException 409 — prazo legal vencido (instrui a emitir nota de
     *         devolução), SEFAZ recusou o cancelamento, ou não foi possível confirmar com a SEFAZ
     */
    public Optional<String> cancelarSeAplicavel(long idEmpresa, long idVenda, Integer idUsuario, String justificativa) {
        Optional<DocumentoParaCancelar> docOpt = repositorio.buscarAutorizadoParaCancelamento(idVenda);
        if (docOpt.isEmpty()) {
            return Optional.empty();
        }
        DocumentoParaCancelar doc = docOpt.get();

        Autorizador autorizador = autorizadores.buscar(doc.uf(), MODELO_NFCE, doc.ambiente().codigo());
        int prazoMinutos = autorizador.prazoCancelamentoMinutos() != null
                ? autorizador.prazoCancelamentoMinutos() : PRAZO_PADRAO_MINUTOS;
        OffsetDateTime limite = doc.dataAutorizacao().plusMinutes(prazoMinutos);
        if (OffsetDateTime.now().isAfter(limite)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ("O prazo de %d minutos para cancelar esta NFC-e pela SEFAZ já passou "
                            + "(autorizada em %s). Não é possível cancelar a venda sem antes tratar a "
                            + "nota fiscal — emita uma nota de devolução.")
                            .formatted(prazoMinutos, FusoDaLoja.formatarEm(
                                    doc.dataAutorizacao(), FusoDaUf.deOuPadrao(doc.uf()), FMT)));
        }

        CertificadoParaAssinatura certificado = certificados.carregarAtivoParaAssinatura(idEmpresa);
        KeyStore keystore = abrir(certificado);

        XmlEventoMontado montado = montador.montar(new EventoCancelamento(
                doc.ambiente(), doc.chaveAcesso(), doc.cnpjEmitente(), doc.uf(),
                OffsetDateTime.now(), doc.protocolo(), justificativa));
        String eventoAssinado = assinador.assinarEvento(montado.xml(), montado.id(), keystore, certificado.senha());
        String envelope = montador.montarEnvelope(doc.idDocumentoFiscal(), eventoAssinado);
        validador.validarEventoCancelamento(envelope);

        String url = autorizadores.urlDe(doc.uf(), MODELO_NFCE, doc.ambiente().codigo(), ServicoSefaz.RECEPCAO_EVENTO);
        RespostaSefaz resposta;
        try {
            resposta = transporte.enviar(url, "RecepcaoEvento4", envelope,
                    certificado.pkcs12(), certificado.senha(), certificado.impressaoDigital());
        } catch (FalhaDeComunicacaoException e) {
            // ⚠️ Não há como saber se o cancelamento chegou a registrar do outro lado — tratar
            // como "recusado" e não reverter é o lado seguro do erro: reverter a venda sem
            // confirmação deixaria o fiscal e o estoque divergindo se a SEFAZ tiver processado.
            repositorio.registrarTentativaCancelamento(doc.idDocumentoFiscal(), justificativa, false,
                    null, null, e.getMessage(), envelope, idUsuario);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não foi possível confirmar o cancelamento junto à SEFAZ. A venda NÃO foi revertida — "
                            + "tente novamente em instantes.");
        }

        // 135 = evento registrado e vinculado à NF-e (leiauteEventoCancNFe_v1.00.xsd). 136
        // (registrado, NÃO vinculado) não conta como sucesso — algo deu errado mesmo respondendo.
        boolean autorizadoNaSefaz = "135".equals(resposta.cStat());
        long idEvento = repositorio.registrarTentativaCancelamento(doc.idDocumentoFiscal(), justificativa,
                autorizadoNaSefaz, resposta.protocolo(), resposta.cStat(), resposta.xMotivo(), envelope, idUsuario);

        if (!autorizadoNaSefaz) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A SEFAZ recusou o cancelamento desta NFC-e: %s (%s). A venda NÃO foi revertida."
                            .formatted(resposta.xMotivo(), resposta.cStat()));
        }

        // Propagação padrão (junta a transação do chamador) — ver o porquê no javadoc do método.
        repositorio.marcarCancelado(doc.idDocumentoFiscal());
        // Caminho quente do arquivamento (handoff §4.1) — best-effort, nunca lança.
        arquivamento.arquivarEventoSeAplicavel(idEvento);

        return Optional.of(resposta.protocolo());
    }

    private static KeyStore abrir(CertificadoParaAssinatura certificado) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(certificado.pkcs12()), certificado.senha().toCharArray());
            return ks;
        } catch (Exception e) {
            throw new AssinadorXmlNfe.AssinaturaInvalidaException(
                    "O certificado digital da empresa não pôde ser aberto para assinar o cancelamento: "
                            + e.getMessage(), e);
        }
    }
}
