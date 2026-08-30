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

    /**
     * Reconta os usuários do tenant.
     *
     * <p>⛔ <b>A coluna nascia 1 no signup e nunca mais mudava</b> (auditoria 2026-08-29, rodada 2).
     * O {@code INSERT} literal {@code VALUES (?, 1, 1)} do {@code SignupService} era o <b>único</b>
     * escritor: criar ou excluir usuário não a tocava. A tela Tenants do backoffice mostrava
     * <b>"1 usuário"</b> para todo tenant, sempre — inclusive um com 12 operadores. É o número que
     * o suporte usa para dimensionar a conta e a Vetor para métrica de uso, e estava
     * estruturalmente errado desde o primeiro dia.
     *
     * <p>⚠️ Ninguém percebeu porque <b>1 é plausível</b> num tenant recém-criado — que é o caso de
     * todo banco de desenvolvimento. Número plausível e errado não se denuncia sozinho.
     */
    public void recontarUsuarios() {
        jdbc.sql("""
                        INSERT INTO plataforma.uso_tenant (id_tenant, qtd_usuarios)
                        VALUES (plataforma.tenant_atual(),
                                (SELECT count(*) FROM usuario WHERE id_tenant = plataforma.tenant_atual()))
                        ON CONFLICT (id_tenant) DO UPDATE
                           SET qtd_usuarios = EXCLUDED.qtd_usuarios, atualizado_em = now()
                        """)
                .update();
    }
}
