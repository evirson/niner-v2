package com.vetor.niner;

import com.vetor.niner.canais.TipoCanal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prende o enum Java {@code TipoCanal} ao enum {@code tipo_canal} do banco (V013), e o
 * {@code origem_venda} ao valor {@code MARKETPLACE} que a V063 acrescentou.
 *
 * <p><b>Por que este teste existe.</b> Uma lista duplicada entre Java e banco fica correta
 * enquanto ninguém mexe nela, e o defeito nasce no dia em que alguém acrescenta um canal de um
 * lado só — longe de quem escreveu. É o mesmo padrão de
 * {@code EmpresaUfValidaTest}, que prende as três listas de UF do projeto, e o mesmo risco de
 * "constante literal onde existe um campo de domínio": correta até o segundo valor aparecer.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CanalTipoEmSincroniaTest {

    @Autowired
    JdbcClient jdbc;

    private Set<String> valoresDoEnum(String nomeDoTipo) {
        List<String> valores = jdbc.sql("""
                        SELECT e.enumlabel FROM pg_type t
                          JOIN pg_enum e ON e.enumtypid = t.oid
                         WHERE t.typname = ?
                        """)
                .param(nomeDoTipo)
                .query(String.class)
                .list();
        return Set.copyOf(valores);
    }

    @Test
    void tipoCanalDoJavaEDoBancoTemExatamenteOsMesmosValores() {
        Set<String> noJava = java.util.Arrays.stream(TipoCanal.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(valoresDoEnum("tipo_canal"))
                .as("acrescentou canal no Java sem ALTER TYPE (ou o contrário) — as duas listas "
                        + "precisam andar juntas, senão o INSERT falha só em produção")
                .isEqualTo(noJava);
    }

    /**
     * O valor que a V063 acrescentou. Sem ele, importar pedido de marketplace falharia no INSERT
     * da venda — e só quando a integração já estivesse pronta.
     */
    @Test
    void origemVendaConheceMarketplace() {
        assertThat(valoresDoEnum("origem_venda"))
                .contains("PDV", "IMPORTACAO", "MARKETPLACE");
    }
}
