package com.vetor.niner.plataforma.uso;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Cota de vendas do plano (ADR-015). O produto cobra por <b>uma</b> dimensão — venda emitida no
 * mês, somando todas as empresas (CNPJs) do tenant —, e é aqui que ela é medida e barrada.
 *
 * <p><b>Uma chamada só, de propósito.</b> A spec previa "checa depois incrementa"; a
 * implementação faz as duas coisas em {@link #registrarVenda()} porque entre a checagem e o
 * incremento existiria uma janela em que duas vendas simultâneas passariam pelo mesmo último
 * slot. O {@code INSERT … ON CONFLICT DO UPDATE … RETURNING} incrementa e <b>trava a linha</b>
 * do tenant até o commit, então vendas concorrentes serializam; se o novo total estourar a
 * cota, a exceção derruba a transação inteira da venda — e o incremento vai junto no rollback.
 *
 * <p><b>Incremento puro:</b> cancelar venda <b>não</b> devolve cota (decisão do dono do produto,
 * 2026-08-18). Nunca escreva um decremento aqui.
 *
 * <p><b>O que não conta:</b> este serviço só é chamado pelo PDV
 * ({@code PdvVendaService.efetivarVenda}). Venda inserida pela Rotina de Importação de Dados
 * ({@code ContasReceberImportador}) é histórico do sistema antigo e não passa por aqui — contá-la
 * queimaria a cota do lojista no dia da migração. Devolução também não conta.
 *
 * <p>Todas as consultas filtram {@code id_tenant = plataforma.tenant_atual()} explicitamente
 * (P8) — as tabelas de {@code plataforma} são globais e <b>não</b> têm RLS para proteger.
 */
@Service
public class LimiteVendasService {

    /** Mês virou: arquiva a competência fechada antes de o contador zerar. */
    private static final String SQL_FECHAR_COMPETENCIA = """
            INSERT INTO plataforma.uso_venda_mes (id_tenant, competencia, qtd_vendas)
            SELECT u.id_tenant, u.competencia_vendas, u.qtd_vendas_mes
              FROM plataforma.uso_tenant u
             WHERE u.id_tenant = plataforma.tenant_atual()
               AND u.competencia_vendas < date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo')::date
               AND u.qtd_vendas_mes > 0
            ON CONFLICT (id_tenant, competencia)
            DO UPDATE SET qtd_vendas = EXCLUDED.qtd_vendas, fechado_em = now()
            """;

    /** Incrementa (ou reinicia, se a competência virou) e devolve o total do mês corrente. */
    private static final String SQL_INCREMENTAR = """
            INSERT INTO plataforma.uso_tenant (id_tenant, competencia_vendas, qtd_vendas_mes)
            VALUES (plataforma.tenant_atual(), date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo')::date, 1)
            ON CONFLICT (id_tenant) DO UPDATE
               SET qtd_vendas_mes = CASE
                       WHEN uso_tenant.competencia_vendas = date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo')::date
                       THEN uso_tenant.qtd_vendas_mes + 1
                       ELSE 1 END,
                   competencia_vendas = date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo')::date,
                   atualizado_em = now()
            RETURNING qtd_vendas_mes
            """;

    /**
     * Limite do plano vigente + tolerância efetiva. Assinatura sem plano com limite (ou sem
     * assinatura viva) devolve {@code limite = null} = ilimitado: falta de dado no control-plane
     * nunca pode impedir a loja de vender.
     */
    private static final String SQL_LIMITE = """
            SELECT p.limite_vendas_mes,
                   COALESCE(a.tolerancia_vendas, pc.tolerancia_vendas) AS tolerancia
              FROM plataforma.assinatura a
              JOIN plataforma.plano p ON p.id_plano = a.id_plano
             CROSS JOIN plataforma.parametro_comercial pc
             WHERE a.id_tenant = plataforma.tenant_atual()
               AND a.status <> 'CANCELADA'
               AND pc.id = 1
             ORDER BY a.id_assinatura DESC
             LIMIT 1
            """;

    /** Menor faixa paga que comporta o volume já usado — é o que a tela oferece no bloqueio. */
    private static final String SQL_FAIXA_RECOMENDADA = """
            SELECT nome, preco_mensal
              FROM plataforma.plano
             WHERE ativo AND NOT gratuito AND faixa_ordem IS NOT NULL
               AND (limite_vendas_mes IS NULL OR limite_vendas_mes >= ?)
             ORDER BY faixa_ordem
             LIMIT 1
            """;

    private final JdbcClient jdbc;

    public LimiteVendasService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Registra uma venda na cota do mês e bloqueia se ela passar do limite + tolerância.
     * Chamado <b>dentro</b> da transação da venda (PDV) — a exceção desfaz tudo, inclusive
     * este incremento.
     *
     * @return total de vendas do tenant na competência, já com esta
     * @throws LimiteVendasExcedidoException 409 quando a cota e a tolerância acabaram
     */
    public int registrarVenda() {
        jdbc.sql(SQL_FECHAR_COMPETENCIA).update();
        int usadas = jdbc.sql(SQL_INCREMENTAR).query(Integer.class).single();

        Limite limite = jdbc.sql(SQL_LIMITE)
                .query((rs, n) -> new Limite((Integer) rs.getObject("limite_vendas_mes"), rs.getInt("tolerancia")))
                .optional()
                .orElse(new Limite(null, 0));

        if (limite.limiteVendasMes() == null) {
            return usadas;                                   // faixa sem teto (ou sem assinatura)
        }
        int teto = limite.limiteVendasMes() + limite.tolerancia();
        if (usadas > teto) {
            FaixaRecomendada faixa = jdbc.sql(SQL_FAIXA_RECOMENDADA)
                    .param(usadas)
                    .query((rs, n) -> new FaixaRecomendada(rs.getString("nome"), rs.getBigDecimal("preco_mensal")))
                    .optional()
                    .orElse(new FaixaRecomendada(null, null));
            throw new LimiteVendasExcedidoException(
                    usadas - 1, limite.limiteVendasMes(), limite.tolerancia(), faixa.nome(), faixa.precoMensal());
        }
        return usadas;
    }

    private record Limite(Integer limiteVendasMes, int tolerancia) {
    }

    private record FaixaRecomendada(String nome, BigDecimal precoMensal) {
    }
}
