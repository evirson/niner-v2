package com.vetor.niner.comum.web;

import com.vetor.niner.comum.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints de fumaça que provam o roteamento das três superfícies (ADR-007) e o
 * wiring do contexto de tenant. Substituídos pelos controllers de domínio conforme
 * as features forem implementadas.
 */
@RestController
public class PingController {

    @GetMapping("/api/publico/ping")
    public Map<String, Object> publico() {
        return resposta("publico");
    }

    @GetMapping("/api/v1/ping")
    public Map<String, Object> tenant() {
        Map<String, Object> corpo = resposta("v1");
        corpo.put("id_tenant", TenantContext.idTenantAtualOuNull());
        return corpo;
    }

    @GetMapping("/api/admin/ping")
    public Map<String, Object> admin() {
        return resposta("admin");
    }

    private static Map<String, Object> resposta(String superficie) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("app", "niner-api");
        corpo.put("superficie", superficie);
        corpo.put("status", "ok");
        return corpo;
    }
}
