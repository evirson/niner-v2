package com.vetor.niner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarda de arquitetura: <b>ninguém lê o IP do cliente por {@code getRemoteAddr()} cru</b>.
 *
 * <h2>Por que este teste existe</h2>
 *
 * <p>Atrás do nginx, {@code HttpServletRequest.getRemoteAddr()} devolve o IP do <b>proxy</b> — o
 * mesmo endereço para todo mundo. Quem grava esse valor num campo de auditoria não recebe erro
 * nenhum: recebe uma coluna cheia de dado errado, que só se revela quando alguém precisa dela.
 *
 * <p>⚠️ E o defeito nasce <b>certo em dev</b> (sem proxy, {@code getRemoteAddr()} é o IP real) e
 * errado só em produção, que é a pior combinação possível — não reproduz na máquina de quem
 * escreveu. Foi o que aconteceu com {@code codigo_login.ip_solicitante} e
 * {@code recuperacao_senha.ip_solicitante} (pendência 86): os dois nasceram assim, o primeiro foi
 * corrigido junto com o log de acesso em 2026-09-01 e o segundo sobreviveu mais um dia, porque
 * <b>nada comparava</b>.
 *
 * <p>A fonte única é {@code comum.web.IpDoCliente}, que lê {@code X-Real-IP} (o nginx
 * <b>sobrescreve</b> esse cabeçalho, então o cliente não o forja) e cai para o <b>último</b> salto
 * do {@code X-Forwarded-For} — nunca o primeiro, que é escolhido por quem chama.
 *
 * <h2>As duas exceções, e por que são exceções</h2>
 *
 * <ul>
 *   <li>{@code IpDoCliente} — é ele quem faz a resolução; o {@code getRemoteAddr()} lá dentro é o
 *       último recurso, quando não há proxy confiável.</li>
 *   <li>{@code LimiteRequisicaoFilter} — tem a mesma lógica embutida desde antes de a classe
 *       existir. ⛔ Não foi unificado aqui de propósito: mexer no balde de requisições exige
 *       exercitar o limite, e este teste é sobre <b>dado gravado</b>, não sobre throttling.</li>
 * </ul>
 */
class IpDoClienteEhFonteUnicaTest {

    private static final Path FONTES = Path.of("src", "main", "java");

    /** Chamada de {@code getRemoteAddr()} em qualquer receptor ({@code http.}, {@code req.}, …). */
    private static final Pattern PROIBIDA = Pattern.compile("\\.getRemoteAddr\\s*\\(\\s*\\)");

    /**
     * As únicas classes autorizadas — o nome do arquivo, não o pacote, porque é o que o
     * {@code Path} entrega em qualquer sistema de arquivos.
     */
    private static final Set<String> AUTORIZADAS =
            Set.of("IpDoCliente.java", "LimiteRequisicaoFilter.java");

    @Test
    void nenhumCodigoLeOIpPeloGetRemoteAddr() throws IOException {
        List<String> infracoes = new ArrayList<>();

        try (Stream<Path> arquivos = Files.walk(FONTES)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (AUTORIZADAS.contains(arquivo.getFileName().toString())) {
                    continue;
                }
                List<String> linhas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
                for (int i = 0; i < linhas.size(); i++) {
                    String linha = linhas.get(i);
                    if (ehComentario(linha) || !PROIBIDA.matcher(linha).find()) {
                        continue;
                    }
                    infracoes.add("%s:%d → %s".formatted(arquivo, i + 1, linha.trim()));
                }
            }
        }

        assertThat(infracoes)
                .as("""
                        Leitura do IP por getRemoteAddr() fora da fonte única. Atrás do nginx isso \
                        devolve o IP do PROXY, igual para todos os clientes — e o defeito não \
                        reproduz em dev, onde não há proxy. Injete comum.web.IpDoCliente e use \
                        ipDoCliente.de(request); quando o valor for gravado como auditoria, grave \
                        junto ipDoCliente.confiavel(), senão daqui a um ano ninguém sabe se aquele \
                        endereço é do cliente ou do nginx.""")
                .isEmpty();
    }

    /**
     * O guarda só vale se pegar o defeito — sem este par, um regex quebrado viraria um verde vazio
     * e o próximo {@code getRemoteAddr()} entraria sem ninguém ver.
     */
    @Test
    void oGuardaReprovaAConstrucaoErradaEAceitaACerta() {
        assertThat(reprovaria("recuperacao.solicitar(req, http.getRemoteAddr());")).isTrue();
        assertThat(reprovaria("String ip = request.getRemoteAddr();")).isTrue();
        assertThat(reprovaria("recuperacao.solicitar(req, ipDoCliente.de(http));")).isFalse();
        assertThat(reprovaria("signup.login(req, ipDoCliente.de(http))")).isFalse();
        // Comentário que cita o método não é código, e reprovar por ele treinaria quem lê a falha
        // a desconfiar do guarda — foi assim que o javadoc do IpDoCliente quase reprovou o build.
        assertThat(reprovaria("     * nunca getRemoteAddr() cru, que devolveria o IP do proxy")).isFalse();
    }

    private static boolean reprovaria(String linha) {
        return !ehComentario(linha) && PROIBIDA.matcher(linha).find();
    }

    private static boolean ehComentario(String linha) {
        String limpa = linha.stripLeading();
        return limpa.startsWith("//") || limpa.startsWith("*") || limpa.startsWith("/*")
                || limpa.startsWith("--");
    }
}
