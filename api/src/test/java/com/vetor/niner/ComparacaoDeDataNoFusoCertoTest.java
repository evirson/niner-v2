package com.vetor.niner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarda de arquitetura: <b>nenhuma comparação de data pode usar o relógio do banco</b>.
 *
 * <h2>Por que este teste existe</h2>
 *
 * <p>A sessão do Postgres roda em {@code Etc/UTC}. Então {@code CURRENT_DATE}, {@code now()::date},
 * {@code localtime} e {@code coluna::date} viram o <b>dia seguinte às 21:00 de Brasília</b>. Em
 * 2026-08-19 isso apareceu do pior jeito possível: o caixa aberto de manhã <b>sumia às 21h</b> e o
 * PDV recusava vender, e o controle de horário de acesso consultava o <b>dia da semana errado</b>
 * (sexta às 21h virava "sábado") e expulsava o usuário. Foram ~19 serviços corrigidos de uma vez.
 *
 * <p>O modo de falhar dessa classe de bug é <b>esquecer</b>: quem escreve a próxima query não tem
 * como lembrar de uma convenção que não está em lugar nenhum do compilador. Em 2026-08-20 uma
 * auditoria encontrou 15 comparações ainda em UTC — todas escritas <i>depois</i> da varredura, e
 * todas no plano de controle, que a varredura não tinha coberto. Daí este teste: a convenção passou
 * a ser verificada, não lembrada.
 *
 * <h2>Como funciona</h2>
 *
 * <p>Varre o texto-fonte de {@code src/main/java} procurando as construções que dependem do relógio
 * da sessão. É o <b>primeiro teste do projeto que lê código-fonte</b> — os outros invariantes
 * (privilégio, autorizador por UF) são verificados contra o banco.
 *
 * <p><b>A conversão certa</b> depende do plano:
 * <ul>
 *   <li><b>lojista</b> — o fuso é o da <b>UF da empresa</b> ({@code comum.tempo.FusoDaUf});</li>
 *   <li><b>plataforma</b> — é sempre {@code America/Sao_Paulo}, o relógio da Vetor
 *       ({@code comum.tempo.FusoDaPlataforma}), porque competência de cota e agenda de backup são
 *       fatos do negócio dela e um tenant multi-UF não teria fuso para desempatar.</li>
 * </ul>
 * Os dois casos passam por {@code AT TIME ZONE}, que é o que este teste exige.
 */
class ComparacaoDeDataNoFusoCertoTest {

    private static final Path FONTES = Path.of("src", "main", "java");

