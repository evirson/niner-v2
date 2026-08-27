package com.vetor.niner.fiscal.configuracao;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.EmpresaFiscalResponse;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.FiscalConfigRequest;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.FiscalConfigResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Configuração fiscal da empresa (docs/telas/fiscal-configuracao.md), superfície do tenant
 * ({@code /api/v1}, JWT + RLS). <b>Somente ADMIN</b> em todas as rotas — verificado no serviço a
 * partir do claim {@code roles}, mesmo mecanismo de {@code ConfiguracaoGeralService}.
 *
 * <p>Não há {@code POST} nem {@code DELETE}: a configuração é um singleton por empresa, criado
 * pelo primeiro {@code PUT} (upsert) e nunca removido — desligar o fiscal é
 * {@code emiteNfce=false}, não apagar a linha.
 */
@RestController
@RequestMapping("/api/v1/fiscal/config")
@Tela("fiscal.configuracao")
public class FiscalConfigController {

    private final FiscalConfigService service;

    public FiscalConfigController(FiscalConfigService service) {
        this.service = service;
    }

    /** Empresas do tenant e se cada uma já tem fiscal configurado — alimenta o seletor do topo. */
    @GetMapping("/empresas")
    public List<EmpresaFiscalResponse> listarEmpresas(@AuthenticationPrincipal Jwt jwt) {
        return service.listarEmpresas(jwt);
    }

    /** 200 com os defaults do banco e {@code configurado=false} quando a empresa não tem linha. */
    @GetMapping("/{idEmpresa}")
    public FiscalConfigResponse buscar(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable long idEmpresa) {
        return service.buscar(jwt, idEmpresa);
    }

    /** Cria ou atualiza. 409 quando um gate de emissão não pode ser ligado (F11). */
    @PutMapping("/{idEmpresa}")
    public FiscalConfigResponse salvar(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable long idEmpresa,
                                       @Valid @RequestBody FiscalConfigRequest req) {
        return service.salvar(jwt, idEmpresa, req);
    }
}
