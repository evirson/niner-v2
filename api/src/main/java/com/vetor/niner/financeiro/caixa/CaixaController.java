package com.vetor.niner.financeiro.caixa;

import com.vetor.niner.identidade.permissao.Acao;
import com.vetor.niner.identidade.permissao.PermissaoService;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.financeiro.caixa.CaixaDtos.AbrirCaixaRequest;
import com.vetor.niner.financeiro.caixa.CaixaDtos.CaixaAbertoResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.CaixaStatusResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.CarteiraParaAberturaResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.FecharCaixaRequest;
import com.vetor.niner.financeiro.caixa.CaixaDtos.FechamentoCaixaResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.LancamentoCarteiraResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.ReaberturaCaixaResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.ReabrirCaixaRequest;
import com.vetor.niner.financeiro.caixa.CaixaDtos.ResultadoFechamentoResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Caixa (abertura, fechamento, reabertura), superfície do tenant (`/api/v1`, JWT + RLS).
 *
 * <p>Aberto a ADMIN e OPERADOR — mesma decisão de PDV/Recebimento de Crediário, que dependem
 * deste caixa estar aberto. <b>Duas exceções ADMIN-only</b>, checadas no service: consultar ou
 * fechar o caixa <b>de outro usuário</b>, e <b>reabrir</b> um caixa fechado (esta última vale
 * mesmo quando o caixa é do próprio operador — reabrir invalida uma conferência já assinada).
 */
@RestController
@RequestMapping("/api/v1/caixa")
@Tela("fechamento-caixa")
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

    /** "Caixas Abertos" (2026-08-19) — alimenta a grade que substitui a busca por data/usuário.
     *  OPERADOR só vê os próprios; ADMIN vê de todo mundo, em qualquer empresa. */
    @GetMapping("/abertos")
    public List<CaixaAbertoResponse> listarAbertos(@AuthenticationPrincipal Jwt jwt) {
        return service.listarAbertos(jwt);
    }

    @GetMapping("/fechamento/{idCaixa}")
    public FechamentoCaixaResponse buscarPorId(@AuthenticationPrincipal Jwt jwt, @PathVariable long idCaixa) {
        return service.buscarPorId(jwt, idCaixa);
    }

    @PostMapping("/fechamento")
    public ResultadoFechamentoResponse fechar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FecharCaixaRequest req) {
        return service.fechar(jwt, req);
    }

    /** Reabre um caixa fechado (2026-08-14) — **ADMIN-only, checado no service**, com motivo
     *  obrigatório. Existe pra destravar o estorno de crediário e a exclusão/reabertura de conta
     *  a pagar, que recusam apagar lançamento de caixa já fechado. */
    @Acao(PermissaoService.Acao.EXCLUIR)   // desfazer, não incluir
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
