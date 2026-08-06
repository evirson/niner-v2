package com.vetor.niner.configuracao.exportacao;

import com.vetor.niner.configuracao.exportacao.ExportacaoDtos.TabelaExportavel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Rotina de Exportação de Dados (Configurações), superfície do tenant (`/api/v1`, JWT + RLS).
 * ADMIN-only, checado em {@link ExportacaoService}. A planilha em si é montada no navegador
 * (mesmo padrão do CRM, `write-excel-file`) — aqui só devolve os dados já formatados.
 */
@RestController
@RequestMapping("/api/v1/exportacao")
public class ExportacaoController {

    private final ExportacaoService service;

    public ExportacaoController(ExportacaoService service) {
        this.service = service;
    }

    @GetMapping("/tabelas")
    public List<TabelaExportavel> listarTabelas(@AuthenticationPrincipal Jwt jwt) {
        return service.listarTabelas(jwt);
    }

    @GetMapping("/{tabela}")
    public List<Map<String, Object>> exportar(@AuthenticationPrincipal Jwt jwt, @PathVariable String tabela) {
        return service.exportar(jwt, tabela);
    }
}
