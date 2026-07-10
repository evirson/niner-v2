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

    public record PlanoPublico(
            long idPlano, String nome, String descricao,
            BigDecimal precoMensal, BigDecimal precoAnual,
            Integer limiteCanais, Integer limiteProdutos,
            Integer limiteUsuarios, Integer limitePedidosMes) {
    }

    @GetMapping("/planos")
    public List<PlanoPublico> planos() {
        return jdbc.sql("""
                        SELECT id_plano, nome, descricao, preco_mensal, preco_anual,
                               limite_canais, limite_produtos, limite_usuarios, limite_pedidos_mes
                        FROM plataforma.plano
                        WHERE ativo
                        ORDER BY preco_mensal
                        """)
                .query((rs, n) -> new PlanoPublico(
                        rs.getLong("id_plano"), rs.getString("nome"), rs.getString("descricao"),
                        rs.getBigDecimal("preco_mensal"), rs.getBigDecimal("preco_anual"),
                        (Integer) rs.getObject("limite_canais"), (Integer) rs.getObject("limite_produtos"),
                        (Integer) rs.getObject("limite_usuarios"), (Integer) rs.getObject("limite_pedidos_mes")))
                .list();
    }
}
