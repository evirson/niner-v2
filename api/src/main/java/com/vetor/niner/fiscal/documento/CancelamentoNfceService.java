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
    private static final int MODELO_NFE = 55;
    private static final int PRAZO_PADRAO_NFCE_MINUTOS = 30;
    /** NF-e 55: 24 h, o prazo geral do Ajuste SINIEF 07/05. So vale como piso - o valor
     *  real vem de cfg_uf_autorizador por (UF, modelo, ambiente), que a V047 carregou. */
    private static final int PRAZO_PADRAO_NFE_MINUTOS = 1440;
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
        return repositorio.buscarAutorizadoParaCancelamento(idVenda)
                .map(doc -> executar(idEmpresa, doc, MODELO_NFCE, idUsuario, justificativa, ROTULO_VENDA));
    }

    /**
     * Cancela a <b>NF-e de devolução ao fornecedor</b> antes de o Cancelamento de Devolução de
     * Compra reverter o estoque — mesma garantia, mesma ordem: se este método voltar sem lançar,
     * pode reverter.
     *
     * <p>A ordem importa mais aqui do que na venda. A nota de devolução <b>declara que a mercadoria
     * saiu</b>; se o estoque voltasse antes e a SEFAZ recusasse o cancelamento, o sistema ficaria
     * com a mercadoria em casa e um documento válido dizendo que ela viajou.
     *
     * @param idMovimento o movimento {@code DEVOLUCAO_COMPRA} que está sendo cancelado
     */
    public Optional<String> cancelarNotaDeDevolucaoDeCompraSeAplicavel(long idEmpresa, long idMovimento,
                                                                      Integer idUsuario, String justificativa) {
        return repositorio.buscarAutorizadoDeMovimentoParaCancelamento(idMovimento)
                .map(doc -> executar(idEmpresa, doc, MODELO_NFE, idUsuario, justificativa, ROTULO_DEVOLUCAO_COMPRA));
    }

    /**
     * O evento 110111 em si — idêntico para os dois modelos. Só o prazo (que vem da UF) e o texto
     * das mensagens mudam, e os dois entram por parâmetro justamente para que este corpo, que é
     * onde mora a regra "não reverta sem confirmação", exista uma vez só.
     */
    private String executar(long idEmpresa, DocumentoParaCancelar doc, int modelo, Integer idUsuario,
                            String justificativa, Rotulo rotulo) {
        Autorizador autorizador = autorizadores.buscar(doc.uf(), modelo, doc.ambiente().codigo());
        int prazoPadrao = modelo == MODELO_NFCE ? PRAZO_PADRAO_NFCE_MINUTOS : PRAZO_PADRAO_NFE_MINUTOS;
        int prazoMinutos = autorizador.prazoCancelamentoMinutos() != null
                ? autorizador.prazoCancelamentoMinutos() : prazoPadrao;
        OffsetDateTime limite = doc.dataAutorizacao().plusMinutes(prazoMinutos);
        if (OffsetDateTime.now().isAfter(limite)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ("O prazo de %d minutos para cancelar esta %s pela SEFAZ já passou (autorizada em %s). "
                            + "Não é possível cancelar %s sem antes tratar a nota fiscal — %s")
                            .formatted(prazoMinutos, rotulo.documento(),
                                    FusoDaLoja.formatarEm(doc.dataAutorizacao(), FusoDaUf.deOuPadrao(doc.uf()), FMT),
                                    rotulo.operacao(), rotulo.remedio()));
        }

        CertificadoParaAssinatura certificado = certificados.carregarAtivoParaAssinatura(idEmpresa);
        KeyStore keystore = abrir(certificado);

        XmlEventoMontado montado = montador.montar(new EventoCancelamento(
                doc.ambiente(), doc.chaveAcesso(), doc.cnpjEmitente(), doc.uf(),
                OffsetDateTime.now(), doc.protocolo(), justificativa));
        String eventoAssinado = assinador.assinarEvento(montado.xml(), montado.id(), keystore, certificado.senha());
        String envelope = montador.montarEnvelope(doc.idDocumentoFiscal(), eventoAssinado);
        validador.validarEventoCancelamento(envelope);

        String url = autorizadores.urlDe(doc.uf(), modelo, doc.ambiente().codigo(), ServicoSefaz.RECEPCAO_EVENTO);
        RespostaSefaz resposta;
        try {
            resposta = transporte.enviar(url, "RecepcaoEvento4", envelope,
                    certificado.pkcs12(), certificado.senha(), certificado.impressaoDigital());
        } catch (FalhaDeComunicacaoException e) {
            // ⚠️ Não há como saber se o cancelamento chegou a registrar do outro lado — tratar
            // como "recusado" e não reverter é o lado seguro do erro: reverter sem confirmação
            // deixaria o fiscal e o estoque divergindo se a SEFAZ tiver processado.
            repositorio.registrarTentativaCancelamento(doc.idDocumentoFiscal(), justificativa, false,
                    null, null, e.getMessage(), envelope, idUsuario);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não foi possível confirmar o cancelamento junto à SEFAZ. " + rotulo.naoRevertido()
                            + " — tente novamente em instantes.");
        }

        // 135 = evento registrado e vinculado à NF-e (leiauteEventoCancNFe_v1.00.xsd). 136
        // (registrado, NÃO vinculado) não conta como sucesso — algo deu errado mesmo respondendo.
        //
        // ⚠️ 155 entrou em 2026-08-21 (achado de auditoria): "Cancelamento homologado FORA DE
        // PRAZO" também deixa o evento registrado e vinculado — a nota está cancelada na SEFAZ. Só
        // aceitar 135 fazia o ERP recusar a reversão de uma nota que o fisco já cancelou, e aí o
        // fiscal andava e o estoque/caixa não: exatamente a divergência que a ordem "nota primeiro,
        // estoque depois" existe para impedir, só que na direção inversa.
        boolean autorizadoNaSefaz = "135".equals(resposta.cStat()) || "155".equals(resposta.cStat());
        long idEvento = repositorio.registrarTentativaCancelamento(doc.idDocumentoFiscal(), justificativa,
                autorizadoNaSefaz, resposta.protocolo(), resposta.cStat(), resposta.xMotivo(), envelope, idUsuario);

        if (!autorizadoNaSefaz) {
            // ⚠️ 573 ("Duplicidade de Evento") NÃO é recusa: significa que o evento JÁ ESTÁ
            // registrado, quase sempre de uma tentativa anterior cuja resposta se perdeu no
            // caminho (o `catch` de falha de comunicação acima responde 409 sem reverter, que é o
            // lado seguro do erro). Como o evento é determinístico — mesmo `Id`, `nSeqEvento` 1 —,
            // toda retentativa devolvia 573 e o operador ficava num beco: a NFC-e cancelada na
            // SEFAZ e a venda viva no ERP, sem caminho para reconciliar.
            //
            // Desde 2026-08-22 (auditoria, item 20) o 573 não é mais o fim da linha: consultamos a
            // nota antes de decidir — mesma disciplina do F5 que a autorização já tem. Aceitar 573
            // como sucesso CEGO continuaria errado (o evento registrado pode ser outro); quem diz
            // se a nota está cancelada é a SEFAZ, perguntada.
            if ("573".equals(resposta.cStat()) && notaJaEstaCanceladaNaSefaz(doc, modelo, certificado)) {
                repositorio.marcarCancelado(doc.idDocumentoFiscal());
                arquivamento.arquivarEventoSeAplicavel(idEvento);
                return doc.protocolo();
            }
            if ("573".equals(resposta.cStat())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        ("O cancelamento desta %s já foi registrado na SEFAZ numa tentativa anterior (573), mas a"
                                + " consulta não confirmou que a nota está cancelada. %s. Use \"Consultar SEFAZ\" na"
                                + " tela de Documentos Fiscais antes de tentar de novo.")
                                .formatted(rotulo.documento(), rotulo.naoRevertido()));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A SEFAZ recusou o cancelamento desta %s: %s (%s). %s."
                            .formatted(rotulo.documento(), resposta.xMotivo(), resposta.cStat(),
                                    rotulo.naoRevertido()));
        }

        // Propagação padrão (junta a transação do chamador) — ver o porquê no javadoc do método.
        repositorio.marcarCancelado(doc.idDocumentoFiscal());
        // Caminho quente do arquivamento (handoff §4.1) — best-effort, nunca lança.
        arquivamento.arquivarEventoSeAplicavel(idEvento);

        return resposta.protocolo();
    }

    /**
     * Pergunta à SEFAZ se a nota <b>já está cancelada</b>, para decidir o que fazer com um
     * {@code cStat 573} ("Duplicidade de Evento") — auditoria 2026-08-21, item 20.
     *
     * <p>O 573 quase sempre significa que uma tentativa anterior chegou lá e só a resposta se
     * perdeu. Como o evento de cancelamento é determinístico (mesmo {@code Id}, {@code nSeqEvento}
     * 1), toda retentativa devolve 573 para sempre — e sem esta consulta o operador ficava preso
     * com a nota cancelada no fisco e a venda viva no ERP.
     *
     * <p><b>Só o "sim" é aproveitado.</b> Qualquer resposta que não confirme o cancelamento —
     * inclusive falha de comunicação — devolve {@code false}, e o chamador mantém a recusa sem
     * reverter. É o mesmo lado seguro do erro que o {@code catch} da transmissão escolhe: reverter
     * estoque e caixa por causa de uma resposta ambígua é pior que pedir para tentar de novo.
     *
     * <p>⚠️ Os códigos aceitos são os de <b>topo</b> da {@code consSitNFe}: <b>101</b>
     * (cancelamento homologado) e <b>151</b> (homologado fora de prazo) — o par do 135/155 que a
     * transmissão do evento aceita. Não interpreto {@code <retEvento>} aninhado: seria adivinhar
     * estrutura sem transmitir, e neste projeto já custou caro concluir coisa de XML fiscal sem
     * homologação real (cStat 1010 e 975 passaram no XSD e foram recusados na SEFAZ). Se numa UF o
     * cancelamento vier só no evento aninhado, o caminho continua sendo o do "Consultar SEFAZ" da
     * tela — que é o que a mensagem de recusa manda fazer.
     */
    private boolean notaJaEstaCanceladaNaSefaz(DocumentoParaCancelar doc, int modelo,
                                               CertificadoParaAssinatura certificado) {
        try {
            String url = autorizadores.urlDe(doc.uf(), modelo, doc.ambiente().codigo(),
                    ServicoSefaz.CONSULTA_PROTOCOLO);
            String consSitNFe = ("<consSitNFe xmlns=\"%s\" versao=\"4.00\">"
                    + "<tpAmb>%d</tpAmb><xServ>CONSULTAR</xServ><chNFe>%s</chNFe></consSitNFe>")
                    .formatted(MontadorXmlNfce.NS, doc.ambiente().codigo(), doc.chaveAcesso());

            RespostaSefaz consulta = transporte.enviar(url, "NFeConsultaProtocolo4", consSitNFe,
                    certificado.pkcs12(), certificado.senha(), certificado.impressaoDigital());
            return "101".equals(consulta.cStat()) || "151".equals(consulta.cStat());
        } catch (FalhaDeComunicacaoException e) {
            return false;
        }
    }

    /** O vocabulário de cada caminho — o que é o documento, o que é a operação que NÃO foi
     *  revertida, e o que o operador deve fazer quando o prazo já passou. */
    private record Rotulo(String documento, String operacao, String naoRevertido, String remedio) {
    }

    private static final Rotulo ROTULO_VENDA = new Rotulo("NFC-e", "a venda",
            "A venda NÃO foi revertida", "emita uma nota de devolução.");

    private static final Rotulo ROTULO_DEVOLUCAO_COMPRA = new Rotulo(
            "NF-e de devolução ao fornecedor", "a devolução",
            "A devolução NÃO foi cancelada",
            "a mercadoria já saiu com nota válida; peça ao fornecedor a nota de devolução "
                    + "correspondente para trazê-la de volta.");

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
