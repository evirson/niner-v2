package com.vetor.niner.fiscal.conformidade;

import com.vetor.niner.comum.seguranca.EmpresaDaSessao;
import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.fiscal.conformidade.ConformidadeFiscalDtos.CategoriaConformidade;
import com.vetor.niner.fiscal.conformidade.ConformidadeFiscalDtos.PaginaPendencias;
import com.vetor.niner.fiscal.conformidade.ConformidadeFiscalDtos.PainelConformidadeResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Conformidade Fiscal (docs/telas/fiscal-conformidade.md), superfície do tenant (`/api/v1`,
 * JWT + RLS). ADMIN-only, somente leitura — sem POST/PUT/DELETE, a tela aponta e navega.
 */
@RestController
@RequestMapping("/api/v1/fiscal/conformidade")
@Tela("fiscal.conformidade")
public class ConformidadeFiscalController {

    private final ConformidadeFiscalService service;

    public ConformidadeFiscalController(ConformidadeFiscalService service) {
        this.service = service;
    }

    @GetMapping("/{idEmpresa}")
    public PainelConformidadeResponse painel(@AuthenticationPrincipal Jwt jwt, @PathVariable long idEmpresa) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        return service.painel(jwt, idEmpresa);
    }

    /** {@code categoria} chega em minúsculas na URL (contrato da spec) — o enum Java é maiúsculo
     *  por convenção, então a conversão é manual em vez de deixar o {@code @PathVariable} usar
     *  {@code Enum.valueOf} cru (case-sensitive, rejeitaria "produtos"). */
    @GetMapping("/{idEmpresa}/{categoria}")
    public PaginaPendencias drillDown(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable long idEmpresa,
                                      @PathVariable String categoria,
                                      @RequestParam(required = false) Integer pagina,
                                      @RequestParam(required = false) Integer limite) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        return service.drillDown(jwt, idEmpresa, categoriaDaUrl(categoria), pagina, limite);
    }

    private static CategoriaConformidade categoriaDaUrl(String valor) {
        try {
            return CategoriaConformidade.valueOf(valor.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Categoria inválida: %s. Use empresa, produtos, pagamentos ou clientes.".formatted(valor));
        }
    }
}
