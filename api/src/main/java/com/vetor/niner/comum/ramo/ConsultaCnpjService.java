package com.vetor.niner.comum.ramo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Consulta pública de CNPJ — traz razão social, nome fantasia, endereço e, principalmente, o
 * <b>CNAE principal</b>, que é o que permite <i>sugerir</i> o ramo de atividade em vez de fazer o
 * lojista escolher entre 28 opções no escuro.
 *
 * <p><b>A sugestão nunca decide.</b> Decisão do dono do produto (2026-08-27): <i>"dar a sugestão,
 * mas o usuário define"</i>. Quem grava é a escolha dele; isto aqui só adianta o trabalho.
 *
 * <p><b>Falhar aqui não pode travar nada.</b> Serviço externo cai, muda de formato, demora. Toda
 * falha vira {@code Optional.empty()} e a tela abre no preenchimento manual — nunca um erro na
 * cara de quem está tentando cadastrar uma empresa. Mesmo princípio do {@code EmailService}, que
 * também não lança para quem chamou.
 *
 * <p><b>Por que no backend e não no navegador</b> (ao contrário do ViaCEP, que o front chama
 * direto): aqui a resposta já volta com o ramo resolvido pelo mapa CNAE→ramo, que é dado nosso e
 * mora no banco. Resolver isso no cliente exigiria baixar o mapa inteiro para o navegador.
 */
@Service
public class ConsultaCnpjService {

    private static final Logger log = LoggerFactory.getLogger(ConsultaCnpjService.class);

    /** Esta aplicação não expõe bean de ObjectMapper — cada bean cria o seu (ver CLAUDE.md). */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final RamoAtividadeService ramos;
    private final String baseUrl;

    public ConsultaCnpjService(RamoAtividadeService ramos,
            @Value("${niner.consulta-cnpj.url:https://brasilapi.com.br/api/cnpj/v1}") String baseUrl) {
        this.ramos = ramos;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Dados públicos do CNPJ, ou vazio quando não deu para consultar — CNPJ inexistente, serviço
     * fora do ar, tempo esgotado, resposta em formato inesperado. Quem chama trata os quatro
     * casos igual, porque para a tela eles são o mesmo: preencher à mão.
     */
    public Optional<DadosCnpj> consultar(String cnpj) {
        String limpo = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (limpo.length() != 14) {
            return Optional.empty();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/" + limpo))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.info("Consulta de CNPJ {} respondeu {} — a tela cai no preenchimento manual.",
                        limpo, resp.statusCode());
                return Optional.empty();
            }
            JsonNode n = JSON.readTree(resp.body());
            // O CNAE vem como número inteiro (ex.: 4782201), sem máscara e sem zero à esquerda —
            // que só existiria na divisão 01..09, fora do varejo. Normalizado para 7 dígitos.
            String cnae = n.path("cnae_fiscal").isMissingNode() || n.path("cnae_fiscal").isNull()
                    ? null
                    : String.format("%07d", n.path("cnae_fiscal").asLong());
            var sugestao = ramos.sugerirPorCnae(cnae).orElse(null);
            return Optional.of(new DadosCnpj(
                    limpo,
                    texto(n, "razao_social"),
                    texto(n, "nome_fantasia"),
                    cnae,
                    texto(n, "cnae_fiscal_descricao"),
                    sugestao));
        } catch (Exception e) {
            log.info("Consulta de CNPJ {} falhou ({}) — a tela cai no preenchimento manual.",
                    limpo, e.getMessage());
            return Optional.empty();
        }
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.path(campo);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    /**
     * @param ramoSugerido pode ser {@code null}: o CNAE existe mas é genérico demais para
     *                     identificar o ramo (ver V072). Nesse caso a tela não sugere nada.
     */
    public record DadosCnpj(
            String cnpj,
            String razaoSocial,
            String nomeFantasia,
            String cnae,
            String cnaeDescricao,
            RamoAtividadeService.Ramo ramoSugerido) {
    }
}
