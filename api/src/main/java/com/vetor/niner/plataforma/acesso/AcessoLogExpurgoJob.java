package com.vetor.niner.plataforma.acesso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Apaga acessos mais velhos que a retenção — <b>entregue junto com a tabela, não depois</b>.
 *
 * <h2>Por que isto existe desde o primeiro dia</h2>
 *
 * <p>{@code plataforma.codigo_login} foi criada <b>sem expurgo nenhum</b>: ela cresce para sempre e
 * hoje guarda hash e IP de contas <b>já excluídas</b> — descoberto em 2026-09-01 ao excluir um
 * usuário de teste. Esta tabela ganha uma linha por login de <b>todos</b> os tenants, então o mesmo
 * descuido custaria mais e apareceria mais tarde.
 *
 * <p>🔵 Retenção de <b>24 meses</b>, decisão do dono do produto. ⚠️ O piso legal é <b>6 meses</b>
 * (Marco Civil da Internet, art. 15, para provedor de aplicações) — quem baixar este número
 * precisa saber que 6 é o chão, não uma sugestão.
 *
 * <p>⚠️ O {@code DELETE} roda por {@code plataforma.expurgar_acesso_login}, que é
 * {@code SECURITY DEFINER}: {@code niner_app} tem {@code INSERT} e {@code SELECT} na tabela, mas
 * <b>não tem {@code DELETE}</b> — trilha de auditoria não se apaga pelo caminho comum da aplicação.
 *
 * <p>⚠️ Sem {@code TenantContext}: a tabela é de plataforma, sem RLS. É o mesmo caso do backup e
 * dos demais jobs de plano de controle.
 */
@Component
public class AcessoLogExpurgoJob {

    private static final Logger log = LoggerFactory.getLogger(AcessoLogExpurgoJob.class);

    private final JdbcClient jdbc;
    private final int mesesRetencao;

    public AcessoLogExpurgoJob(JdbcClient jdbc,
            @Value("${niner.acesso-log.meses-retencao:24}") int mesesRetencao) {
        this.jdbc = jdbc;
        this.mesesRetencao = mesesRetencao;
    }

    /**
     * Uma vez por dia. ⚠️ {@code initialDelay} alto de propósito: na subida a aplicação tem coisa
     * melhor a fazer, e um expurgo não tem pressa nenhuma.
     */
    @Scheduled(fixedRate = 86_400_000, initialDelay = 300_000)
    public void expurgar() {
        try {
            Integer apagados = jdbc.sql("SELECT plataforma.expurgar_acesso_login(?)")
                    .param(mesesRetencao)
                    .query(Integer.class)
                    .single();
            if (apagados != null && apagados > 0) {
                log.info("Expurgo do log de acesso: {} registro(s) com mais de {} meses.",
                        apagados, mesesRetencao);
            }
        } catch (RuntimeException e) {
            // Um expurgo que falha não pode derrubar nada — mas também não pode falhar calado,
            // senão a tabela cresce e ninguém descobre até o disco acabar.
            log.error("Falha ao expurgar o log de acesso.", e);
        }
    }
}
