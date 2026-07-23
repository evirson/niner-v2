package com.vetor.niner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
