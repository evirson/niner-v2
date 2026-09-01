package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService.CertificadoParaAssinatura;
import com.vetor.niner.fiscal.documento.FiscalNumeracaoService.NumeroReservado;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DevolucaoParaMontar;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.XmlMontado;
import com.vetor.niner.fiscal.sefaz.SefazAutorizadorService;
import com.vetor.niner.fiscal.sefaz.SefazDtos.FalhaDeComunicacaoException;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazDtos.ServicoSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.util.Optional;

/**
 * Emissão da <b>NF-e de devolução</b> (modelo 55, entrada) — bloco B9, §10.2. Espelha o
 * {@link EmissaoNfceService}: numeração própria → montar/assinar/validar → transmitir → desfecho.
 *
 * <h2>O que muda em relação à emissão da NFC-e</h2>
 *
 * <ul>
 *   <li><b>Sem contingência offline.</b> A NFC-e entra em contingência porque há um cliente
 *       esperando no caixa e a venda não pode parar (§9.7). A devolução é operação de retaguarda:
 *       se a SEFAZ está fora, o certo é falhar e tentar de novo depois, não emitir um documento
 *       que ninguém está esperando imprimir.</li>
 *   <li><b>Sem QR Code</b> — é da NFC-e; o DANFE do modelo 55 leva chave e protocolo.</li>
 *   <li><b>Numeração própria</b> ({@code modelo 55}, {@code serie_nfe}), independente da NFC-e —
 *       são duas sequências que a SEFAZ trata separadamente.</li>
 * </ul>
 *
 * <h2>⚠️ Este serviço NÃO é transacional, de propósito</h2>
 *
 * <p>F2: nenhuma chamada de rede dentro de transação de banco. Cada gravação usa a transação curta
 * do {@link DocumentoFiscalRepositorio}; a transmissão (até 10 s) acontece fora de qualquer uma.
 * Quem chama precisa respeitar isso — ver {@code DevolucaoProdutoService}, que emite <b>depois</b>
 * de a devolução estar gravada e commitada.
 */
@Service
public class EmissaoNfeDevolucaoService {

    private static final int MODELO_NFE = 55;
    private static final String SERVICO_AUTORIZACAO = "NFeAutorizacao4";

    private final DevolucaoFiscalAssembler assembler;
    private final DevolucaoCompraFiscalAssembler assemblerCompra;
    private final FiscalNumeracaoService numeracao;
    private final DocumentoFiscalRepositorio repositorio;
    private final MontadorXmlNfeDevolucao montador;
    private final AssinadorXmlNfe assinador;
    private final ValidadorXsd validador;
    private final SefazTransporte transporte;
    private final SefazAutorizadorService autorizadores;
    private final FiscalCertificadoService certificados;
    private final ArquivamentoXmlService arquivamento;

    public EmissaoNfeDevolucaoService(DevolucaoFiscalAssembler assembler,
                                      DevolucaoCompraFiscalAssembler assemblerCompra,
                                      FiscalNumeracaoService numeracao,
                                      DocumentoFiscalRepositorio repositorio, MontadorXmlNfeDevolucao montador,
                                      AssinadorXmlNfe assinador, ValidadorXsd validador,
                                      SefazTransporte transporte, SefazAutorizadorService autorizadores,
                                      FiscalCertificadoService certificados, ArquivamentoXmlService arquivamento) {
        this.assembler = assembler;
        this.assemblerCompra = assemblerCompra;
        this.numeracao = numeracao;
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
     * @return vazio quando não há nota a emitir — fiscal desligado, devolução sem venda de origem,
     *         ou venda sem NFC-e autorizada (as três regras decididas com o dono do produto; ver
     *         {@link DevolucaoFiscalAssembler}). Nesses casos a devolução segue valendo com o
     *         vale-mercadoria, exatamente como antes do B9.
     */
    public Optional<ResultadoDevolucaoFiscal> emitirSeAplicavel(long idEmpresa, long idDevolucao, Integer idUsuario) {
        Optional<DevolucaoParaMontar> pedidoOpt = assembler.montar(idEmpresa, idDevolucao);
        if (pedidoOpt.isEmpty()) {
            return Optional.empty();
        }
        DevolucaoParaMontar base = pedidoOpt.get();

        CertificadoParaAssinatura certificado = certificados.carregarAtivoParaAssinatura(idEmpresa);
        KeyStore keystore = abrir(certificado);

        // ---------- 1. numeração — transação curta e própria (F4) ----------
        NumeroReservado numero = numeracao.reservar(idEmpresa, MODELO_NFE, base.serie(),
                base.ambiente() == AmbienteSefaz.PRODUCAO);

        // ---------- 2. montar, assinar e validar — nenhum I/O de rede ----------
        DevolucaoParaMontar dev = comNumeracao(base, numero);
        XmlMontado montado = montador.montar(dev);
        String xmlAssinado = assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, certificado.senha());
        validador.validarNfe(xmlAssinado);

        long idDocumentoOriginal = assembler.idDocumentoOriginal(idDevolucao);
        long idDocumento = repositorio.gravarDevolucaoAssinada(idEmpresa, idDevolucao, idUsuario, dev,
                numero, montado.chaveAcesso(), xmlAssinado, idDocumentoOriginal);
        repositorio.registrarUsoDoCertificado(certificado.idCertificado(), idDocumento, "ASSINATURA");

        return Optional.of(transmitirEDesfechar(certificado, dev, montado.chaveAcesso(), xmlAssinado,
                idDocumento, TEXTOS_VENDA));
    }

