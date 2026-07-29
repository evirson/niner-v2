package com.vetor.niner.financeiro.recebimentocrediario;

import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.CarteiraDisponivelResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.ClienteCrediarioResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.EfetivarRecebimentoRequest;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.EstornoEfetivadoResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.LoteRecebimentoResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.ParcelaCrediarioResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.ParcelaDoLoteResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.RecebimentoEfetivadoResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Recebimento de Crediário, superfície do tenant (`/api/v1`, JWT + RLS). Aberto a ADMIN e
 * OPERADOR (operação do dia a dia, mesma decisão de PDV/Transferência).
 */
@RestController
@RequestMapping("/api/v1/recebimento-crediario")
public class RecebimentoCrediarioController {

    private final RecebimentoCrediarioService service;

    public RecebimentoCrediarioController(RecebimentoCrediarioService service) {
        this.service = service;
    }

    @GetMapping("/clientes")
    public List<ClienteCrediarioResponse> buscarClientes(
            @RequestParam(required = false) String nome,
            @RequestParam(required = false) String cpf,
            @RequestParam(required = false) String celular) {
        return service.buscarClientes(nome, cpf, celular);
    }

    @GetMapping("/parcelas")
    public List<ParcelaCrediarioResponse> listarParcelas(@RequestParam long idCliente) {
        return service.listarParcelas(idCliente);
    }

    @GetMapping("/carteiras")
    public List<CarteiraDisponivelResponse> listarCarteirasDisponiveis() {
        return service.listarCarteirasDisponiveis();
    }

    @PostMapping
    public RecebimentoEfetivadoResponse efetivar(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EfetivarRecebimentoRequest req) {
        return service.efetivarRecebimento(jwt, req);
    }

    @GetMapping("/estornos")
    public List<LoteRecebimentoResponse> listarLotes(
            @RequestParam String nomeCliente,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal) {
        return service.listarLotes(nomeCliente, dataInicial, dataFinal);
    }

    @GetMapping("/estornos/{idLoteRecebimento}/parcelas")
    public List<ParcelaDoLoteResponse> listarParcelasDoLote(@PathVariable long idLoteRecebimento) {
        return service.listarParcelasDoLote(idLoteRecebimento);
    }

    @PostMapping("/estornos/{idLoteRecebimento}")
    public EstornoEfetivadoResponse estornar(@PathVariable long idLoteRecebimento) {
        return service.estornarLote(idLoteRecebimento);
    }
}
