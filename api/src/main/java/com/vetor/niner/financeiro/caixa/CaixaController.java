package com.vetor.niner.financeiro.caixa;

import com.vetor.niner.financeiro.caixa.CaixaDtos.AbrirCaixaRequest;
import com.vetor.niner.financeiro.caixa.CaixaDtos.CaixaStatusResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.CarteiraParaAberturaResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.FecharCaixaRequest;
import com.vetor.niner.financeiro.caixa.CaixaDtos.FechamentoCaixaResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.LancamentoCarteiraResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.ReaberturaCaixaResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.ReabrirCaixaRequest;
import com.vetor.niner.financeiro.caixa.CaixaDtos.ResultadoFechamentoResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Abertura de Caixa, superfície do tenant (`/api/v1`, JWT + RLS). Aberto a ADMIN e OPERADOR —
 * mesma decisão de PDV/Recebimento de Crediário, que dependem deste caixa estar aberto.
 */
@RestController
@RequestMapping("/api/v1/caixa")
public class CaixaController {

    private final CaixaService service;

    public CaixaController(CaixaService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public CaixaStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return service.status(jwt);
    }

    @GetMapping("/carteiras")
    public List<CarteiraParaAberturaResponse> listarCarteirasParaAbertura() {
        return service.listarCarteirasParaAbertura();
    }

    @PostMapping("/abrir")
    public CaixaStatusResponse abrir(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AbrirCaixaRequest req) {
        return service.abrir(jwt, req);
    }

    @GetMapping("/fechamento")
    public FechamentoCaixaResponse buscarParaFechamento(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long idUsuario,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {
        return service.buscarParaFechamento(jwt, idUsuario, data);
    }

    @PostMapping("/fechamento")
    public ResultadoFechamentoResponse fechar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FecharCaixaRequest req) {
        return service.fechar(jwt, req);
    }

    /** Reabre um caixa fechado (2026-08-14) — **ADMIN-only, checado no service**, com motivo
     *  obrigatório. Existe pra destravar o estorno de crediário e a exclusão/reabertura de conta
     *  a pagar, que recusam apagar lançamento de caixa já fechado. */
    @PostMapping("/fechamento/{idCaixa}/reabrir")
    public ReaberturaCaixaResponse reabrir(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long idCaixa,
            @Valid @RequestBody ReabrirCaixaRequest req) {
        return service.reabrir(jwt, idCaixa, req);
    }

    @GetMapping("/fechamento/{idCaixa}/carteiras/{idCarteira}/lancamentos")
    public List<LancamentoCarteiraResponse> listarLancamentosDaCarteira(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long idCaixa, @PathVariable long idCarteira) {
        return service.listarLancamentosDaCarteira(jwt, idCaixa, idCarteira);
    }
}
