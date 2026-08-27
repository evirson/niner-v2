package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.identidade.usuario.HorarioAcessoService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Relê usuário e conta na hora de emitir o token da <b>segunda etapa</b> do login (V079).
 *
 * <p>⚠️ <b>Por que é um bean separado do {@link SignupService}.</b> Esta leitura precisa de
 * transação (o {@code SET LOCAL app.id_tenant} do RLS só existe dentro de uma), mas a conferência
 * do código <b>não pode</b> estar dentro de transação nenhuma — senão o rollback da exceção apaga
 * a tentativa errada que acabou de ser contada. Como método anotado chamado de dentro do próprio
 * bean não passa pelo proxy do Spring (armadilha já documentada neste projeto), a única forma de
 * ter os dois comportamentos no mesmo fluxo é a transação começar em <b>outro</b> bean.
 *
 * <p>⚠️ <b>E relê de propósito.</b> Entre a senha e o código passam até 10 minutos: o usuário pode
 * ter sido desativado ou promovido a administrador no meio. O token tem de refletir o agora, não o
 * que era verdade quando a senha foi digitada.
 */
@Component
public class ContaDoUsuario {

    private final JdbcClient jdbc;
    private final HorarioAcessoService horarioAcesso;

    public ContaDoUsuario(JdbcClient jdbc, HorarioAcessoService horarioAcesso) {
        this.jdbc = jdbc;
        this.horarioAcesso = horarioAcesso;
    }

    @Transactional
    public Dados buscar(long idTenant, long idUsuario) {
        jdbc.sql("SELECT set_config('app.id_tenant', ?, true)")
                .param(Long.toString(idTenant)).query(String.class).single();

        // id_tenant explícito além do RLS (P8): aqui um vazamento significaria emitir token de
        // outra conta.
        Dados dados = jdbc.sql("""
                        SELECT u.nome_usuario, u.email, u.administrador, u.ativo, t.slug
                        FROM usuario u
                        JOIN plataforma.tenant t ON t.id_tenant = u.id_tenant
                        WHERE u.id_tenant = ? AND u.id_usuario = ?
                        """)
                .params(idTenant, idUsuario)
                .query((rs, n) -> new Dados(rs.getString("nome_usuario"), rs.getString("email"),
                        rs.getBoolean("administrador"), rs.getBoolean("ativo"), rs.getString("slug")))
                .optional().orElse(null);

        if (dados == null || !dados.ativo()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Credenciais inválidas.");
        }
        return dados;
    }

    /**
     * Confere a janela de horário de acesso <b>dentro</b> de uma transação com o tenant posto.
     *
     * <p>⚠️ <b>Chamar {@code HorarioAcessoService.podeAcessarAgora} direto daqui do caminho público
     * não bloqueia nada.</b> A consulta dele termina em {@code AND u.id_tenant =
     * plataforma.tenant_atual()}; a segunda etapa do login roda sem {@code TenantContext} (é
     * pública — o token ainda não existe), então a consulta casa <b>zero linhas</b>, o
     * {@code .optional()} vem vazio e o {@code .orElse(true)} do serviço responde "pode acessar".
     * SELECT vazio lido como permissão. Medido: operador com janela até 18:00 entrava às 18:05
     * pela segunda etapa. É por isso que a checagem passa por aqui.
     */
    @Transactional
    public boolean podeAcessarAgora(long idTenant, long idUsuario) {
        jdbc.sql("SELECT set_config('app.id_tenant', ?, true)")
                .param(Long.toString(idTenant)).query(String.class).single();
        return horarioAcesso.podeAcessarAgora(idUsuario, 0);
    }

    public record Dados(String nome, String email, boolean administrador, boolean ativo, String slug) {
    }
}
