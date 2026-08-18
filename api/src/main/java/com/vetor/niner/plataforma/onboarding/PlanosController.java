package com.vetor.niner.plataforma.onboarding;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Catálogo público de planos (R11) — consumido pela landing do site para exibir
 * preços/limites. Planos são globais (control-plane, P9); não há tenant envolvido.
 */
@RestController
@RequestMapping("/api/publico")
public class PlanosController {

    private final JdbcClient jdbc;

    public PlanosController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Faixa do catálogo (ADR-015): o que a landing mostra é volume de vendas e preço — os limites
     * estruturais saíram do DTO em 2026-08-18 porque são ilimitados em todos os planos (o produto
     * não vende funcionalidade, vende volume).
     */
    public record PlanoPublico(
            long idPlano, String nome, String descricao, boolean gratuito, Integer faixaOrdem,
            Integer limiteVendasMes, BigDecimal precoMensal, BigDecimal precoAnual) {
    }

    @GetMapping("/planos")
    public List<PlanoPublico> planos() {
        return jdbc.sql("""
                        SELECT id_plano, nome, descricao, gratuito, faixa_ordem,
                               limite_vendas_mes, preco_mensal, preco_anual
                        FROM plataforma.plano
                        WHERE ativo AND faixa_ordem IS NOT NULL
                        ORDER BY faixa_ordem
                        """)
                .query((rs, n) -> new PlanoPublico(
                        rs.getLong("id_plano"), rs.getString("nome"), rs.getString("descricao"),
                        rs.getBoolean("gratuito"), (Integer) rs.getObject("faixa_ordem"),
                        (Integer) rs.getObject("limite_vendas_mes"),
                        rs.getBigDecimal("preco_mensal"), rs.getBigDecimal("preco_anual")))
                .list();
    }
}
