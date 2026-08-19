package com.vetor.niner.plataforma.aquisicao;

import com.vetor.niner.plataforma.aquisicao.AquisicaoDtos.LeadRequest;
import com.vetor.niner.plataforma.aquisicao.AquisicaoDtos.LoteEventosRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Superfície pública de medição (ADR-017). Anônima, sem efeito de negócio.
 *
 * <p>Responde <b>204</b> sempre que o corpo é aceitável — inclusive quando o lote é descartado.
 * O beacon não trata resposta: devolver erro só encheria o console do visitante.
 */
@RestController
@RequestMapping("/api/publico")
public class AquisicaoController {

    private final AquisicaoService service;

    public AquisicaoController(AquisicaoService service) {
        this.service = service;
    }

    @PostMapping("/eventos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eventos(@RequestBody LoteEventosRequest req, HttpServletRequest http) {
        // Dispositivo é derivado aqui (MOBILE/DESKTOP) e o user-agent bruto é descartado — o que
        // interessa é "veio do celular?", não identificar o aparelho.
        String ua = http.getHeader("User-Agent");
        boolean mobile = ua != null && ua.toLowerCase().contains("mobi");
        service.registrarLote(req, mobile);
    }

    @PostMapping("/leads")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lead(@Valid @RequestBody LeadRequest req) {
        service.registrarLead(req);
    }
}
