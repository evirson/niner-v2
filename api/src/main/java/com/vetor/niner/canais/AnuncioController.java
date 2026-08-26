package com.vetor.niner.canais;

import com.vetor.niner.canais.AnuncioDtos.AnuncioParaVincular;
import com.vetor.niner.canais.AnuncioDtos.VinculoGravado;
import com.vetor.niner.canais.AnuncioDtos.VinculoRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vínculo anúncio ↔ produto (R6). ADMIN-only, como tudo que mexe em integração.
 *
 * <p>⚠️ A paginação aqui é por <b>página</b>, sem total — ao contrário do resto do produto, que
 * devolve a contagem. A origem é o marketplace, e a busca de itens do ML não dá um total
 * confiável; inventar um faria a tela mentir sobre quantas páginas existem.
 */
@RestController
@RequestMapping("/api/v1/canais/{idCanal}/anuncios")
public class AnuncioController {

    /** O ML limita a busca de itens a 50 por página — pedir mais não traz mais. */
    private static final int LIMITE_PADRAO = 50;

    private final AnuncioService service;

    public AnuncioController(AnuncioService service) {
        this.service = service;
    }

    @GetMapping
    public AnuncioParaVincular listar(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable long idCanal,
                                      @RequestParam(defaultValue = "1") int pagina,
                                      @RequestParam(defaultValue = "" + LIMITE_PADRAO) int limite) {
        return service.listar(jwt, idCanal, pagina, limite);
    }

    /**
     * O que já está vinculado — <b>sem</b> chamar o marketplace.
     *
     * <p>⭐ Separado da listagem de propósito: com o ML fora do ar, o lojista ainda precisa ver e
     * poder desfazer o que vinculou.
     */
    @GetMapping("/vinculos")
    public java.util.List<VinculoGravado> vinculos(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable long idCanal) {
        return service.listarVinculos(jwt, idCanal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void vincular(@AuthenticationPrincipal Jwt jwt, @PathVariable long idCanal,
                         @Valid @RequestBody VinculoRequest req) {
        service.vincular(jwt, idCanal, req);
    }

    @DeleteMapping("/{idAnuncio}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desvincular(@AuthenticationPrincipal Jwt jwt, @PathVariable long idCanal,
                            @PathVariable long idAnuncio) {
        service.desvincular(jwt, idCanal, idAnuncio);
    }
}
