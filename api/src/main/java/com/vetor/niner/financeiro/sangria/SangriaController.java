package com.vetor.niner.financeiro.sangria;

import com.vetor.niner.financeiro.sangria.SangriaDtos.SangriaContextoResponse;
import com.vetor.niner.financeiro.sangria.SangriaDtos.SangriaRequest;
import com.vetor.niner.financeiro.sangria.SangriaDtos.SangriaResponse;
import com.vetor.niner.identidade.permissao.Tela;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sangria de Caixa (V094) — dinheiro que sai da gaveta e entra numa conta corrente.
 *
 * <p>Aberto a ADMIN e OPERADOR: quem sangra é quem está no caixa. A tela só tem <b>incluir</b> —
 * sangria não se altera nem se exclui, e o {@code cfg_tela} declara isso, então a grade de
 * permissão nem oferece as caixas.
 */
@RestController
@RequestMapping("/api/v1/caixa/sangrias")
@Tela("sangria-caixa")
public class SangriaController {

    private final SangriaService service;

    public SangriaController(SangriaService service) {
        this.service = service;
    }

    @GetMapping("/contexto")
    public SangriaContextoResponse contexto(@AuthenticationPrincipal Jwt jwt) {
        return service.contexto(jwt);
    }

    @GetMapping
    public List<SangriaResponse> listar(@AuthenticationPrincipal Jwt jwt) {
        return service.listarDoCaixaAberto(jwt);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SangriaResponse registrar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SangriaRequest req) {
        return service.registrar(jwt, req);
    }
}
