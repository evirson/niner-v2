package com.vetor.niner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `gerar_ean13_interno()` (V017) — gerador de código de barras interno de
 * {@code produto_barra.sku}. Tabela de controle ({@code cfg_ean_gerador}) é GLOBAL (sem
 * id_tenant/RLS, P9): "banco" aqui é a instância de banco de dados, não o tenant — por isso o
 * teste não passa por nenhum tenant/JWT, só chama a função direto via JDBC.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EanGeradorTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void geraCodigoComTrezeDigitosEDigitoVerificadorValido() {
        String codigo = jdbc.sql("SELECT gerar_ean13_interno()").query(String.class).single();

        assertThat(codigo).hasSize(13);
        assertThat(codigo).matches("\\d{13}");
        assertThat(codigo).startsWith("9"); // F fixo (código interno, não GS1)
        assertThat(digitoVerificadorValido(codigo)).isTrue();
    }

    @Test
    void chamadasSucessivasGeramCodigosDiferentesEIncrementais() {
        String primeiro = jdbc.sql("SELECT gerar_ean13_interno()").query(String.class).single();
        String segundo = jdbc.sql("SELECT gerar_ean13_interno()").query(String.class).single();
        String terceiro = jdbc.sql("SELECT gerar_ean13_interno()").query(String.class).single();

        Set<String> gerados = new HashSet<>(Set.of(primeiro, segundo, terceiro));
        assertThat(gerados).hasSize(3); // sem duplicata

        // Sequencial embutido (posições 5-12, entre id_banco e DV) cresce a cada chamada.
        long seq1 = Long.parseLong(primeiro.substring(4, 12));
        long seq2 = Long.parseLong(segundo.substring(4, 12));
        long seq3 = Long.parseLong(terceiro.substring(4, 12));
        assertThat(seq2).isEqualTo(seq1 + 1);
        assertThat(seq3).isEqualTo(seq2 + 1);
    }

    /** Algoritmo padrão EAN-13/GTIN: peso 1/3 alternado nas 12 primeiras posições. */
    /**
     * ⚠️ O prefixo saiu do corpo da função e virou <b>dado</b> (V050), porque a importação de
     * estoque precisa recusar código de barras que caia na nossa faixa — e escrever o {@code '9'}
     * de novo lá criaria uma segunda cópia da regra, que divergiria no dia da troca.
     *
     * <p>Este teste é o que <b>prende os dois lados na mesma linha da tabela</b>: gerador e
     * validação leem o mesmo valor.
     */
    @Test
    void oPrefixoDoCodigoGeradoVemDaTabelaNaoDoCorpoDaFuncao() {
        String prefixoConfigurado = jdbc.sql("SELECT prefixo FROM cfg_ean_gerador LIMIT 1")
                .query(String.class).single();
        String codigo = jdbc.sql("SELECT gerar_ean13_interno()").query(String.class).single();

        assertThat(codigo).startsWith(prefixoConfigurado);
        assertThat(prefixosReservados()).contains(prefixoConfigurado);
    }

    /**
     * O prefixo corrente <b>tem</b> de estar entre os reservados — o CHECK da V050 impede trocar e
     * esquecer. Sem isso, a troca abriria em silêncio a faixa nova para código legado.
     */
    @Test
    void trocarOPrefixoSemReservarEhRecusadoPeloBanco() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE cfg_ean_gerador SET prefixo = '8'").update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // Trocando do jeito certo (prefixo + reserva) o banco aceita — e o código gerado acompanha.
        // ⚠️ O `::text` é obrigatório: sem ele o Postgres lê o '8' como literal de ARRAY e recusa
        // com "malformed array literal" — descoberto escrevendo este teste.
        jdbc.sql("UPDATE cfg_ean_gerador SET prefixo = '8', "
                        + "prefixos_reservados = prefixos_reservados || '8'::text")
                .update();
        try {
            assertThat(jdbc.sql("SELECT gerar_ean13_interno()").query(String.class).single()).startsWith("8");
            assertThat(prefixosReservados()).contains("9", "8"); // o antigo continua barrado
        } finally {
            jdbc.sql("UPDATE cfg_ean_gerador SET prefixo = '9', prefixos_reservados = ARRAY['9']").update();
        }
    }

    private List<String> prefixosReservados() {
        return List.of(jdbc.sql("SELECT prefixos_ean_reservados() AS p")
                .query((rs, n) -> (String[]) rs.getArray("p").getArray()).single());
    }

    private static boolean digitoVerificadorValido(String codigo) {
        int soma = 0;
        for (int i = 0; i < 12; i++) {
            int digito = codigo.charAt(i) - '0';
            soma += digito * (i % 2 == 0 ? 1 : 3);
        }
        int dv = (10 - (soma % 10)) % 10;
        return dv == (codigo.charAt(12) - '0');
    }
}
