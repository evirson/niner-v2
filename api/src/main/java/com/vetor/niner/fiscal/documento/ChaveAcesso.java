package com.vetor.niner.fiscal.documento;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Chave de acesso da NF-e/NFC-e — 44 posições (docs/MODULOFISCAL.md §9.3).
 *
 * <p>Layout: {@code cUF(2) + AAMM(4) + CNPJ(14) + mod(2) + serie(3) + nNF(9) + tpEmis(1) +
 * cNF(8) + cDV(1)}.
 *
 * <p><b>O CNPJ pode ser alfanumérico</b> (IN RFB 2.229/2024) e a chave acomoda isso nativamente —
 * confirmado no pattern do XSD oficial (`leiauteNFe_v4.00.xsd`, tipo da chave):
 * {@code [0-9]{6}[0-9A-Z]{12}[0-9]{16}(1|3|4|9)[0-9]{9}}. Lendo o pattern por partes:
 * as 6 primeiras (cUF+AAMM) numéricas, as <b>12 seguintes aceitam A-Z</b> (raiz+ordem do CNPJ),
 * as 16 seguintes numéricas (DV do CNPJ + mod + série + nNF), depois tpEmis e as 9 finais
 * (cNF + cDV). Fonte primária — fecha a pergunta §9.3 da F0.
 *
 * <p>⚠️ <b>O que o pattern NÃO diz</b> é como o módulo 11 do {@code cDV} trata uma letra. Aqui se
 * usa a mesma convenção do próprio CNPJ alfanumérico (valor = ASCII − 48, então '0'-'9' → 0-9 e
 * 'A'-'Z' → 17-42), que é a única coerente com o resto da norma e não muda nada para CNPJ
 * numérico. <b>Confirmar contra uma chave real de emitente com CNPJ alfanumérico antes de
 * produção</b> — só afeta empresa aberta a partir de julho/2026.
 */
public final class ChaveAcesso {

    private static final DateTimeFormatter AAMM = DateTimeFormatter.ofPattern("yyMM", Locale.ROOT);

    /** {@code cUF}/{@code cOrgao} — código IBGE da UF. Único mapa do módulo (era duplicado em
     *  {@code MontadorXmlNfce} até o B8, quando o montador de evento também passou a precisar). */
    private static final Map<String, Integer> CODIGO_UF = Map.ofEntries(
            Map.entry("RO", 11), Map.entry("AC", 12), Map.entry("AM", 13), Map.entry("RR", 14),
            Map.entry("PA", 15), Map.entry("AP", 16), Map.entry("TO", 17), Map.entry("MA", 21),
            Map.entry("PI", 22), Map.entry("CE", 23), Map.entry("RN", 24), Map.entry("PB", 25),
            Map.entry("PE", 26), Map.entry("AL", 27), Map.entry("SE", 28), Map.entry("BA", 29),
            Map.entry("MG", 31), Map.entry("ES", 32), Map.entry("RJ", 33), Map.entry("SP", 35),
            Map.entry("PR", 41), Map.entry("SC", 42), Map.entry("RS", 43), Map.entry("MS", 50),
            Map.entry("MT", 51), Map.entry("GO", 52), Map.entry("DF", 53));

    private ChaveAcesso() {
    }

    /** Código IBGE da UF (2 dígitos) — usado tanto na chave quanto no {@code cOrgao} do evento. */
    public static int codigoUfDe(String uf) {
        Integer codigo = CODIGO_UF.get(uf == null ? "" : uf.trim().toUpperCase(Locale.ROOT));
        if (codigo == null) {
            throw new IllegalArgumentException("UF inválida: %s.".formatted(uf));
        }
        return codigo;
    }

    /**
     * Monta a chave completa (43 posições + DV).
     *
     * @param codigoUf   código IBGE da UF do emitente (2 dígitos, ex.: 41 = PR)
     * @param emissao    data/hora de emissão — só ano e mês entram na chave
     * @param cnpj       CNPJ do emitente, só alfanumérico (14 posições, sem máscara)
     * @param modelo     55 (NF-e) ou 65 (NFC-e)
     * @param serie      série do documento (1-999)
     * @param numero     número do documento (nNF)
     * @param tpEmis     1 normal · 3 SCAN · 4 EPEC · 9 contingência offline
     * @param codigoNumerico cNF — 8 dígitos, o "código numérico" aleatório do MOC
     */
    public static String montar(int codigoUf, OffsetDateTime emissao, String cnpj, int modelo,
                                int serie, long numero, int tpEmis, int codigoNumerico) {
        if (cnpj == null || cnpj.length() != 14) {
            throw new IllegalArgumentException("CNPJ do emitente precisa ter 14 posições para a chave de acesso.");
        }
        String base = "%02d%s%s%02d%03d%09d%d%08d".formatted(
                codigoUf, emissao.format(AAMM), cnpj, modelo, serie, numero, tpEmis, codigoNumerico);
        if (base.length() != 43) {
            throw new IllegalStateException("Chave de acesso montada com %d posições (esperado 43): %s"
                    .formatted(base.length(), base));
        }
        return base + digitoVerificador(base);
    }

    /**
     * Dígito verificador (módulo 11, pesos 2..9 da direita para a esquerda) — MOC.
     *
     * <p>O valor de cada caractere é {@code ASCII − 48}: dígitos viram o próprio valor e letras
     * viram 17-42, a mesma tabela do CNPJ alfanumérico (ver {@code Documentos.digitos}). Resto
     * 0 ou 1 ⇒ DV 0.
     */
    public static char digitoVerificador(String base43) {
        int soma = 0;
        int peso = 2;
        for (int i = base43.length() - 1; i >= 0; i--) {
            soma += (base43.charAt(i) - '0') * peso;
            peso = (peso == 9) ? 2 : peso + 1;
        }
        int resto = soma % 11;
        return (char) ('0' + (resto == 0 || resto == 1 ? 0 : 11 - resto));
    }
}
