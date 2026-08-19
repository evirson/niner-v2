package com.vetor.niner.plataforma.cobranca;

import com.vetor.niner.plataforma.cobranca.CobrancaDtos.FaturaResponse;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.IniciarPagamentoRequest;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.PagamentoPixResponse;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.SituacaoFaturaResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Assinatura paga do próprio tenant (ADMIN) — ADR-015/016. */
@RestController
@RequestMapping("/api/v1/assinatura")
public class AssinaturaController {

    private final CobrancaService service;

    public AssinaturaController(CobrancaService service) {
        this.service = service;
    }

    /** Escolhe a faixa + ciclo e devolve o PIX para pagar. Não troca o plano — quem troca é o
     *  worker, depois da confirmação do gateway. */
    @PostMapping("/pagamento")
    public PagamentoPixResponse iniciarPagamento(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody IniciarPagamentoRequest req) {
        return service.iniciarPagamento(jwt, req);
    }

    /** Polling da tela enquanto o cliente paga. */
    @GetMapping("/faturas/{id}")
    public SituacaoFaturaResponse situacao(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.situacao(jwt, id);
    }

    @GetMapping("/faturas")
    public List<FaturaResponse> faturas(@AuthenticationPrincipal Jwt jwt) {
        return service.listarFaturas(jwt);
    }
}
