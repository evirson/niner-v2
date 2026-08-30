package com.vetor.niner.identidade.usuario;

import com.vetor.niner.comum.tempo.FusoDaUf;
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
    /** Só é consultado nas UFs fora do fuso de Brasília — ver o comentário em `avaliar`. */
    private final HorarioAcessoService horarioAcesso;

    public SessaoDoUsuarioService(JdbcClient jdbc, HorarioAcessoService horarioAcesso) {
        this.jdbc = jdbc;
        this.horarioAcesso = horarioAcesso;
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
                               (u.administrador OR NOT u.controla_horario_acesso) AS dispensado,
                               emp.estado AS uf,
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
                        LEFT JOIN empresa emp
                               ON emp.id_empresa = u.id_empresa AND emp.id_tenant = u.id_tenant
                        WHERE u.id_usuario = ? AND u.id_tenant = plataforma.tenant_atual()
                        """)
                .params(toleranciaMinutos, idUsuario)
                .query((rs, n) -> new Estado(rs.getBoolean("ativo"),
                        rs.getObject("sessao_valida_desde", OffsetDateTime.class),
                        rs.getBoolean("dentro_da_janela"),
                        rs.getBoolean("dispensado"),
                        rs.getString("uf")))
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
        // ⛔ A janela acima foi calculada em `America/Sao_Paulo`, e o expediente é no RELÓGIO DA
        // LOJA (auditoria 2026-08-29, rodada 2). Numa loja do Acre (UTC−5) com janela 08:00–18:00,
        // às 16:00 do relógio da loja já são 18:00 em Brasília: o operador era expulso DUAS HORAS
        // antes do fim do expediente, com venda aberta, e o login não o deixava voltar.
        //
        // ⚠️ A recomputação só acontece nas UFs cujo fuso NÃO é o de Brasília — AM, RO, RR, MT, MS
        // (1 h) e AC (2 h). Para o resto do país, e para quem nem controla horário, o custo desta
        // correção é exatamente zero: nenhuma consulta a mais numa rotina que roda em TODA
        // requisição autenticada. Trocar um erro de 1 h em seis UFs por um SELECT por requisição
        // em todas seria um mau negócio.
        if (!e.dispensado() && !FusoDaUf.deOuPadrao(e.uf()).equals(FusoDaUf.PADRAO)) {
            return horarioAcesso.podeAcessarAgora(idUsuario, toleranciaMinutos)
                    ? Veredito.OK : Veredito.FORA_DO_HORARIO;
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

    /** {@code uf} e {@code dispensado} existem só para decidir se vale recomputar a janela no fuso
     *  da loja — ver o comentário em `avaliar`. */
    private record Estado(boolean ativo, OffsetDateTime sessaoValidaDesde, boolean dentroDaJanela,
                          boolean dispensado, String uf) {
    }
}
