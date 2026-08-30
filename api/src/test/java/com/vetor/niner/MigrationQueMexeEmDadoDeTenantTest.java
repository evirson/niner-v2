package com.vetor.niner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Migration que LÊ ou TRANSFORMA dado de tenant precisa de {@code NO FORCE ROW LEVEL SECURITY}.
 *
 * <p>⛔ <b>O defeito que este teste existe para impedir sai VAZIO e anuncia SUCESSO.</b> Migration
 * roda como {@code niner_owner}, e {@code FORCE ROW LEVEL SECURITY} (V024) <b>não poupa nem o dono
 * da tabela</b>: sem {@code app.id_tenant} no contexto, todo {@code SELECT} devolve zero linhas e
 * todo {@code UPDATE ... WHERE} casa zero. O Flyway não tem como saber que zero era o número
 * errado.
 *
 * <p>Medido no banco de desenvolvimento em 2026-08-29, e é a diferença entre as duas leituras que
 * prova o mecanismo:
 * <pre>
 *   SET app.id_tenant=1; SELECT count(*) FROM usuario_permissao;  --&gt;  4
 *   (sem SET)            SELECT count(*) FROM usuario_permissao;  --&gt;  0
 * </pre>
 *
 * <p>Já mordeu <b>três vezes</b>: a V057 (backfill da geometria de etiqueta, gravou zero e só não
 * se perdeu porque o resultado foi conferido à mão), a V089 (347 vendas, 0 preenchidas) e a V096
 * (a migração de concessões do cancelamento de orçamento — consertada pela V097). Nas três, o
 * deploy passou verde.
 *
 * <p>⚠️ <b>Por que um teste e não mais uma revisão:</b> o guarda-corpo desta regra existia copiado
 * <i>dentro</i> das migrations (V024–V031) e deixou de ser repetido a partir da V033 — ou seja,
 * valia só no dia em que cada uma rodou. Aqui ele passa a valer para toda migration futura, que é
 * o único jeito de uma regra assim sobreviver.
 *
 * <p>⚠️ {@code INSERT} de linhas novas em tabela criada na própria migration <b>não</b> precisa da
 * liberação: não há dado preexistente para ler. O que precisa é {@code UPDATE}, {@code DELETE} e
 * {@code INSERT ... SELECT} — tudo que enxerga o que já estava lá.
 */
class MigrationQueMexeEmDadoDeTenantTest {

    /**
     * Migrations já aplicadas quando o teste nasceu, e que <b>não</b> podem ser editadas (o
     * checksum do Flyway quebraria o deploy). Cada uma foi conferida no banco:
     *
     * <ul>
     *   <li>{@code V019}, {@code V054} — {@code produto_estoque}: o {@code UPDATE} é de coluna
     *       recém-criada, com valor constante, e o {@code DEFAULT} da mesma migration cobre as
     *       linhas novas;</li>
     *   <li>{@code V032} — {@code cfg_plano_contas}/{@code cfg_geral}: idem, semeadura com
     *       {@code DEFAULT} irmão;</li>
     *   <li>{@code V049} — {@code empresa}: o CHECK é {@code NOT VALID} de propósito, e a coluna
     *       aceita NULL; nada dependia do backfill;</li>
     *   <li>{@code V055} — {@code cfg_geral}: o {@code UPDATE} saiu vazio. ⛔ <b>A justificativa
     *       anterior desta linha era FALSA</b> e dizia que "o {@code SET DEFAULT true} salvou o
     *       caso (medido: o parâmetro está ligado)". O DEFAULT cobre linha <b>nova</b>; ele nunca
     *       tocou a que já existia. A medição estava certa e a conclusão sobre a CAUSA, errada — o
     *       que tinha posto o parâmetro em {@code true} foi alguém salvando pela tela, nove dias
     *       depois. Cronologia medida: tenant 1 criado em 18/08; V054 (que cria a coluna
     *       {@code NOT NULL DEFAULT false}) em 20/08 19:35; V055 em 20/08 19:53, casando zero
     *       linhas. O parâmetro ficou errado por nove dias. <b>Consertado pela V098</b>, que
     *       libera o RLS, distingue acidente de escolha do lojista e confere o resultado;</li>
     *   <li>{@code V076}, {@code V078} — {@code usuario_permissao}: saíram vazios. Medido em
     *       2026-08-29: <b>zero</b> inconsistência restante, porque as concessões existentes
     *       nasceram depois delas. Em um banco com concessões anteriores, teriam falhado;</li>
     *   <li>{@code V096} — consertada pela {@code V097}, que tem a liberação e <b>confere o
     *       resultado</b>.</li>
     * </ul>
     *
     * ⚠️ Acrescentar aqui é decisão, não formalidade: significa afirmar que a migration não
     * depende de enxergar dado preexistente. Se depender, ela precisa da liberação — e, se já
     * rodou, de uma migration corretiva.
     */
    private static final Set<String> APLICADAS_ANTES_DO_GUARDA = Set.of(
            "V019__estoque.sql",
            "V032__entrada_planilha.sql",
            "V049__empresa_estado_so_uf_valida.sql",
            "V054__estoque_negativo_configuravel.sql",
            "V055__estoque_negativo_permitido_por_padrao.sql",
            "V076__acoes_por_tela.sql",
            "V078__telas_exclusivas_do_administrador.sql",
            "V096__cancelar_orcamento_e_excluir.sql");

