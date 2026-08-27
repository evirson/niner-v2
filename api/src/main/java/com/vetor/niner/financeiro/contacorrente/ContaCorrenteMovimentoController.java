package com.vetor.niner.financeiro.contacorrente;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.financeiro.contacorrente.ContaCorrenteMovimentoDtos.ContaCorrenteMovimentoRequest;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteMovimentoDtos.ContaCorrenteMovimentoResponse;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteMovimentoDtos.ExclusaoContaCorrenteMovimentoResponse;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteMovimentoDtos.PaginaContaCorrenteMovimento;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * CRUD de lançamentos de conta corrente, superfície do tenant (`/api/v1`, JWT + RLS). PK
 * surrogate ({@code localizador}) no path. Sem restrição de papel — mesma decisão de produto
 * dos demais cadastros.
 */
@RestController
@RequestMapping("/api/v1/contas-corrente-movimento")
@Tela("contas-corrente-movimento")
public class ContaCorrenteMovimentoController {

    private final ContaCorrenteMovimentoService service;

    public ContaCorrenteMovimentoController(ContaCorrenteMovimentoService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaContaCorrenteMovimento listar(
            @RequestParam(required = false) String idContaCorrente,
            @RequestParam(required = false) Long idEmpresa,
            @RequestParam(required = false) String idPlanoContas,
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) String compensado,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(idContaCorrente, idEmpresa, idPlanoContas, busca, dataInicial, dataFinal, compensado,
                pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{localizador}")
    public ContaCorrenteMovimentoResponse buscar(@PathVariable long localizador) {
        return service.buscar(localizador);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContaCorrenteMovimentoResponse criar(@Valid @RequestBody ContaCorrenteMovimentoRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{localizador}")
    public ContaCorrenteMovimentoResponse atualizar(@PathVariable long localizador, @Valid @RequestBody ContaCorrenteMovimentoRequest req) {
        return service.atualizar(localizador, req);
    }

    @DeleteMapping("/{localizador}")
    public ExclusaoContaCorrenteMovimentoResponse excluir(@PathVariable long localizador) {
        return service.excluir(localizador);
    }
}
