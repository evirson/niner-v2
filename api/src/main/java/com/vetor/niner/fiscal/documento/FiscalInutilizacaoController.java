package com.vetor.niner.fiscal.documento;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.fiscal.documento.FiscalInutilizacaoDtos.FaixaBuracoResponse;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoDtos.InutilizacaoItemResponse;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoDtos.InutilizacaoRequest;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoDtos.InutilizacaoResponse;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoService.ResultadoInutilizacao;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoRepositorio.InutilizacaoItem;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Inutilização de numeração (§10.4/§12, bloco B8), superfície do tenant (`/api/v1`). ADMIN-only. */
@RestController
@RequestMapping("/api/v1/fiscal/inutilizacoes")
@Tela("fiscal.inutilizacao")
public class FiscalInutilizacaoController {

    private final FiscalInutilizacaoService service;

    public FiscalInutilizacaoController(FiscalInutilizacaoService service) {
        this.service = service;
    }

    @GetMapping
    public List<InutilizacaoItemResponse> listar(@AuthenticationPrincipal Jwt jwt, @RequestParam long idEmpresa) {
        return service.listar(jwt, idEmpresa).stream().map(FiscalInutilizacaoController::mapear).toList();
    }

    @GetMapping("/buracos")
    public List<FaixaBuracoResponse> buracos(@AuthenticationPrincipal Jwt jwt, @RequestParam long idEmpresa,
                                             @RequestParam int modelo, @RequestParam int serie) {
        return service.detectarBuracos(jwt, idEmpresa, modelo, serie).stream()
                .map(f -> new FaixaBuracoResponse(f.numeroInicial(), f.numeroFinal()))
                .toList();
    }

    @PostMapping
    public InutilizacaoResponse inutilizar(@AuthenticationPrincipal Jwt jwt, @RequestBody InutilizacaoRequest req) {
        ResultadoInutilizacao resultado = service.executar(jwt, req.idEmpresa(), req.modelo(), req.serie(),
                req.numeroInicial(), req.numeroFinal(), req.justificativa());
        return new InutilizacaoResponse(resultado.protocolo(), resultado.ano());
    }

    private static InutilizacaoItemResponse mapear(InutilizacaoItem item) {
        return new InutilizacaoItemResponse(item.modelo(), item.serie(), item.ano(),
                item.numeroInicial(), item.numeroFinal(), item.justificativa(), item.autorizado(),
                item.protocolo(), item.motivoSefaz(), item.criadoEm());
    }
}
