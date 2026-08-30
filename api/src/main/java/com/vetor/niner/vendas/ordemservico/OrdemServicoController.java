package com.vetor.niner.vendas.ordemservico;

import com.vetor.niner.identidade.permissao.Acao;
import com.vetor.niner.identidade.permissao.PermissaoService;
import com.vetor.niner.identidade.permissao.Tela;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.CancelamentoRequest;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.LinhaListagem;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.OrdemServicoRequest;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.OrdemServicoResponse;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.PaginaOrdensServico;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Ordem de Serviço (bloco S4, {@code docs/MODULOSERVICOS.md} §4.2).
 *
 * <p>⚠️ <b>Não existe endpoint de "faturar".</b> A OS concluída é puxada pelo PDV (F5) e vira venda
 * pelo {@code PdvVendaService} — o mesmo caminho do orçamento. Uma segunda porta de faturamento
 * teria de reimplementar caixa aberto, split-tender, desconto máximo, limite de crédito, cota do
 * plano, papeleta e emissão fiscal.
 *
 * <p>⚠️ O <b>cancelamento</b> é `POST /{id}/cancelar` e não `DELETE`, de propósito: ele não apaga,
 * marca — e devolve a reserva de estoque das peças (DS17). A ação declarada é
 * {@code EXCLUIR} porque "desfazer é excluir", a mesma classificação dos outros oito métodos
 * de desfazer do sistema: quem pode abrir OS não deveria poder cancelar a de ontem.
 */
@RestController
@RequestMapping("/api/v1/ordens-servico")
@Tela("ordens-servico")
public class OrdemServicoController {

    private final OrdemServicoService service;

    public OrdemServicoController(OrdemServicoService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaOrdensServico listar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "50") int limite) {
        return service.listar(jwt, busca, situacao, dataInicial, dataFinal, pagina, limite);
    }

    /** As OS concluídas de um cliente — é a consulta do F5 do PDV (DS18). */
    @GetMapping("/faturaveis")
    public List<LinhaListagem> faturaveis(@AuthenticationPrincipal Jwt jwt, @RequestParam long idCliente) {
        return service.faturaveisDoCliente(jwt, idCliente);
    }

    @GetMapping("/{id}")
    public OrdemServicoResponse buscar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.buscar(jwt, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrdemServicoResponse criar(@AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody OrdemServicoRequest req) {
        return service.criar(jwt, req);
    }

    @PutMapping("/{id}")
    public OrdemServicoResponse atualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                          @Valid @RequestBody OrdemServicoRequest req) {
        return service.atualizar(jwt, id, req);
    }

    /** Avança o estado de execução (aprovar, iniciar, concluir). Só para frente. */
    @PutMapping("/{id}/situacao")
    public OrdemServicoResponse mudarSituacao(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                              @RequestParam String para) {
        return service.mudarSituacao(jwt, id, para);
    }

    @PostMapping("/{id}/cancelar")
    @Acao(PermissaoService.Acao.EXCLUIR)   // desfazer, não incluir
    public OrdemServicoResponse cancelar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                         @Valid @RequestBody CancelamentoRequest req) {
        return service.cancelar(jwt, id, req.motivo());
    }
}
