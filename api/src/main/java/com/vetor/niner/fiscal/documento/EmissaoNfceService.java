package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService.CertificadoParaAssinatura;
import com.vetor.niner.fiscal.documento.FiscalNumeracaoService.NumeroReservado;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.*;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.ItemTributado;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.TotaisTributarios;
import com.vetor.niner.fiscal.sefaz.SefazAutorizadorService;
import com.vetor.niner.fiscal.sefaz.SefazDtos.Autorizador;
import com.vetor.niner.fiscal.sefaz.SefazDtos.FalhaDeComunicacaoException;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazDtos.ServicoSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.KeyStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Emissão síncrona da NFC-e (bloco B7 — docs/MODULOFISCAL.md §9.6): o orquestrador que amarra
 * motor, montagem, assinatura e transporte, e que move o documento pela máquina de estados (§9.1).
 *
 * <h2>F3 — a venda nunca desaparece porque a nota falhou</h2>
 *
 * <p>Este serviço <b>nunca</b> desfaz a venda. Rejeição da SEFAZ, timeout, certificado vencido,
 * venda a contribuinte — em todos os casos a venda continua registrada e o documento fiscal fica
 * no estado que descreve o que aconteceu, com o motivo em português. O lojista descobre na tela
 * de Documentos Fiscais, não na contabilidade três meses depois.
 *
 * <h2>F2 — nenhuma chamada de rede dentro de transação de banco</h2>
 *
 * <p>A emissão é uma sequência de transações curtas separadas pela ida à SEFAZ — reserva do número
 * ({@code REQUIRES_NEW}), gravação do assinado, transmissão <b>fora</b> de transação, gravação do
 * desfecho. Segurar transação aberta durante a transmissão prenderia conexão do pool e travaria a
 * linha de numeração pelos 10 s do timeout, parando os outros caixas.
 *
 * <p>Por isso a persistência vive em {@link DocumentoFiscalRepositorio}, bean separado: método
 * {@code @Transactional} chamado de dentro da própria classe não passa pelo proxy do Spring, e sem
 * transação não há {@code app.id_tenant} — o RLS bloquearia tudo (P8).
 */
@Service
public class EmissaoNfceService {

    private static final int MODELO_NFCE = 65;
    private static final int TP_EMIS_NORMAL = 1;
    private static final String SERVICO_AUTORIZACAO = "NFeAutorizacao4";

    /**
     * cStat de <b>denegação</b>: irregularidade cadastral do emitente ou do destinatário. É
     * diferente de rejeição — o número foi consumido, a nota existe para o fisco, e não se cancela
     * nem se reaproveita. Tratar como rejeição levaria o lojista a "corrigir e reenviar" um número
     * que já morreu.
     */
    private static final Set<String> CSTAT_DENEGACAO = Set.of("301", "302", "303", "304", "305");

    private final FiscalNumeracaoService numeracao;
    private final DocumentoFiscalRepositorio repositorio;
    private final MontadorXmlNfce montador;
    private final AssinadorXmlNfe assinador;
    private final ValidadorXsd validador;
    private final SefazTransporte transporte;
    private final SefazAutorizadorService autorizadores;
    private final FiscalCertificadoService certificados;
    private final FiscalContingenciaService contingencia;

    public EmissaoNfceService(FiscalNumeracaoService numeracao, DocumentoFiscalRepositorio repositorio,
                              MontadorXmlNfce montador, AssinadorXmlNfe assinador,
                              ValidadorXsd validador, SefazTransporte transporte,
                              SefazAutorizadorService autorizadores,
                              FiscalCertificadoService certificados,
                              FiscalContingenciaService contingencia) {
        this.contingencia = contingencia;
        this.numeracao = numeracao;
        this.repositorio = repositorio;
        this.montador = montador;
        this.assinador = assinador;
        this.validador = validador;
        this.transporte = transporte;
        this.autorizadores = autorizadores;
        this.certificados = certificados;
    }

