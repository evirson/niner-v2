package com.vetor.niner.identidade.permissao;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Permissão por tela e por ação (RBAC do ERP, V073).
 *
 * <p><b>Sem perfis, por decisão do dono do produto</b> (2026-08-27): <i>"às vezes para um tenant o
 * estoquista pode fazer uma coisa, e no outro tenant vai fazer coisas diferentes"</i>. A grade é do
 * usuário.
 *
 * <p><b>Duas regras que valem mais que o código:</b>
 * <ul>
 *   <li><b>ADMIN pode tudo</b> — não tem grade, não é consultado, não pode ser restringido. É um
 *       por conta e imutável (índice {@code usuario_um_admin_uk}, V015).</li>
 *   <li><b>Ausência de linha = nada.</b> Usuário novo não enxerga nenhuma tela até o admin liberar.
 *       O contrário — nascer com tudo e ir tirando — deixaria uma tela nova acessível a todo mundo
 *       no dia em que ela fosse criada, que é exatamente o erro que ninguém percebe.</li>
 * </ul>
 */
@Service
public class PermissaoService {

    /** Ações de uma tela. {@code ACESSAR} é pré-requisito das outras três (CHECK na V073). */
    public enum Acao {
        ACESSAR, INCLUIR, ALTERAR, EXCLUIR
    }

    private static final String SQL_DA_TELA = """
            SELECT acessar, incluir, alterar, excluir
              FROM usuario_permissao
             WHERE id_tenant = plataforma.tenant_atual() AND id_usuario = ? AND chave_tela = ?
            """;

    private static final String SQL_TELA_ADMIN = "SELECT admin_apenas FROM cfg_tela WHERE chave = ?";

    private final JdbcClient jdbc;

    public PermissaoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean ehAdmin(Jwt jwt) {
        Object roles = jwt.getClaim("roles");
        return roles instanceof List<?> lista && lista.contains("ADMIN");
    }

    /**
     * {@code true} se o usuário pode fazer {@code acao} em {@code chaveTela}.
     *
     * <p>⚠️ Tela marcada {@code admin_apenas} é <b>fechada para todo não-admin</b>, mesmo que
     * alguém tenha marcado a permissão na grade: são as telas que mexem na conta e no resultado do
     * negócio (Configurações, DRE, Lucratividade). A grade não pode conceder o que o produto não
     * permite conceder.
     */
    @Transactional(readOnly = true)
    public boolean pode(Jwt jwt, String chaveTela, Acao acao) {
        if (ehAdmin(jwt)) {
            return true;
        }
        boolean somenteAdmin = Boolean.TRUE.equals(
                jdbc.sql(SQL_TELA_ADMIN).param(chaveTela).query(Boolean.class).optional().orElse(false));
        if (somenteAdmin) {
            return false;
        }
        return jdbc.sql(SQL_DA_TELA)
                .params(Long.parseLong(jwt.getSubject()), chaveTela)
                .query((rs, n) -> switch (acao) {
                    case ACESSAR -> rs.getBoolean("acessar");
                    case INCLUIR -> rs.getBoolean("incluir");
                    case ALTERAR -> rs.getBoolean("alterar");
                    case EXCLUIR -> rs.getBoolean("excluir");
                })
                .optional()
                .orElse(false);
    }

    /**
     * Mesma checagem, mas responde <b>403</b> em vez de {@code false}.
     *
     * <p>A mensagem nomeia a tela e a ação de propósito: "acesso negado" sozinho faz o operador
     * ligar para o suporte, enquanto "você não tem permissão para excluir em Clientes" ele resolve
     * pedindo ao próprio administrador da loja.
     */
    public void exigir(Jwt jwt, String chaveTela, Acao acao) {
        if (pode(jwt, chaveTela, acao)) {
            return;
        }
        String nome = jdbc.sql("SELECT nome FROM cfg_tela WHERE chave = ?")
                .param(chaveTela).query(String.class).optional().orElse(chaveTela);
        throw new ResponseStatusException(FORBIDDEN,
                "Você não tem permissão para %s em %s. Peça ao administrador da conta."
                        .formatted(acao.name().toLowerCase(), nome));
    }
}
