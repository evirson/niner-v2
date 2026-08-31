package com.vetor.niner.fiscal.nfse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Cliente do <b>ADN — Parâmetros Municipais</b>. É a peça que faz a configuração ser autoatendida.
 *
 * <p>⭐ Em vez de perguntar ao lojista a alíquota do ISS (que ele não sabe) ou pedir ao suporte que
 * procure na lei municipal, <b>o sistema pergunta à fonte</b> e preenche, mostrando de onde veio.
 * Nem o {@code finance-v} nem o {@code workshop} usam esta API — {@code grep} nos dois devolve
 * zero.
 *
 * <h2>⛔ Fica em OUTRO host, e é por isso que o mapa de referência erra</h2>
 *
 * <pre>
 *   ⛔ errado   https://sefin.nfse.gov.br/SefinNacional/parametros_municipais/...
 *   ✅ certo    https://adn.nfse.gov.br/parametrizacao/...
 * </pre>
 *
 * <p>O swagger do SEFIN declara em duas rotas <i>"este serviço foi movido"</i>; chamar o caminho
 * antigo devolve uma página HTML de 404 do IIS — que o {@link RespostaSefin} classifica como
 * indisponibilidade, mascarando um erro de rota. Medido em 2026-08-29.
 *
 * <h2>⚠️ O código do serviço vai PONTUADO aqui, ao contrário do XML</h2>
 *
 * <pre>
 *   no XML da DPS ..... 010501        (cTribNac, 6 dígitos)
 *   nesta API ......... 01.05.01.000  (com o desdobro municipal, 9 dígitos pontuados)
 * </pre>
 *
 * <p>E a mensagem de erro manda para o lado errado: mandei os nove dígitos sem ponto
 * ({@code 010501000}) e recebi <b>a mesma</b> mensagem <i>"o código do serviço deve ser composto
 * por nove dígitos"</i>. São nove, sim — no formato {@code NN.NN.NN.NNN}. Custou quatro tentativas.
 *
 * <p>⚠️ A competência é <b>data ISO</b> ({@code 2026-08-01}); {@code 202608} volta
 * <i>"is not valid"</i>.
 */
@Component
public class ParametrosMunicipaisClient {

    private static final Logger log = LoggerFactory.getLogger(ParametrosMunicipaisClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ADN_PRODUCAO = "https://adn.nfse.gov.br/parametrizacao";
    private static final String ADN_RESTRITA =
            "https://adn.producaorestrita.nfse.gov.br/parametrizacao";

    private final NfseTransporte transporte;

    public ParametrosMunicipaisClient(NfseTransporte transporte) {
        this.transporte = transporte;
    }

    /**
     * O município opera no Emissor Nacional?
     *
     * <p>⭐ É a DS8 respondida <b>por município, pela fonte oficial</b>. "Aderir ao ambiente
     * nacional" e "operar no Emissor Nacional" são coisas diferentes: medindo em 2026-08-29,
     * Salvador tinha {@code aderenteAmbienteNacional=1} e {@code aderenteEmissorNacional=0}. O
     * estudo não tinha conseguido fechar quantos municípios operam de fato — a pergunta não
     * precisava de número agregado, ela é por município.
     */
    public Optional<Convenio> convenio(int codigoIbge, EmissorDeNfse.Credencial credencial) {
        return ler(base(credencial) + "/" + codigoIbge + "/convenio", credencial)
                .map(raiz -> {
                    JsonNode p = raiz.path("parametrosConvenio");
                    return new Convenio(
                            p.path("aderenteAmbienteNacional").asInt(0) == 1,
                            p.path("aderenteEmissorNacional").asInt(0) == 1,
                            p.path("situacaoEmissaoPadraoContribuintesRFB").asInt(0) == 1);
                });
    }

    /**
     * A alíquota do ISS daquele município para aquele serviço, na competência.
     *
     * <p>⚠️ <b>"Alíquotas não encontradas" é resposta legítima, não erro</b> — São Paulo e Rio de
     * Janeiro não publicaram a tabela no ADN (medido). A tela degrada: sugere quando tem, pede ao
     * lojista quando não tem, e <b>nunca bloqueia</b> por causa disso. É a mesma regra que o
     * {@code workshop} provou no CEP: não conseguir checar nunca pode bloquear.
     */
    public Optional<Aliquota> aliquota(int codigoIbge, String codigoServicoCompleto,
                                       LocalDate competencia,
                                       EmissorDeNfse.Credencial credencial) {
        String url = base(credencial) + "/" + codigoIbge + "/" + codigoServicoCompleto + "/"
                + competencia + "/aliquota";
        return ler(url, credencial).flatMap(raiz -> {
            JsonNode aliquotas = raiz.path("aliquotas");
            if (aliquotas.isMissingNode() || aliquotas.isNull()) {
                return Optional.empty();
            }
            var campos = aliquotas.fields();
            if (!campos.hasNext()) {
                return Optional.empty();
            }
            JsonNode historico = campos.next().getValue();
            if (!historico.isArray() || historico.isEmpty()) {
                return Optional.empty();
            }
            JsonNode vigente = historico.get(0);
            return Optional.of(new Aliquota(
                    new BigDecimal(vigente.path("Aliq").asText("0")),
                    "SIM".equalsIgnoreCase(vigente.path("Incidencia").asText()),
                    vigente.path("DtIni").asText(null)));
        });
    }

    /**
     * Converte o {@code cTribNac} de 6 dígitos no formato pontuado que ESTA API exige.
     *
     * <p>{@code 010501} → {@code 01.05.01.000}. O último grupo é o desdobro <b>municipal</b>, que
     * a maioria dos serviços não tem — daí o {@code 000} como padrão.
     */
    public static String formatoDaApi(String cTribNac, String cTribMun) {
        String codigo = cTribNac == null ? "" : cTribNac.replaceAll("\\D", "");
        if (codigo.length() != 6) {
            throw new IllegalArgumentException("cTribNac deve ter 6 dígitos: " + cTribNac);
        }
        String municipal = cTribMun == null || cTribMun.isBlank() ? "000"
                : String.format("%03d", Integer.parseInt(cTribMun.replaceAll("\\D", "")));
        return codigo.substring(0, 2) + "." + codigo.substring(2, 4) + "."
                + codigo.substring(4, 6) + "." + municipal;
    }

    private Optional<JsonNode> ler(String url, EmissorDeNfse.Credencial credencial) {
        try {
            var retorno = transporte.get(url, credencial.pkcs12(), credencial.senha(),
                    credencial.impressaoDigital());
            if (retorno.status() < 200 || retorno.status() >= 300) {
                log.info("[nfse-adn] {} respondeu HTTP {}", url, retorno.status());
                return Optional.empty();
            }
            return Optional.of(JSON.readTree(retorno.corpo()));
        } catch (Exception e) {
            // ⚠️ Consulta de apoio NUNCA derruba a operação de quem a chamou: ela melhora a
            // configuração, não é pré-requisito dela.
            log.info("[nfse-adn] falha ao consultar {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private static String base(EmissorDeNfse.Credencial credencial) {
        return credencial.ambienteProducao() ? ADN_PRODUCAO : ADN_RESTRITA;
    }

    public record Convenio(boolean aderenteAmbienteNacional, boolean aderenteEmissorNacional,
                           boolean emissaoPadraoContribuintesRfb) {
    }

    public record Aliquota(BigDecimal percentual, boolean incide, String vigenteDesde) {
    }
}
