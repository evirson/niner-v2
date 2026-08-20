package com.vetor.niner;

import com.vetor.niner.fiscal.documento.ChaveAcesso;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A UF <b>congelada na chave de acesso</b> — o caminho inverso do {@code cUF} (2026-08-20).
 *
 * <p>Teste unitário puro: não sobe contexto nem banco, porque a regra é aritmética de string sobre
 * um mapa constante.
 */
class ChaveAcessoUfTest {

    /**
     * A UF de um documento que já existe vem <b>da chave</b>, não da empresa de hoje (2026-08-20).
     * Documento fiscal é registro imutável: se o lojista mudar a empresa de UF, ler a UF atual
     * reinterpretaria o histórico inteiro — e o cancelamento, que escolhe a SEFAZ de destino por
     * essa UF, mandaria a nota antiga para o autorizador errado.
     */
    @Test
    void ufVemCongeladaNaChaveDeAcesso() {
        // Chave real do B0 (PR, cUF 41) — a mesma que autorizou na SEFAZ-PR.
        assertThat(ChaveAcesso.ufDaChave("41260837829453000135650010000000051323005118")).isEqualTo("PR");
        assertThat(ChaveAcesso.ufDaChave("35260812345678000199650010000000011234567890")).isEqualTo("SP");
        assertThat(ChaveAcesso.ufDaChave("13260812345678000199650010000000011234567890")).isEqualTo("AM");
        assertThat(ChaveAcesso.ufDaChave("12260812345678000199650010000000011234567890")).isEqualTo("AC");
    }

    /** Sem chave, o chamador cai na UF da empresa — documento que nunca recebeu número. */
    @Test
    void semChaveNaoInventaUf() {
        assertThat(ChaveAcesso.ufDaChave(null)).isNull();
        assertThat(ChaveAcesso.ufDaChave("")).isNull();
        assertThat(ChaveAcesso.ufDaChave("99260812345678000199650010000000011234567890")).isNull();
        assertThat(ChaveAcesso.ufDaChave("XX2608")).isNull();
    }

    /** O mapa inverso é derivado do direto — as 27 UFs têm de dar a volta completa. */
    @Test
    void oMapaInversoCobreAs27UfsSemDivergir() {
        for (String uf : new String[] {
                "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA",
                "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO"}) {
            String chaveFicticia = "%02d".formatted(ChaveAcesso.codigoUfDe(uf)) + "2608123456780001996500100000000112345678";
            assertThat(ChaveAcesso.ufDaChave(chaveFicticia)).as("volta de %s", uf).isEqualTo(uf);
        }
    }
}
