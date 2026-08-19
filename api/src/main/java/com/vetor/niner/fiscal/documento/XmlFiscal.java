package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.MontagemNfceDtos.MontagemInvalidaException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Formatação e escape de XML fiscal — <b>fonte única</b> para todos os montadores do módulo
 * (NFC-e modelo 65, NF-e modelo 55 de devolução, eventos, inutilização).
 *
 * <p><b>Por que existe</b> (2026-08-19, extraído de {@code MontadorXmlNfce} quando o montador da
 * NF-e de devolução chegou): estas regras são pequenas e parecem triviais, mas cada uma nasceu de
 * um detalhe do XSD ou de um bug real — em especial {@link #texto} (o {@code &} de "SABÃO P&G",
 * que sozinho quebra o XML de uma nota só) e {@link #dec} (o pattern do XSD recusa zero à esquerda
 * e exige as casas decimais completas). Duplicá-las por montador é convidar a divergência
 * silenciosa: o montador novo escaparia diferente do antigo e ninguém notaria até a SEFAZ rejeitar
 * — ou pior, aceitar uma nota com o texto errado.
 */
final class XmlFiscal {

    private XmlFiscal() {
    }

    static String tag(String nome, String valor) {
        return "<" + nome + ">" + valor + "</" + nome + ">";
    }

    /** Emite a tag só quando há valor — campo opcional vazio é omitido, nunca emitido vazio. */
    static String opcional(String nome, String valor) {
        return vazio(valor) ? "" : tag(nome, texto(valor));
    }

    /**
     * Escapa o que o XML não aceita cru. Texto de produto vem do cadastro do lojista e pode ter
     * {@code &} ("SABÃO P&G"), que sozinho quebra o XML — e quebraria só na nota daquele item,
     * o tipo de bug que só aparece em produção.
     */
    static String texto(String valor) {
        if (valor == null) {
            return "";
        }
        return valor.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Formata para o pattern do XSD: {@code 0|0\.[0-9]{2}|[1-9][0-9]{0,12}(\.[0-9]{2})?} — ou
     * seja, sem zero à esquerda e sempre com as casas decimais completas.
     * {@code BigDecimal.setScale().toPlainString()} já produz exatamente isso.
     */
    static String dec(BigDecimal valor, int casas) {
        return nz(valor).setScale(casas, RoundingMode.HALF_UP).toPlainString();
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static boolean positivo(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    static <T> T ouEntao(T valor, T alternativa) {
        return valor == null ? alternativa : valor;
    }

    static String apenasDigitos(String s) {
        return s == null ? null : s.replaceAll("\\D", "");
    }

    /** CNPJ/IE podem ser alfanuméricos (IN RFB 2.229/2024) — nunca limpar com digits-only. */
    static String apenasAlfanumerico(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
    }

    /** Código IBGE da UF (`cUF`) — mapa único do módulo, ver {@link ChaveAcesso#codigoUfDe}. */
    static int codigoUfDe(String uf) {
        try {
            return ChaveAcesso.codigoUfDe(uf);
        } catch (IllegalArgumentException e) {
            throw new MontagemInvalidaException("UF do emitente inválida: %s.".formatted(uf));
        }
    }
}
