package com.vetor.niner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guarda da pendência #79: {@code docs/TELAS.md} não pode divergir de {@code web/src/App.tsx}.
 *
 * <p><b>Por que existe.</b> O arquivo foi <b>gerado uma vez</b> (2026-08-25) e passou a receber
 * acréscimos à mão. Documento gerado que vira documento editado perde a única garantia que tinha —
 * e foi o que aconteceu: em 2026-08-31 ele trazia <b>58</b> numa linha e <b>57</b> noutra, e o
 * banco trazia <b>60</b>. Quando a divergência foi finalmente medida, três telas de verdade
 * estavam faltando: o <b>Relatório de Ordens de Serviço</b> (entregue em 31/08 e nunca listado),
 * a abertura de OS e o Histórico do Cliente.
 *
 * <p>⭐ É a mesma família de {@code feedback_catalogo_de_telas_medido_pelo_codigo}: catálogo que
 * espelha o código diverge no dia em que ninguém compara. O {@code AcoesPorTelaConferemTest} já faz
 * isso para as <b>ações</b> de cada tela; este faz para a <b>existência</b> delas.
 *
 * <p>⚠️ Como aquele, este teste <b>falha</b> se não encontrar os fontes, em vez de passar vazio.
 * Guarda que passa por não ter achado nada é pior que guarda nenhum.
 *
 * <p>⚠️ Não é teste de Spring de propósito: é leitura de arquivo, não precisa de contexto nem de
 * banco. A contagem de {@code cfg_tela} fica de fora justamente por isso — ela mede outra coisa
 * (chaves de RBAC, com sub-ações sem rota), e amarrá-la aqui misturaria duas bases, que é
 * exatamente o defeito que o item #79 descreve.
 */
class ContagemDeTelasConfereTest {

    private static final String SECAO_PUBLICA = "Entrada (públicas, sem login)";
    private static final String SECAO_FUTURAS = "Implementações Futuras";

    /**
     * Rotas que o inventário não precisa listar: a raiz, o hub de menu e o catch-all.
     * A raiz aparece no doc (como "futura"), mas não é uma tela de menu.
     */
    private static final Set<String> IGNORAR = Set.of("/", "/menu/:grupo", "*");

    /**
     * Divergências já vistas, decididas e registradas — cada uma com o número da pendência.
     *
     * <p>⚠️ Exceção sem dono vira filtro que esconde. Ao fechar a pendência, apague a linha: se ela
     * sobreviver, o teste volta a apontar.
     */
    private static final Map<String, String> EXCECOES_NOMEADAS = Map.of(
            "/", "pendência #13 — o Painel é tela real e está listado como futura (decisão do dono)");

    private Path raiz() {
        Path aqui = Path.of("").toAbsolutePath();
        // A suíte roda com o diretório de trabalho em api/; aceita também a raiz do repositório.
        for (Path candidata : List.of(aqui.getParent() == null ? aqui : aqui.getParent(), aqui)) {
            if (candidata != null && Files.isRegularFile(candidata.resolve("docs/TELAS.md"))) {
                return candidata;
            }
        }
        throw new AssertionError("não encontrei docs/TELAS.md a partir de " + aqui);
    }

