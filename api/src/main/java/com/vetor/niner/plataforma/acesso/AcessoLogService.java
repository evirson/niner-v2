package com.vetor.niner.plataforma.acesso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Grava quem entrou no ERP — sucessos <b>e</b> falhas (docs/MODULOLOGACESSO.md).
 *
 * <h2>⛔ Chamado FORA da transação do login. Sempre.</h2>
 *
 * <p>{@code SignupService.login} é {@code @Transactional}, e este registro acontece <b>no
 * controller, depois do retorno</b>. Não é estilo: em 2026-08-18 a medição do funil <b>dentro</b> da
 * transação do signup fez a API responder <b>{@code 201} com token válido e a conta inexistente</b>.
 * No Postgres um comando que falha <b>aborta a transação inteira</b>, e {@code try/catch} em Java
 * <b>não desfaz isso</b> — o {@code COMMIT} seguinte é tratado como {@code ROLLBACK} e devolve
 * sucesso. Ver {@code feedback_efeito_secundario_fora_da_transacao}.
 *
 * <h2>⚠️ E nunca derruba o login</h2>
 *
 * <p>{@link #registrar} engole qualquer exceção e apenas loga. <b>Trilha de auditoria não pode
 * impedir o lojista de trabalhar</b> — se a gravação falhar, perde-se uma linha do log, não a
 * venda do balcão. É escolha deliberada, e é o que o critério de aceitação nº 4 prende.
 *
 * <h2>⚠️ Sem TenantContext</h2>
 *
 * <p>Roda no caminho <b>público</b>, onde não há {@code TenantContext} — por isso a tabela vive em
 * {@code plataforma} (sem RLS), como {@code codigo_login} e {@code recuperacao_senha}. O
 * {@code id_tenant} é <b>coluna comum</b> aqui, não discriminador de RLS.
 */
@Service
public class AcessoLogService {

    private static final Logger log = LoggerFactory.getLogger(AcessoLogService.class);

    private final JdbcClient jdbc;

    public AcessoLogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * O que o servidor sabe sobre a origem da tentativa — montado no controller, a partir da
     * requisição HTTP.
     *
     * @param ip           já resolvido por {@code IpDoCliente}: atrás do nginx, {@code X-Real-IP};
     *                     nunca {@code getRemoteAddr()} cru, que devolveria o IP do proxy
     * @param ipConfiavel  gravado junto porque, sem ele, ninguém sabe depois se o endereço é do
     *                     cliente ou do proxy
     */
    public record Origem(String ip, boolean ipConfiavel, String userAgent) {
    }

    /**
     * @param idTenant  {@code null} quando o e-mail não pertence a conta nenhuma — a tentativa
     *                  existe e precisa ser registrada mesmo assim
     * @param idUsuario {@code null} pelo mesmo motivo
     * @param email     <b>o que foi digitado</b>, não o do cadastro: é o que a auditoria precisa
     *                  ver, inclusive quando ninguém com esse e-mail existe
     */
    public void registrar(ResultadoAcesso resultado, Long idTenant, Long idUsuario, Long idEmpresa,
                          String email, Origem origem) {
        try {
            UserAgentLido lido = UserAgentLido.de(origem == null ? null : origem.userAgent());
            jdbc.sql("""
                            INSERT INTO plataforma.acesso_login
                                (id_tenant, id_usuario, id_empresa, email_informado, resultado,
                                 ip, ip_confiavel, user_agent, so, navegador, dispositivo)
                            VALUES (?, ?, ?, ?, ?, ?::inet, ?, ?, ?, ?, ?)
                            """)
                    .params(idTenant, idUsuario, idEmpresa, email, resultado.name(),
                            origem == null ? null : origem.ip(),
                            origem != null && origem.ipConfiavel(),
                            origem == null ? null : origem.userAgent(),
                            lido.so(), lido.navegador(), lido.dispositivo())
                    .update();
        } catch (RuntimeException e) {
            // ⚠️ Engolir é o comportamento correto — ver o javadoc da classe. Mas nunca em
            // silêncio: sem este log, o dia em que a tabela ficar inacessível ninguém descobre,
            // e a auditoria fica com um buraco que ninguém sabe explicar.
            log.error("Não foi possível registrar o acesso de {} ({}) — o login seguiu normalmente.",
                    email, resultado, e);
        }
    }
}
