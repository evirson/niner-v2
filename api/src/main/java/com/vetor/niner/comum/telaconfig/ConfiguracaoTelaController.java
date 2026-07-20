package com.vetor.niner.comum.telaconfig;

import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaDtos.ConfiguracaoCampoRequest;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaDtos.ConfiguracaoCampoResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Configuração de tela (docs/telas/configuracao-tela.md) — quais campos aparecem/são
 * obrigatórios em cada tela do produto, por tenant. Leitura liberada a qualquer usuário do
 * tenant (a tela precisa saber como se renderizar); gravação só para ADMIN (verificado no
 * serviço a partir do claim {@code roles} do JWT).
 */
@RestController
@RequestMapping("/api/v1/config-tela")
public class ConfiguracaoTelaController {

    private final ConfiguracaoTelaService service;

    public ConfiguracaoTelaController(ConfiguracaoTelaService service) {
        this.service = service;
    }

    @GetMapping("/{chaveTela}")
    public List<ConfiguracaoCampoResponse> listar(@PathVariable String chaveTela) {
        return service.listar(chaveTela);
    }

    @PutMapping("/{chaveTela}")
    public List<ConfiguracaoCampoResponse> salvar(
            @PathVariable String chaveTela,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody List<ConfiguracaoCampoRequest> req) {
        return service.salvar(chaveTela, jwt, req);
    }
}
