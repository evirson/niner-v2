package com.vetor.niner.plataforma.configuracao;

import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaDtos.AtualizarConfiguracaoRequest;
import com.vetor.niner.plataforma.configuracao.ConfiguracaoPlataformaDtos.ConfiguracaoResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configuração da plataforma no backoffice: leitura para staff, gravação só para SUPER_ADMIN. */
@RestController
@RequestMapping("/api/admin/configuracao")
public class ConfiguracaoPlataformaController {

    private final ConfiguracaoPlataformaService service;

    public ConfiguracaoPlataformaController(ConfiguracaoPlataformaService service) {
        this.service = service;
    }

    @GetMapping
    public ConfiguracaoResponse consultar(@AuthenticationPrincipal Jwt jwt) {
        return service.consultar(jwt);
    }

    @PutMapping
    public ConfiguracaoResponse atualizar(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AtualizarConfiguracaoRequest req) {
        return service.atualizar(jwt, req);
    }
}