    /**
     * Emite a NF-e de <b>devolução de compra</b> — a mercadoria voltando ao fornecedor.
     *
     * <p>Mora nesta classe, e não numa irmã, de propósito: da assinatura em diante <b>o caminho é
     * exatamente o mesmo</b>, e é justamente o trecho que não pode divergir — a regra F5 ("nunca
     * retransmitir daqui, a nota pode ter sido autorizada e só a resposta ter se perdido") é o tipo
     * de decisão que, copiada, se perde na segunda cópia. O que difere entre as duas devoluções —
     * quem monta, onde grava, o que dizer ao operador — está isolado nos parâmetros.
     *
     * @param idMovimento o movimento {@code DEVOLUCAO_COMPRA} já gravado e <b>commitado</b>
     * @return vazio quando o fiscal está desligado para a empresa (F12) — a devolução e a baixa de
     *         estoque seguem valendo, sem nota
     */
    public Optional<ResultadoDevolucaoFiscal> emitirDevolucaoDeCompraSeAplicavel(long idEmpresa, long idMovimento,
                                                                                 Integer idUsuario) {
        Optional<DevolucaoParaMontar> pedidoOpt = assemblerCompra.montar(idEmpresa, idMovimento);
        if (pedidoOpt.isEmpty()) {
            return Optional.empty();
        }
        DevolucaoParaMontar base = pedidoOpt.get();

        CertificadoParaAssinatura certificado = certificados.carregarAtivoParaAssinatura(idEmpresa);
        KeyStore keystore = abrir(certificado);

        NumeroReservado numero = numeracao.reservar(idEmpresa, MODELO_NFE, base.serie(),
                base.ambiente() == AmbienteSefaz.PRODUCAO);
        DevolucaoParaMontar dev = comNumeracao(base, numero);
        XmlMontado montado = montador.montar(dev);
        String xmlAssinado = assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, certificado.senha());
        validador.validarNfe(xmlAssinado);

        long idDocumento = repositorio.gravarDevolucaoCompraAssinada(idEmpresa, idMovimento, idUsuario, dev,
                numero, montado.chaveAcesso(), xmlAssinado);
        repositorio.registrarUsoDoCertificado(certificado.idCertificado(), idDocumento, "ASSINATURA");

