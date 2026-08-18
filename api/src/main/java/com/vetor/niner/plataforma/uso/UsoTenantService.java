package com.vetor.niner.plataforma.uso;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Contadores estruturais de {@code plataforma.uso_tenant} que o domínio precisa manter em dia.
 *
 * <p>Hoje só a quantidade de empresas (CNPJs) do tenant — ilimitada em todos os planos (D4
 * revisada), medida para o painel e para métrica de uso, nunca para bloquear. Fica neste pacote
 * pelo mesmo motivo do {@link LimiteVendasService}: a travessia domínio ↔ plataforma é permitida
 * em um lugar só (P9).
 */
@Service
public class UsoTenantService {

    private final JdbcClient jdbc;

    public UsoTenantService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Reconta as empresas do tenant. Recontar (em vez de somar 1) mantém o número certo mesmo
     *  se alguma empresa tiver entrado por outro caminho — SQL direto, migração, importação. */
    public void recontarEmpresas() {
        jdbc.sql("""
                        INSERT INTO plataforma.uso_tenant (id_tenant, qtd_empresas)
                        VALUES (plataforma.tenant_atual(),
                                (SELECT count(*) FROM empresa WHERE id_tenant = plataforma.tenant_atual()))
                        ON CONFLICT (id_tenant) DO UPDATE
                           SET qtd_empresas = EXCLUDED.qtd_empresas, atualizado_em = now()
                        """)
                .update();
    }
}
