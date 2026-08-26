package com.vetor.niner.integracao.mercadolivre;

import com.vetor.niner.comum.config.NinerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Os dois lados do OAuth do Mercado Livre — e eles moram em <b>superfícies diferentes</b>.
 *
 * <ul>
 *   <li>{@code GET /api/v1/canais/{idCanal}/mercadolivre/autorizar} — <b>autenticado</b>, ADMIN.
 *       Devolve a URL do consentimento em JSON; quem manda o navegador para lá é o front. ⚠️ Não
 *       responde 302 de propósito: um redirect quebraria a chamada {@code fetch} da tela, que
 *       precisa do endereço para abrir a janela.</li>
 *   <li>{@code GET /api/publico/canais/mercadolivre/retorno} — <b>anônimo</b>. Quem chega é o
 *       navegador do lojista devolvido pelo ML, sem JWT. Este responde 302 mesmo, para o ERP.</li>
 * </ul>
 *
 * <p>⚠️ <b>O endereço do retorno não pode mudar sem mexer no painel do Mercado Livre.</b> Ele está
 * registrado lá como {@code redirect_uri} e é comparado <b>caractere por caractere</b>; renomear
 * este mapeamento quebra a conexão de todos os lojistas de uma vez, com uma mensagem do ML que
 * não diz que a culpa é nossa.
 */
@RestController
public class MercadoLivreOAuthController {

    private final MercadoLivreOAuthService service;
    private final NinerProperties.MercadoLivre config;

    public MercadoLivreOAuthController(MercadoLivreOAuthService service, NinerProperties props) {
        this.service = service;
        this.config = props.canais().mercadolivre();
    }

    /** Começa a conexão: devolve para onde mandar o lojista. */
    @GetMapping("/api/v1/canais/{idCanal}/mercadolivre/autorizar")
    public Map<String, String> autorizar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idCanal) {
        return Map.of("url", service.urlDeAutorizacao(jwt, idCanal));
    }

    /**
     * A volta do Mercado Livre.
     *
     * <p>⚠️ <b>Sempre redireciona, nunca responde JSON de erro.</b> Quem está deste lado é uma
     * pessoa olhando um navegador, não um cliente de API: um Problem Details cru na tela seria o
     * fim da jornada de conexão. O que deu errado viaja na query string e a tela de Canais mostra.
     *
     * <p>O ML manda {@code error}/{@code error_description} quando o lojista recusa o
     * consentimento — caso que não é falha nossa e precisa de mensagem própria.
     */
    @GetMapping("/api/publico/canais/mercadolivre/retorno")
    public ResponseEntity<Void> retorno(@RequestParam(required = false) String code,
                                        @RequestParam(required = false) String state,
                                        @RequestParam(name = "error", required = false) String erro) {
        var desfecho = service.concluir(code, state, erro);

        String destino = "%s?canal=%s&%s=%s".formatted(
                config.retornoWeb(),
                desfecho.idCanal(),
                desfecho.sucesso() ? "conectado" : "erro",
                URLEncoder.encode(desfecho.mensagem(), StandardCharsets.UTF_8));

        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(destino)).build();
    }
}