        return Optional.of(transmitirEDesfechar(certificado, dev, montado.chaveAcesso(), xmlAssinado,
                idDocumento, TEXTOS_COMPRA));
    }

    /**
     * Transmite o XML já assinado e gravado, e traduz a resposta da SEFAZ em desfecho — o trecho
     * comum às duas devoluções.
     *
     * <p>⚠️ <b>Fora de transação, sempre</b> (F2): a chamada pode levar 10 s. Quem chega aqui já
     * gravou o documento em transação curta e própria.
     */
    private ResultadoDevolucaoFiscal transmitirEDesfechar(CertificadoParaAssinatura certificado,
                                                          DevolucaoParaMontar dev, String chave,
                                                          String xmlAssinado, long idDocumento, Textos textos) {
        String url = autorizadores.urlDe(dev.emitente().uf(), MODELO_NFE, dev.ambiente().codigo(),
                ServicoSefaz.AUTORIZACAO);
        String enviNFe = ("<enviNFe versao=\"4.00\" xmlns=\"%s\">"
                + "<idLote>%d</idLote><indSinc>1</indSinc>%s</enviNFe>")
                .formatted(MontadorXmlNfce.NS, idDocumento, xmlAssinado);

        RespostaSefaz resposta;
        repositorio.marcarTransmitindo(idDocumento);
        try {
            resposta = transporte.enviar(url, SERVICO_AUTORIZACAO, enviNFe,
                    certificado.pkcs12(), certificado.senha(), certificado.impressaoDigital());
            repositorio.registrarUsoDoCertificado(certificado.idCertificado(), idDocumento, "MTLS");
        } catch (FalhaDeComunicacaoException e) {
            // F5: NUNCA retransmitir daqui. A nota pode ter sido autorizada e só a resposta ter se
            // perdido — fica em TRANSMITINDO e o reprocessamento consulta a chave na SEFAZ antes de
            // reenviar. Emitir de novo aqui geraria duas notas para a mesma operação.
            repositorio.registrarFalhaDeComunicacao(idDocumento, e.getMessage());
            return new ResultadoDevolucaoFiscal(Situacao.FALHA_COMUNICACAO, idDocumento, chave, null, null,
                    textos.falhaComunicacao());
        }

        if (resposta.autorizado()) {
            repositorio.marcarAutorizado(idDocumento, resposta);
            arquivamento.arquivarDocumentoSeAplicavel(idDocumento);   // best-effort, nunca lança
            return new ResultadoDevolucaoFiscal(Situacao.AUTORIZADO, idDocumento, chave,
                    resposta.protocolo(), resposta.cStat(), textos.autorizado());
        }
        if (resposta.emProcessamento()) {
            repositorio.registrarProcessamento(idDocumento, resposta);
            return new ResultadoDevolucaoFiscal(Situacao.EM_PROCESSAMENTO, idDocumento, chave,
                    null, resposta.cStat(), textos.emProcessamento().formatted(resposta.xMotivo()));
        }

        boolean denegado = EmissaoNfceService.CSTAT_DENEGACAO.contains(resposta.cStat());
        repositorio.marcarRecusado(idDocumento, resposta, denegado);
        return new ResultadoDevolucaoFiscal(denegado ? Situacao.DENEGADO : Situacao.REJEITADO, idDocumento,
                chave, null, resposta.cStat(),
                textos.recusado().formatted(resposta.xMotivo(), resposta.cStat()));
    }

    /**
     * O que dizer ao operador em cada desfecho. São dois conjuntos porque as consequências são
     * diferentes: na devolução ao cliente o vale-mercadoria já resolveu a vida dele e a nota é
     * burocracia; na devolução ao fornecedor, <b>a nota é a autorização para a mercadoria viajar</b>
     * — sem ela, a carga sai irregular.
     */
    private record Textos(String falhaComunicacao, String autorizado, String emProcessamento, String recusado) {
    }

    private static final Textos TEXTOS_VENDA = new Textos(
            "A devolução foi gravada, mas não foi possível falar com a SEFAZ para emitir a nota de "
                    + "entrada. A nota fica pendente e pode ser reprocessada em Documentos Fiscais.",
            "Nota fiscal de devolução autorizada.",
            "A SEFAZ recebeu a nota de devolução e ainda está processando: %s",
            "A SEFAZ recusou a nota de devolução: %s (%s). A devolução e o vale-mercadoria continuam válidos.");

    private static final Textos TEXTOS_COMPRA = new Textos(
            "A devolução foi gravada e o estoque já foi baixado, mas não foi possível falar com a SEFAZ "
                    + "para emitir a nota ao fornecedor. NÃO envie a mercadoria: a nota fica pendente e pode "
                    + "ser reprocessada em Documentos Fiscais.",
            "Nota fiscal de devolução ao fornecedor autorizada. A mercadoria já pode seguir viagem.",
            "A SEFAZ recebeu a nota de devolução ao fornecedor e ainda está processando: %s",
            "A SEFAZ recusou a nota de devolução ao fornecedor: %s (%s). A devolução e a baixa de estoque "
                    + "continuam valendo, mas NÃO envie a mercadoria sem nota autorizada — cancele a devolução "
                    + "ou corrija o motivo da recusa e emita de novo.");

    /** O assembler monta tudo menos a numeração (que só existe depois da reserva, F4) — aqui ela
     *  entra, sem o montador precisar saber que veio em dois tempos. */
    private static DevolucaoParaMontar comNumeracao(DevolucaoParaMontar base, NumeroReservado numero) {
        return new DevolucaoParaMontar(base.ambiente(), numero.serie(), numero.numero(),
                numero.codigoNumerico(), base.emissao(), base.naturezaOperacao(), base.chaveNotaOriginal(),
                base.tipoNf(), base.idDestino(),
                base.emitente(), base.destinatario(), base.itens(), base.totais(),
                base.informacoesComplementares(), base.responsavelTecnico(), base.versaoAplicativo());
    }

    private static KeyStore abrir(CertificadoParaAssinatura certificado) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(certificado.pkcs12()), certificado.senha().toCharArray());
            return ks;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O certificado digital da empresa não pôde ser aberto para assinar a nota de devolução: "
                            + e.getMessage());
        }
    }

    public enum Situacao {
        AUTORIZADO, REJEITADO, DENEGADO, EM_PROCESSAMENTO, FALHA_COMUNICACAO
    }

    /** Desfecho da emissão, escrito para o operador ler — diz o que aconteceu com a
     *  <b>devolução</b>, não só com a nota. */
    public record ResultadoDevolucaoFiscal(Situacao situacao, long idDocumentoFiscal, String chaveAcesso,
                                            String protocolo, String cStat, String mensagem) {
    }
}
