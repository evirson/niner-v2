package com.vetor.niner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A grade de permissões oferece <b>exatamente</b> as ações que os controllers exigem.
 *
 * <p>⭐ <b>Por que este teste existe.</b> A V076 mediu as ações olhando os métodos HTTP que o
 * <b>front</b> chama, e errou em seis telas. A pior: {@code cfg_tela.pdv.tem_incluir = false}
 * contra um {@code POST /pdv/vendas} que exige INCLUIR — <b>nenhum operador conseguia vender</b>, e
 * não havia conserto pela tela, porque {@code PermissaoController.salvar} descarta a concessão de
 * uma ação que a tela declara não ter. O administrador jurava ter liberado tudo.
 *
 * <p>O erro na direção oposta também importa: {@code estoque} e {@code minha-conta} ofereciam uma
 * caixa que não governava nada. Quem configura permissão passa a desconfiar do que a grade diz.
 *
 * <p><b>Como funciona:</b> lê os {@code @Tela}/{@code @Acao}/{@code @Livre} do código-fonte e
 * deriva a ação de cada endpoint pela mesma regra do {@code PermissaoInterceptor} (GET→acessar,
 * POST→incluir, PUT/PATCH→alterar, DELETE→excluir, salvo {@code @Acao} explícito). Depois compara
 * com {@code cfg_tela}.
 *
 * <p>⚠️ <b>Lê o fonte, não o classpath</b>, porque {@code @Acao} e {@code @Livre} estão em métodos e
 * o que interessa é a combinação anotação-por-método com o verbo — muito mais direto de extrair do
 * texto do que por reflexão sobre proxies do Spring. O custo é depender do layout de
 * {@code api/src/main/java}: se o teste não achar os fontes, ele <b>falha</b> em vez de passar
 * vazio (um teste que "não encontrou nada" e passa é pior que teste nenhum).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AcoesPorTelaConferemTest {

    private static final Pattern TELA = Pattern.compile("@Tela\\(\"([^\"]+)\"\\)");
    private static final Pattern ACAO = Pattern.compile("@Acao\\(([A-Za-z._]+)\\)");
    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping");

    @Autowired
    JdbcClient jdbc;

    private record Acoes(boolean incluir, boolean alterar, boolean excluir) {
        @Override
        public String toString() {
            return (incluir ? "incluir " : "") + (alterar ? "alterar " : "") + (excluir ? "excluir" : "");
        }
    }

    @Test
    void aGradeOfereceExatamenteOQueOsControllersExigem() throws IOException {
        Map<String, Acoes> exigido = lerDoCodigo();
        assertTrue(exigido.size() > 40,
                "esperava dezenas de telas anotadas com @Tela; achei " + exigido.size()
                        + " — o teste não achou os fontes e passaria vazio");

        Map<String, Acoes> catalogo = new HashMap<>();
        jdbc.sql("SELECT chave, tem_incluir, tem_alterar, tem_excluir FROM cfg_tela")
                .query((rs, n) -> catalogo.put(rs.getString("chave"),
                        new Acoes(rs.getBoolean("tem_incluir"), rs.getBoolean("tem_alterar"),
                                rs.getBoolean("tem_excluir"))))
                .list();

        List<String> divergentes = new ArrayList<>();
        for (var e : new TreeMap<>(exigido).entrySet()) {
            Acoes cat = catalogo.get(e.getKey());
            if (cat == null) {
                divergentes.add(e.getKey() + ": controller anotado, mas a tela não está em cfg_tela");
            } else if (!cat.equals(e.getValue())) {
                divergentes.add(e.getKey() + ": o código exige [" + e.getValue()
                        + "], a grade oferece [" + cat + "]");
            }
        }
        assertEquals(List.of(), divergentes,
                "Ação exigida pelo controller e não oferecida na grade = permissão IMPOSSÍVEL de "
                        + "conceder (403 numa tela que o admin jura ter liberado). Ação oferecida e "
                        + "não exigida = caixa que não governa nada. Corrija com uma migration "
                        + "nova, no espírito da V081.");
    }

    /**
     * ⛔ O lado INVERSO: chave que a grade oferece e nenhum {@code @Tela} do código usa.
     *
     * <p>Uma chave assim é uma <b>caixa que não governa nada</b>, e as duas direções doem (achado
     * de auditoria, 2026-08-29 — cinco chaves nessa situação):
     * <ul>
     *   <li><b>falsa proteção</b>: o admin deixa "Estorno de Crediário" desmarcado, o item some do
     *       menu, e o POST responde <b>200</b> porque o servidor pedia a permissão da tela-mãe —
     *       ele acredita ter fechado a porta de dinheiro saindo do caixa;</li>
     *   <li><b>permissão impossível</b>: o admin marca só "Efetivar Balanço" e leva 403 citando
     *       "Contagem de Estoque", uma tela que ele não liberou e cujo nome não aparece na decisão
     *       que ele tomou.</li>
     * </ul>
     *
     * <p>⚠️ O teste irmão acima pega o contrário (código anotado sem linha na grade). Os dois
     * juntos fecham o cerco: nenhuma chave sem dono, nenhum dono sem chave.
     */
    @Test
    void nenhumaChaveDaGradeFicaSemDonoNoCodigo() throws IOException {
        Set<String> anotadasNoCodigo = lerDoCodigo().keySet();

        List<String> orfas = new ArrayList<>();
        jdbc.sql("SELECT chave FROM cfg_tela ORDER BY chave")
                .query((rs, n) -> rs.getString("chave"))
                .list()
                .forEach(chave -> {
                    if (!anotadasNoCodigo.contains(chave) && !TELAS_SEM_ENDPOINT_PROPRIO.contains(chave)) {
                        orfas.add(chave);
                    }
                });

        assertEquals(List.of(), orfas,
                "Estas chaves aparecem na grade de permissão e NENHUM @Tela do código as usa — "
                        + "o admin marca/desmarca e o servidor ignora. Ou anote o método com "
                        + "@Tela(\"chave\") (o interceptor prefere a anotação de método), ou "
                        + "declare em TELAS_SEM_ENDPOINT_PROPRIO por que ela não tem dono.");
    }

    /**
     * Chaves cuja tela não tem endpoint próprio — a permissão governa só a navegação.
     *
     * <p>⚠️ Entrar aqui é uma decisão: significa afirmar que <b>não existe</b> chamada de API
     * exclusiva daquela tela. Se existir, a permissão vira decoração.
     */
    private static final Set<String> TELAS_SEM_ENDPOINT_PROPRIO = Set.of(
            // As cinco telas de Importação de Dados são `admin_apenas = true` e o
            // `ImportacaoService` chama `exigirAdmin` nos seis métodos: a permissão por tela é
            // irrelevante ali, porque só o administrador chega — e ele passa por cima da grade.
            // ⚠️ Se alguma delas deixar de ser admin_apenas, sai desta lista e ganha `@Tela`.
            "importacao-dados.clientes",
            "importacao-dados.contas-receber",
            "importacao-dados.estoque",
            "importacao-dados.fornecedores",
            "importacao-dados.produtos");

    private Map<String, Acoes> lerDoCodigo() throws IOException {
        Path raiz = Path.of("src/main/java/com/vetor/niner");
        if (!Files.isDirectory(raiz)) {
            raiz = Path.of("api/src/main/java/com/vetor/niner");
        }
        assertTrue(Files.isDirectory(raiz), "não encontrei os fontes em " + raiz.toAbsolutePath());

        Map<String, boolean[]> acumulado = new HashMap<>();
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            for (Path p : arquivos.filter(a -> a.toString().endsWith(".java")).toList()) {
                String fonte = Files.readString(p, StandardCharsets.UTF_8);
                Matcher mTela = TELA.matcher(fonte);
                if (!mTela.find()) {
                    continue;
                }
                // ⚠️ A primeira @Tela do arquivo é a da CLASSE; um método pode ter a sua e ela
                // VENCE (o PermissaoInterceptor prefere a de método). Até 2026-08-29 este leitor
                // atribuía todo método à tela da classe, e as chaves declaradas por método
                // ficavam invisíveis para os dois testes.
                acumularMetodos(fonte, mTela.group(1), acumulado);
            }
        }
        Map<String, Acoes> resultado = new HashMap<>();
        acumulado.forEach((tela, a) -> resultado.put(tela, new Acoes(a[0], a[1], a[2])));
        return resultado;
    }

    /** Percorre os blocos "anotações + assinatura pública", que é como um endpoint se parece. */
    private void acumularMetodos(String fonte, String telaDaClasse, Map<String, boolean[]> acumulado) {
        // Garante que a tela da CLASSE apareça mesmo num controller cujos métodos sejam todos
        // anotados: sem isto ela sumiria do mapa e o teste irmão acusaria falso.
        acumulado.computeIfAbsent(telaDaClasse, k -> new boolean[3]);
        List<String> anotacoes = new ArrayList<>();
        for (String linha : fonte.split("\\R")) {
            String t = linha.trim();
            if (t.startsWith("@")) {
                anotacoes.add(t);
                continue;
            }
            if (t.startsWith("public ") && !anotacoes.isEmpty()) {
                String bloco = String.join(" ", anotacoes);
                Matcher mTelaDoMetodo = TELA.matcher(bloco);
                String tela = mTelaDoMetodo.find() ? mTelaDoMetodo.group(1) : telaDaClasse;
                classificar(bloco, acumulado.computeIfAbsent(tela, k -> new boolean[3]));
                anotacoes.clear();
                continue;
            }
            // Javadoc e comentários entre a anotação e a assinatura não quebram o bloco; qualquer
            // outra coisa (campo, chave, código) quebra.
            if (!t.isEmpty() && !t.startsWith("*") && !t.startsWith("/*") && !t.startsWith("//")) {
                anotacoes.clear();
            }
        }
    }

    private void classificar(String bloco, boolean[] acoes) {
        if (!MAPPING.matcher(bloco).find() || bloco.contains("@Livre")) {
            return;
        }
        Matcher mAcao = ACAO.matcher(bloco);
        String acao;
        if (mAcao.find()) {
            String valor = mAcao.group(1);
            acao = valor.substring(valor.lastIndexOf('.') + 1);
        } else if (bloco.contains("@GetMapping")) {
            acao = "ACESSAR";
        } else if (bloco.contains("@PostMapping")) {
            acao = "INCLUIR";
        } else if (bloco.contains("@PutMapping") || bloco.contains("@PatchMapping")) {
            acao = "ALTERAR";
        } else {
            acao = "EXCLUIR";
        }
        switch (acao) {
            case "INCLUIR" -> acoes[0] = true;
            case "ALTERAR" -> acoes[1] = true;
            case "EXCLUIR" -> acoes[2] = true;
            default -> { /* ACESSAR: toda tela tem, não é coluna em cfg_tela */ }
        }
    }
}
