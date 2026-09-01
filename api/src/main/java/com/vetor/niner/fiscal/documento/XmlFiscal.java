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
public final class XmlFiscal {

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
     * O inverso de {@link #texto} — devolve ao texto lido de um XML já gravado os caracteres que a
     * montagem escapou.
     *
     * <p><b>Por que é público e mora aqui</b> (2026-09-01): quem LÊ o {@code infCpl} do
     * {@code xml_assinado} para mostrar num documento impresso precisa desfazer exatamente o que
     * {@link #texto} fez — e são <b>dois</b> leitores em pacotes diferentes (o DANFE A4, em
     * {@code DocumentoFiscalConsultaService}, e o cupom da NFC-e, em {@code PdvVendaService}).
     * Cada um com a sua cópia é a divergência silenciosa que o javadoc da classe já descreve: no
     * dia em que uma entidade nova entrar no escape, só um dos dois desfaz, e o papel sai com
     * {@code &amp;amp;} no meio da observação do lojista.
     *
     * <p>⚠️ A ordem importa: {@code &amp;amp;} vem por <b>último</b>. Desfazê-lo antes
     * transformaria {@code &amp;amp;lt;} — que representa o texto literal "&amp;lt;" — em "&lt;".
     */
    public static String desescapar(String valor) {
        if (valor == null) {
            return null;
        }
        return valor.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
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

    /**
     * {@code hashCSRT} do grupo {@code infRespTec} (NT 2018.005): SHA-1 de
     * {@code CSRT + chaveDeAcesso}, o digest <b>bruto</b> (20 bytes) codificado em Base-64 — 28
     * caracteres.
     *
     * <p>⚠️ As duas armadilhas: (1) é o digest binário que vai para o Base-64, <b>não</b> a
     * representação hexadecimal dele (Base-64 de hex daria 56 caracteres e a SEFAZ rejeita); (2) a
     * concatenação é CSRT seguido da chave, sem separador, e a chave são os <b>44 dígitos</b>, sem
     * o prefixo {@code NFe}. Errar qualquer um dos dois dá cStat 976 ("Rejeicao: Hash do CSRT
     * inválido"), que não diz qual dos dois foi.
     */
    static String hashCsrt(String csrt, String chaveAcesso) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest((csrt + chaveAcesso).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-1 é obrigatório em toda JVM; se faltar, o ambiente está quebrado.
            throw new IllegalStateException("SHA-1 indisponível na JVM.", e);
        }
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