    /** {@code UPDATE x}, {@code DELETE FROM x} e {@code INSERT INTO x ... SELECT} — o que lê. */
    private static final Pattern ESCRITA = Pattern.compile(
            "\\b(?:UPDATE|DELETE\\s+FROM)\\s+([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_SELECT = Pattern.compile(
            "\\bINSERT\\s+INTO\\s+([a-z_][a-z0-9_]*)[\\s\\S]{0,600}?\\bSELECT\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void migrationQueTransformaDadoDeTenantLiberaOForceRls() throws IOException {
        Path dir = Path.of("../db/migration");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("db/migration");
        }
        // ⚠️ Falha em vez de passar vazio: um teste que não acha o que auditar não prova nada —
        // seria o mesmo tipo de silêncio que ele existe para caçar.
        assertThat(Files.isDirectory(dir)).as("diretório de migrations não encontrado").isTrue();

        Set<String> tabelasDeTenant = tabelasDeTenant(dir);
        assertThat(tabelasDeTenant).as("nenhuma tabela com id_tenant encontrada nas migrations").isNotEmpty();

        List<String> faltando = new ArrayList<>();
        try (Stream<Path> arquivos = Files.list(dir)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                String nome = arquivo.getFileName().toString();
                if (APLICADAS_ANTES_DO_GUARDA.contains(nome)) {
                    continue;
                }
                String sql = semComentarios(Files.readString(arquivo));
                if (sql.toUpperCase(Locale.ROOT).contains("NO FORCE ROW LEVEL SECURITY")) {
                    continue;
                }
                Set<String> alvos = new java.util.TreeSet<>();
                for (Pattern p : List.of(ESCRITA, INSERT_SELECT)) {
                    Matcher m = p.matcher(sql);
                    while (m.find()) {
                        String tabela = m.group(1).toLowerCase(Locale.ROOT);
                        // Tabela criada nesta mesma migration não tem dado preexistente a enxergar.
                        if (tabelasDeTenant.contains(tabela) && !criadaAqui(sql, tabela)) {
                            alvos.add(tabela);
                        }
                    }
                }
                if (!alvos.isEmpty()) {
                    faltando.add("  - " + nome + " mexe em " + String.join(", ", alvos));
                }
            }
        }

        if (!faltando.isEmpty()) {
            fail("""
                    Estas migrations LEEM ou TRANSFORMAM dado de tenant sem liberar o FORCE RLS:
                    %s

                    Migration roda como niner_owner, e FORCE ROW LEVEL SECURITY não poupa nem o dono:
                    sem app.id_tenant no contexto o SELECT devolve ZERO linhas, o UPDATE casa ZERO, e
                    o Flyway anuncia SUCESSO. Já aconteceu na V057, na V089 e na V096.

                    O remédio é `ALTER TABLE x NO FORCE ROW LEVEL SECURITY;` antes e `FORCE` depois
                    (NO FORCE, não DISABLE: libera só o dono, a policy continua valendo para
                    niner_app) -- e CONFERIR o resultado dentro da própria migration, como a V097.
                    """.formatted(String.join("\n", faltando)));
        }
    }

    /** Tabelas que alguma migration declara com coluna {@code id_tenant}. */
    private static Set<String> tabelasDeTenant(Path dir) throws IOException {
        Pattern criacao = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)\\s*\\(([^;]*?)\\)\\s*;",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Set<String> tabelas = new java.util.TreeSet<>();
        try (Stream<Path> arquivos = Files.list(dir)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".sql")).toList()) {
                String sql = semComentarios(Files.readString(arquivo));
                Matcher m = criacao.matcher(sql);
                while (m.find()) {
                    if (m.group(2).toLowerCase(Locale.ROOT).contains("id_tenant")) {
                        tabelas.add(m.group(1).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        // `plataforma.*` é a exceção documentada (control plane, fora do RLS de tenant) e nunca
        // aparece sem o esquema no nome, então não entra aqui.
        return tabelas;
    }

    private static boolean criadaAqui(String sql, String tabela) {
        return Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + tabela + "\\b",
                Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }

    /** Comentário citando `UPDATE tabela` não é código — sem isto o teste acusa a própria doc. */
    private static String semComentarios(String sql) {
        return sql.replaceAll("(?m)--.*$", "");
    }
}
