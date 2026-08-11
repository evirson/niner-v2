package com.vetor.niner.identidade.empresa;

import com.vetor.niner.identidade.empresa.EmpresaDtos.EmpresaResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Listagem de empresas do tenant (`/api/v1`, JWT + RLS) — qualquer papel pode ler. */
@RestController
@RequestMapping("/api/v1/empresas")
public class EmpresaController {

    private final EmpresaService service;

    public EmpresaController(EmpresaService service) {
        this.service = service;
    }

    @GetMapping
    public List<EmpresaResponse> listar() {
        return service.listar();
    }

    /** Empresas que o usuário logado pode operar (ADMIN: todas; OPERADOR: só as liberadas) —
     *  usado pela Entrada de Produtos por Compra pra escolher em qual empresa dar entrada. */
    @GetMapping("/permitidas")
    public List<EmpresaResponse> listarPermitidas(@AuthenticationPrincipal Jwt jwt) {
        return service.listarPermitidas(jwt);
    }
}
