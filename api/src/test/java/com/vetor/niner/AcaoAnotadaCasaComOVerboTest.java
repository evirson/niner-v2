package com.vetor.niner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Toda `@Acao` que <b>contraria</b> o verbo HTTP tem de estar nesta lista, nomeada.
 *
 * <h2>⛔ O defeito que este teste existe para impedir</h2>
 *
 * <p>Anotação de método é <b>posicional</b> e não avisa quando muda de dono. Em 2026-08-29 dois
 * casos do mesmo tipo apareceram no mesmo dia:
 * <ul>
 *   <li>um `@Livre` que protegia `/desconto-venda` passou a proteger um endpoint novo inserido
 *       entre ele e o método — e o desconto sumiu da tela do caixa;</li>
 *   <li>um `@Acao(EXCLUIR)` com o comentário <i>"desfazer, não incluir"</i> pousou em
 *       `registrarContagem` — <b>bipar um produto passou a exigir permissão de EXCLUIR</b>, e a
 *       mensagem de 403 falava de excluir numa ação de incluir.</li>
 * </ul>
 *
 * <p>⚠️ O `AcoesPorTelaConferemTest` <b>não pega</b> esse caso: ele confere se a ação exigida
 * existe em `cfg_tela`, e `EXCLUIR` existe. O que estava errado não era a ação, era <b>de quem</b>
 * ela era.
 *
 * <h2>Como funciona</h2>
 *
 * <p>O verbo HTTP já traduz para uma ação (POST→INCLUIR, PUT→ALTERAR, DELETE→EXCLUIR), e a `@Acao`
 * existe para os casos em que essa tradução mente — os <b>desfazeres</b> do produto, que são POST
 * por não apagarem nada, mas exigem a permissão de quem desfaz ("desfazer é excluir"). Toda
 * divergência dessas é legítima <b>e conhecida</b>; uma nova reprova o build até alguém declarar a
 * intenção aqui, que é exatamente o momento de perguntar "de quem é essa anotação?".
 */
class AcaoAnotadaCasaComOVerboTest {

    /**
     * As divergências legítimas: POST que é desfazer, e por isso exige EXCLUIR.
     *
     * <p>⚠️ Acrescentar aqui é uma decisão, não uma formalidade. Se o método não desfaz nada, a
     * anotação está no lugar errado — foi o caso de `registrarContagem`.
     */
    private static final Set<String> DESFAZERES_CONHECIDOS = Set.of(
            "CancelamentoVendaController.cancelar",
            "CancelamentoDevolucaoController.cancelar",
            "DevolucaoCompraController.cancelar",
            "EntradaMercadoriaController.cancelar",
            "CaixaController.reabrir",
            "RecebimentoCrediarioController.estornar",
            "BalancoEstoqueController.desfazer",
            "OrdemServicoController.cancelar");

    private static final Pattern ANOTACAO = Pattern.compile(
            "@Acao\\(PermissaoService\\.Acao\\.([A-Z_]+)\\)");
    private static final Pattern MAPEAMENTO = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping");
    private static final Pattern METODO = Pattern.compile(
            "public\\s+[\\w<>,\\[\\]\\s.]+\\s+(\\w+)\\s*\\(");

    @Test
    void todaAcaoQueContrariaOVerboEstaDeclaradaAqui() throws IOException {
        Path raiz = Path.of("src/main/java/com/vetor/niner");
        if (!Files.isDirectory(raiz)) {
            raiz = Path.of("api/src/main/java/com/vetor/niner");
        }
        // ⚠️ Falha em vez de passar vazio: um teste de varredura que não acha os fontes vira
        // aprovação automática — a armadilha que o AcoesPorTelaConferemTest já documenta.
        assertThat(Files.isDirectory(raiz))
                .as("fontes de src/main não encontrados — o teste passaria vazio")
                .isTrue();

        List<String> inesperadas = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                inesperadas.addAll(divergenciasDe(arquivo));
            }
        }

        if (!inesperadas.isEmpty()) {
            fail("""
                    Estas @Acao contrariam o verbo HTTP e não estão em DESFAZERES_CONHECIDOS:
                    %s

                    Antes de acrescentar à lista, confira DE QUEM é a anotação: em 2026-08-29 uma
                    @Acao(EXCLUIR) com o comentário "desfazer, não incluir" estava sobre
                    registrarContagem — e bipar um produto passou a exigir permissão de excluir.
                    """.formatted(String.join("\n", inesperadas)));
        }
    }

    /** Lê o arquivo linha a linha e casa cada `@Acao` com o verbo e o método logo abaixo dela. */
    private static List<String> divergenciasDe(Path arquivo) throws IOException {
        String classe = arquivo.getFileName().toString().replace(".java", "");
        List<String> linhas = Files.readAllLines(arquivo);
        List<String> achados = new ArrayList<>();

        for (int i = 0; i < linhas.size(); i++) {
            Matcher anotacao = ANOTACAO.matcher(linhas.get(i));
            // ⚠️ Só a anotação de verdade, não uma citação dentro de comentário.
            if (!anotacao.find() || linhas.get(i).trim().startsWith("//") || linhas.get(i).trim().startsWith("*")) {
                continue;
            }
            String acaoDeclarada = anotacao.group(1);

            String verbo = null;
            String metodo = null;
            for (int j = i + 1; j < Math.min(i + 12, linhas.size()); j++) {
                if (verbo == null) {
                    Matcher m = MAPEAMENTO.matcher(linhas.get(j));
                    if (m.find()) verbo = m.group(1);
                }
                Matcher m = METODO.matcher(linhas.get(j));
                if (m.find()) { metodo = m.group(1); break; }
            }
            if (verbo == null || metodo == null) continue;

            String acaoDoVerbo = switch (verbo) {
                case "Post" -> "INCLUIR";
                case "Put", "Patch" -> "ALTERAR";
                case "Delete" -> "EXCLUIR";
                default -> "ACESSAR";
            };
            if (!acaoDeclarada.equals(acaoDoVerbo)) {
                String chave = classe + "." + metodo;
                if (!DESFAZERES_CONHECIDOS.contains(chave)) {
                    achados.add("  - " + chave + ": @Acao(" + acaoDeclarada + ") num @" + verbo
                            + "Mapping (o verbo diz " + acaoDoVerbo + ")");
                }
            }
        }
        return achados;
    }
}