    /**
     * Emite a NFC-e de uma venda já gravada.
     *
     * @param pedido tudo que a nota precisa <b>menos</b> número, série e chave — quem aloca é este
     *               serviço, o mais tarde possível (§9.2)
     */
    public ResultadoEmissao emitir(PedidoDeEmissao pedido) {
        // ---------- DF13: NFC-e não serve para contribuinte de ICMS ----------
        // Recusa ANTES de queimar número: nada de inutilizar depois por um caso que dava para
        // prever. A venda continua existindo (F3) e o documento fica em NAO_EMITIDO com o motivo.
        Destinatario dest = pedido.destinatario();
        if (dest != null && dest.indicadorIe() == 1) {
            long id = repositorio.gravarNaoEmitido(pedido,
                    "Venda a contribuinte de ICMS não sai em NFC-e (modelo 65). "
                            + "Este cliente exige NF-e modelo 55.");
            return ResultadoEmissao.naoEmitido(id,
                    "O cliente é contribuinte de ICMS — a NFC-e não serve para revenda. A venda foi "
                            + "registrada normalmente e aparece em Documentos Fiscais como não emitida.");
        }

        Autorizador autorizador = autorizadores.buscar(
                pedido.emitente().uf(), MODELO_NFCE, pedido.ambiente().codigo());
        CertificadoParaAssinatura certificado = certificados.carregarAtivoParaAssinatura(pedido.idEmpresa());
        KeyStore keystore = abrir(certificado);

        // ---------- contingência (§9.7) ----------
        // A empresa já está em contingência: não adianta tentar a SEFAZ a cada venda e fazer o
        // caixa esperar 10 s de timeout. Sai direto em tpEmis 9, na série própria (DF33), e a
        // transmissão fica para o job que drena a fila quando a SEFAZ voltar.
        FiscalContingenciaService.Estado estado = contingencia.consultar(pedido.idEmpresa());
        int tipoEmissao = estado.ativa() ? MontadorXmlNfce.TP_EMIS_CONTINGENCIA_OFFLINE : TP_EMIS_NORMAL;
        int serie = estado.ativa() ? estado.serieContingencia() : pedido.serie();

        // ---------- 1. numeração — transação curta e própria (F4) ----------
        NumeroReservado numero = numeracao.reservar(pedido.idEmpresa(), MODELO_NFCE, serie);

        // ---------- 2. montar, assinar e validar — nenhum I/O de rede ----------
        XmlMontado montado = montador.montar(
                new NotaParaMontar(
                        pedido.ambiente(), numero.serie(), numero.numero(), numero.codigoNumerico(),
                        pedido.emissao(), pedido.naturezaOperacao(), tipoEmissao,
                        pedido.emitente(), dest, pedido.itens(), pedido.itensTributados(), pedido.totais(),
                        pedido.pagamentos(), pedido.troco(), pedido.informacoesComplementares(),
                        pedido.responsavelTecnico(),
                        new UrlsConsultaUf(autorizador.urlQrCode(), autorizador.urlConsultaPublica()),
                        pedido.versaoAplicativo()),
                parametros -> assinador.assinarQrCodeOffline(parametros, keystore, certificado.senha()));

        String xmlAssinado = assinador.assinar(
                montado.xml(), montado.chaveAcesso(), keystore, certificado.senha());

        // F11 — bloqueio preventivo: rejeição por schema é a mais barata de evitar, e evitá-la
        // aqui poupa a viagem de rede e a mensagem críptica que a SEFAZ devolveria.
        validador.validarNfe(xmlAssinado);

        long idDocumento = repositorio.gravarAssinado(
                pedido, numero, montado.chaveAcesso(), xmlAssinado, tipoEmissao);
        repositorio.registrarUsoDoCertificado(certificado.idCertificado(), idDocumento, "ASSINATURA");

        // Em contingência a emissão termina aqui: o cupom sai, a venda segue, e a nota entra na
        // fila. Tentar transmitir agora só devolveria o mesmo timeout que colocou a empresa em
        // contingência — e faria o caixa esperar por ele em toda venda.
        if (estado.ativa()) {
            repositorio.marcarEmContingencia(idDocumento);
            return ResultadoEmissao.emContingencia(idDocumento, montado.chaveAcesso());
        }

        // ---------- 3. transmitir — FORA de transação, pode levar 10 s ----------
        String url = autorizadores.urlDe(pedido.emitente().uf(), MODELO_NFCE,
                pedido.ambiente().codigo(), ServicoSefaz.AUTORIZACAO);
        String enviNFe = ("<enviNFe versao=\"4.00\" xmlns=\"%s\">"
                + "<idLote>%d</idLote><indSinc>1</indSinc>%s</enviNFe>")
                .formatted(MontadorXmlNfce.NS, idDocumento, xmlAssinado);

        RespostaSefaz resposta;
        repositorio.marcarTransmitindo(idDocumento);
        try {
            resposta = transporte.enviar(url, SERVICO_AUTORIZACAO, enviNFe,
                    certificado.pkcs12(), certificado.senha(), pedido.impressaoDigitalCertificado());
            repositorio.registrarUsoDoCertificado(certificado.idCertificado(), idDocumento, "MTLS");
            contingencia.registrarSucesso(pedido.idTenant(), pedido.idEmpresa());
        } catch (FalhaDeComunicacaoException e) {
            // ⚠️ NUNCA retransmitir automaticamente daqui: a nota pode ter sido autorizada e só a
            // resposta ter se perdido (F5). Fica em TRANSMITINDO, e quem for retomar consulta a
            // chave na SEFAZ primeiro. Marcar como REJEITADO aqui emitiria a venda duas vezes.
            repositorio.registrarFalhaDeComunicacao(idDocumento, e.getMessage());
            boolean entrou = contingencia.registrarFalhaEavaliarEntrada(
                    pedido.idTenant(), pedido.idEmpresa(), e.getMessage());
            return ResultadoEmissao.falhaDeComunicacao(idDocumento, montado.chaveAcesso(), entrou);
        }

        // ---------- 4. desfecho ----------
        return concluir(idDocumento, montado.chaveAcesso(), resposta);
    }

