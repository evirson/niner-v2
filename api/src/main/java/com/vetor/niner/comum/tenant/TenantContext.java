package com.vetor.niner.comum.tenant;

/**
 * Contexto do tenant vigente na execução atual (P8, spec §3.1.1).
 *
 * <p>Usa {@link ScopedValue} (Java 25): o id_tenant fica ligado apenas dentro do
 * escopo de execução (requisição HTTP ou tarefa de worker), sem vazamento entre
 * threads/conexões do pool. Para <b>caminhos sem requisição</b> (worker/outbox/
 * webhook) o valor é estabelecido explicitamente via {@link #comTenant}.
 *
 * <p>As políticas RLS do domínio (migrations V013+) leem
 * {@code current_setting('app.id_tenant')}; quem materializa esse setting na
 * transação é o {@link TenantAwareTransactionManager}, a partir deste contexto.
 */
public final class TenantContext {

    private static final ScopedValue<Long> ID_TENANT = ScopedValue.newInstance();

    private TenantContext() {
    }

    /** {@code true} se há um tenant ligado no escopo atual. */
    public static boolean temTenant() {
        return ID_TENANT.isBound();
    }

    /** id_tenant vigente ou {@code null} se não há tenant no escopo (publico/admin). */
    public static Long idTenantAtualOuNull() {
        return ID_TENANT.isBound() ? ID_TENANT.get() : null;
    }

    /** id_tenant vigente; lança se ausente (uso onde o tenant é obrigatório). */
    public static long idTenantAtual() {
        if (!ID_TENANT.isBound()) {
            throw new IllegalStateException("Nenhum tenant no contexto da execução atual.");
        }
        return ID_TENANT.get();
    }

    /** Executa {@code op} com o tenant ligado no contexto (worker/outbox/webhook — P8). */
    public static <R, X extends Throwable> R comTenant(long idTenant, ScopedValue.CallableOp<? extends R, X> op) throws X {
        return ScopedValue.where(ID_TENANT, idTenant).call(op);
    }

    /** Variante sem retorno de {@link #comTenant(long, ScopedValue.CallableOp)}. */
    public static void comTenant(long idTenant, Runnable op) {
        ScopedValue.where(ID_TENANT, idTenant).run(op);
    }
}
