package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.seguranca.EmpresaDaSessao;
import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.fiscal.documento.FiscalContingenciaDtos.ContingenciaResponse;
import com.vetor.niner.fiscal.documento.FiscalContingenciaDtos.EntrarSairRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Painel de Contingência (§9.7, bloco B7) — estado atual, fila pendente, entrar/sair manualmente.
 * <b>Somente ADMIN</b>, mesmo mecanismo de {@code FiscalConfigService}: {@link FiscalContingenciaService}
 * fica sem Jwt de propósito — é chamado também pelo {@link EmissaoNfceService} e pelo
 * {@link FiscalContingenciaDrenoJob}, nenhum dos dois com requisição HTTP.
 */
@RestController
@RequestMapping("/api/v1/fiscal/contingencia")
@Tela("fiscal.contingencia")
public class FiscalContingenciaController {

    private final FiscalContingenciaService service;

    public FiscalContingenciaController(FiscalContingenciaService service) {
        this.service = service;
    }

    @GetMapping("/{idEmpresa}")
    public ContingenciaResponse consultar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idEmpresa) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        var estado = service.consultar(idEmpresa);
        return new ContingenciaResponse(estado.ativa(), estado.desde(), estado.justificativa(),
                estado.serieContingencia(), estado.pendentes(), estado.duracao().toMinutes());
    }

    /** Entrada manual — a automática (DF19) já acontece sozinha na emissão, sem passar por aqui. */
    @PostMapping("/{idEmpresa}/entrar")
    public ContingenciaResponse entrar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idEmpresa,
                                       @Valid @RequestBody EntrarSairRequest req) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        service.entrar(idEmpresa, "Manual: " + req.justificativa());
        return consultar(jwt, idEmpresa);
    }

    /** Saída manual — a automática acontece no {@link FiscalContingenciaDrenoJob} quando a SEFAZ volta. */
    @PostMapping("/{idEmpresa}/sair")
    public ContingenciaResponse sair(@AuthenticationPrincipal Jwt jwt, @PathVariable long idEmpresa,
                                     @Valid @RequestBody EntrarSairRequest req) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        service.sair(idEmpresa, "Manual: " + req.justificativa());
        return consultar(jwt, idEmpresa);
    }

}
