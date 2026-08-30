package com.vetor.niner.identidade.usuario;

import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.comum.tempo.FusoDaUf;
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
    private final FusoDaLoja fusoDaLoja;

    public HorarioAcessoService(JdbcClient jdbc, FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.fusoDaLoja = fusoDaLoja;
    }

    /**
     * O fuso do <b>relógio de parede da loja</b> a que este usuário pertence.
     *
     * <p>⛔ Era {@code 'America/Sao_Paulo'} cravado no texto do SQL (auditoria 2026-08-29, rodada
     * 2). Numa loja do <b>Acre</b> (UTC−5) com janela 08:00–18:00, às <b>16:00 do relógio da
     * loja</b> já são 18:00 em Brasília: o filtro respondia 403 <i>"Fora do horário de acesso
     * permitido"</i> e o operador era expulso <b>duas horas antes</b> do fim do expediente — com
     * uma venda aberta, e sem conseguir entrar de novo (o login usa tolerância 0). No Amazonas e
     * no Mato Grosso (UTC−4) o erro é de 1 h. E o {@code EXTRACT(ISODOW …)} erra o <b>dia da
     * semana</b> pelo mesmo motivo perto da meia-noite, que é o defeito que a correção de
     * 2026-08-19 veio consertar — ela trocou o fuso do banco pelo de Brasília e parou ali.
     *
     * <p>⚠️ A resolução é <b>preguiçosa</b>, e isso é deliberado: o filtro roda em <b>toda</b>
     * requisição autenticada, e a maioria dos usuários não controla horário. A consulta da janela
     * já responde {@code true} de graça para admin e para quem não controla; pagar um SELECT de
     * UF por requisição para todo mundo trocaria um erro de 1 h em seis UFs por um custo em todas.
     */
    private String fusoDoUsuario(long idUsuario) {
        Long idEmpresa = jdbc.sql("""
                        SELECT id_empresa FROM usuario
                        WHERE id_tenant = plataforma.tenant_atual() AND id_usuario = ?
                        """)
                .param(idUsuario).query(Long.class).optional().orElse(null);
        return idEmpresa == null ? FusoDaUf.PADRAO.getId() : fusoDaLoja.da(idEmpresa).getId();
    }

    /** {@code true} quando o usuário nem chega a ter janela (admin, ou controle desligado). */
    private boolean dispensadoDeJanela(long idUsuario) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT u.administrador OR NOT u.controla_horario_acesso
                        FROM usuario u
                        WHERE u.id_usuario = ? AND u.id_tenant = plataforma.tenant_atual()
                        """)
                .param(idUsuario).query(Boolean.class).optional()
                // usuário não encontrado (JWT de sessão morta, id inválido) — deixa a checagem
                // seguinte da cadeia tratar; não é papel deste serviço.
                .orElse(true));
    }

    /**
     * {@code toleranciaMinutos} soma ao horário final do dia antes de comparar — 0 para o login
     * (não existe "rotina em andamento" pra proteger numa sessão que ainda nem começou) e
     * {@link #TOLERANCIA_MINUTOS_PADRAO} para o filtro de cada requisição autenticada (tempo de
     * sobra pra terminar/imprimir uma venda já iniciada antes do horário fechar).
     */
    @Transactional(readOnly = true)
    public boolean podeAcessarAgora(long idUsuario, int toleranciaMinutos) {
        // A tolerância soma no domínio de TIMESTAMP (data + hora), nunca em `time`
        // puro: `time + interval` do Postgres "embrulha" na virada da meia-noite (ex.:
        // 23:50 + 15 min vira 00:05, um limite superior MENOR que o inferior, quebrando o
        // BETWEEN sempre que o fim do expediente cai nos últimos `toleranciaMinutos` do dia —
        // bug real encontrado na verificação manual, 2026-08-11).
        //
        // ⚠️ 2026-08-19 — e esse timestamp é o do RELÓGIO DE PAREDE DA LOJA, não o do banco. A
        // sessão do Postgres roda em UTC, então `now()`/`current_date` crus adiantam o dia às
        // 21:00 de Brasília: `EXTRACT(ISODOW ...)` já devolvia o dia da semana SEGUINTE (sexta
        // às 21h virava sábado, e a janela consultada era a do dia errado) e a janela do dia
        // era montada sobre a data errada. `now() AT TIME ZONE 'America/Sao_Paulo'` devolve um
        // `timestamp` sem fuso com o horário local — daí pra frente toda a conta é local e
        // homogênea (mesma correção do "hoje" de CaixaService).
        if (dispensadoDeJanela(idUsuario)) {
            return true;
        }
        String fuso = fusoDoUsuario(idUsuario);
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM usuario_horario_acesso h
                            WHERE h.id_tenant = plataforma.tenant_atual() AND h.id_usuario = u.id_usuario
                              AND h.dia_semana = EXTRACT(ISODOW FROM (now() AT TIME ZONE ?::text))
                              AND h.hora_inicio IS NOT NULL
                              AND (now() AT TIME ZONE ?::text)
                                  BETWEEN ((now() AT TIME ZONE ?::text)::date + h.hora_inicio)
                                      AND ((now() AT TIME ZONE ?::text)::date + h.hora_fim
                                           + (? * interval '1 minute'))
                        )
                        FROM usuario u
                        WHERE u.id_usuario = ? AND u.id_tenant = plataforma.tenant_atual()
                        """)
                .params(fuso, fuso, fuso, fuso, toleranciaMinutos, idUsuario)
                .query(Boolean.class)
                .optional()
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
        // ⚠️ O MESMO fuso de `podeAcessarAgora` — as duas contas precisam concordar, senão o anel
        // de contagem regressiva mostra tempo de sobra numa loja que o filtro já está expulsando
        // (é o javadoc acima que exige isso, para o aviso visual bater com o bloqueio real).
        if (dispensadoDeJanela(idUsuario)) {
            return null;
        }
        String fuso = fusoDoUsuario(idUsuario);
        return jdbc.sql("""
                        SELECT GREATEST(0, CEIL(EXTRACT(EPOCH FROM (
                            ((now() AT TIME ZONE ?::text)::date + h.hora_fim
                             + (? * interval '1 minute'))
                            - (now() AT TIME ZONE ?::text)
                        ))))::bigint
                        FROM usuario u
                        JOIN usuario_horario_acesso h
                          ON h.id_tenant = plataforma.tenant_atual() AND h.id_usuario = u.id_usuario
                        WHERE u.id_usuario = ? AND u.id_tenant = plataforma.tenant_atual()
                          AND NOT u.administrador AND u.controla_horario_acesso
                          AND h.dia_semana = EXTRACT(ISODOW FROM (now() AT TIME ZONE ?::text))
                          AND h.hora_inicio IS NOT NULL
                          AND (now() AT TIME ZONE ?::text)
                              > ((now() AT TIME ZONE ?::text)::date + h.hora_fim)
                          AND (now() AT TIME ZONE ?::text)
                              <= ((now() AT TIME ZONE ?::text)::date + h.hora_fim
                                  + (? * interval '1 minute'))
                        """)
                .params(fuso, toleranciaMinutos, fuso, idUsuario, fuso, fuso, fuso, fuso, fuso,
                        toleranciaMinutos)
                .query(Long.class)
                .optional()
                .orElse(null);
    }
}
