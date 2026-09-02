package com.vetor.niner.plataforma.acesso;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Log de acesso ao ERP, para a <b>Vetor</b> (docs/MODULOLOGACESSO.md).
 *
 * <h2>⛔ Só existe sob {@code /api/admin}. Nunca sob {@code /api/v1}.</h2>
 *
 * <p>É o que garante que o <b>administrador do tenant não vê</b> estes registros — decisão do dono
 * do produto: <i>"se o funcionário pedir os logs para o patrão, o patrão vai ter que pedir pra
 * Vetor"</i>.
 *
 * <p>⚠️ A tabela mora em {@code plataforma} <b>sem RLS</b>, então ela é alcançável por qualquer
 * query da aplicação: o que separa os dois mundos é a <b>superfície</b> ({@code /api/v1} só aceita
 * {@code aud=tenant}, {@code /api/admin} só {@code aud=plataforma}, com dois {@code JwtDecoder}
 * distintos) somada ao fato de <b>não existir a porta</b> do lado do lojista. Por isso há teste
 * prendendo exatamente isso — se alguém escrever esse endpoint um dia, ele reprova.
 *
 * <p>🔵 <b>Qualquer papel de staff lê</b>, no mesmo padrão de {@code TenantAdminController}.
 * Recomendei restringir a {@code SUPER_ADMIN} (a tela expõe IP e comportamento de funcionários dos
 * clientes) e o dono do produto decidiu que qualquer staff vê — fica registrado como escolha.
 */
@RestController
@RequestMapping("/api/admin/acessos")
public class AcessoLogAdminController {

    /** Teto por página: a tela pagina, e um período largo traria centenas de milhares de linhas. */
    private static final int LIMITE_MAXIMO = 200;

    private final JdbcClient jdbc;

    public AcessoLogAdminController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Pagina listar(@AuthenticationPrincipal Jwt jwt,
                         @RequestParam(required = false) String de,
                         @RequestParam(required = false) String ate,
                         @RequestParam(required = false) Long idTenant,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String resultado,
                         @RequestParam(defaultValue = "false") boolean somenteFalhas,
                         @RequestParam(defaultValue = "1") int pagina,
                         @RequestParam(defaultValue = "50") int limite) {
        exigirStaff(jwt);

        int tamanho = Math.min(Math.max(limite, 1), LIMITE_MAXIMO);
        int deslocamento = (Math.max(pagina, 1) - 1) * tamanho;

        // ⚠️ Filtros por PARÂMETRO, nunca concatenando o que o cliente mandou — inclusive
        // `resultado`, que parece inofensivo por ser um enum na tela e chega como texto aqui.
        // ⚠️ E todas as colunas prefixadas com `a.`: `id_tenant` existe nas DUAS tabelas do JOIN,
        // e sem o prefixo o Postgres recusa por ambiguidade.
        StringBuilder onde = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (de != null && !de.isBlank()) {
            onde.append(" AND a.ocorrido_em >= ?::timestamptz");
            params.add(de);
        }
        if (ate != null && !ate.isBlank()) {
            // A tela manda o DIA; somar 1 dia é o que faz "até 05/09" incluir o próprio dia 5.
            onde.append(" AND a.ocorrido_em < (?::timestamptz + interval '1 day')");
            params.add(ate);
        }
        if (idTenant != null) {
            onde.append(" AND a.id_tenant = ?");
            params.add(idTenant);
        }
        if (email != null && !email.isBlank()) {
            onde.append(" AND lower(a.email_informado) LIKE '%' || lower(?) || '%'");
            params.add(email.trim());
        }
        if (resultado != null && !resultado.isBlank()) {
            onde.append(" AND a.resultado = ?");
            params.add(resultado.trim());
        }
        if (somenteFalhas) {
            onde.append(" AND a.resultado <> 'SUCESSO'");
        }

        // A contagem usa o MESMO `onde`, então precisa do mesmo alias — e não precisa do JOIN.
        long total = jdbc.sql("SELECT count(*) FROM plataforma.acesso_login a" + onde)
                .params(params).query(Long.class).single();

        List<Object> comPaginacao = new ArrayList<>(params);
        comPaginacao.add(tamanho);
        comPaginacao.add(deslocamento);

        List<Linha> itens = jdbc.sql("""
                        SELECT a.id_acesso, a.ocorrido_em, a.id_tenant, t.nome_conta,
                               a.email_informado, a.resultado, host(a.ip) AS ip, a.ip_confiavel,
                               a.so, a.navegador, a.dispositivo, a.user_agent
                          FROM plataforma.acesso_login a
                          LEFT JOIN plataforma.tenant t ON t.id_tenant = a.id_tenant
                        """
                        + onde
                        + " ORDER BY a.ocorrido_em DESC LIMIT ? OFFSET ?")
                .params(comPaginacao)
                .query((rs, n) -> new Linha(
                        rs.getLong("id_acesso"),
                        rs.getObject("ocorrido_em", OffsetDateTime.class),
                        (Long) rs.getObject("id_tenant"),
                        rs.getString("nome_conta"),
                        rs.getString("email_informado"),
                        rs.getString("resultado"),
                        rs.getString("ip"),
                        rs.getBoolean("ip_confiavel"),
                        rs.getString("so"),
                        rs.getString("navegador"),
                        rs.getString("dispositivo"),
                        rs.getString("user_agent")))
                .list();

        return new Pagina(itens, total, pagina, tamanho);
    }

    private static void exigirStaff(Jwt jwt) {
        if (jwt == null || jwt.getClaimAsString("papel") == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Acesso restrito ao backoffice.");
        }
    }

    /**
     * @param userAgent o texto <b>bruto</b> vai junto de propósito: os campos derivados são
     *         interpretação nossa, e quem audita precisa poder ver o original
     */
    public record Linha(long idAcesso, OffsetDateTime ocorridoEm, Long idTenant, String nomeConta,
                        String emailInformado, String resultado, String ip, boolean ipConfiavel,
                        String so, String navegador, String dispositivo, String userAgent) {
    }

    public record Pagina(List<Linha> itens, long total, int pagina, int limite) {
    }
}
