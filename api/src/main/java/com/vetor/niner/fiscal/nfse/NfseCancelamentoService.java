package com.vetor.niner.fiscal.nfse;

import com.vetor.niner.comum.armazenamento.AreaPrivada;
import com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.Set;

/**
 * Cancelamento da NFS-e — evento <b>101101</b>.
 *
 * <h2>O prazo é MUNICIPAL, e o que não se sabe não se afirma</h2>
 *
 * <p>24 h em Curitiba, 5 dias no padrão nacional, outros valores em outras cidades. Quando
 * {@code cfg_municipio_nfse} não tem o prazo, isto <b>não bloqueia</b> — avisa que o prazo não foi
 * confirmado e deixa o SEFIN decidir. Inventar um número seria pior: barraria cancelamento
 * legítimo com uma regra que não existe.
 *
 * <h2>⭐ Como se sabe que o cancelamento REGISTROU</h2>
 *
 * <p>HTTP 201 diz que o pedido foi aceito. Quem prova que ele ficou é o segundo envio: o SEFIN
 * responde {@code E0840} — <i>"o evento de Cancelamento já está vinculado à NFS-e"</i>. Por isso
 * este serviço trata {@code E0840} como <b>sucesso idempotente</b>, e não como erro: a nota está
 * cancelada, que é o que o usuário pediu.
 */
@Service
public class NfseCancelamentoService {

    private static final Logger log = LoggerFactory.getLogger(NfseCancelamentoService.class);
    private static final String TIPO_EVENTO = "101101";
    private static final ZoneId FUSO_SEFIN = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DH =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** Anexo II: 1 Erro na Emissão · 2 Serviço não Prestado · 9 Outros. Não há outros valores. */
    private static final Set<Integer> MOTIVOS = Set.of(1, 2, 9);

    private final NfseDocumentoRepositorio repositorio;
    private final IdDps idDps;
    private final AssinadorXmlDps assinador;
    private final EmpacotadorDps empacotador;
    private final EmissorDeNfse emissor;
    private final FiscalCertificadoService certificados;
    private final ArmazenamentoPrivado armazenamento;
    private final MunicipioNfseService municipios;

    @Value("${niner.nfse.versao-aplicativo:Nainer-1.0}")
    private String versaoAplicativo;

    public NfseCancelamentoService(NfseDocumentoRepositorio repositorio, IdDps idDps,
                                   AssinadorXmlDps assinador, EmpacotadorDps empacotador,
                                   EmissorDeNfse emissor, FiscalCertificadoService certificados,
                                   ArmazenamentoPrivado armazenamento,
                                   MunicipioNfseService municipios) {
        this.repositorio = repositorio;
        this.idDps = idDps;
        this.assinador = assinador;
        this.empacotador = empacotador;
        this.emissor = emissor;
        this.certificados = certificados;
        this.armazenamento = armazenamento;
        this.municipios = municipios;
    }

