package com.vetor.niner.fiscal.sefaz;

import com.vetor.niner.fiscal.sefaz.SefazDtos.Autorizador;
import com.vetor.niner.fiscal.sefaz.SefazDtos.ServicoSefaz;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * Resolve o autorizador da UF — endpoints, prazos e URLs de consulta (F10: "a UF é dado, não
 * código"). Lê {@code cfg_uf_autorizador}, que é <b>global e sem RLS</b>, igual a
 * {@code cfg_produto_ncm}: é referência nacional, não dado de tenant.
 *
 * <p>É esta indireção que permite a UF piloto virar Brasil sem reescrever o módulo: acrescentar
 * um estado é uma linha na tabela, nunca um {@code if (uf == "PR")} espalhado pelo código.
 */
@Service
public class SefazAutorizadorService {

    /** Allowlist — o nome da coluna entra concatenado no SQL, então nunca pode vir do cliente. */
    private static final Set<String> COLUNAS_URL = Set.of(
            "url_autorizacao", "url_ret_autorizacao", "url_status_servico",
            "url_recepcao_evento", "url_inutilizacao", "url_consulta_protocolo");

    private final JdbcClient jdbc;

    public SefazAutorizadorService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Autorizador buscar(String uf, int modelo, int ambiente) {
        return jdbc.sql("""
                        SELECT uf, codigo_uf_ibge, autorizador, url_qrcode, url_consulta_publica,
                               prazo_cancelamento_min, prazo_contingencia_horas
                        FROM cfg_uf_autorizador
                        WHERE uf = ? AND modelo = ? AND ambiente = ?
                        """)
                .params(normalizar(uf), modelo, ambiente)
                .query((rs, n) -> new Autorizador(
                        rs.getString("uf"),
                        rs.getInt("codigo_uf_ibge"),
                        rs.getString("autorizador"),
                        rs.getString("url_qrcode"),
                        rs.getString("url_consulta_publica"),
                        (Integer) rs.getObject("prazo_cancelamento_min"),
                        (Integer) rs.getObject("prazo_contingencia_horas")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(CONFLICT,
                        ("A UF %s ainda não está cadastrada para o modelo %d no ambiente %d. "
                                + "O Niner atende hoje as UFs presentes em cfg_uf_autorizador.")
                                .formatted(normalizar(uf), modelo, ambiente)));
    }

    /**
     * URL de um serviço específico. Devolve {@code null} quando a UF existe mas o serviço ainda
     * não foi mapeado — quem chama decide se isso bloqueia (o transporte recusa com mensagem que
     * aponta a tabela).
     */
    @Transactional(readOnly = true)
    public String urlDe(String uf, int modelo, int ambiente, ServicoSefaz servico) {
        String coluna = servico.coluna();
        if (!COLUNAS_URL.contains(coluna)) {
            throw new IllegalArgumentException("Coluna de URL não permitida: " + coluna);
        }
        return jdbc.sql("""
                        SELECT %s FROM cfg_uf_autorizador
                        WHERE uf = ? AND modelo = ? AND ambiente = ?
                        """.formatted(coluna))
                .params(normalizar(uf), modelo, ambiente)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static String normalizar(String uf) {
        return uf == null ? null : uf.trim().toUpperCase(Locale.ROOT);
    }
}
