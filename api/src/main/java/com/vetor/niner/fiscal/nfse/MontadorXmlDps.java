package com.vetor.niner.fiscal.nfse;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Monta o XML da DPS (Declaração de Prestação de Serviço), versão <b>1.01</b>.
 *
 * <p>A ordem dos elementos é a do XSD e <b>não é negociável</b>: elemento fora de lugar volta como
 * {@code E1235} ("falha no esquema"), com um campo {@code Complemento} dizendo qual elemento o
 * validador esperava — é esse complemento que aciona a correção, e por isso ele precisa chegar ao
 * usuário (ver {@code docs/MODULONFSE.md} §5, item 11).
 *
 * <h2>As regras medidas contra o SEFIN real que estão codificadas aqui</h2>
 *
 * <ol>
 *   <li><b>{@code prest} manda só o CNPJ.</b> Com {@code tpEmit=1} o SEFIN já tem os dados do
 *       emitente: {@code xNome} devolve {@code E0121} e endereço devolve {@code E0128}. A IM é
 *       condicional — em Curitiba/produção ela é <b>proibida</b> ({@code E0120}).</li>
 *   <li><b>{@code toma} exige {@code xNome}.</b> Medido em 2026-08-31: bloco só com {@code CPF}
 *       volta {@code E1235 — "the element 'toma' has incomplete content. List of possible elements
 *       expected: CAEPF, IM, xNome"}. ⚠️ O {@code MAPA.md} do {@code finance-v} marca {@code xNome}
 *       como opcional; não é. Sem nome, o bloco inteiro é omitido — a DPS sem tomador é válida.</li>
 *   <li><b>{@code pAliq} é omitido</b> quando {@code opSimpNac=3} + {@code regApTribSN=1} +
 *       {@code tpRetISSQN=1}: na apuração pelo Simples sem retenção, quem calcula é a prefeitura.
 *       Informar alíquota para não optante devolve {@code E0617}.</li>
 *   <li><b>ME/EPP usa {@code pTotTribSN}, nunca {@code indTotTrib}.</b> Medido em produção:
 *       {@code E0712 — "Para ME/EPP o indicador de informação de valor total de tributos não pode
 *       ser informado"}. E omitir o {@code totTrib} inteiro devolve {@code E1235}, porque o schema
 *       o exige. Ou seja: para optante do Simples <b>não existe emissão sem a alíquota efetiva</b>,
 *       e é por isso que ela é pré-requisito na configuração (V101).</li>
 *   <li><b>{@code tpRetISSQN} não aceita 0.</b> O enum começa em 1 (não retido).</li>
 * </ol>
 *
 * <h2>Fuso do {@code dhEmi}</h2>
 *
 * <p>Sai no <b>mesmo instante normalizado para {@code America/Sao_Paulo}</b>, e não no fuso da UF
 * do emitente como na NF-e. Razão: o SEFIN compara o {@code dhEmi} com o relógio dele (Brasília), e
 * o {@code workshop} registrou {@code E0008} ("emissão posterior ao processamento") quando o
 * horário de parede não batia. Normalizar preserva o instante <b>e</b> não depende de o SEFIN
 * honrar um offset diferente de −03:00 — o que ninguém mediu, porque nenhum dos sistemas de
 * referência tem emitente fora de Curitiba. ⚠️ Se um lojista do Acre reclamar de data, é aqui.
 */
@Component
public class MontadorXmlDps {

    public static final String NS = "http://www.sped.fazenda.gov.br/nfse";
    public static final String VERSAO = "1.01";

    /** O relógio contra o qual o SEFIN valida o dhEmi. Brasília não tem horário de verão. */
    private static final ZoneId FUSO_SEFIN = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DH_EMI =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** opSimpNac: 1 não optante · 2 MEI · 3 ME/EPP. */
    public static final int SIMPLES_NAO_OPTANTE = 1;
    public static final int SIMPLES_MEI = 2;
    public static final int SIMPLES_ME_EPP = 3;

    /** tpRetISSQN: 1 não retido · 2 retido pelo tomador · 3 pelo intermediário. Não existe 0. */
    private static final int RET_NAO_RETIDO = 1;
    private static final int RET_TOMADOR = 2;

    public String montar(DadosDps d) {
        StringBuilder x = new StringBuilder(2048);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        x.append("<DPS xmlns=\"").append(NS).append("\" versao=\"").append(VERSAO).append("\">");
        x.append("<infDPS Id=\"").append(d.idDps()).append("\">");

        x.append(tag("tpAmb", String.valueOf(d.ambienteProducao() ? 1 : 2)));
        x.append(tag("dhEmi", d.emitidoEm().atZoneSameInstant(FUSO_SEFIN).format(DH_EMI)));
        x.append(tag("verAplic", d.versaoAplicativo()));
        x.append(tag("serie", String.valueOf(d.serie())));
        x.append(tag("nDPS", String.valueOf(d.numeroDps())));
        x.append(tag("dCompet", d.competencia().toString()));
        x.append(tag("tpEmit", "1"));                       // 1 = prestador do serviço
        x.append(tag("cLocEmi", String.valueOf(d.codigoMunicipioIbge())));

        x.append(prestador(d));
        x.append(tomador(d));
        x.append(servico(d));
        x.append(valores(d));

        x.append("</infDPS></DPS>");
        return x.toString();
    }

    /**
     * ⛔ Só CNPJ, IM condicional e o regime. Nada de nome ou endereço: o SEFIN recusa
     * ({@code E0121}/{@code E0128}) porque já tem esses dados do cadastro do emitente.
     */
    private String prestador(DadosDps d) {
        StringBuilder x = new StringBuilder();
        x.append("<prest>");
        x.append(tag("CNPJ", apenasDigitos(d.cnpjEmitente())));
        if (d.enviarInscricaoMunicipal() && naoVazio(d.inscricaoMunicipal())) {
            x.append(tag("IM", d.inscricaoMunicipal().trim()));
        }
        x.append("<regTrib>");
        x.append(tag("opSimpNac", String.valueOf(d.optaSimples())));
        // regApTribSN só existe (e é obrigatório) quando opSimpNac = 3.
        if (d.optaSimples() == SIMPLES_ME_EPP) {
            x.append(tag("regApTribSN", "1"));   // 1 = apuração pelo próprio Simples
        }
        x.append(tag("regEspTrib", "0"));        // 0 = sem regime especial
        x.append("</regTrib>");
        x.append("</prest>");
        return x.toString();
    }

    /**
     * O bloco é opcional na DPS, mas <b>incompleto ele é inválido</b>: sem {@code xNome} o SEFIN
     * devolve {@code E1235}. Então ou vai completo, ou não vai.
     */
    private String tomador(DadosDps d) {
        String doc = apenasDigitos(d.documentoTomador());
        if (doc.isEmpty() || !naoVazio(d.nomeTomador())) {
            return "";
        }
        String etiqueta = doc.length() <= 11 ? "CPF" : "CNPJ";
        StringBuilder x = new StringBuilder();
        x.append("<toma>");
        x.append(tag(etiqueta, doc));
        x.append(tag("xNome", d.nomeTomador()));
        x.append("</toma>");
        return x.toString();
    }

    private String servico(DadosDps d) {
        StringBuilder x = new StringBuilder();
        x.append("<serv>");
        x.append("<locPrest>")
         .append(tag("cLocPrestacao", String.valueOf(d.codigoMunicipioPrestacao())))
         .append("</locPrest>");
        x.append("<cServ>");
        x.append(tag("cTribNac", d.codigoTributacaoNacional()));
        if (naoVazio(d.codigoTributacaoMunicipal())) {
            x.append(tag("cTribMun", d.codigoTributacaoMunicipal()));
        }
        x.append(tag("xDescServ", d.descricaoServico()));
        x.append("</cServ>");
        x.append("</serv>");
        return x.toString();
    }

    private String valores(DadosDps d) {
        StringBuilder x = new StringBuilder();
        x.append("<valores>");
        x.append("<vServPrest>").append(tag("vServ", dinheiro(d.valorServicos())))
         .append("</vServPrest>");

        // Desconto incondicionado reduz a base do ISS. Sem o bloco, a prefeitura apura sobre o
        // bruto mesmo que o comprovante mostre o desconto.
        if (positivo(d.valorDesconto())) {
            x.append("<vDescCondIncond>")
             .append(tag("vDescIncond", dinheiro(d.valorDesconto())))
             .append("</vDescCondIncond>");
        }

        int tipoRetencao = d.issRetido() ? RET_TOMADOR : RET_NAO_RETIDO;

        x.append("<trib>");
        x.append("<tribMun>");
        x.append(tag("tribISSQN", "1"));               // 1 = operação tributável
        // A ordem do XSD é tribISSQN, [.. pAliq], tpRetISSQN — pAliq no meio, nunca depois.
        if (informaAliquota(d, tipoRetencao)) {
            x.append(tag("pAliq", dinheiro(d.aliquotaIss())));
        }
        x.append(tag("tpRetISSQN", String.valueOf(tipoRetencao)));
        x.append("</tribMun>");

        x.append("<totTrib>");
        if (d.optaSimples() == SIMPLES_ME_EPP || d.optaSimples() == SIMPLES_MEI) {
            // ⛔ E0712: para ME/EPP o indTotTrib é proibido, e o totTrib é exigido pelo schema.
            // Sem a alíquota efetiva não há emissão — a configuração barra antes (F11).
            if (d.aliquotaSimplesEfetiva() == null) {
                throw new IllegalStateException(
                        "NFSE_SEM_ALIQUOTA_SIMPLES|Optante do Simples Nacional não emite NFS-e sem "
                        + "a alíquota efetiva do Simples. Ela está no extrato do PGDAS-D do mês "
                        + "anterior e é informada em Configuração da NFS-e.");
            }
            x.append(tag("pTotTribSN", dinheiro(d.aliquotaSimplesEfetiva())));
        } else {
            x.append(tag("indTotTrib", "0"));          // 0 = não informado
        }
        x.append("</totTrib>");
        x.append("</trib>");
        x.append("</valores>");
        return x.toString();
    }

    /**
     * Na apuração pelo Simples sem retenção, a alíquota <b>não</b> vai — quem calcula é a
     * prefeitura. Fora disso ela vai, quando conhecida.
     */
    private boolean informaAliquota(DadosDps d, int tipoRetencao) {
        if (d.aliquotaIss() == null) {
            return false;
        }
        boolean apuracaoPeloSimples = d.optaSimples() == SIMPLES_ME_EPP
                && tipoRetencao == RET_NAO_RETIDO;
        return !apuracaoPeloSimples;
    }

    // ---- utilitários -------------------------------------------------------------------------

    private static String tag(String nome, String valor) {
        return "<" + nome + ">" + escapar(valor) + "</" + nome + ">";
    }

    /**
     * Saneamento exigido pelo tipo {@code TString} do schema: caractere de controle e espaço no
     * começo ou no fim <b>rejeitam</b> o XML.
     */
    private static String escapar(String texto) {
        if (texto == null) {
            return "";
        }
        String limpo = texto.replaceAll("[\\u0000-\\u001F\\u007F]+", " ")
                            .replaceAll("\\s+", " ")
                            .trim();
        return limpo.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }

    private static String dinheiro(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static boolean positivo(BigDecimal valor) {
        return valor != null && valor.signum() > 0;
    }

    private static boolean naoVazio(String s) {
        return s != null && !s.isBlank();
    }

    private static String apenasDigitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    /**
     * Tudo que a DPS precisa, já resolvido — o montador não consulta banco nem decide regra de
     * negócio, só formata. Facilita o teste e mantém a montagem reproduzível.
     */
    public record DadosDps(
            String idDps,
            boolean ambienteProducao,
            OffsetDateTime emitidoEm,
            String versaoAplicativo,
            int serie,
            long numeroDps,
            LocalDate competencia,
            int codigoMunicipioIbge,
            // Prestador
            String cnpjEmitente,
            String inscricaoMunicipal,
            boolean enviarInscricaoMunicipal,
            int optaSimples,
            BigDecimal aliquotaSimplesEfetiva,
            // Tomador (os dois nulos = DPS sem tomador, que é válida)
            String documentoTomador,
            String nomeTomador,
            // Serviço
            int codigoMunicipioPrestacao,
            String codigoTributacaoNacional,
            String codigoTributacaoMunicipal,
            String descricaoServico,
            // Valores
            BigDecimal valorServicos,
            BigDecimal valorDesconto,
            BigDecimal aliquotaIss,
            boolean issRetido) {
    }
}
