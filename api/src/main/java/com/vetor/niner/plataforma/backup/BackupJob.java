package com.vetor.niner.plataforma.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agendador do backup. Confere de minuto em minuto se a janela configurada no backoffice já
 * chegou e se ainda não rodou hoje.
 *
 * <p>Verificar a cada minuto — em vez de um {@code cron} fixo — é o que permite ao admin
 * <b>mudar o horário pela tela</b> e a mudança valer no mesmo dia, sem reiniciar a API. O custo é
 * uma consulta de uma linha por minuto.
 */
@Component
public class BackupJob {

    private static final Logger log = LoggerFactory.getLogger(BackupJob.class);

    private final JdbcClient jdbc;
    private final BackupService backup;

    public BackupJob(JdbcClient jdbc, BackupService backup) {
        this.jdbc = jdbc;
        this.backup = backup;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void verificarJanela() {
        // ⚠️ `localtime` e `current_date` são o relógio da SESSÃO, que roda em UTC — o staff
        // configurava backup para as 03:00 e ele disparava às 00:00 de Brasília, no meio do
        // movimento de quem fecha tarde. Horário e "já rodou hoje" são do relógio da Vetor
        // (FusoDaPlataforma), não do banco.
        Boolean estaNaHora = jdbc.sql("""
                        SELECT backup_habilitado
                               AND (now() AT TIME ZONE 'America/Sao_Paulo')::time >= backup_hora
                               AND (backup_ultimo_em IS NULL
                                    OR (backup_ultimo_em AT TIME ZONE 'America/Sao_Paulo')::date
                                        < (now() AT TIME ZONE 'America/Sao_Paulo')::date
                                    -- ⚠️ A retentativa depois de uma falha precisa ESPERAR (achado
                                    -- de auditoria, 2026-08-21). Sem o intervalo, e como
                                    -- `registrar` carimba `backup_ultimo_em` também no ERRO, uma
                                    -- falha persistente satisfazia a condição a cada rodada de 60 s:
                                    -- backup agendado para 03:00 que falha vira ~1.260 `pg_dump` do
                                    -- banco inteiro até a meia-noite, cada um com conexão própria
                                    -- fora do pool. Não é hipótese — foi o primeiro dia de produção,
                                    -- quando faltava SELECT nas sequências para `niner_backup` e o
                                    -- job passou o dia gravando ERRO.
                                    OR (backup_ultimo_status <> 'OK'
                                        AND backup_ultimo_em < now() - interval '1 hour'))
                          FROM plataforma.configuracao_plataforma WHERE id = 1
                        """)
                .query(Boolean.class).optional().orElse(false);

        if (Boolean.TRUE.equals(estaNaHora)) {
            log.info("Janela de backup atingida — iniciando.");
            backup.executar();
        }
    }
}
