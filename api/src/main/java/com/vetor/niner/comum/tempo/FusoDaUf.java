package com.vetor.niner.comum.tempo;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

/**
 * Fuso horário de cada unidade da federação.
 *
 * <p><b>O Brasil tem 4 fusos</b> e o Nainer é vendido nos 27 entes, então "hoje", "agora" e a hora
 * impressa no cupom não podem sair de uma constante. Antes de 2026-08-20 todo o sistema convertia
 * com {@code America/Sao_Paulo} fixo — correto para 21 UFs, errado em 1 h para AM/MT/MS/RO/RR e em
 * 2 h para o AC.
 *
 * <p><b>⚠️ Decisão do dono do produto (2026-08-20): o fuso vem da UF, sem exceção por município.</b>
 * Duas manchas do mapa não cabem nessa regra, e a divergência é <b>conhecida e aceita</b>:
 * <ul>
 *   <li><b>Fernando de Noronha</b> (PE) é UTC−2, e aqui recebe o fuso de Recife (UTC−3);</li>
 *   <li>o <b>extremo oeste do Amazonas</b> (Benjamin Constant, Tabatinga, Atalaia do Norte) é
 *       UTC−5, e aqui recebe o fuso de Manaus (UTC−4).</li>
 * </ul>
 * Nesses dois bolsões o cupom sai com 1 h a menos que o relógio da parede e a venda feita entre
 * 00:00 e 01:00 cai no caixa do dia anterior. <b>Isso não é defeito — é a simplificação escolhida</b>,
 * pesada contra o custo de um campo por empresa que 99,9% das lojas nunca abririam. Se um dia
 * aparecer cliente lá, a saída é uma coluna de fuso em {@code empresa} que se sobrepõe a este mapa,
 * não mexer aqui.
 *
 * <p><b>Por que identificador IANA e não offset.</b> Guardar {@code -03:00} apodrece: o Brasil
 * aboliu o horário de verão em 2019, mas isso é lei, não física. Com o identificador, se o horário
 * de verão voltar, o {@code tzdata} do JDK resolve e nenhuma linha deste projeto muda.
 *
 * <p><b>Nota fiscal.</b> O {@code dhEmi} do XML (tipo {@code TDateTimeUTC}) carrega o offset
 * explícito — {@code AAAA-MM-DDThh:mm:ss±hh:00} —, então a SEFAZ compara <b>instantes</b> e o
 * emitente declara a <b>sua</b> hora local. É por isso que multi-fuso é suportado pelo padrão e não
 * exige nada da SEFAZ: exige que o offset seja o certo.
 */
public final class FusoDaUf {

    /**
     * Fuso padrão quando a UF não está preenchida. Vale só para <b>exibição</b> (mensagem de erro,
     * observação de auditoria): o caminho fiscal exige a UF da empresa e falha explicitamente sem
     * ela, porque nota com fuso chutado é pior que nota não emitida.
     */
    public static final ZoneId PADRAO = ZoneId.of("America/Sao_Paulo");

    private static final Map<String, ZoneId> POR_UF = Map.ofEntries(
            // UTC−5 — horário do Acre
            Map.entry("AC", ZoneId.of("America/Rio_Branco")),
            // UTC−4 — horário da Amazônia
            Map.entry("AM", ZoneId.of("America/Manaus")),
            Map.entry("MT", ZoneId.of("America/Cuiaba")),
            Map.entry("MS", ZoneId.of("America/Campo_Grande")),
            Map.entry("RO", ZoneId.of("America/Porto_Velho")),
            Map.entry("RR", ZoneId.of("America/Boa_Vista")),
            // UTC−3 — horário de Brasília
            Map.entry("AL", ZoneId.of("America/Maceio")),
            Map.entry("AP", ZoneId.of("America/Belem")),
            Map.entry("BA", ZoneId.of("America/Bahia")),
            Map.entry("CE", ZoneId.of("America/Fortaleza")),
            Map.entry("DF", ZoneId.of("America/Sao_Paulo")),
            Map.entry("ES", ZoneId.of("America/Sao_Paulo")),
            Map.entry("GO", ZoneId.of("America/Sao_Paulo")),
            Map.entry("MA", ZoneId.of("America/Fortaleza")),
            Map.entry("MG", ZoneId.of("America/Sao_Paulo")),
            Map.entry("PA", ZoneId.of("America/Belem")),
            Map.entry("PB", ZoneId.of("America/Fortaleza")),
            Map.entry("PE", ZoneId.of("America/Recife")),
            Map.entry("PI", ZoneId.of("America/Fortaleza")),
            Map.entry("PR", ZoneId.of("America/Sao_Paulo")),
            Map.entry("RJ", ZoneId.of("America/Sao_Paulo")),
            Map.entry("RN", ZoneId.of("America/Fortaleza")),
            Map.entry("RS", ZoneId.of("America/Sao_Paulo")),
            Map.entry("SC", ZoneId.of("America/Sao_Paulo")),
            Map.entry("SE", ZoneId.of("America/Maceio")),
            Map.entry("SP", ZoneId.of("America/Sao_Paulo")),
            Map.entry("TO", ZoneId.of("America/Araguaina")));

    private FusoDaUf() {
    }

    /** @throws IllegalArgumentException se a sigla não for uma das 27 — usar no caminho fiscal. */
    public static ZoneId de(String uf) {
        ZoneId fuso = POR_UF.get(uf == null ? "" : uf.trim().toUpperCase(Locale.ROOT));
        if (fuso == null) {
            throw new IllegalArgumentException("UF inválida: %s.".formatted(uf));
        }
        return fuso;
    }

    /** Igual a {@link #de}, mas cai no {@link #PADRAO} em vez de falhar — usar só para exibição. */
    public static ZoneId deOuPadrao(String uf) {
        return POR_UF.getOrDefault(uf == null ? "" : uf.trim().toUpperCase(Locale.ROOT), PADRAO);
    }
}
