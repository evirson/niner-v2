package com.vetor.niner.fiscal.nfse;

import com.vetor.niner.comum.armazenamento.AreaPrivada;
import com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado;
import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Orquestra a emissão da NFS-e de uma venda.
 *
 * <h2>⚠️ As fronteiras de transação são o desenho, não detalhe</h2>
 *
 * <p>Nenhuma chamada de rede acontece dentro de transação de banco (F2). Este método público
 * <b>não</b> é {@code @Transactional}: cada passo que grava abre a sua, curta, pelo repositório.
 * Se o SEFIN demorar 60 s, nenhuma linha fica travada esse tempo — o balcão continua vendendo.
 *
 * <p>⚠️ E é por isso que {@code marcarTransmitindo} commita <b>antes</b> do envio: participasse ele
 * da transação do resultado, uma queda no meio deixaria a nota em {@code ASSINADA} sem registro de
 * que uma tentativa saiu — e a próxima reenviaria sem saber que a primeira pode ter chegado.
 *
 * <h2>⭐ Recuperação de nota órfã</h2>
 *
 * <p>Timeout <b>não</b> significa que a nota não existe: o SEFIN pode tê-la gerado e a resposta
 * ter se perdido. Reenviar cego devolve {@code E0014} e trava a nota para sempre. O caminho é
 * {@code GET /dps/{id}}, possível porque o {@code Id} é determinístico. ⛔ Não confundir com a
 * chave de acesso, que <b>não</b> é derivável.
 *
 * <h2>O que se guarda é o XML que o SEFIN DEVOLVE</h2>
 *
 * <p>Não o que enviamos: é o devolvido que traz a assinatura da Sefin e vale como prova fiscal
 * pelos 5 anos, em {@link AreaPrivada#FISCAL_XML} (imutável, recusa sobrescrever e apagar). O
 * script do S0 gravava só o enviado — omissão registrada em {@code docs/MODULONFSE.md} §2.7
 * justamente para não se repetir aqui.
 */
@Service
public class NfseEmissaoService {

    private static final Logger log = LoggerFactory.getLogger(NfseEmissaoService.class);

    private final VendaNfseAssembler assembler;
    private final NfseNumeracaoService numeracao;
    private final NfseDocumentoRepositorio repositorio;
    private final IdDps idDps;
    private final MontadorXmlDps montador;
    private final AssinadorXmlDps assinador;
    private final EmpacotadorDps empacotador;
    private final EmissorDeNfse emissor;
    private final FiscalCertificadoService certificados;
    private final ArmazenamentoPrivado armazenamento;
    private final FusoDaLoja fusoDaLoja;
    private final MunicipioNfseService municipios;

    @Value("${niner.nfse.versao-aplicativo:Nainer-1.0}")
    private String versaoAplicativo;

    public NfseEmissaoService(VendaNfseAssembler assembler, NfseNumeracaoService numeracao,
                              NfseDocumentoRepositorio repositorio, IdDps idDps,
                              MontadorXmlDps montador, AssinadorXmlDps assinador,
                              EmpacotadorDps empacotador, EmissorDeNfse emissor,
                              FiscalCertificadoService certificados,
                              ArmazenamentoPrivado armazenamento, FusoDaLoja fusoDaLoja,
                              MunicipioNfseService municipios) {
        this.assembler = assembler;
        this.numeracao = numeracao;
        this.repositorio = repositorio;
        this.idDps = idDps;
        this.montador = montador;
        this.assinador = assinador;
        this.empacotador = empacotador;
        this.emissor = emissor;
        this.certificados = certificados;
        this.armazenamento = armazenamento;
        this.fusoDaLoja = fusoDaLoja;
        this.municipios = municipios;
    }

    /**
     * Emite todas as NFS-e de uma venda — <b>uma por código de serviço</b>.
     *
     * <p>⚠️ Uma nota que falha <b>não impede</b> as outras: a venda de oficina com dois códigos
     * tem de conseguir emitir o que dá. O resultado diz o desfecho de cada uma, e o que ficou
     * pendente aparece na fila para reprocessamento — a consequência de tela da DS13, porque nota
     * que ninguém emitiu por esquecimento é pior que nota que falhou: não aparece em lugar nenhum.
     */
    public List<Resultado> emitirDaVenda(long idVenda) {
        VendaNfseAssembler.PlanoDeEmissao plano = assembler.montar(idVenda);
        var cab = plano.cabecalho();

        // O certificado é lido UMA vez para a venda inteira: são N notas do mesmo emitente, e
        // decifrar o .pfx a cada uma seria trabalho repetido com segredo em memória (F7).
        var certificado = certificados.carregarAtivoParaAssinatura(cab.idEmpresa());
        var chaves = abrir(certificado);
        var credencial = new EmissorDeNfse.Credencial(certificado.pkcs12(), certificado.senha(),
                certificado.impressaoDigital(), cab.ambienteProducao());
        boolean enviarIm = municipios.enviarInscricaoMunicipal(
                cab.codigoMunicipioIbge(), cab.ambienteProducao());

        List<Resultado> resultados = new ArrayList<>();
        for (var nota : plano.notas()) {
            resultados.add(emitirUma(idVenda, cab, nota, credencial, chaves, enviarIm,
                    certificado.idCertificado()));
        }
        return resultados;
    }

    private Resultado emitirUma(long idVenda, VendaNfseAssembler.Cabecalho cab,
                                VendaNfseAssembler.DpsDaVenda nota,
                                EmissorDeNfse.Credencial credencial, ChavesDoCertificado chaves,
                                boolean enviarIm, long idCertificado) {
        // 1. Número e linha em RASCUNHO. Reusa a linha (e o número) se já existir — DPS rejeitada
        //    não queima número no SEFIN, então a segunda tentativa vai com o mesmo Id.
        var jaExiste = repositorio.daVenda(idVenda).stream()
                .filter(d -> d.codigoTributacaoNacional().equals(nota.codigoTributacaoNacional()))
                .findFirst();
        if (jaExiste.isPresent() && ehFinal(jaExiste.get().situacao())) {
            var d = jaExiste.get();
            return new Resultado(d.idNfse(), d.situacao(), d.chaveAcesso(), d.numeroNfse(),
                    d.codigoStatus(), "Esta nota já está " + d.situacao().toLowerCase() + ".");
        }

        long numeroDps = jaExiste.map(NfseDocumentoRepositorio.Documento::numeroDps)
                // ⭐ O ambiente entra na reserva (V106): homologação e produção têm sequências
                // separadas no SEFIN, e sem isso a nota de teste queimava o nDPS de produção.
                .orElseGet(() -> numeracao.reservar(cab.idEmpresa(), cab.serie(), cab.ambienteProducao()));
        String id = idDps.montar(cab.codigoMunicipioIbge(), cab.cnpj(), cab.serie(), numeroDps);
        OffsetDateTime agora = OffsetDateTime.now();

        long idNfse = repositorio.criarOuRecuperarRascunho(new NfseDocumentoRepositorio.NovoDocumento(
                cab.idEmpresa(), idVenda, cab.serie(), numeroDps, id, cab.ambienteProducao(),
                cab.codigoMunicipioIbge(), cab.competencia(fusoDaLoja.da(cab.idEmpresa())), agora,
                nota.codigoTributacaoNacional(), nota.codigoTributacaoMunicipal(),
                nota.descricaoServico(), nota.valorServicos(), nota.valorDesconto(),
                nota.baseCalculo(), nota.aliquotaIss(), nota.issRetido(), cab.optaSimples(),
                cab.aliquotaSimplesEfetiva(), idCertificado));

        repositorio.gravarItens(idNfse, nota.linhas().stream()
                .map(l -> new NfseDocumentoRepositorio.ItemGravavel(
                        l.idVariacao(), l.descricao(), l.quantidade(), l.precoVenda(),
                        l.valorDesconto(),
                        l.precoVenda().multiply(l.quantidade()).subtract(l.valorDesconto())))
                .toList());

        // 2. Montar, assinar, empacotar — tudo em memória, sem rede e sem transação aberta.
        String xml = montador.montar(new MontadorXmlDps.DadosDps(
                id, cab.ambienteProducao(), agora, versaoAplicativo, cab.serie(), numeroDps,
                cab.competencia(fusoDaLoja.da(cab.idEmpresa())), cab.codigoMunicipioIbge(),
                cab.cnpj(), cab.inscricaoMunicipal(), enviarIm, cab.optaSimples(),
                cab.aliquotaSimplesEfetiva(), cab.cpfCnpjCliente(), cab.nomeCliente(),
                cab.codigoMunicipioIbge(), nota.codigoTributacaoNacional(),
                nota.codigoTributacaoMunicipal(), nota.descricaoServico(), nota.valorServicos(),
                nota.valorDesconto(), nota.aliquotaIss(), nota.issRetido()));

        String assinado = assinador.assinar(xml, "infDPS", id, chaves.chave(), chaves.certificado());
        repositorio.marcarAssinada(idNfse);

        // 3. Transmitir. O marcarTransmitindo commita ANTES — ver o javadoc da classe.
        repositorio.marcarTransmitindo(idNfse);
        RespostaSefin resposta;
        try {
            resposta = emissor.emitir(new EmissorDeNfse.EnvioDps(
                    id, empacotador.empacotar(assinado), credencial));
        } catch (NfseTransporte.FalhaDeComunicacaoException falha) {
            // ⭐ Pode ter virado nota do outro lado. Perguntar é barato; reenviar cego custa E0014.
            resposta = recuperarOrfa(id, credencial, falha);
        }

        return gravarDesfecho(idNfse, resposta);
    }

    /**
     * Depois de uma falha de comunicação, pergunta ao SEFIN se aquela DPS virou nota.
     *
     * <p>Se virou, a emissão foi bem-sucedida e só a resposta se perdeu — tratar como falha
     * deixaria uma nota válida invisível no ERP. Se não virou, a falha é real e a nota volta para
     * a fila com o mesmo número.
     */
    private RespostaSefin recuperarOrfa(String id, EmissorDeNfse.Credencial credencial,
                                        NfseTransporte.FalhaDeComunicacaoException falha) {
        try {
            String chave = emissor.consultarChavePorDps(id, credencial);
            if (chave != null) {
                log.warn("[nfse] resposta perdida, mas a DPS {} virou a nota {} — recuperada", id, chave);
                return new RespostaSefin(200, true, chave, null, null, null,
                        "Nota recuperada por consulta: a resposta da emissão se perdeu, mas a "
                        + "NFS-e existe.", false, "");
            }
        } catch (RuntimeException aoConsultar) {
            log.warn("[nfse] consulta de recuperação da DPS {} também falhou: {}", id,
                    aoConsultar.getMessage());
        }
        return new RespostaSefin(0, false, null, null, null, null, falha.getMessage(), true, "");
    }

    private Resultado gravarDesfecho(long idNfse, RespostaSefin resposta) {
        if (resposta.sucesso() && resposta.chaveAcesso() != null) {
            String xmlChave = guardarXml(idNfse, resposta, "nfse");
            repositorio.marcarAutorizada(idNfse, resposta.chaveAcesso(), resposta.numeroNfse(),
                    xmlChave, resposta.primeiroCodigo());
            return new Resultado(idNfse, "AUTORIZADA", resposta.chaveAcesso(),
                    resposta.numeroNfse(), null, "NFS-e autorizada.");
        }
        if (resposta.falhaDeComunicacao()) {
            // ⚠️ Pendente, NÃO rejeitada: o número continua valendo e o reenvio usa o mesmo.
            repositorio.marcarPendente(idNfse, resposta.mensagem());
            return new Resultado(idNfse, "PENDENTE", null, null, null, resposta.mensagem());
        }
        repositorio.marcarRejeitada(idNfse, resposta.primeiroCodigo(), resposta.mensagem());
        return new Resultado(idNfse, "REJEITADA", null, null, resposta.primeiroCodigo(),
                resposta.mensagem());
    }

    /**
     * Guarda o XML devolvido pelo SEFIN. ⚠️ Falha aqui <b>não</b> derruba a emissão: a nota já
     * existe na prefeitura, e perder a nota no ERP por causa do arquivo seria trocar um problema
     * pequeno por um grande (F3). Fica registrado no log e o arquivamento é retentável.
     */
    private String guardarXml(long idNfse, RespostaSefin resposta, String prefixo) {
        if (resposta.xmlGZipB64() == null) {
            return null;
        }
        try {
            String xml = empacotador.desempacotar(resposta.xmlGZipB64());
            return armazenamento.gravar(AreaPrivada.FISCAL_XML,
                    "nfse/" + prefixo + "-" + idNfse + ".xml",
                    xml.getBytes(StandardCharsets.UTF_8), "application/xml");
        } catch (RuntimeException e) {
            log.error("[nfse] NFS-e {} autorizada, mas o XML não foi arquivado: {}",
                    idNfse, e.getMessage(), e);
            return null;
        }
    }

    private static boolean ehFinal(String situacao) {
        return "AUTORIZADA".equals(situacao) || "CANCELADA".equals(situacao);
    }

    /** Abre o {@code .pfx} uma vez e devolve o par que assina. Nada disto vai para log (F7). */
    private ChavesDoCertificado abrir(FiscalCertificadoService.CertificadoParaAssinatura cert) {
        try {
            char[] senha = cert.senha().toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(cert.pkcs12()), senha);
            String alias = null;
            for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
                String a = e.nextElement();
                if (ks.isKeyEntry(a)) {
                    alias = a;
                    break;
                }
            }
            if (alias == null) {
                throw new IllegalStateException("Certificado sem chave privada");
            }
            return new ChavesDoCertificado((PrivateKey) ks.getKey(alias, senha),
                    (X509Certificate) ks.getCertificate(alias));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Falha ao abrir o certificado para assinar a NFS-e: " + e.getMessage(), e);
        }
    }

    private record ChavesDoCertificado(PrivateKey chave, X509Certificate certificado) {
    }

    /** O desfecho de uma NFS-e. */
    public record Resultado(long idNfse, String situacao, String chaveAcesso, Long numeroNfse,
                            String codigo, String mensagem) {
    }
}
