package com.vetor.niner.plataforma.diretorio;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Diretório de login — descobre <b>em que conta</b> mora um e-mail, antes de qualquer contexto de
 * tenant existir.
 *
 * <p><b>Por que isto é uma classe só, e não uma consulta solta dentro do login.</b> Este é o
 * único ponto do sistema que responde "quem é o dono deste e-mail". Hoje ele lê
 * {@code plataforma.diretorio_login} (V071), uma tabela local; quando o parque de células existir
 * (parque &gt; célula &gt; tenant &gt; empresa, ver {@code docs/infra/parque-de-celulas.md}), a
 * mesma pergunta passa a ser feita ao <b>catálogo</b>, que fica fora das células. Concentrar a
 * pergunta aqui é o que torna essa troca uma mudança de uma classe.
 *
 * <p><b>Nada aqui autentica.</b> O diretório é um índice: diz onde procurar, não se a senha está
 * certa. A senha continua sendo conferida dentro da célula, contra {@code usuario.senha_hash} —
 * é por isso que o diretório não guarda hash nenhum, e um vazamento dele não vira invasão.
 *
 * <p><b>Sem RLS, de propósito</b> (mesma exceção de {@code plataforma.recuperacao_senha} e
 * {@code plataforma.oauth_estado_canal}): quem consulta ainda <i>não sabe</i> de que tenant é a
 * requisição — é justamente o que está tentando descobrir. Sob RLS a consulta devolveria zero
 * linhas em silêncio e todo login falharia sem nada em log.
 */
@Repository
public class DiretorioLogin {

    /**
     * Ordem estável por conta: a lista chega ao usuário como opções numa tela, e opção que muda
     * de lugar entre duas tentativas é opção clicada por engano.
     */
    private static final String SQL_POR_EMAIL = """
            SELECT d.id_celula, d.id_tenant, d.id_usuario, t.nome_conta, t.slug
              FROM plataforma.diretorio_login d
              JOIN plataforma.tenant t ON t.id_tenant = d.id_tenant
             WHERE d.email = lower(btrim(?))
             ORDER BY d.id_tenant
            """;

    private final JdbcClient jdbc;

    public DiretorioLogin(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Contas em que este e-mail existe — vazio quando não existe nenhuma.
     *
     * <p>⚠️ O resultado <b>não</b> pode chegar ao cliente HTTP como está: ele afirma quantas
     * contas usam aquele e-mail, que é informação de quem ainda não provou ser dono dele.
     */
    @Transactional(readOnly = true)
    public List<ContaCandidata> porEmail(String email) {
        return jdbc.sql(SQL_POR_EMAIL)
                .param(email)
                .query((rs, n) -> new ContaCandidata(
                        rs.getShort("id_celula"),
                        rs.getLong("id_tenant"),
                        rs.getLong("id_usuario"),
                        rs.getString("nome_conta"),
                        rs.getString("slug")))
                .list();
    }

    /**
     * Uma conta candidata do diretório.
     *
     * @param idCelula em que célula abrir a conexão para conferir a senha. Hoje é sempre 1 (uma
     *                 célula só) e ninguém o consome ainda — está aqui porque é exatamente o dado
     *                 que falta no dia em que houver a segunda, e descobri-lo depois exigiria
     *                 reescrever a consulta e a tabela.
     */
    public record ContaCandidata(short idCelula, long idTenant, long idUsuario, String nomeConta, String slug) {
    }
}
