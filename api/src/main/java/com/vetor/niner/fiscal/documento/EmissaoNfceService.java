package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService.CertificadoParaAssinatura;
import com.vetor.niner.fiscal.configuracao.FiscalConfigService;
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
    public static final Set<String> CSTAT_DENEGACAO = Set.of("301", "302", "303", "304", "305");

    private final FiscalNumeracaoService numeracao;
    private final DocumentoFiscalRepositorio repositorio;
    private final MontadorXmlNfce montador;
    private final AssinadorXmlNfe assinador;
    private final ValidadorXsd validador;
    private final SefazTransporte transporte;
    private final SefazAutorizadorService autorizadores;
    private final FiscalCertificadoService certificados;
    private final FiscalConfigService fiscalConfig;
    private final FiscalContingenciaService contingencia;
    private final ArquivamentoXmlService arquivamento;

    public EmissaoNfceService(FiscalNumeracaoService numeracao, DocumentoFiscalRepositorio repositorio,
                              MontadorXmlNfce montador, AssinadorXmlNfe assinador,
                              ValidadorXsd validador, SefazTransporte transporte,
                              SefazAutorizadorService autorizadores,
                              FiscalCertificadoService certificados,
                              FiscalConfigService fiscalConfig,
                              FiscalContingenciaService contingencia,
                              ArquivamentoXmlService arquivamento) {
        this.contingencia = contingencia;
        this.numeracao = numeracao;
        this.repositorio = repositorio;
        this.montador = montador;
        this.assinador = assinador;
        this.validador = validador;
        this.transporte = transporte;
        this.autorizadores = autorizadores;
        this.certificados = certificados;
        this.fiscalConfig = fiscalConfig;
        this.arquivamento = arquivamento;
    }

    /**
     * Emite a NFC-e de uma venda já gravada.
     *
     * @param pedido tudo que a nota precisa <b>menos</b> número, série e chave — quem aloca é este
     *               serviço, o mais tarde possível (§9.2)
     */
    public ResultadoEmissao emitir(PedidoDeEmissao pedido) {
        // Ponto ÚNICO onde o modelo entra no resultado — as sete fábricas de ResultadoEmissao não
        // precisam carregar um parâmetro que nenhuma delas usa para decidir nada.
        return emitirInterno(pedido).comModelo(pedido.modelo().codigo());
    }

    /**
     * Motivo gravado na linha do número queimado (pendência #71).
     *
     * <p>⚠️ A mensagem da exceção entra <b>inteira</b>, truncada só pelo limite da coluna: quem vai
     * ler isto é quem precisa decidir se inutiliza a faixa, meses depois, e "erro ao emitir" não
     * ajuda ninguém a decidir nada. O nome da classe vai junto porque exceção de validação de
     * schema costuma vir com {@code getMessage()} nulo.
     */
    static String motivoDoNumeroQueimado(RuntimeException e) {
        String detalhe = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String texto = "Número reservado e não utilizado: a montagem/assinatura/validação do XML "
                + "falhou antes de gravar a nota. " + detalhe;
        return texto.length() > 500 ? texto.substring(0, 500) : texto;
    }

    private ResultadoEmissao emitirInterno(PedidoDeEmissao pedido) {
        // ---------- DF13: contribuinte de ICMS sai em NF-e 55, não em NFC-e ----------
        // Até 2026-08-24 este caso era apenas RECUSADO aqui e a venda ficava sem documento nenhum.
        // Agora o modelo vem decidido do assembler (a partir do indicador_ie do cliente) e a
        // emissão segue este mesmo caminho — o que muda é o modelo, a série e o autorizador.
        // Os pré-requisitos da 55 (emite_nfe ligado, cadastro do cliente completo) são conferidos
        // no assembler, ANTES de qualquer número ser reservado: nada de queimar numeração por um
        // caso que dava para prever.
        Destinatario dest = pedido.destinatario();
        int modelo = pedido.modelo().codigo();

        // A venda exige NF-e 55 mas ela não pode sair agora (emite_nfe desligado, cadastro do
        // cliente incompleto). F3: a venda continua registrada e o documento guarda o motivo —
        // travar o fechamento por causa disso deixaria o caixa parado com cliente na frente.
        if (pedido.impedimentoNfe() != null) {
            long id = repositorio.gravarNaoEmitido(pedido, pedido.impedimentoNfe());
            return ResultadoEmissao.naoEmitido(id, pedido.impedimentoNfe());
        }

        Autorizador autorizador = autorizadores.buscar(
                pedido.emitente().uf(), modelo, pedido.ambiente().codigo());
        CertificadoParaAssinatura certificado = certificados.carregarAtivoParaAssinatura(pedido.idEmpresa());
        KeyStore keystore = abrir(certificado);

        // ---------- contingência (§9.7) ----------
        // A empresa já está em contingência: não adianta tentar a SEFAZ a cada venda e fazer o
        // caixa esperar 10 s de timeout. Sai direto em tpEmis 9, na série própria (DF33), e a
        // transmissão fica para o job que drena a fila quando a SEFAZ voltar.
        // ⚠️ Contingência OFFLINE (tpEmis 9) é EXCLUSIVA da NFC-e. A NF-e 55 só tem SVC (Sefaz
        // Virtual de Contingência), que não está implementada — decisão do dono do produto em
        // 2026-08-24: se a SEFAZ não responder, a venda é registrada e a nota fica em NAO_EMITIDO
        // para reprocessar em Documentos Fiscais, que é o comportamento que já existia. Por isso a
        // 55 nunca entra neste caminho.
        FiscalContingenciaService.Estado estado = pedido.modelo().ehNfe()
                ? null
                : contingencia.consultar(pedido.idEmpresa());
        boolean emContingencia = estado != null && estado.ativa();
        int tipoEmissao = emContingencia ? MontadorXmlNfce.TP_EMIS_CONTINGENCIA_OFFLINE : TP_EMIS_NORMAL;
        int serie = emContingencia ? estado.serieContingencia() : pedido.serie();

        // O CSC só entra no QR Code ONLINE (NT 2015.002 v2) — em contingência quem garante a
        // autenticidade é a assinatura do certificado, não o CSC (ver MontadorXmlNfce.qrCodeOffline).
        // Nem na NF-e 55: ela não tem QR Code (o grupo infNFeSupl não existe no XSD do 55).
        CscEmpresa csc = pedido.modelo().ehNfe() || tipoEmissao == MontadorXmlNfce.TP_EMIS_CONTINGENCIA_OFFLINE
                ? null
                : carregarCsc(pedido.idEmpresa());

        // ---------- 1. numeração — transação curta e própria (F4) ----------
        // ⚠️ Numeração é por (empresa, modelo, série): a NF-e 55 tem sequência PRÓPRIA, e é por
        // isso que o modelo entra aqui em vez do MODELO_NFCE fixo.
        NumeroReservado numero = numeracao.reservar(pedido.idEmpresa(), modelo, serie,
                pedido.ambiente() == AmbienteSefaz.PRODUCAO);

        // ---------- 2. montar, assinar e validar — nenhum I/O de rede ----------
        // ⚠️ O número JÁ ESTÁ RESERVADO daqui para baixo, e os três passos a seguir podem lançar.
        // Sem o try, uma falha de montagem, assinatura ou schema consumia o número e não deixava
        // linha nenhuma em documento_fiscal: buraco silencioso na numeração, que só reaparece
        // como pendência de inutilização perante a SEFAZ, sem ninguém saber o que houve ali
        // (pendência #71 — que apontava só a devolução; a venda tinha o mesmo defeito).
        XmlMontado montado;
        String xmlAssinado;
        try {
            montado = montador.montar(
                    new NotaParaMontar(
                            pedido.ambiente(), pedido.modelo(), numero.serie(), numero.numero(), numero.codigoNumerico(),
                            pedido.emissao(), pedido.naturezaOperacao(), tipoEmissao,
                            pedido.emitente(), dest, pedido.itens(), pedido.itensTributados(), pedido.totais(),
                            pedido.pagamentos(), pedido.troco(), pedido.informacoesComplementares(),
                            pedido.responsavelTecnico(),
                            new UrlsConsultaUf(autorizador.urlQrCode(), autorizador.urlConsultaPublica()),
                            csc, pedido.versaoAplicativo()),
                    parametros -> assinador.assinarQrCodeOffline(parametros, keystore, certificado.senha()));

            xmlAssinado = assinador.assinar(
                    montado.xml(), montado.chaveAcesso(), keystore, certificado.senha());

            // F11 — bloqueio preventivo: rejeição por schema é a mais barata de evitar, e evitá-la
            // aqui poupa a viagem de rede e a mensagem críptica que a SEFAZ devolveria.
            validador.validarNfe(xmlAssinado);
        } catch (RuntimeException e) {
            repositorio.gravarNumeroQueimado(pedido.idEmpresa(), modelo, pedido.ambiente().name(),
                    "VENDA_CONSUMIDOR", numero.serie(), numero.numero(), pedido.idVenda(),
                    pedido.idUsuario(), motivoDoNumeroQueimado(e));
            throw e;
        }

        long idDocumento = repositorio.gravarAssinado(
                pedido, numero, montado.chaveAcesso(), xmlAssinado, tipoEmissao);
        repositorio.registrarUsoDoCertificado(certificado.idCertificado(), idDocumento, "ASSINATURA");

        // Em contingência a emissão termina aqui: o cupom sai, a venda segue, e a nota entra na
        // fila. Tentar transmitir agora só devolveria o mesmo timeout que colocou a empresa em
        // contingência — e faria o caixa esperar por ele em toda venda.
        if (emContingencia) {
            repositorio.marcarEmContingencia(idDocumento);
            return ResultadoEmissao.emContingencia(idDocumento, montado.chaveAcesso());
        }

        // ---------- 3. transmitir — FORA de transação, pode levar 10 s ----------
        String url = autorizadores.urlDe(pedido.emitente().uf(), modelo,
                pedido.ambiente().codigo(), ServicoSefaz.AUTORIZACAO);
        String enviNFe = ("<enviNFe versao=\"4.00\" xmlns=\"%s\">"
                + "<idLote>%d</idLote><indSinc>1</indSinc>%s</enviNFe>")
                .formatted(MontadorXmlNfce.NS, idDocumento, xmlAssinado);

        RespostaSefaz resposta;
        repositorio.marcarTransmitindo(idDocumento);
        try {
            resposta = transporte.enviar(url, SERVICO_AUTORIZACAO, enviNFe,
                    certificado.pkcs12(), certificado.senha(), certificado.impressaoDigital());
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
            // Caminho quente do arquivamento (handoff §4.1) — best-effort, nunca lança: a nota já
            // está autorizada e o cupom já vai sair, o bucket não pode atrasar nem quebrar isso.
            arquivamento.arquivarDocumentoSeAplicavel(idDocumento);
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

    private CscEmpresa carregarCsc(long idEmpresa) {
        var csc = fiscalConfig.carregarCscParaEmissao(idEmpresa);
        return new CscEmpresa(csc.id(), csc.token());
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
            /** Motivo por extenso quando a venda exige NF-e 55 mas ela não pode sair agora
             *  ({@code emite_nfe} desligado, cadastro do cliente incompleto). {@code null} = pode
             *  emitir. ⚠️ É mensagem, não exceção: <b>F3</b> — a venda nunca deixa de ser
             *  registrada porque a nota falhou. Ver {@code VendaFiscalAssembler.impedimentoParaNfe}. */
            String impedimentoNfe,
            long idTenant,
            long idEmpresa,
            Integer idVenda,
            Integer idCliente,
            Integer idUsuario,
            AmbienteSefaz ambiente,
            /** 65 ou 55 — decidido pelo {@code VendaFiscalAssembler} a partir do
             *  {@code indicador_ie} do cliente (2026-08-24). Ver {@link ModeloVenda}. */
            ModeloVenda modelo,
            /** Já é a série do modelo escolhido: {@code serie_nfce} ou {@code serie_nfe}. */
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
            String versaoAplicativo) {
    }

    /**
     * Como a emissão terminou. A {@code mensagem} é escrita para o operador do caixa ler com
     * cliente na frente: diz o que aconteceu com a <b>venda</b>, não só com a nota.
     */
    public record ResultadoEmissao(Situacao situacao, Long idDocumentoFiscal, String chaveAcesso,
                                   String protocolo, String cStat, String mensagem,
                                   /** 65 (NFC-e) ou 55 (NF-e). O PDV usa para escolher o documento
                                    *  que imprime: DANFCE térmico ou DANFE A4 (2026-08-25). As
                                    *  fábricas abaixo nascem com 0 e o modelo é aplicado num ponto
                                    *  só, em {@link EmissaoNfceService#emitir} — assim nenhuma
                                    *  delas precisa carregar um parâmetro que não usa. */
                                   int modelo,
                                   /** ⭐ Preenchido só quando a venda tem SERVIÇO (2026-08-31): a NFC-e
                                    *  cobre apenas as mercadorias, e sem este aviso o operador vai
                                    *  embora achando que documentou a venda inteira. `null` na
                                    *  esmagadora maioria das vendas, e aí a tela não mostra nada.
                                    *  ⚠️ Desde 2026-09-01 ele fica {@code null} quando a NFS-e é
                                    *  emitida de fato: o aviso é sobre o que <b>ficou sem
                                    *  documento</b>, e mantê-lo depois de a nota sair diria ao
                                    *  operador que falta algo que não falta. */
                                   String avisoServicos,
                                   /** ⭐ As NFS-e da mesma venda (2026-09-01, pendência #78). Lista
                                    *  porque a DPS carrega UM código de serviço: uma venda de
                                    *  petshop com banho/tosa e consulta veterinária rende DUAS
                                    *  notas. Vazia quando a venda não tem serviço ou a NFS-e está
                                    *  desligada para a empresa (F12). ⚠️ Cada nota traz o próprio
                                    *  desfecho — uma falhar não impede as outras nem invalida a
                                    *  NFC-e, que é documento de outro imposto. */
                                   List<com.vetor.niner.fiscal.nfse.NfseEmissaoService.Resultado> nfse) {

        /** Cópia com o aviso de serviços fora da nota (2026-08-31) — ver
         *  {@code VendaFiscalService#avisoDeServicosForaDaNota}. */
        public ResultadoEmissao comAvisoServicos(String aviso) {
            return new ResultadoEmissao(situacao, idDocumentoFiscal, chaveAcesso, protocolo, cStat,
                    mensagem, modelo, aviso, nfse);
        }

        /** Cópia com as NFS-e da venda (2026-09-01, pendência #78). */
        public ResultadoEmissao comNfse(List<com.vetor.niner.fiscal.nfse.NfseEmissaoService.Resultado> notas) {
            return new ResultadoEmissao(situacao, idDocumentoFiscal, chaveAcesso, protocolo, cStat,
                    mensagem, modelo, avisoServicos, notas);
        }

        /** Cópia com o modelo preenchido. */
        ResultadoEmissao comModelo(int modelo) {
            return new ResultadoEmissao(situacao, idDocumentoFiscal, chaveAcesso, protocolo, cStat,
                    mensagem, modelo, avisoServicos, nfse);
        }

        public enum Situacao {
            AUTORIZADO, REJEITADO, DENEGADO, EM_PROCESSAMENTO, FALHA_COMUNICACAO, CONTINGENCIA,
            NAO_EMITIDO,
            /**
             * ⭐ A venda não tem <b>mercadoria</b> — logo não existe NFC-e/NF-e a emitir
             * (2026-09-01, pendência #78). É o caso normal de petshop e de consultório, e
             * <b>não é falha</b>: o documento dessa venda é a NFS-e, que vem no campo
             * {@code nfse} do mesmo resultado.
             *
             * <p>⚠️ Não reaproveitei {@code NAO_EMITIDO} de propósito: ele significa "a nota
             * devia sair e não saiu" e a tela o pinta de vermelho. Dizer "não emitida" de um
             * documento que não deveria existir manda o operador procurar um defeito que não há.
             */
            SEM_MERCADORIA
        }

        static ResultadoEmissao autorizado(long id, String chave, String protocolo) {
            return new ResultadoEmissao(Situacao.AUTORIZADO, id, chave, protocolo, "100",
                    "Nota autorizada.", 0, null, List.of());
        }

        /**
         * ⚠️ <b>Alguns {@code cStat} apontam para um cadastro específico, e dizer isso economiza
         * horas</b> (2026-08-29). O texto genérico <i>"corrija e emita de novo"</i> não diz o que
         * corrigir: o {@code 464} chegou ao dono do produto como uma falha misteriosa do QR Code,
         * quando a causa era o CSC gravado não conferir com o credenciado na SEFAZ — medido
         * recalculando o hash da última nota autorizada com o CSC atual, que <b>não</b> o
         * reproduziu. Mesma família do {@code cStat 974}, que fala em CNPJ e é do CSRT.
         */
        private static String dicaDoCstat(String cStat) {
            return switch (cStat) {
                case "464" -> " O hash do QR Code é calculado com o CSC: este erro significa que o CSC "
                        + "gravado não é o que está credenciado na SEFAZ. Confira o CSC e o ID do CSC em "
                        + "Fiscal › Configuração Fiscal, copiando de novo do portal da SEFAZ.";
                case "539" -> " Já existe uma nota autorizada com esta chave e conteúdo diferente —"
                        + " consulte a chave antes de reemitir.";
                default -> "";
            };
        }

        static ResultadoEmissao rejeitado(long id, String chave, String cStat, String motivo) {
            return new ResultadoEmissao(Situacao.REJEITADO, id, chave, null, cStat,
                    ("A SEFAZ rejeitou a nota: %s (%s). A venda está registrada; corrija e emita de novo."
                            + "%s").formatted(motivo, cStat, dicaDoCstat(cStat)), 0, null, List.of());
        }

        static ResultadoEmissao denegado(long id, String chave, String cStat, String motivo) {
            return new ResultadoEmissao(Situacao.DENEGADO, id, chave, null, cStat,
                    ("Nota denegada pela SEFAZ: %s (%s). Este número não pode ser reaproveitado nem "
                            + "cancelado — a venda continua registrada.").formatted(motivo, cStat), 0, null, List.of());
        }

        static ResultadoEmissao emProcessamento(long id, String chave, String motivo) {
            return new ResultadoEmissao(Situacao.EM_PROCESSAMENTO, id, chave, null, null,
                    "A SEFAZ recebeu a nota e ainda está processando (%s). Não emita de novo."
                            .formatted(motivo), 0, null, List.of());
        }

        static ResultadoEmissao falhaDeComunicacao(long id, String chave, boolean entrouEmContingencia) {
            String base = "Não foi possível falar com a SEFAZ. A venda está registrada. A nota pode "
                    + "ter sido autorizada mesmo assim — o sistema precisa consultar a chave antes "
                    + "de tentar de novo.";
            return new ResultadoEmissao(Situacao.FALHA_COMUNICACAO, id, chave, null, null,
                    entrouEmContingencia
                            ? base + " As próximas vendas sairão em CONTINGÊNCIA automaticamente."
                            : base, 0, null, List.of());
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
                            + "SEFAZ voltar.", 0, null, List.of());
        }

        static ResultadoEmissao naoEmitido(long id, String mensagem) {
            return new ResultadoEmissao(Situacao.NAO_EMITIDO, id, null, null, null, mensagem, 0, null, List.of());
        }

        /**
         * Venda sem mercadoria: não há NFC-e/NF-e a emitir, e isso <b>não é erro</b>
         * (2026-09-01, pendência #78). O documento desta venda são as NFS-e, que a mesma resposta
         * carrega em {@code nfse}.
         */
        public static ResultadoEmissao semMercadoria(long idVenda, boolean temMercadoria) {
            // ⚠️ 0, não null: é a convenção já usada pelo front para "não houve documento"
            // (`erroDeComunicacao()` faz o mesmo) e o teste `idDocumentoFiscal > 0` que decide se
            // abre o DANFE continua valendo sem precisar aprender um terceiro caso.
            //
            // ⚠️ DUAS causas, duas mensagens — e achei isto revisando o meu próprio desenho antes
            // de testar. A primeira versão tinha uma frase só ("é só de serviços"), que sairia
            // TAMBÉM para a venda que TEM mercadoria e está com a NFC-e desligada. O operador de
            // uma loja mista leria que a venda dele não tem mercadoria, o que é falso e manda o
            // diagnóstico para o lado errado.
            return new ResultadoEmissao(Situacao.SEM_MERCADORIA, 0L, null, null, null,
                    temMercadoria
                            ? "A NFC-e está desligada para esta empresa — saiu só a nota de serviço."
                            : "Venda nº " + idVenda + " é só de serviços — NFC-e/NF-e é documento de "
                                    + "mercadoria. O documento desta venda é a NFS-e.",
                    0, null, List.of());
        }

        public boolean autorizada() {
            return situacao == Situacao.AUTORIZADO;
        }
    }
}
