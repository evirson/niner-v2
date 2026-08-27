package com.vetor.niner.identidade.usuario;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.identidade.usuario.UsuarioDtos.ExclusaoUsuarioResponse;
import com.vetor.niner.identidade.usuario.UsuarioDtos.PaginaUsuarios;
import com.vetor.niner.identidade.usuario.UsuarioDtos.UsuarioRequest;
import com.vetor.niner.identidade.usuario.UsuarioDtos.UsuarioResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD de usuários (docs/telas/usuario.md), superfície do tenant (`/api/v1`, JWT + RLS).
 * Quem entra aqui é decidido pela grade de permissões (tela {@code usuarios}), não mais por um
 * {@code exigirAdmin} no serviço — as cinco chamadas saíram quando o RBAC entrou.
 *
 * <p>⛔ <b>Exceção que não é opcional:</b> o cadastro do <b>administrador</b> só é acessível ao
 * próprio administrador — não aparece na listagem de terceiros e responde 404 no GET, PUT e
 * DELETE (ver {@code UsuarioService.exigirAlvoAcessivel}).
 */
@RestController
@RequestMapping("/api/v1/usuarios")
@Tela("usuarios")
public class UsuarioController {

    private final UsuarioService service;

    public UsuarioController(UsuarioService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaUsuarios listar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String nome,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(jwt, nome, status, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{id}")
    public UsuarioResponse buscar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.buscar(jwt, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioResponse criar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UsuarioRequest req) {
        return service.criar(jwt, req);
    }

    @PutMapping("/{id}")
    public UsuarioResponse atualizar(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody UsuarioRequest req) {
        return service.atualizar(jwt, id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoUsuarioResponse excluir(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.excluir(jwt, id);
    }
}