    /**
     * Construções que leem o relógio/calendário da <b>sessão</b> do banco. Cada uma já apareceu
     * como bug real neste projeto, exceto {@code age(} — que entrou por ser a mesma família.
     */
    private static final List<Pattern> PROIBIDAS = List.of(
            Pattern.compile("::\\s*date", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcurrent_date\\b", Pattern.CASE_INSENSITIVE),
            // ⚠️ Sem CASE_INSENSITIVE de propósito: `localtime` é palavra-chave SQL (escrita toda
            // minúscula ou toda maiúscula) e `LocalTime` é o tipo do Java, que aparece em DTO e em
            // `rs.getObject(...)` por todo lado. Ignorar a caixa aqui reprovava 11 linhas legítimas.
            Pattern.compile("\\b(localtime(stamp)?|LOCALTIME(STAMP)?)\\b"),
            Pattern.compile("\\bage\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("extract\\s*\\(\\s*\\w+\\s+from\\s+now\\s*\\(\\s*\\)", Pattern.CASE_INSENSITIVE),
            // Lado Java da mesma armadilha: sem argumento, estes usam o fuso da JVM — que só está
            // definido em PRODUÇÃO (`TZ` no docker-compose.prod.yml). Em dev roda em UTC, então o
            // bug não reproduz na máquina de quem escreveu. `OffsetDateTime.now()` fica de fora de
            // propósito: representa um instante, e comparar instante independe de fuso.
            Pattern.compile("\\bLocal(Date|DateTime|Time)\\.now\\s*\\(\\s*\\)"),
            // ⚠️ Os dois abaixo entraram em 2026-08-29, depois de uma auditoria achar QUATRO
            // defeitos de fuso que este guarda deixava passar por não conhecer o padrão:
            //   • `to_char(timestamptz, …)` formata no fuso da SESSÃO (UTC) — a planilha do
            //     contador saía com o mês de competência errado;
            //   • `ZoneId.systemDefault()` pega o TZ do container, que só existe em produção.
            // ⛔ Fora do alcance de regex e por isso NÃO cobertos: `.toLocalDate()`/`.getHour()`
            // sobre OffsetDateTime e `DateTimeFormatter.format(OffsetDateTime)` — os dois deram
            // defeito no mesmo dia (valor sumindo do gráfico, mensagem com o dia errado). Quem
            // escrever qualquer um dos dois precisa lembrar sozinho: passe pelo FusoDaLoja.
            // ⚠️ `data_nascimento` e `data_abertura` ficam de fora por serem colunas `date` de
            // verdade — `AT TIME ZONE` sobre `date` não significa nada, e reprovar o build por elas
            // treinaria quem lê a falha a ignorá-la.
            Pattern.compile("to_char\\s*\\(\\s*[a-z_]+\\.(criado_em|atualizado_em|data_(?!nascimento|abertura)[a-z_]+)\\s*,",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bZoneId\\.systemDefault\\s*\\(\\s*\\)"));

    /** A conversão que redime qualquer uma das acima. */
    private static final Pattern CONVERTIDA =
            Pattern.compile("AT\\s+TIME\\s+ZONE", Pattern.CASE_INSENSITIVE);

    /**
     * Cast de <b>parâmetro</b> ({@code ?::date}, {@code CAST(? AS date)}) não lê relógio nenhum: é
     * só tipagem do valor que o Java mandou, e o Postgres recusa o comando sem ele quando o
     * parâmetro é comparado com NULL.
     */
    private static final Pattern CAST_DE_PARAMETRO =
            Pattern.compile("\\?\\s*::\\s*date|CAST\\s*\\(\\s*\\?\\s+AS\\s+date\\s*\\)", Pattern.CASE_INSENSITIVE);

    @Test
    void nenhumaComparacaoDeDataUsaORelogioDoBanco() throws IOException {
        List<String> infracoes = new ArrayList<>();

        try (Stream<Path> arquivos = Files.walk(FONTES)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> linhas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
                for (int i = 0; i < linhas.size(); i++) {
                    String linha = linhas.get(i);
                    if (ehComentario(linha) || CONVERTIDA.matcher(linha).find()) {
                        continue;
                    }
                    String semParametro = CAST_DE_PARAMETRO.matcher(linha).replaceAll("");
                    for (Pattern proibida : PROIBIDAS) {
                        if (proibida.matcher(semParametro).find()) {
                            infracoes.add("%s:%d → %s".formatted(arquivo, i + 1, linha.trim()));
                            break;
                        }
                    }
                }
            }
        }

        assertThat(infracoes)
                .as("""
                        Comparação de data usando o relógio do BANCO (a sessão roda em UTC, então o dia \
                        vira às 21:00 de Brasília). Converta os DOIS lados: no plano do lojista, para o \
                        fuso da UF da empresa (comum.tempo.FusoDaUf); no plano da plataforma, para \
                        America/Sao_Paulo (comum.tempo.FusoDaPlataforma). Coluna que é genuinamente \
                        `date` (data_nascimento, data_abertura, competencia) não precisa de cast nenhum \
                        — se precisar, o problema está do outro lado da comparação.""")
                .isEmpty();
    }

    /**
     * O teste varre texto, não AST: linha de comentário não é código e não pode reprovar o build.
     * Inclui {@code --}, porque as queries deste projeto moram em <i>text block</i> e comentam com
     * a sintaxe do SQL — foi assim que o próprio comentário que explica a correção reprovou o teste.
     */
    private static boolean ehComentario(String linha) {
        String limpa = linha.stripLeading();
        return limpa.startsWith("//") || limpa.startsWith("*") || limpa.startsWith("/*")
                || limpa.startsWith("--");
    }

    /**
     * O guarda só vale se pegar o defeito. Este caso prova que a regra reprova a construção errada
     * e aceita a certa — sem isso, um regex quebrado passaria despercebido para sempre e o teste
     * viraria um verde vazio.
     */
    @Test
    void oGuardaReprovaAConstrucaoErradaEAceitaACerta() {
        assertThat(reprovaria("WHERE data_venda::date = CURRENT_DATE")).isTrue();
        assertThat(reprovaria("AND localtime >= backup_hora")).isTrue();
        // …e não pode reprovar o tipo do Java, que é CamelCase e aparece em DTO e em getObject.
        assertThat(reprovaria("rs.getObject(\"backup_hora\", LocalTime.class)")).isFalse();
        assertThat(reprovaria("public record HorarioAcessoRequest(int diaSemana, LocalTime horaInicio) {")).isFalse();
        assertThat(reprovaria("EXTRACT(ISODOW FROM now())")).isTrue();
        assertThat(reprovaria("date_trunc('month', now())::date")).isTrue();
        assertThat(reprovaria("LocalDate hoje = LocalDate.now();")).isTrue();
        assertThat(reprovaria("LocalDate.now(FusoDaUf.PADRAO)")).isFalse();
        // Instante não depende de fuso: comparar OffsetDateTime.now() com um limite está certo.
        assertThat(reprovaria("if (OffsetDateTime.now().isAfter(limite)) {")).isFalse();

        assertThat(reprovaria(
                "AND (data_venda AT TIME ZONE 'America/Sao_Paulo')::date = "
                        + "(now() AT TIME ZONE 'America/Sao_Paulo')::date")).isFalse();
        assertThat(reprovaria("AND c.data_nascimento BETWEEN ? AND ?")).isFalse();
        assertThat(reprovaria("WHERE (? IS NULL OR data >= CAST(? AS date))")).isFalse();
        assertThat(reprovaria("// o dia vira às 21:00 porque CURRENT_DATE está em UTC")).isFalse();
        assertThat(reprovaria("                            -- CURRENT_DATE em UTC vira o dia seguinte")).isFalse();
    }

    private static boolean reprovaria(String linha) {
        if (ehComentario(linha) || CONVERTIDA.matcher(linha).find()) {
            return false;
        }
        String semParametro = CAST_DE_PARAMETRO.matcher(linha).replaceAll("");
        return PROIBIDAS.stream().anyMatch(p -> p.matcher(semParametro).find());
    }

    /** Sanidade: se o caminho das fontes mudar, o teste vira verde vazio sem ninguém perceber. */
    @Test
    void aVarreduraRealmenteEncontraArquivos() throws IOException {
        try (Stream<Path> arquivos = Files.walk(FONTES)) {
            long total = arquivos.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".java")).count();
            assertThat(total).as("arquivos .java varridos em %s", FONTES.toAbsolutePath()).isGreaterThan(200);
        }
    }
}
