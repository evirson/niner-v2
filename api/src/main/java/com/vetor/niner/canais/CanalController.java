package com.vetor.niner.canais;

import com.vetor.niner.canais.CanalDtos.CanalRequest;
import com.vetor.niner.canais.CanalDtos.CanalResponse;
import com.vetor.niner.canais.CanalDtos.SaudeCanaisResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canais de Venda (R7) — ADMIN-only, como o resto do que mexe em integração.
 *
 * <p>⏭️ <b>Conectar ainda não existe aqui.</b> A conexão é OAuth (bloco M1) e depende do
 * {@code client_id} da aplicação do Mercado Livre, que ainda não foi criada. Até lá o canal nasce
 * {@code DESCONECTADO} e a tela diz o que falta — em vez de oferecer um botão que só pode falhar.
 */
@RestController
@RequestMapping("/api/v1/canais")
public class CanalController {

    private final CanalService service;

    public CanalController(CanalService service) {
        this.service = service;
    }

    @GetMapping
    public List<CanalResponse> listar(@AuthenticationPrincipal Jwt jwt) {
        return service.listar(jwt);
    }

    @GetMapping("/saude")
    public SaudeCanaisResponse saude(@AuthenticationPrincipal Jwt jwt) {
        return service.saude(jwt);
    }

    @GetMapping("/{idCanal}")
    public CanalResponse buscar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idCanal) {
        return service.buscar(jwt, idCanal);
    }

    @PostMapping("/{tipo}")
    @ResponseStatus(HttpStatus.CREATED)
    public CanalResponse criar(@AuthenticationPrincipal Jwt jwt, @PathVariable TipoCanal tipo,
                               @Valid @RequestBody CanalRequest req) {
        return service.criar(jwt, tipo, req);
    }

    @PutMapping("/{idCanal}")
    public CanalResponse atualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idCanal,
                                   @Valid @RequestBody CanalRequest req) {
        return service.atualizar(jwt, idCanal, req);
    }

    @PostMapping("/{idCanal}/desconectar")
    public CanalResponse desconectar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idCanal) {
        return service.desconectar(jwt, idCanal);
    }

    @DeleteMapping("/{idCanal}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@AuthenticationPrincipal Jwt jwt, @PathVariable long idCanal) {
        service.excluir(jwt, idCanal);
    }

    /** Reprocessamento manual da fila (R7). */
    @PostMapping("/eventos/{idEvento}/reprocessar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reprocessar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idEvento) {
        service.reprocessar(jwt, idEvento);
    }
}
