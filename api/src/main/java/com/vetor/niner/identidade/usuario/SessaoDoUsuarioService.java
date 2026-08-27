package com.vetor.niner.identidade.usuario;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Estado da sessão de um usuário do tenant, em <b>uma consulta só</b> (V080, 2026-08-27).
 *
 * <p>Responde de uma vez as três perguntas que toda requisição autenticada precisa fazer:
 * <ol>
 *   <li>o usuário ainda existe e está <b>ativo</b>?</li>
 *   <li>está dentro do <b>horário de acesso</b> dele?</li>
 *   <li>o <b>token</b> dele ainda vale, ou foi revogado por troca de senha/desativação?</li>
 * </ol>
 *
 * <p>⭐ <b>Por que isso é barato.</b> A consulta do horário de acesso já rodava a cada requisição
 * desde 2026-08-11. As outras duas perguntas entraram na mesma linha do mesmo {@code SELECT} pela
 * mesma PK — <b>nenhuma consulta nova</b>, nenhum cache para invalidar, nenhuma tabela de sessões.
 * Era o requisito do dono do produto: revogar "o mais breve possível" sem deixar o sistema lento.
 *
 * <p>⚠️ <b>Revogação sem lista de revogação.</b> Comparar o {@code iat} (que já vem assinado
 * dentro do JWT) com {@code usuario.sessao_valida_desde} dispensa guardar estado de sessão no
 * servidor (P6). Trocar a senha ou desativar o usuário só avança essa coluna; o token antigo morre
 * na requisição seguinte.
 */
@Service
public class SessaoDoUsuarioService {

    private final JdbcClient jdbc;

    public SessaoDoUsuarioService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param toleranciaMinutos somados ao fim da janela do dia — ver
     *                          {@link HorarioAcessoService#podeAcessarAgora}
     * @param emitidoEm o {@code iat} do token; {@code null} deixa a revogação passar (não há o que
     *                  comparar), porque negar acesso por um claim ausente derrubaria o sistema
     *                  inteiro se algum emissor parasse de mandá-lo
     */
    @Transactional(readOnly = true)
    public Veredito avaliar(long idUsuario, int toleranciaMinutos, Instant emitidoEm) {
        Estado e = jdbc.sql("""
                        SELECT u.ativo,
                               u.sessao_valida_desde,
                               (u.administrador OR NOT u.controla_horario_acesso OR EXISTS (
                                   SELECT 1 FROM usuario_horario_acesso h
                                   WHERE h.id_tenant = plataforma.tenant_atual() AND h.id_usuario = u.id_usuario
                                     AND h.dia_semana = EXTRACT(ISODOW FROM (now() AT TIME ZONE 'America/Sao_Paulo'))
                                     AND h.hora_inicio IS NOT NULL
                                     AND (now() AT TIME ZONE 'America/Sao_Paulo')
                                         BETWEEN ((now() AT TIME ZONE 'America/Sao_Paulo')::date + h.hora_inicio)
                                             AND ((now() AT TIME ZONE 'America/Sao_Paulo')::date + h.hora_fim
                                                  + (? * interval '1 minute'))
                               )) AS dentro_da_janela
                        FROM usuario u
                        WHERE u.id_usuario = ? AND u.id_tenant = plataforma.tenant_atual()
                        """)
                .params(toleranciaMinutos, idUsuario)
                .query((rs, n) -> new Estado(rs.getBoolean("ativo"),
                        rs.getObject("sessao_valida_desde", OffsetDateTime.class),
                        rs.getBoolean("dentro_da_janela")))
                .optional().orElse(null);

        // ⚠️ Linha ausente = usuário EXCLUÍDO (ou id que não é deste tenant): sessão morta. Até a
        // V080 este caso respondia "pode acessar" — o `.orElse(true)` de HorarioAcessoService —,
        // e era assim que o token de um usuário apagado seguia funcionando por até 8 horas.
        if (e == null) {
            return Veredito.SESSAO_ENCERRADA;
        }
        if (!e.ativo()) {
            return Veredito.SESSAO_ENCERRADA;
        }
        // ⚠️ COMPARAR NO SEGUNDO, não no instante — e isto não é preciosismo: o `iat` do JWT é um
        // NumericDate, contado em segundos inteiros, então ele é sempre ANTERIOR ao instante real
        // da emissão (10:00:05.400 vira 10:00:05). Comparando cru, um usuário recém-criado
        // (`sessao_valida_desde` = 10:00:05.100 pelo DEFAULT da V080) já nasceria com a sessão
        // "revogada" — medido: o token que o signup acabava de devolver era recusado na primeira
        // requisição, e o teste que pegou isso foi justamente o caso NEGATIVO ("editar o nome não
        // pode derrubar ninguém"). Truncar também a coluna dá no máximo 1 segundo de sobrevida a
        // um token revogado, o que é irrelevante, e elimina o falso positivo por completo.
        if (emitidoEm != null && e.sessaoValidaDesde() != null
                && emitidoEm.isBefore(e.sessaoValidaDesde().toInstant().truncatedTo(ChronoUnit.SECONDS))) {
            return Veredito.SESSAO_ENCERRADA;
        }
        return e.dentroDaJanela() ? Veredito.OK : Veredito.FORA_DO_HORARIO;
    }

    public enum Veredito {
        OK,
        /** Fora da janela de horário de acesso — mensagem própria, o usuário volta depois. */
        FORA_DO_HORARIO,
        /** Senha trocada, usuário desativado ou excluído: precisa entrar de novo. */
        SESSAO_ENCERRADA
    }

    private record Estado(boolean ativo, OffsetDateTime sessaoValidaDesde, boolean dentroDaJanela) {
    }
}
