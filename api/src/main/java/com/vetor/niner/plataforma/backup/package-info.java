/**
 * Backup automático do banco (bloqueador nº 3 de produção), com <b>agenda editável pelo
 * backoffice</b> — horário e retenção vivem em {@code plataforma.configuracao_plataforma}, não em
 * arquivo de configuração.
 *
 * <p><b>Por que não usa {@code ArmazenamentoPrivado}:</b> aquele adapter monta sempre o prefixo
 * {@code tenants/{id_tenant}/} a partir do {@code TenantContext} (P8/ADR-014) — invariante que
 * protege o isolamento e que não faz sentido para um dump do banco inteiro, que é dado da
 * plataforma. Em vez de enfraquecer a regra com uma exceção, o backup fala com o mesmo MinIO por
 * um componente próprio, sob o prefixo {@code plataforma/backup/}.
 */
package com.vetor.niner.plataforma.backup;
