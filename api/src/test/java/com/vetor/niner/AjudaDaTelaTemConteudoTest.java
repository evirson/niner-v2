package com.vetor.niner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Toda chave de ajuda pedida por uma tela tem de existir em {@code AjudaDaTela.tsx}.
 *
 * <h2>Por que isto precisa de um guarda</h2>
 * {@code AjudaDaTela} faz {@code if (!conteudo) return null}: uma chave com erro de digitação
 * <b>não dá erro em lugar nenhum</b> — o botão de ajuda simplesmente <b>some daquela tela</b>, e
 * ninguém percebe. É a mesma família de {@code feedback_classe_css_inexistente_nao_da_erro}:
 * passa no {@code tsc -b}, passa na suíte, passa no build, e só aparece para quem for procurar
 * ajuda e não achar.
 *
 * <p>⚠️ E o caminho contrário também importa: conteúdo escrito e nunca exibido é trabalho que
 * ninguém lê e que envelhece sem ninguém notar.
 *
 * <h2>⚠️ Como eu quase reportei 19 falsos positivos (2026-09-01)</h2>
 * A primeira versão deste cruzamento procurava só {@code chaveTela="..."} literal e acusou
 * <b>19</b> conteúdos órfãos. Nenhum era real: as telas passam a chave por
 * {@code const CHAVE_TELA = '...'} e a importação por {@code chaveAjuda:} dentro de um mapa de
 * configuração. ⭐ O guarda tem de conhecer <b>as três formas</b>, senão ele acusa falso — e
 * guarda que acusa falso treina quem lê a falha a desconfiar dele.
 *
 * <p>⚠️ Falha se não encontrar os fontes, em vez de passar vazio.
 *
 * <p>⛔ <b>Limite declarado:</b> isto confere que a ajuda <b>existe</b>, nunca que ela é
 * <b>verdadeira</b>. As ~1.500 linhas de {@code AjudaDaTela.tsx} afirmam comportamento ("o
 * sistema recusa X", "nunca acontece Y") e só se conferem lendo o código de cada afirmação. Uma
 * amostra foi verificada à mão em 2026-09-01; o resto continua em `docs/PENDENCIAS.md` #68.
 */
class AjudaDaTelaTemConteudoTest {

    private Path raiz() {
        Path aqui = Path.of("").toAbsolutePath();
        for (Path candidata : List.of(aqui.getParent() == null ? aqui : aqui.getParent(), aqui)) {
            if (candidata != null && Files.isRegularFile(
                    candidata.resolve("web/src/components/AjudaDaTela.tsx"))) {
                return candidata;
            }
        }
        throw new AssertionError("não encontrei web/src/components/AjudaDaTela.tsx a partir de " + aqui);
    }

    /** As chaves que o arquivo de ajuda define — as entradas de primeiro nível do mapa. */
    private Set<String> chavesComConteudo(Path raiz) throws IOException {
        String fonte = Files.readString(raiz.resolve("web/src/components/AjudaDaTela.tsx"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        Set<String> chaves = new LinkedHashSet<>();
        // ⚠️ NÃO exigir `: {` — quatro entradas são produzidas por uma FUNÇÃO
        // (`'comum.telaconfig.cliente': configuracaoDeTela('Cliente')`), e o regex antigo as
        // ignorava, fazendo o guarda acusar quatro telas sem ajuda que têm ajuda. Foi o segundo
        // falso alarme deste mesmo cruzamento no mesmo dia — o primeiro foram as chaves passadas
        // por constante. Casar o VALOR é errado aqui; o que identifica a entrada é a chave.
        Matcher m = Pattern.compile("(?m)^  '([a-zA-Z0-9._-]+)':").matcher(fonte);
        while (m.find()) {
            chaves.add(m.group(1));
        }
        return chaves;
    }

    /**
     * As chaves que alguma tela pede. Três formas, e todas as três existem no código de hoje:
     * atributo literal, constante do módulo e campo de um mapa de configuração.
     */
    private Set<String> chavesPedidas(Path raiz) throws IOException {
        List<Pattern> formas = List.of(
                Pattern.compile("chaveTela=\"([a-zA-Z0-9._-]+)\""),
                Pattern.compile("const CHAVE_TELA\\s*=\\s*'([a-zA-Z0-9._-]+)'"),
                Pattern.compile("chaveAjuda:\\s*'([a-zA-Z0-9._-]+)'"));

        Set<String> pedidas = new LinkedHashSet<>();
        Path web = raiz.resolve("web/src");
        try (Stream<Path> arquivos = Files.walk(web)) {
            for (Path p : arquivos.filter(a -> a.toString().endsWith(".tsx") || a.toString().endsWith(".ts")).toList()) {
                String fonte = Files.readString(p, StandardCharsets.UTF_8);
                for (Pattern forma : formas) {
                    Matcher m = forma.matcher(fonte);
                    while (m.find()) {
                        pedidas.add(m.group(1));
                    }
                }
            }
        }
        return pedidas;
    }

    @Test
    void todaTelaQuePedeAjudaTemAjuda() throws IOException {
        Path raiz = raiz();
        Set<String> comConteudo = chavesComConteudo(raiz);
        Set<String> pedidas = chavesPedidas(raiz);

        assertTrue(comConteudo.size() > 50,
                "esperava mais de 50 blocos de ajuda, achei " + comConteudo.size()
                        + " — o regex parou de casar, e um guarda que não lê nada passa sempre");
        assertTrue(pedidas.size() > 50,
                "esperava mais de 50 telas pedindo ajuda, achei " + pedidas.size());

        List<String> semConteudo = new ArrayList<>(pedidas);
        semConteudo.removeAll(comConteudo);
        assertTrue(semConteudo.isEmpty(), """
                Estas telas pedem ajuda que NÃO existe em AjudaDaTela.tsx — o botão de ajuda some
                delas sem erro nenhum:
                  %s
                """.formatted(String.join("\n  ", semConteudo)));
    }

    @Test
    void naoHaAjudaEscritaQueNenhumaTelaMostra() throws IOException {
        Path raiz = raiz();
        List<String> orfas = new ArrayList<>(chavesComConteudo(raiz));
        orfas.removeAll(chavesPedidas(raiz));

        assertTrue(orfas.isEmpty(), """
                Estes blocos de ajuda estão escritos e NENHUMA tela os mostra — ou a tela perdeu a
                chamada, ou a tela foi removida e a ajuda ficou:
                  %s
                """.formatted(String.join("\n  ", orfas)));
    }
}
