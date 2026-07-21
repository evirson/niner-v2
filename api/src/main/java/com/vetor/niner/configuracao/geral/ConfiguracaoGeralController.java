package com.vetor.niner.configuracao.geral;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralRequest;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Parâmetros do sistema (docs/telas/configuracao-geral.md), superfície do tenant (`/api/v1`,
 * JWT + RLS). Somente ADMIN — verificado no serviço a partir do claim {@code roles} do JWT
 * (mesmo mecanismo de {@code ConfiguracaoTelaService}).
 */
@RestController
@RequestMapping("/api/v1/config-geral")
public class ConfiguracaoGeralController {

    private final ConfiguracaoGeralService service;

    public ConfiguracaoGeralController(ConfiguracaoGeralService service) {
        this.service = service;
    }

    @GetMapping
    public ConfiguracaoGeralResponse buscar(@AuthenticationPrincipal Jwt jwt) {
        return service.buscar(jwt);
    }

    @PutMapping
    public ConfiguracaoGeralResponse atualizar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConfiguracaoGeralRequest req) {
        return service.atualizar(jwt, req);
    }
}
