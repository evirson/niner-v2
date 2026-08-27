package com.vetor.niner.identidade.permissao;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Grade de permissões por tela (V073).
 *
 * <p>Três endpoints, e a diferença entre eles é quem pergunta:
 * <ul>
 *   <li>{@code GET /api/v1/telas} — o catálogo, para montar a grade;</li>
 *   <li>{@code GET /api/v1/eu/permissoes} — <b>o próprio usuário</b>, para o front saber o que
 *       mostrar no menu e quais botões habilitar;</li>
 *   <li>{@code GET|PUT /api/v1/usuarios/{id}/permissoes} — <b>o administrador</b>, para conceder.</li>
 * </ul>
 *
 * <p>⚠️ O que o front faz com {@code /eu/permissoes} é <b>conveniência</b>. A trava que vale é a do
 * servidor, em cada rotina ({@link PermissaoService#exigir}). Esconder o botão evita o erro
 * honesto; não evita o `curl`.
 */
@RestController
public class PermissaoController {

    private static final String SQL_CATALOGO = """
            SELECT chave, nome, grupo, subgrupo, admin_apenas, tem_incluir, tem_alterar, tem_excluir FROM cfg_tela ORDER BY ordem
            """;

    private static final String SQL_DO_USUARIO = """
            SELECT t.chave, t.nome, t.grupo, t.subgrupo, t.admin_apenas,
                   t.tem_incluir, t.tem_alterar, t.tem_excluir,
                   COALESCE(p.acessar, false) AS acessar,
                   COALESCE(p.incluir, false) AS incluir,
                   COALESCE(p.alterar, false) AS alterar,
                   COALESCE(p.excluir, false) AS excluir
              FROM cfg_tela t
              LEFT JOIN usuario_permissao p
                     ON p.chave_tela = t.chave
                    AND p.id_usuario = ?
                    AND p.id_tenant = plataforma.tenant_atual()
             ORDER BY t.ordem
            """;

    private final JdbcClient jdbc;

    public PermissaoController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/telas")
    @Transactional(readOnly = true)
    public List<TelaResponse> catalogo() {
        return jdbc.sql(SQL_CATALOGO)
                .query((rs, n) -> new TelaResponse(rs.getString("chave"), rs.getString("nome"),
                        rs.getString("grupo"), rs.getString("subgrupo"), rs.getBoolean("admin_apenas"),
                        rs.getBoolean("tem_incluir"), rs.getBoolean("tem_alterar"), rs.getBoolean("tem_excluir")))
                .list();
    }

    /**
     * O que <b>eu</b> posso. Para o ADMIN devolve tudo liberado sem consultar a grade — ele não
     * tem grade, e montar uma para ele daria a impressão de que dá para restringi-lo.
     */
    @GetMapping("/api/v1/eu/permissoes")
    @Transactional(readOnly = true)
    public List<PermissaoResponse> minhas(@AuthenticationPrincipal Jwt jwt) {
        boolean admin = PermissaoService.ehAdmin(jwt);
        return jdbc.sql(SQL_DO_USUARIO)
                .param(Long.parseLong(jwt.getSubject()))
                .query((rs, n) -> {
                    // Tela admin-only some para quem não é admin, mesmo que a grade diga o
                    // contrário. Sem isto o menu ofereceria a tela e a rotina negaria depois — o
                    // usuário culparia o sistema, não a permissão.
                    boolean bloqueada = !admin && rs.getBoolean("admin_apenas");
                    return new PermissaoResponse(
                            rs.getString("chave"), rs.getString("nome"), rs.getString("grupo"), rs.getString("subgrupo"),
                            rs.getBoolean("tem_incluir"), rs.getBoolean("tem_alterar"), rs.getBoolean("tem_excluir"),
                            !bloqueada && (admin || rs.getBoolean("acessar")),
                            !bloqueada && (admin || rs.getBoolean("incluir")),
                            !bloqueada && (admin || rs.getBoolean("alterar")),
                            !bloqueada && (admin || rs.getBoolean("excluir")));
                })
                .list();
    }

    @GetMapping("/api/v1/usuarios/{idUsuario}/permissoes")
    @Transactional(readOnly = true)
    public List<PermissaoResponse> doUsuario(@AuthenticationPrincipal Jwt jwt, @PathVariable long idUsuario) {
        exigirAdmin(jwt);
        exigirUsuarioDoTenant(idUsuario);
        return jdbc.sql(SQL_DO_USUARIO)
                .param(idUsuario)
                .query((rs, n) -> new PermissaoResponse(
                        rs.getString("chave"), rs.getString("nome"), rs.getString("grupo"), rs.getString("subgrupo"),
                        rs.getBoolean("tem_incluir"), rs.getBoolean("tem_alterar"), rs.getBoolean("tem_excluir"),
                        rs.getBoolean("acessar"), rs.getBoolean("incluir"),
                        rs.getBoolean("alterar"), rs.getBoolean("excluir")))
                .list();
    }

    /**
     * Substitui a grade inteira do usuário — apaga e regrava, o mesmo idioma de
     * {@code UsuarioService.salvarEmpresas}. Enviar a grade completa evita o modo de falha do
     * "só o que mudou": uma permissão desmarcada que não chega ao servidor continuaria valendo.
     */
    @PutMapping("/api/v1/usuarios/{idUsuario}/permissoes")
    @Transactional
    public List<PermissaoResponse> salvar(@AuthenticationPrincipal Jwt jwt, @PathVariable long idUsuario,
            @Valid @RequestBody List<@Valid PermissaoRequest> grade) {
        exigirAdmin(jwt);
        exigirUsuarioDoTenant(idUsuario);
        if (idUsuario == Long.parseLong(jwt.getSubject())) {
            // O admin não tem grade e não pode criar uma para si — seria o caminho para uma conta
            // sem administrador nenhum, e não existe quem devolva o acesso depois.
            throw new ResponseStatusException(FORBIDDEN, "O administrador tem acesso a tudo; não há o que configurar.");
        }

        jdbc.sql("DELETE FROM usuario_permissao WHERE id_tenant = plataforma.tenant_atual() AND id_usuario = ?")
                .param(idUsuario).update();

        // ⚠️ Recusa em vez de ignorar em silêncio: o admin marcou aquilo porque queria conceder,
        // e uma tela que "não salva" sem explicação vira chamado de suporte.
        List<String> bloqueadas = jdbc.sql(
                        "SELECT nome FROM cfg_tela WHERE admin_apenas AND chave = ANY (?)")
                .param(grade.stream().filter(PermissaoRequest::acessar)
                        .map(PermissaoRequest::chaveTela).toArray(String[]::new))
                .query(String.class).list();
        if (!bloqueadas.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Estas telas são exclusivas do administrador e não podem ser concedidas: "
                            + String.join(", ", bloqueadas) + ".");
        }

        Map<String, AcoesDaTela> acoes = acoesPorTela();
        for (PermissaoRequest p : grade) {
            AcoesDaTela disponiveis = acoes.getOrDefault(p.chaveTela(), new AcoesDaTela(false, false, false));
            // Linha sem nada marcado não é gravada: ausência já significa "sem acesso", e gravar
            // 63 linhas de `false` por usuário só encheria a tabela.
            if (!p.acessar() && !p.incluir() && !p.alterar() && !p.excluir()) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO usuario_permissao
                                (id_tenant, id_usuario, chave_tela, acessar, incluir, alterar, excluir)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?)
                            """)
                    // Marcar uma ação sem "acessar" é recusado pelo banco (CHECK da V073); aqui o
                    // acessar é ligado junto, porque a intenção de quem marcou "pode excluir" é
                    // óbvia e devolver erro por isso seria implicância.
                    // A ação que a tela não tem é descartada aqui também, não só na interface:
                    // um cliente de API poderia mandar "excluir" num relatório e a grade passaria
                    // a exibir uma permissão impossível.
                    .params(idUsuario, p.chaveTela(), true,
                            p.incluir() && disponiveis.incluir(),
                            p.alterar() && disponiveis.alterar(),
                            p.excluir() && disponiveis.excluir())
                    .update();
        }
        return doUsuario(jwt, idUsuario);
    }

    /**
     * Quais ações cada tela tem (V076), num mapa só.
     *
     * <p>⚠️ Lido de uma vez, e não por linha: com 57 telas × 3 colunas, consultar dentro do laço
     * daria 171 idas ao banco a cada gravação. E evita concatenar nome de coluna em SQL, ainda que
     * viesse de constante — o projeto não abre exceção para isso.
     */
    private Map<String, AcoesDaTela> acoesPorTela() {
        return jdbc.sql("SELECT chave, tem_incluir, tem_alterar, tem_excluir FROM cfg_tela")
                .query((rs, n) -> Map.entry(rs.getString("chave"), new AcoesDaTela(
                        rs.getBoolean("tem_incluir"), rs.getBoolean("tem_alterar"), rs.getBoolean("tem_excluir"))))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private record AcoesDaTela(boolean incluir, boolean alterar, boolean excluir) {
    }

    private void exigirAdmin(Jwt jwt) {
        if (!PermissaoService.ehAdmin(jwt)) {
            throw new ResponseStatusException(FORBIDDEN, "Só o administrador da conta configura permissões.");
        }
    }

    /** P8: id vindo do cliente é sempre conferido contra o tenant da sessão. */
    private void exigirUsuarioDoTenant(long idUsuario) {
        boolean existe = Boolean.TRUE.equals(jdbc.sql(
                        "SELECT exists(SELECT 1 FROM usuario WHERE id_tenant = plataforma.tenant_atual() AND id_usuario = ?)")
                .param(idUsuario).query(Boolean.class).single());
        if (!existe) {
            throw new ResponseStatusException(NOT_FOUND, "Usuário não encontrado.");
        }
    }

    public record TelaResponse(String chave, String nome, String grupo, String subgrupo, boolean adminApenas,
            boolean temIncluir, boolean temAlterar, boolean temExcluir) {
    }

        /**
     * ⚠️ `temIncluir`/`temAlterar`/`temExcluir` dizem se a AÇÃO EXISTE naquela tela — não se o
     * usuário a tem. Um relatório não tem "incluir"; marcar essa caixa concederia uma permissão
     * que não existe e sugeriria um controle que o sistema não faz.
     */
    public record PermissaoResponse(String chave, String nome, String grupo, String subgrupo,
            boolean temIncluir, boolean temAlterar, boolean temExcluir,
            boolean acessar, boolean incluir, boolean alterar, boolean excluir) {
    }

    public record PermissaoRequest(@NotBlank String chaveTela,
            boolean acessar, boolean incluir, boolean alterar, boolean excluir) {
    }
}
