package com.vetor.niner.configuracao.etiqueta;

import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.EtiquetaConfigRequest;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.EtiquetaConfigResponse;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.ExclusaoEtiquetaConfigResponse;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.PaginaEtiquetasConfig;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.ProdutoExemploResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD de Configuração de Etiqueta de Produtos, superfície do tenant (`/api/v1`, JWT + RLS).
 * ADMIN-only por decisão de produto (mesmo grupo "Configurações" de Usuários/Parâmetros do
 * Sistema no menu) — a restrição de papel é aplicada no front (rota dentro de
 * `RequireAdmin`), mesma convenção das demais telas desse grupo.
 */
@RestController
@RequestMapping("/api/v1/etiquetas-config")
public class EtiquetaConfigController {

    private final EtiquetaConfigService service;

    public EtiquetaConfigController(EtiquetaConfigService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaEtiquetasConfig listar(
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer limite,
            @RequestParam(required = false) String ordenarPor,
            @RequestParam(required = false) String direcao) {
        return service.listar(busca, pagina, limite, ordenarPor, direcao);
    }

    @GetMapping("/{id}")
    public EtiquetaConfigResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EtiquetaConfigResponse criar(@Valid @RequestBody EtiquetaConfigRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public EtiquetaConfigResponse atualizar(@PathVariable long id, @Valid @RequestBody EtiquetaConfigRequest req) {
        return service.atualizar(id, req);
    }

    @DeleteMapping("/{id}")
    public ExclusaoEtiquetaConfigResponse excluir(@PathVariable long id) {
        return service.excluir(id);
    }

    /** Busca de produto/variação pra pré-visualizar a etiqueta com dado real. */
    @GetMapping("/produtos-exemplo")
    public List<ProdutoExemploResponse> buscarProdutosExemplo(@RequestParam(required = false) String busca) {
        return service.buscarProdutosExemplo(busca);
    }
}
