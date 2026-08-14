package com.vetor.niner.identidade.usuario;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checagem da janela de horário de acesso (docs/telas/usuario.md, 2026-08-11) — usada tanto no
 * login ({@code SignupService.login}, sem tolerância) quanto no filtro por requisição
 * ({@code HorarioAcessoFilter}, {@link #TOLERANCIA_MINUTOS_PADRAO} de tolerância pra não cortar
 * uma venda em andamento). Nunca se aplica a {@code administrador=true} (só existe um por
 * tenant) nem a usuário com {@code controla_horario_acesso=false} — os dois casos voltam "pode
 * acessar" sem nem olhar {@code usuario_horario_acesso}.
 *
 * <p>O horário é sempre o do SERVIDOR (pedido explícito do dono do produto): a comparação
 * inteira acontece dentro de UMA query SQL usando {@code now()} do Postgres — nunca o relógio
 * do processo Java nem, muito menos, o do cliente.
 */
@Service
public class HorarioAcessoService {

    /** Mesmo texto usado nos dois pontos de bloqueio (login e filtro por requisição) — o
     *  frontend casa por esta string exata pra distinguir "fora do horário" de qualquer outro
     *  403/401 e disparar o fluxo de logoff gracioso em vez do genérico. */
    public static final String MENSAGEM_FORA_DA_JANELA = "Fora do horário de acesso permitido.";

    /** Tolerância padrão (2026-08-11, revisado a pedido do dono do produto — era 30, agora 15)
     *  usada tanto pelo {@code HorarioAcessoFilter} (bloqueio de verdade) quanto por
     *  {@link #segundosRestantesTolerancia} (contagem regressiva mostrada no frontend) — as
     *  duas PRECISAM usar o mesmo valor, senão o aviso visual não bate com o bloqueio real. */
    public static final int TOLERANCIA_MINUTOS_PADRAO = 15;

    private final JdbcClient jdbc;

    public HorarioAcessoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code toleranciaMinutos} soma ao horário final do dia antes de comparar — 0 para o login
     * (não existe "rotina em andamento" pra proteger numa sessão que ainda nem começou) e
     * {@link #TOLERANCIA_MINUTOS_PADRAO} para o filtro de cada requisição autenticada (tempo de
     * sobra pra terminar/imprimir uma venda já iniciada antes do horário fechar).
     */
    @Transactional(readOnly = true)
    public boolean podeAcessarAgora(long idUsuario, int toleranciaMinutos) {
        // A tolerância soma no domínio de TIMESTAMP (current_date + hora), nunca em `time`
        // puro: `time + interval` do Postgres "embrulha" na virada da meia-noite (ex.:
        // 23:50 + 15 min vira 00:05, um limite superior MENOR que o inferior, quebrando o
        // BETWEEN sempre que o fim do expediente cai nos últimos `toleranciaMinutos` do dia —
        // bug real encontrado na verificação manual, 2026-08-11).
        return jdbc.sql("""
                        SELECT u.administrador OR NOT u.controla_horario_acesso OR EXISTS (
                            SELECT 1 FROM usuario_horario_acesso h
                            WHERE h.id_tenant = plataforma.tenant_atual() AND h.id_usuario = u.id_usuario
                              AND h.dia_semana = EXTRACT(ISODOW FROM now())
                              AND h.hora_inicio IS NOT NULL
                              AND now() BETWEEN (current_date + h.hora_inicio)
                                             AND (current_date + h.hora_fim + (? * interval '1 minute'))
                        )
                        FROM usuario u
                        WHERE u.id_usuario = ? AND u.id_tenant = plataforma.tenant_atual()
                        """)
                .params(toleranciaMinutos, idUsuario)
                .query(Boolean.class)
                .optional()
                // usuário não encontrado (JWT de sessão morta, id inválido) — deixa a checagem
                // seguinte da cadeia (autorização/existência) tratar; não é papel deste serviço.
                .orElse(true);
    }

    /**
     * Segundos restantes de tolerância — usado só pelo aviso visual do frontend (anel com
     * contagem regressiva, `HorarioAcessoGuard`), nunca pela decisão de bloquear (essa é sempre
     * {@link #podeAcessarAgora}). Retorna {@code null} quando não se aplica: ADMIN, controle
     * desligado, ainda dentro do horário normal, sem linha pro dia de hoje, ou já além da
     * tolerância (nesse caso o {@code HorarioAcessoFilter} já bloqueia antes de qualquer tela
     * chegar a chamar isto de novo). Só é não-nulo estritamente DEPOIS do {@code hora_fim} de
     * hoje e até {@code toleranciaMinutos} depois — a mesma janela de graça de
     * {@link #podeAcessarAgora}, com o mesmo cuidado de somar a tolerância em domínio de
     * timestamp (nunca `time` puro — ver comentário lá).
     */
    @Transactional(readOnly = true)
    public Long segundosRestantesTolerancia(long idUsuario, int toleranciaMinutos) {
        return jdbc.sql("""
                        SELECT GREATEST(0, CEIL(EXTRACT(EPOCH FROM (
                            (current_date + h.hora_fim + (? * interval '1 minute')) - now()
                        ))))::bigint
                        FROM usuario u
                        JOIN usuario_horario_acesso h
                          ON h.id_tenant = plataforma.tenant_atual() AND h.id_usuario = u.id_usuario
                        WHERE u.id_usuario = ? AND u.id_tenant = plataforma.tenant_atual()
                          AND NOT u.administrador AND u.controla_horario_acesso
                          AND h.dia_semana = EXTRACT(ISODOW FROM now())
                          AND h.hora_inicio IS NOT NULL
                          AND now() > (current_date + h.hora_fim)
                          AND now() <= (current_date + h.hora_fim + (? * interval '1 minute'))
                        """)
                .params(toleranciaMinutos, idUsuario, toleranciaMinutos)
                .query(Long.class)
                .optional()
                .orElse(null);
    }
}
