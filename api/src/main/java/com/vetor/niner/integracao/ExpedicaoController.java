package com.vetor.niner.integracao;

import com.vetor.niner.integracao.ExpedicaoRepositorio.ItemAExpedir;
import com.vetor.niner.integracao.ExpedicaoRepositorio.PedidoNaFila;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Fila de expedição (R5, M7). ⚠️ Não é ADMIN-only — ver o javadoc de {@link ExpedicaoService}. */
@RestController
@RequestMapping("/api/v1/expedicao")
public class ExpedicaoController {

    private final ExpedicaoService service;

    public ExpedicaoController(ExpedicaoService service) {
        this.service = service;
    }

    /** O código de rastreio é opcional: em Mercado Envios quem o gera é o próprio marketplace. */
    public record EnvioRequest(@Size(max = 60) String codigoRastreio) {
    }

    @GetMapping
    public List<PedidoNaFila> fila(@AuthenticationPrincipal Jwt jwt) {
        return service.fila(jwt);
    }

    @GetMapping("/{idPedido}/itens")
    public List<ItemAExpedir> itens(@AuthenticationPrincipal Jwt jwt, @PathVariable long idPedido) {
        return service.itens(jwt, idPedido);
    }

    @PostMapping("/{idPedido}/separar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void separar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idPedido) {
        service.separar(jwt, idPedido);
    }

    @PostMapping("/{idPedido}/enviar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enviar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idPedido,
                       @RequestBody(required = false) EnvioRequest req) {
        service.enviar(jwt, idPedido, req == null ? null : req.codigoRastreio());
    }
}