    private String ler(Path p) throws IOException {
        assertTrue(Files.isRegularFile(p), "não encontrei " + p.toAbsolutePath());
        // ⚠️ O \r do CRLF entra no nome da seção e faz toda comparação por igualdade falhar em
        // silêncio — foi o primeiro defeito do script que originou este teste.
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** Rotas listadas nas tabelas de {@code docs/TELAS.md}, por seção. */
    private record Inventario(List<String> publicas, List<String> futuras, List<String> emUso, List<String> filhas) {
        Set<String> todas() {
            var s = new LinkedHashSet<String>();
            s.addAll(publicas);
            s.addAll(futuras);
            s.addAll(emUso);
            s.addAll(filhas);
            return s;
        }
    }

    private Inventario lerInventario(String md) {
        // ⚠️ A rota NÃO está sempre na 2ª célula: a tabela de Relatórios tem uma coluna "Subgrupo"
        // a mais, e casar por posição perdia 11 telas em silêncio.
        Pattern rotaNaLinha = Pattern.compile("`(/[^`]*)`");
        List<String> publicas = new ArrayList<>();
        List<String> futuras = new ArrayList<>();
        List<String> emUso = new ArrayList<>();
        List<String> filhas = new ArrayList<>();
        String secao = "";
        boolean emFilhas = false;
        for (String linha : md.split("\n")) {
            if (linha.startsWith("## ")) {
                secao = linha.substring(3).trim();
                emFilhas = secao.equals("Telas-filhas");
                continue;
            }
            if (emFilhas && linha.startsWith("- `")) {
                Matcher m = Pattern.compile("^- `([^`]+)`").matcher(linha);
                if (m.find()) {
                    filhas.add(m.group(1));
                }
                continue;
            }
            if (!linha.startsWith("|")) {
                continue;
            }
            Matcher m = rotaNaLinha.matcher(linha);
            if (!m.find()) {
                continue;
            }
            String rota = m.group(1);
            switch (secao) {
                case SECAO_PUBLICA -> publicas.add(rota);
                case SECAO_FUTURAS -> futuras.add(rota);
                default -> emUso.add(rota);
            }
        }
        return new Inventario(publicas, futuras, emUso, filhas);
    }

    private record RotaDoCodigo(String rota, String componente, boolean emBreve) {
    }

    private List<RotaDoCodigo> lerRotas(String appTsx) {
        Matcher m = Pattern.compile("<Route\\s+path=\"([^\"]+)\"\\s+element=\\{<(\\w+)").matcher(appTsx);
        List<RotaDoCodigo> rotas = new ArrayList<>();
        while (m.find()) {
            rotas.add(new RotaDoCodigo(m.group(1), m.group(2), m.group(2).equals("EmBreve")));
        }
        return rotas;
    }

    /**
     * As três ações de linha do padrão de cadastro (ver/editar/excluir) geram rotas derivadas —
     * {@code /clientes/:id}, {@code /clientes/:id/visualizar}, {@code /ordens-servico/:id/:modo} —
     * que o documento não lista de propósito: são o mesmo formulário em outro modo.
     */
    private String baseDaVariante(String rota, Set<String> noDoc) {
        Matcher m = Pattern.compile("^(/[^/]+(?:/[^/:]+)*)/:[^/]+(?:/(?::[^/]+|visualizar))?$").matcher(rota);
        return m.matches() && noDoc.contains(m.group(1)) ? m.group(1) : null;
    }

    @Test
    void todaRotaDoAppEstaNoInventarioDeTelas() throws IOException {
        Path raiz = raiz();
        Inventario doc = lerInventario(ler(raiz.resolve("docs/TELAS.md")));
        List<RotaDoCodigo> codigo = lerRotas(ler(raiz.resolve("web/src/App.tsx")));

        assertTrue(codigo.size() > 100, "esperava mais de 100 rotas em App.tsx, achei " + codigo.size()
                + " — o regex de leitura provavelmente parou de casar, e um guarda que não lê nada passa sempre");

        Set<String> noDoc = doc.todas();
        List<String> faltando = new ArrayList<>();
        for (RotaDoCodigo r : codigo) {
            if (IGNORAR.contains(r.rota()) || noDoc.contains(r.rota()) || baseDaVariante(r.rota(), noDoc) != null) {
                continue;
            }
            faltando.add(r.rota() + " (" + r.componente() + ")");
        }
        assertTrue(faltando.isEmpty(), """
                Estas rotas existem em web/src/App.tsx e NÃO estão em docs/TELAS.md:
                %s
                Acrescente cada uma à tabela da seção certa (ou à lista "Telas-filhas") e refaça a
                contagem com: node scripts/auditoria/contagem-de-telas.js
                """.formatted(String.join("\n  ", faltando)));
    }

    @Test
    void inventarioNaoListaTelaQueNaoExisteMais() throws IOException {
        Path raiz = raiz();
        Inventario doc = lerInventario(ler(raiz.resolve("docs/TELAS.md")));
        Set<String> noCodigo = new LinkedHashSet<>(lerRotas(ler(raiz.resolve("web/src/App.tsx")))
                .stream().map(r -> r.rota()).toList());

        List<String> sobrando = doc.todas().stream().filter(r -> !noCodigo.contains(r)).toList();
        assertTrue(sobrando.isEmpty(),
                "docs/TELAS.md lista rota que não existe mais em App.tsx: " + sobrando
                        + "\nRemoção deixa rastro falso na documentação — é o que "
                        + "feedback_remocao_deixa_rastro_falso_na_memoria descreve.");
    }

    /**
     * "Implementações Futuras" tem de ser exatamente o conjunto de rotas que apontam para o
     * placeholder {@code <EmBreve>}.
     *
     * <p>Pega o defeito de {@code feedback_placeholder_embreve_vira_rota_duplicada} pelo outro
     * lado: ligar a tela e esquecer de tirar o item da lista deixa o inventário prometendo "em
     * construção" para função pronta.
     */
    @Test
    void futurasSaoExatamenteAsRotasComEmBreve() throws IOException {
        Path raiz = raiz();
        Inventario doc = lerInventario(ler(raiz.resolve("docs/TELAS.md")));
        Set<String> emBreve = new LinkedHashSet<>(lerRotas(ler(raiz.resolve("web/src/App.tsx")))
                .stream().filter(r -> r.emBreve()).map(r -> r.rota()).toList());

        List<String> problemas = new ArrayList<>();
        for (String r : doc.futuras()) {
            if (!emBreve.contains(r) && !EXCECOES_NOMEADAS.containsKey(r)) {
                problemas.add(r + ": o doc diz \"futura\", o código NÃO usa <EmBreve>");
            }
        }
        for (String r : emBreve) {
            if (!doc.futuras().contains(r)) {
                problemas.add(r + ": o código usa <EmBreve>, o doc NÃO lista como futura");
            }
        }
        assertTrue(problemas.isEmpty(), String.join("\n  ", problemas));
    }

    /**
     * ⭐ O caso que originou o item: o arquivo discordava <b>de si mesmo</b>. O rodapé trazia
     * "57 telas em uso" desde 26/08 enquanto o cabeçalho dizia outra coisa, e nada acusava.
     */
    @Test
    void osNumerosEscritosNoDocumentoBatemComAsTabelasDoProprioDocumento() throws IOException {
        String md = ler(raiz().resolve("docs/TELAS.md"));
        Inventario doc = lerInventario(md);

        assertEquals(doc.emUso().size(), numeroAntesDe(md, "telas em uso"),
                "o rodapé de docs/TELAS.md diverge das tabelas do próprio arquivo");
        assertEquals(doc.filhas().size(), numeroAntesDe(md, "telas-filhas"),
                "a contagem de telas-filhas diverge da lista do próprio arquivo");
    }

    /**
     * Número em negrito que precede a expressão. Aceita as duas formas que o arquivo usa —
     * {@code **59 telas em uso**} e {@code **59** telas em uso} — porque prender o guarda a uma
     * delas o faria reprovar por formatação, e um guarda que acusa falso treina quem lê a falha a
     * desconfiar dele (foi o que aconteceu com o {@code AcoesPorTelaConferemTest} em 29/08).
     */
    private int numeroAntesDe(String md, String expressao) {
        Matcher m = Pattern.compile("\\*\\*(\\d+)\\*{0,2}\\s+" + Pattern.quote(expressao)).matcher(md);
        assertTrue(m.find(), "não achei a contagem de \"" + expressao + "\" em docs/TELAS.md — "
                + "se o rodapé mudou de formato, este guarda precisa acompanhar, e não ser apagado");
        return Integer.parseInt(m.group(1));
    }
}
