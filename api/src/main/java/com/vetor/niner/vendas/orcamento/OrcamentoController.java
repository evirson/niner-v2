package com.vetor.niner.vendas.orcamento;

import com.vetor.niner.vendas.orcamento.OrcamentoDtos.CancelarOrcamentoRequest;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.EmitirOrcamentoRequest;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.OrcamentoCanceladoResponse;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.OrcamentoResponse;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.PaginaOrcamentos;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Orçamento de Venda (docs/telas/orcamento.md) — superfície do tenant (`/api/v1`, JWT + RLS).
 * ADMIN e OPERADOR têm acesso completo: orçamento não move dinheiro nem mercadoria, e travar em
 * ADMIN faria o vendedor chamar o gerente para corrigir uma quantidade.
 *
 * <p>⚠️ <b>Não existe endpoint de alteração</b>, e a ausência é a regra R1: o orçamento é imutável
 * depois de emitido. Quem quiser mudar cancela e emite outro — a mesma filosofia da venda. Não
 * acrescente um PUT aqui sem falar com o dono do produto.
 */
@RestController
@RequestMapping("/api/v1/orcamentos")
public class OrcamentoController {

    private final OrcamentoService service;

    public OrcamentoController(OrcamentoService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrcamentoResponse emitir(@AuthenticationPrincipal Jwt jwt,
                                    @Valid @RequestBody EmitirOrcamentoRequest req) {
        return service.emitir(jwt, req);
    }

    @GetMapping
    public PaginaOrcamentos listar(@RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
                                   @RequestParam(required = false) Long idCliente,
                                   @RequestParam(required = false) Long idFuncionario,
                                   @RequestParam(required = false) String situacao,
                                   @RequestParam(required = false) Integer pagina,
                                   @RequestParam(required = false) Integer limite) {
        return service.listar(dataInicial, dataFinal, idCliente, idFuncionario, situacao, pagina, limite);
    }

    /** ⚠️ Consultar um orçamento vencido o marca como VENCIDO na hora (R6) — por isso não é um
     *  GET puro. É o que impede a tela de mostrar "aberto" algo que o PDV vai recusar em seguida. */
    @GetMapping("/{idOrcamento}")
    public OrcamentoResponse buscar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idOrcamento) {
        return service.buscar(jwt, idOrcamento);
    }

    @PostMapping("/{idOrcamento}/cancelar")
    public OrcamentoCanceladoResponse cancelar(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable long idOrcamento,
                                               @Valid @RequestBody CancelarOrcamentoRequest req) {
        return service.cancelar(jwt, idOrcamento, req);
    }
}