    public NfseEmissaoService.Resultado cancelar(long idNfse, int codigoMotivo, String motivo,
                                                 String cnpjEmitente, Long idUsuario) {
        var doc = repositorio.buscar(idNfse).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "NFS-e não encontrada"));

        exigirCancelavel(doc, codigoMotivo, motivo);
        avisarSePrazoVencido(doc);

        String idPedido = idDps.montarIdEvento(doc.chaveAcesso(), TIPO_EVENTO);
        String xml = montarEvento(idPedido, doc, codigoMotivo, motivo, cnpjEmitente);

        var certificado = certificados.carregarAtivoParaAssinatura(doc.idEmpresa());
        var chaves = abrir(certificado);
        var credencial = new EmissorDeNfse.Credencial(certificado.pkcs12(), certificado.senha(),
                certificado.impressaoDigital(), "PRODUCAO".equals(doc.ambiente()));

        String assinado = assinador.assinar(xml, "infPedReg", idPedido,
                chaves.chave(), chaves.certificado());

        RespostaSefin resposta = emissor.registrarEvento(new EmissorDeNfse.EnvioEvento(
                doc.chaveAcesso(), empacotador.empacotar(assinado), credencial));

        // ⭐ E0840 = já estava cancelada. É o desfecho que o usuário queria, não um erro.
        boolean jaCancelada = "E0840".equals(resposta.primeiroCodigo());
        if (!resposta.sucesso() && !jaCancelada) {
            repositorio.registrarEvento(idNfse, TIPO_EVENTO, idPedido, codigoMotivo, motivo,
                    "REJEITADO", resposta.primeiroCodigo(), resposta.mensagem(), null, idUsuario);
            return new NfseEmissaoService.Resultado(idNfse, doc.situacao(), doc.chaveAcesso(),
                    doc.numeroNfse(), resposta.primeiroCodigo(), resposta.mensagem());
        }

        String xmlChave = guardarXml(idNfse, resposta);
        repositorio.registrarEvento(idNfse, TIPO_EVENTO, idPedido, codigoMotivo, motivo,
                "REGISTRADO", resposta.primeiroCodigo(), null, xmlChave, idUsuario);
        repositorio.marcarCancelada(idNfse, xmlChave);

        return new NfseEmissaoService.Resultado(idNfse, "CANCELADA", doc.chaveAcesso(),
                doc.numeroNfse(), null,
                jaCancelada ? "Esta NFS-e já estava cancelada na prefeitura."
                            : "NFS-e cancelada.");
    }

    private void exigirCancelavel(NfseDocumentoRepositorio.Documento doc, int codigoMotivo,
                                  String motivo) {
        if (!"AUTORIZADA".equals(doc.situacao())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Só NFS-e autorizada pode ser cancelada. Esta está " + doc.situacao().toLowerCase()
                    + (("REJEITADA".equals(doc.situacao()))
                        ? " — nota rejeitada nunca existiu na prefeitura, não há o que cancelar." : "."));
        }
        if (!MOTIVOS.contains(codigoMotivo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Motivo de cancelamento inválido. Os aceitos são 1 (erro na emissão), "
                    + "2 (serviço não prestado) e 9 (outros).");
        }
        // ⚠️ A faixa 15..255 é do XSD. Barrar aqui evita o E1235 chegar DEPOIS de o operador ter
        // digitado e a nota ter ido — e a mensagem diz quantos caracteres faltam.
        int tamanho = motivo == null ? 0 : motivo.trim().length();
        if (tamanho < 15 || tamanho > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O motivo do cancelamento precisa ter entre 15 e 255 caracteres (tem "
                    + tamanho + "). É exigência do layout nacional, não deste sistema.");
        }
    }

    /**
     * ⚠️ Avisa, não bloqueia. O prazo é municipal e pode ser desconhecido; e mesmo conhecido,
     * quem decide se aceita fora do prazo é a prefeitura — barrar aqui inventaria uma regra
     * nossa em cima da dela.
     */
    private void avisarSePrazoVencido(NfseDocumentoRepositorio.Documento doc) {
        municipios.prazoCancelamentoHoras(doc.codigoMunicipioIbge(),
                        "PRODUCAO".equals(doc.ambiente()))
                .ifPresent(horas -> {
                    if (doc.dataAutorizacao() != null
                            && Duration.between(doc.dataAutorizacao(), OffsetDateTime.now())
                                       .toHours() > horas) {
                        log.info("[nfse] cancelamento da nota {} fora do prazo municipal de {}h — "
                                + "enviando assim mesmo, quem decide é a prefeitura",
                                doc.idNfse(), horas);
                    }
                });
    }

    private String montarEvento(String idPedido, NfseDocumentoRepositorio.Documento doc,
                                int codigoMotivo, String motivo, String cnpjEmitente) {
        String dh = OffsetDateTime.now().atZoneSameInstant(FUSO_SEFIN).format(DH);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<pedRegEvento xmlns=\"" + MontadorXmlDps.NS + "\" versao=\"" + MontadorXmlDps.VERSAO + "\">"
                + "<infPedReg Id=\"" + idPedido + "\">"
                + "<tpAmb>" + ("PRODUCAO".equals(doc.ambiente()) ? 1 : 2) + "</tpAmb>"
                + "<verAplic>" + versaoAplicativo + "</verAplic>"
                + "<dhEvento>" + dh + "</dhEvento>"
                + "<CNPJAutor>" + cnpjEmitente.replaceAll("\\D", "") + "</CNPJAutor>"
                + "<chNFSe>" + doc.chaveAcesso() + "</chNFSe>"
                + "<e101101>"
                + "<xDesc>Cancelamento de NFS-e</xDesc>"   // xDesc: faixa 5..60 no XSD
                + "<cMotivo>" + codigoMotivo + "</cMotivo>"
                + "<xMotivo>" + escapar(motivo.trim()) + "</xMotivo>"
                + "</e101101></infPedReg></pedRegEvento>";
    }

    private String guardarXml(long idNfse, RespostaSefin resposta) {
        if (resposta.xmlGZipB64() == null) {
            return null;
        }
        try {
            String xml = empacotador.desempacotar(resposta.xmlGZipB64());
            return armazenamento.gravar(AreaPrivada.FISCAL_XML,
                    "nfse/evento-" + idNfse + "-" + TIPO_EVENTO + ".xml",
                    xml.getBytes(StandardCharsets.UTF_8), "application/xml");
        } catch (RuntimeException e) {
            // O cancelamento já valeu na prefeitura; perdê-lo aqui por causa do arquivo seria pior.
            log.error("[nfse] cancelamento da nota {} registrado, mas o XML não foi arquivado: {}",
                    idNfse, e.getMessage(), e);
            return null;
        }
    }

    private static String escapar(String texto) {
        return texto.replaceAll("[\\u0000-\\u001F\\u007F]+", " ").replaceAll("\\s+", " ").trim()
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

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
            throw new IllegalStateException("Falha ao abrir o certificado: " + e.getMessage(), e);
        }
    }

    private record ChavesDoCertificado(PrivateKey chave, X509Certificate certificado) {
    }
}
