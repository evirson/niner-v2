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
        Boolean estaNaHora = jdbc.sql("""
                        SELECT backup_habilitado
                               AND localtime >= backup_hora
                               AND (backup_ultimo_em IS NULL
                                    OR backup_ultimo_em::date < current_date
                                    OR backup_ultimo_status <> 'OK')
                          FROM plataforma.configuracao_plataforma WHERE id = 1
                        """)
                .query(Boolean.class).optional().orElse(false);

        if (Boolean.TRUE.equals(estaNaHora)) {
            log.info("Janela de backup atingida — iniciando.");
            backup.executar();
        }
    }
}
