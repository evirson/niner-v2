package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.GradeDtos.GradeRequest;
import com.vetor.niner.catalogo.GradeDtos.GradeResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Grade de tamanhos ({@code cfg_grade}) — gerida embutida no formulário de Produto (modal
 * "＋ Gerenciar Grades"), sem tela própria nesta versão.
 */
@RestController
@RequestMapping("/api/v1/grades")
public class GradeController {

    private final GradeService service;

    public GradeController(GradeService service) {
        this.service = service;
    }

    @GetMapping
    public List<GradeResponse> listar() {
        return service.listar();
    }

    @GetMapping("/{id}")
    public GradeResponse buscar(@PathVariable long id) {
        return service.buscar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GradeResponse criar(@Valid @RequestBody GradeRequest req) {
        return service.criar(req);
    }

    @PutMapping("/{id}")
    public GradeResponse atualizar(@PathVariable long id, @Valid @RequestBody GradeRequest req) {
        return service.atualizar(id, req);
    }
}
