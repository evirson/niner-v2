package com.vetor.niner.cadastros.crm;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.cadastros.crm.CrmDtos.ClienteCrmResponse;
import com.vetor.niner.cadastros.crm.CrmDtos.OpcoesCrmResponse;
import com.vetor.niner.cadastros.crm.CrmService.FiltrosCrm;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * CRM (docs/telas/crm.md), superfície do tenant (`/api/v1`, JWT + RLS). Qualquer papel — somente
 * leitura, sem lançamento de dado nenhum (é um filtro/exportação sobre `cliente`/`venda`).
 */
@RestController
@RequestMapping("/api/v1/crm")
@Tela("crm")
public class CrmController {

    private final CrmService service;

    public CrmController(CrmService service) {
        this.service = service;
    }

    @GetMapping("/opcoes")
    public OpcoesCrmResponse opcoes() {
        return service.opcoes();
    }

    @GetMapping("/clientes")
    public List<ClienteCrmResponse> buscarClientes(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String clienteInicial,
            @RequestParam(required = false) String clienteFinal,
            @RequestParam(required = false) List<String> generos,
            @RequestParam(required = false) Integer idadeDe,
            @RequestParam(required = false) Integer idadeAte,
            @RequestParam(required = false) String aniversarioDe,
            @RequestParam(required = false) String aniversarioAte,
            @RequestParam(required = false) List<Long> idsCategoriaCliente,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cadastroDe,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cadastroAte,
            @RequestParam(required = false) Integer diasSemComprasMinimo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate comprasDe,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate comprasAte,
            @RequestParam(required = false) List<Long> idsCategoriaProduto,
            @RequestParam(required = false) List<Long> idsCor,
            @RequestParam(required = false) List<Long> idsTamanho) {
        FiltrosCrm filtros = new FiltrosCrm(
                clienteInicial, clienteFinal, generos, idadeDe, idadeAte, aniversarioDe, aniversarioAte,
                idsCategoriaCliente, cadastroDe, cadastroAte, diasSemComprasMinimo, comprasDe, comprasAte,
                idsCategoriaProduto, idsCor, idsTamanho);
        return service.buscarClientes(jwt, filtros);
    }
}
