package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.tempo.FusoDaUf;
import com.vetor.niner.fiscal.documento.MontagemEventoDtos.EventoCancelamento;
import com.vetor.niner.fiscal.documento.MontagemEventoDtos.XmlEventoMontado;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Montagem do evento de cancelamento (110111, §10.1, bloco B8) — {@code leiauteEventoCancNFe_v1.00.xsd}.
 * Mesmo espírito do {@link MontadorXmlNfce}: puro, sem I/O, decide só a forma do XML.
 *
 * <p><b>Estrutura bem diferente da NFe</b>, apesar do mesmo namespace: elemento raiz
 * {@code <evento>}, alvo da assinatura é {@code infEvento} (não {@code infNFe}), e o {@code Id}
 * segue outra convenção — {@code "ID" + tpEvento(6) + chave(44) + nSeqEvento(2, zero-padded)} —
 * daí {@link AssinadorXmlNfe#assinarEvento} existir separado de {@link AssinadorXmlNfe#assinar}.
 */
@Component
public class MontadorEventoCancelamento {

    public static final String TP_EVENTO_CANCELAMENTO = "110111";
    private static final String VERSAO = "1.00";

    private static final DateTimeFormatter DH =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);

    public XmlEventoMontado montar(EventoCancelamento dados) {
        validar(dados);

        String justificativa = dados.justificativa().trim();
        String cnpj = apenasAlfanumerico(dados.cnpjAutor());
        int codigoUf = ChaveAcesso.codigoUfDe(dados.uf());
        // Fuso da UF do autor do evento (mesmo achado do B7: TDateTimeUTC proíbe o sufixo Z, e data
        // vinda do banco chega em UTC). Desde 2026-08-20 vem da UF, não de uma constante.
        OffsetDateTime dataLocal = dados.dataEvento()
                .atZoneSameInstant(FusoDaUf.de(dados.uf())).toOffsetDateTime();
        // "1" com 2 posições — nSeqEvento sempre 1 no cancelamento (só a CC-e, fora do v1, usa
        // sequência > 1 para correções sucessivas da mesma nota).
        String id = "ID%s%s%02d".formatted(TP_EVENTO_CANCELAMENTO, dados.chaveAcesso(), 1);

        StringBuilder xml = new StringBuilder(1024);
        xml.append("<evento xmlns=\"").append(MontadorXmlNfce.NS).append("\" versao=\"").append(VERSAO).append("\">");
        xml.append("<infEvento Id=\"").append(id).append("\">");
        xml.append(tag("cOrgao", String.valueOf(codigoUf)));
        xml.append(tag("tpAmb", String.valueOf(dados.ambiente().codigo())));
        xml.append(tag("CNPJ", cnpj));
        xml.append(tag("chNFe", dados.chaveAcesso()));
        xml.append(tag("dhEvento", dataLocal.format(DH)));
        xml.append(tag("tpEvento", TP_EVENTO_CANCELAMENTO));
        xml.append(tag("nSeqEvento", "1"));
        xml.append(tag("verEvento", VERSAO));
        xml.append("<detEvento versao=\"").append(VERSAO).append("\">");
        xml.append(tag("descEvento", "Cancelamento"));
        xml.append(tag("nProt", dados.protocoloAutorizacao()));
        xml.append(tag("xJust", escapar(justificativa)));
        xml.append("</detEvento>");
        xml.append("</infEvento>");
        xml.append("</evento>");

        return new XmlEventoMontado(id, xml.toString());
    }

    /**
     * {@code envEvento} — o envelope de transmissão, montado <b>depois</b> de assinar (o
     * {@code <evento>} que entra aqui já veio de {@link AssinadorXmlNfe#assinarEvento}). Mesmo
     * padrão do {@code enviNFe} no B7: o envelope nunca é assinado, só o conteúdo.
     */
    public String montarEnvelope(long idLote, String eventoAssinado) {
        return "<envEvento xmlns=\"%s\" versao=\"%s\"><idLote>%d</idLote>%s</envEvento>"
                .formatted(MontadorXmlNfce.NS, VERSAO, idLote, eventoAssinado);
    }

    private void validar(EventoCancelamento dados) {
        if (dados.chaveAcesso() == null || dados.chaveAcesso().length() != 44) {
            throw new MontagemNfceDtos.MontagemInvalidaException(
                    "Chave de acesso inválida para cancelamento (precisa ter 44 posições).");
        }
        if (dados.protocoloAutorizacao() == null || dados.protocoloAutorizacao().isBlank()) {
            throw new MontagemNfceDtos.MontagemInvalidaException(
                    "Cancelamento exige o protocolo de autorização da nota original.");
        }
        String justificativa = dados.justificativa() == null ? "" : dados.justificativa().trim();
        if (justificativa.length() < 15) {
            throw new MontagemNfceDtos.MontagemInvalidaException(
                    "A justificativa do cancelamento precisa ter pelo menos 15 caracteres (MOC).");
        }
        if (justificativa.length() > 255) {
            throw new MontagemNfceDtos.MontagemInvalidaException(
                    "A justificativa do cancelamento não pode passar de 255 caracteres (MOC).");
        }
    }

    private static String tag(String nome, String valor) {
        return "<" + nome + ">" + valor + "</" + nome + ">";
    }

    private static String escapar(String texto) {
        return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String apenasAlfanumerico(String valor) {
        return valor == null ? "" : valor.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }
}
