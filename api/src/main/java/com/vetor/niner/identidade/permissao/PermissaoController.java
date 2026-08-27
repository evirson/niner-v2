package com.vetor.niner.identidade.permissao;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
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

    /**
     * A mesma grade, sem as telas exclusivas do administrador.
     *
     * <p>⚠️ Elas não são apenas "não concedíveis": elas <b>não aparecem</b>. Antes desta
     * separação a tela listava tudo e o salvamento recusava — ao clicar em "liberar tudo", o
     * administrador recebia um erro citando 22 telas que ele nem sabia estarem ali. Oferecer o
     * que o sistema depois recusa é convidar ao erro.
     */
    private static final String SQL_DO_USUARIO_CONCEDIVEIS =
            SQL_DO_USUARIO.replace("ORDER BY t.ordem", "WHERE NOT t.admin_apenas ORDER BY t.ordem");

    private final JdbcClient jdbc;
    private final PermissaoService permissoes;

    public PermissaoController(JdbcClient jdbc, PermissaoService permissoes) {
        this.jdbc = jdbc;
        this.permissoes = permissoes;
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
        exigirQuemConfiguraPermissao(jwt);
        exigirUsuarioDoTenant(idUsuario);
        // ⚠️ Quem não é admin vê na grade SÓ o que pode delegar. Mostrar tudo e recusar no Salvar
        // é o mesmo incômodo que o dono do produto relatou com o "liberar tudo": ele marcaria
        // telas fora do alcance e só descobriria no fim, com um erro listando o que não pode.
        return recortarPeloMeuTeto(jwt, doUsuarioSemChecar(idUsuario));
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
        exigirQuemConfiguraPermissao(jwt);
        exigirUsuarioDoTenant(idUsuario);
        exigirDentroDoTeto(jwt, idUsuario, grade);
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

    /**
     * Quem pode mexer na grade de outro: o administrador, ou quem recebeu a tela <b>Usuários</b>
     * com "alterar" — decisão do dono do produto (2026-08-27), junto com a regra do teto abaixo.
     */
    private void exigirQuemConfiguraPermissao(Jwt jwt) {
        if (PermissaoService.ehAdmin(jwt)) {
            return;
        }
        if (!permissoes.pode(jwt, "usuarios", PermissaoService.Acao.ALTERAR)) {
            throw new ResponseStatusException(FORBIDDEN,
                    "Você não tem permissão para configurar as permissões de outros usuários.");
        }
    }

    /**
     * <b>Ninguém delega o que não tem.</b> Regra dele: <i>"as permissões que ele pode delegar para
     * este novo usuário são no máximo as permissões que ele tem"</i>.
     *
     * <p>⚠️ O teto sozinho seria contornável em dois passos, e por isso vêm junto duas travas:
     * <ul>
     *   <li><b>não editar a própria grade</b> — senão ele marca tudo para si e o teto vira
     *       decoração;</li>
     *   <li><b>não editar quem tem mais permissão que ele</b> — senão tira o acesso de um colega
     *       mais graduado, ou usa a conta dele como degrau.</li>
     * </ul>
     *
     * <p>O administrador não passa por nada disto: ele tem tudo, e o teto dele é o sistema inteiro.
     */
    private void exigirDentroDoTeto(Jwt jwt, long idAlvo, List<PermissaoRequest> grade) {
        if (PermissaoService.ehAdmin(jwt)) {
            return;
        }
        long idQuemConcede = Long.parseLong(jwt.getSubject());
        if (idQuemConcede == idAlvo) {
            throw new ResponseStatusException(FORBIDDEN,
                    "Você não pode alterar as suas próprias permissões. Peça ao administrador da conta.");
        }

        Map<String, PermissaoResponse> minhas = new java.util.HashMap<>();
        for (PermissaoResponse p : minhas(jwt)) {
            minhas.put(p.chave(), p);
        }

        // Quem já tem alguma permissão que eu não tenho está acima de mim — não mexo nele.
        for (PermissaoResponse alvo : doUsuarioSemChecar(idAlvo)) {
            PermissaoResponse eu = minhas.get(alvo.chave());
            boolean euNaoTenho = eu == null
                    || (alvo.acessar() && !eu.acessar()) || (alvo.incluir() && !eu.incluir())
                    || (alvo.alterar() && !eu.alterar()) || (alvo.excluir() && !eu.excluir());
            if (euNaoTenho) {
                throw new ResponseStatusException(FORBIDDEN,
                        "Este usuário tem permissões que você não possui (" + alvo.nome() + "). "
                                + "Só o administrador pode alterá-lo.");
            }
        }

        List<String> excedem = new ArrayList<>();
        for (PermissaoRequest pedido : grade) {
            PermissaoResponse eu = minhas.get(pedido.chaveTela());
            if (eu == null && (pedido.acessar() || pedido.incluir() || pedido.alterar() || pedido.excluir())) {
                excedem.add(pedido.chaveTela());
                continue;
            }
            if (eu == null) {
                continue;
            }
            if ((pedido.acessar() && !eu.acessar()) || (pedido.incluir() && !eu.incluir())
                    || (pedido.alterar() && !eu.alterar()) || (pedido.excluir() && !eu.excluir())) {
                excedem.add(eu.nome());
            }
        }
        if (!excedem.isEmpty()) {
            throw new ResponseStatusException(FORBIDDEN,
                    "Você só pode conceder o que você mesmo tem. Fora do seu alcance: "
                            + String.join(", ", excedem) + ".");
        }
    }

    /**
     * Deixa na grade apenas as telas que <b>quem está configurando</b> pode delegar. O
     * administrador recebe tudo — o teto dele é o sistema inteiro.
     */
    private List<PermissaoResponse> recortarPeloMeuTeto(Jwt jwt, List<PermissaoResponse> grade) {
        if (PermissaoService.ehAdmin(jwt)) {
            return grade;
        }
        Map<String, PermissaoResponse> minhas = new java.util.HashMap<>();
        for (PermissaoResponse p : minhas(jwt)) {
            minhas.put(p.chave(), p);
        }
        List<PermissaoResponse> recortada = new ArrayList<>();
        for (PermissaoResponse p : grade) {
            PermissaoResponse eu = minhas.get(p.chave());
            if (eu == null || !eu.acessar()) {
                continue;
            }
            // As AÇÕES que eu não tenho somem da linha, pelo mesmo motivo: a tela mostraria uma
            // caixa que o servidor depois recusaria.
            recortada.add(new PermissaoResponse(p.chave(), p.nome(), p.grupo(), p.subgrupo(),
                    p.temIncluir() && eu.incluir(), p.temAlterar() && eu.alterar(),
                    p.temExcluir() && eu.excluir(),
                    p.acessar(), p.incluir(), p.alterar(), p.excluir()));
        }
        return recortada;
    }

    /** A grade de um usuário, sem checar quem está perguntando — uso interno do teto. */
    private List<PermissaoResponse> doUsuarioSemChecar(long idUsuario) {
        return jdbc.sql(SQL_DO_USUARIO_CONCEDIVEIS).param(idUsuario).query(PermissaoController::mapear).list();
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

    private static PermissaoResponse mapear(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PermissaoResponse(
                rs.getString("chave"), rs.getString("nome"), rs.getString("grupo"), rs.getString("subgrupo"),
                rs.getBoolean("tem_incluir"), rs.getBoolean("tem_alterar"), rs.getBoolean("tem_excluir"),
                rs.getBoolean("acessar"), rs.getBoolean("incluir"),
                rs.getBoolean("alterar"), rs.getBoolean("excluir"));
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