    private ResultadoEmissao concluir(long idDocumento, String chave, RespostaSefaz resposta) {
        if (resposta.autorizado()) {
            repositorio.marcarAutorizado(idDocumento, resposta);
            return ResultadoEmissao.autorizado(idDocumento, chave, resposta.protocolo());
        }
        if (resposta.emProcessamento()) {
            // Lote aceito, resultado ainda não disponível — não é erro. Tratar como erro faria o
            // caixa emitir de novo uma nota que está a caminho de ser autorizada.
            repositorio.registrarProcessamento(idDocumento, resposta);
            return ResultadoEmissao.emProcessamento(idDocumento, chave, resposta.xMotivo());
        }

        boolean denegado = CSTAT_DENEGACAO.contains(resposta.cStat());
        repositorio.marcarRecusado(idDocumento, resposta, denegado);
        return denegado
                ? ResultadoEmissao.denegado(idDocumento, chave, resposta.cStat(), resposta.xMotivo())
                : ResultadoEmissao.rejeitado(idDocumento, chave, resposta.cStat(), resposta.xMotivo());
    }

    private static KeyStore abrir(CertificadoParaAssinatura certificado) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(certificado.pkcs12()), certificado.senha().toCharArray());
            return ks;
        } catch (Exception e) {
            throw new AssinadorXmlNfe.AssinaturaInvalidaException(
                    "O certificado digital da empresa não pôde ser aberto para assinar: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- contratos

    /**
     * O que a venda entrega para virar nota. Não traz número, série nem chave — quem aloca é este
     * serviço, no momento de montar o XML (§9.2: quanto menor a distância entre alocar e
     * transmitir, menos número perdido para inutilizar depois).
     */
    public record PedidoDeEmissao(
            long idTenant,
            long idEmpresa,
            Integer idVenda,
            Integer idCliente,
            Integer idUsuario,
            AmbienteSefaz ambiente,
            int serie,
            OffsetDateTime emissao,
            String naturezaOperacao,
            Emitente emitente,
            Destinatario destinatario,
            List<ItemNota> itens,
            List<ItemTributado> itensTributados,
            TotaisTributarios totais,
            List<Pagamento> pagamentos,
            BigDecimal troco,
            String informacoesComplementares,
            ResponsavelTecnico responsavelTecnico,
            String impressaoDigitalCertificado,
            String versaoAplicativo) {
    }

    /**
     * Como a emissão terminou. A {@code mensagem} é escrita para o operador do caixa ler com
     * cliente na frente: diz o que aconteceu com a <b>venda</b>, não só com a nota.
     */
    public record ResultadoEmissao(Situacao situacao, Long idDocumentoFiscal, String chaveAcesso,
                                   String protocolo, String cStat, String mensagem) {

        public enum Situacao {
            AUTORIZADO, REJEITADO, DENEGADO, EM_PROCESSAMENTO, FALHA_COMUNICACAO, CONTINGENCIA,
            NAO_EMITIDO
        }

        static ResultadoEmissao autorizado(long id, String chave, String protocolo) {
            return new ResultadoEmissao(Situacao.AUTORIZADO, id, chave, protocolo, "100",
                    "Nota autorizada.");
        }

        static ResultadoEmissao rejeitado(long id, String chave, String cStat, String motivo) {
            return new ResultadoEmissao(Situacao.REJEITADO, id, chave, null, cStat,
                    "A SEFAZ rejeitou a nota: %s (%s). A venda está registrada; corrija e emita de novo."
                            .formatted(motivo, cStat));
        }

        static ResultadoEmissao denegado(long id, String chave, String cStat, String motivo) {
            return new ResultadoEmissao(Situacao.DENEGADO, id, chave, null, cStat,
                    ("Nota denegada pela SEFAZ: %s (%s). Este número não pode ser reaproveitado nem "
                            + "cancelado — a venda continua registrada.").formatted(motivo, cStat));
        }

        static ResultadoEmissao emProcessamento(long id, String chave, String motivo) {
            return new ResultadoEmissao(Situacao.EM_PROCESSAMENTO, id, chave, null, null,
                    "A SEFAZ recebeu a nota e ainda está processando (%s). Não emita de novo."
                            .formatted(motivo));
        }

        static ResultadoEmissao falhaDeComunicacao(long id, String chave, boolean entrouEmContingencia) {
            String base = "Não foi possível falar com a SEFAZ. A venda está registrada. A nota pode "
                    + "ter sido autorizada mesmo assim — o sistema precisa consultar a chave antes "
                    + "de tentar de novo.";
            return new ResultadoEmissao(Situacao.FALHA_COMUNICACAO, id, chave, null, null,
                    entrouEmContingencia
                            ? base + " As próximas vendas sairão em CONTINGÊNCIA automaticamente."
                            : base);
        }

        /**
         * Nota emitida em contingência: o cupom sai, a venda segue, e a transmissão fica para
         * quando a SEFAZ voltar. Do ponto de vista do caixa isto é <b>sucesso</b> — por isso a
         * mensagem não fala em erro.
         */
        static ResultadoEmissao emContingencia(long id, String chave) {
            return new ResultadoEmissao(Situacao.CONTINGENCIA, id, chave, null, null,
                    "Nota emitida em CONTINGÊNCIA — a SEFAZ está fora do ar. O cupom vale e deve ser "
                            + "entregue ao cliente; a nota será transmitida automaticamente quando a "
                            + "SEFAZ voltar.");
        }

        static ResultadoEmissao naoEmitido(long id, String mensagem) {
            return new ResultadoEmissao(Situacao.NAO_EMITIDO, id, null, null, null, mensagem);
        }

        public boolean autorizada() {
            return situacao == Situacao.AUTORIZADO;
        }
    }
}
