package com.vetor.niner.identidade.empresa;

import com.vetor.niner.identidade.permissao.Livre;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.identidade.empresa.EmpresaDtos.AtualizarEmpresaRequest;
import com.vetor.niner.identidade.empresa.EmpresaDtos.CriarEmpresaRequest;
import com.vetor.niner.identidade.empresa.EmpresaDtos.EmpresaDetalheResponse;
import com.vetor.niner.identidade.empresa.EmpresaDtos.EmpresaResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Empresas do tenant (`/api/v1`, JWT + RLS) — listar é aberto a qualquer papel; ficha completa
 *  e atualização (2026-08-19) são ADMIN-only, checado em {@link EmpresaService}. */
@RestController
@RequestMapping("/api/v1/empresas")
@Tela("empresas")
public class EmpresaController {

    private final EmpresaService service;

    public EmpresaController(EmpresaService service) {
        this.service = service;
    }

    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping
    public List<EmpresaResponse> listar() {
        return service.listar();
    }

    /** Empresas que o usuário logado pode operar (ADMIN: todas; OPERADOR: só as liberadas) —
     *  usado pela Entrada de Produtos por Compra pra escolher em qual empresa dar entrada. */
    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping("/permitidas")
    public List<EmpresaResponse> listarPermitidas(@AuthenticationPrincipal Jwt jwt) {
        return service.listarPermitidas(jwt);
    }

    /** Ficha completa (2026-08-19) — inclui os dados fiscais do emitente que a Conformidade
     *  Fiscal cobra (CNPJ/Inscrição Estadual/código de município IBGE/CNAE). */
    @GetMapping("/{id}")
    public EmpresaDetalheResponse buscar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.buscarPorId(jwt, id);
    }

    /** Inclui um CNPJ no tenant (2026-08-18, ADR-015) — ADMIN-only, checado no serviço. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmpresaDetalheResponse criar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CriarEmpresaRequest req) {
        return service.criar(jwt, req);
    }

    @PutMapping("/{id}")
    public EmpresaDetalheResponse atualizar(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody AtualizarEmpresaRequest req) {
        return service.atualizar(jwt, id, req);
    }
}
