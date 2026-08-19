package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService.CertificadoParaAssinatura;
import com.vetor.niner.fiscal.documento.FiscalNumeracaoService.NumeroReservado;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DevolucaoParaMontar;
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
    private final FiscalNumeracaoService numeracao;
    private final DocumentoFiscalRepositorio repositorio;
    private final MontadorXmlNfeDevolucao montador;
    private final AssinadorXmlNfe assinador;
    private final ValidadorXsd validador;
    private final SefazTransporte transporte;
    private final SefazAutorizadorService autorizadores;
    private final FiscalCertificadoService certificados;
    private final ArquivamentoXmlService arquivamento;

    public EmissaoNfeDevolucaoService(DevolucaoFiscalAssembler assembler, FiscalNumeracaoService numeracao,
                                      DocumentoFiscalRepositorio repositorio, MontadorXmlNfeDevolucao montador,
                                      AssinadorXmlNfe assinador, ValidadorXsd validador,
                                      SefazTransporte transporte, SefazAutorizadorService autorizadores,
                                      FiscalCertificadoService certificados, ArquivamentoXmlService arquivamento) {
        this.assembler = assembler;
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
        NumeroReservado numero = numeracao.reservar(idEmpresa, MODELO_NFE, base.serie());

        // ---------- 2. montar, assinar e validar — nenhum I/O de rede ----------
        DevolucaoParaMontar dev = comNumeracao(base, numero);
        XmlMontado montado = montador.montar(dev);
        String xmlAssinado = assinador.assinar(montado.xml(), montado.chaveAcesso(), keystore, certificado.senha());
        validador.validarNfe(xmlAssinado);

        long idDocumentoOriginal = assembler.idDocumentoOriginal(idDevolucao);
        long idDocumento = repositorio.gravarDevolucaoAssinada(idEmpresa, idDevolucao, idUsuario, dev,
                numero, montado.chaveAcesso(), xmlAssinado, idDocumentoOriginal);
        repositorio.registrarUsoDoCertificado(certificado.idCertificado(), idDocumento, "ASSINATURA");

        // ---------- 3. transmitir — FORA de transação, pode levar 10 s ----------
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
            // Mesma regra da NFC-e (F5): NUNCA retransmitir daqui. A nota pode ter sido autorizada
            // e só a resposta ter se perdido — fica em TRANSMITINDO e o reprocessamento consulta a
            // chave na SEFAZ antes de reenviar.
            repositorio.registrarFalhaDeComunicacao(idDocumento, e.getMessage());
            return Optional.of(new ResultadoDevolucaoFiscal(Situacao.FALHA_COMUNICACAO, idDocumento,
                    montado.chaveAcesso(), null, null,
                    "A devolução foi gravada, mas não foi possível falar com a SEFAZ para emitir a nota de "
                            + "entrada. A nota fica pendente e pode ser reprocessada em Documentos Fiscais."));
        }

        // ---------- 4. desfecho ----------
        if (resposta.autorizado()) {
            repositorio.marcarAutorizado(idDocumento, resposta);
            arquivamento.arquivarDocumentoSeAplicavel(idDocumento);   // best-effort, nunca lança
            return Optional.of(new ResultadoDevolucaoFiscal(Situacao.AUTORIZADO, idDocumento,
                    montado.chaveAcesso(), resposta.protocolo(), resposta.cStat(),
                    "Nota fiscal de devolução autorizada."));
        }
        if (resposta.emProcessamento()) {
            repositorio.registrarProcessamento(idDocumento, resposta);
            return Optional.of(new ResultadoDevolucaoFiscal(Situacao.EM_PROCESSAMENTO, idDocumento,
                    montado.chaveAcesso(), null, resposta.cStat(),
                    "A SEFAZ recebeu a nota de devolução e ainda está processando: " + resposta.xMotivo()));
        }

        boolean denegado = EmissaoNfceService.CSTAT_DENEGACAO.contains(resposta.cStat());
        repositorio.marcarRecusado(idDocumento, resposta, denegado);
        return Optional.of(new ResultadoDevolucaoFiscal(
                denegado ? Situacao.DENEGADO : Situacao.REJEITADO, idDocumento, montado.chaveAcesso(),
                null, resposta.cStat(),
                "A SEFAZ recusou a nota de devolução: %s (%s). A devolução e o vale-mercadoria continuam válidos."
                        .formatted(resposta.xMotivo(), resposta.cStat())));
    }

    /** O assembler monta tudo menos a numeração (que só existe depois da reserva, F4) — aqui ela
     *  entra, sem o montador precisar saber que veio em dois tempos. */
    private static DevolucaoParaMontar comNumeracao(DevolucaoParaMontar base, NumeroReservado numero) {
        return new DevolucaoParaMontar(base.ambiente(), numero.serie(), numero.numero(),
                numero.codigoNumerico(), base.emissao(), base.naturezaOperacao(), base.chaveNotaOriginal(),
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
