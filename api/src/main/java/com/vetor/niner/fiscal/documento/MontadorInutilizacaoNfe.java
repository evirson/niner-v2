package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.MontagemInutilizacaoDtos.PedidoInutilizacao;
import com.vetor.niner.fiscal.documento.MontagemInutilizacaoDtos.XmlInutilizacaoMontado;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.MontagemInvalidaException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Montagem do pedido de inutilização de numeração (§10.4, bloco B8) — {@code inutNFe_v4.00.xsd}.
 * Mesmo espírito puro do {@link MontadorEventoCancelamento}: sem I/O, só decide a forma do XML.
 *
 * <p><b>Estrutura mais simples que o cancelamento</b>: não existe envelope de lote — o
 * {@code <inutNFe>} <i>é</i> o corpo enviado ao serviço {@code NFeInutilizacao4}, direto. O alvo
 * da assinatura é {@code infInut}, e o {@code Id} segue a convenção própria do leiaute:
 * {@code "ID" + cUF(2) + ano(2) + CNPJ(14) + mod(2) + série(3, zero-padded) + nNFIni(9, zero-padded)
 * + nNFFin(9, zero-padded)} — 43 caracteres, confirmado contra o padrão
 * {@code ID[0-9]{4}[0-9A-Z]{12}[0-9]{25}} de {@code leiauteInutNFe_v4.00.xsd}.
 */
@Component
public class MontadorInutilizacaoNfe {

    private static final String VERSAO = "4.00";

    public XmlInutilizacaoMontado montar(PedidoInutilizacao dados) {
        validar(dados);

        String justificativa = dados.justificativa().trim();
        String cnpj = apenasAlfanumerico(dados.cnpjEmitente());
        int codigoUf = ChaveAcesso.codigoUfDe(dados.uf());
        String id = "ID%02d%02d%s%02d%03d%09d%09d".formatted(
                codigoUf, dados.ano(), cnpj, dados.modelo(), dados.serie(),
                dados.numeroInicial(), dados.numeroFinal());

        StringBuilder xml = new StringBuilder(512);
        xml.append("<inutNFe xmlns=\"").append(MontadorXmlNfce.NS).append("\" versao=\"").append(VERSAO).append("\">");
        xml.append("<infInut Id=\"").append(id).append("\">");
        xml.append(tag("tpAmb", String.valueOf(dados.ambiente().codigo())));
        xml.append(tag("xServ", "INUTILIZAR"));
        xml.append(tag("cUF", "%02d".formatted(codigoUf)));
        xml.append(tag("ano", "%02d".formatted(dados.ano())));
        xml.append(tag("CNPJ", cnpj));
        xml.append(tag("mod", String.valueOf(dados.modelo())));
        xml.append(tag("serie", String.valueOf(dados.serie())));
        xml.append(tag("nNFIni", String.valueOf(dados.numeroInicial())));
        xml.append(tag("nNFFin", String.valueOf(dados.numeroFinal())));
        xml.append(tag("xJust", escapar(justificativa)));
        xml.append("</infInut>");
        xml.append("</inutNFe>");

        return new XmlInutilizacaoMontado(id, xml.toString());
    }

    private void validar(PedidoInutilizacao dados) {
        if (dados.numeroFinal() < dados.numeroInicial()) {
            throw new MontagemInvalidaException(
                    "O número final da faixa de inutilização não pode ser menor que o inicial.");
        }
        String justificativa = dados.justificativa() == null ? "" : dados.justificativa().trim();
        if (justificativa.length() < 15) {
            throw new MontagemInvalidaException(
                    "A justificativa da inutilização precisa ter pelo menos 15 caracteres (MOC).");
        }
        if (justificativa.length() > 255) {
            throw new MontagemInvalidaException(
                    "A justificativa da inutilização não pode passar de 255 caracteres (MOC).");
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
