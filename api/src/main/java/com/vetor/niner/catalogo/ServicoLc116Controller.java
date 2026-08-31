package com.vetor.niner.catalogo;

import com.vetor.niner.identidade.permissao.Tela;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Consulta à Lista Nacional de Serviços ({@code cfg_servico_lc116}, V099).
 *
 * <p>⭐ É a resposta para a maior fonte de chamado do módulo: <b>ninguém sabe que banho e tosa é
 * {@code 050801}</b>. A tela oferece dois caminhos, e o primeiro resolve a maioria dos casos:
 *
 * <ol>
 *   <li><b>Sugestão pelo ramo da loja</b> — a petshop vê 5 códigos, não 334. Foi ideia do dono do
 *       produto: <i>"como definimos os ramos de atuação fica mais restrito e certeiro"</i>.</li>
 *   <li><b>Busca por texto</b> — para quem não se encaixa na sugestão. O lojista digita "tosa",
 *       "conserto", "cabeleireiro"; a lista inteira fica atrás disso.</li>
 * </ol>
 *
 * <p>⚠️ A tabela é GLOBAL (sem {@code id_tenant}, sem RLS) — é a lista da União, igual para todos.
 * Por isso não há filtro de tenant nestas consultas, e é a exceção documentada, não descuido.
 *
 * <p>⚠️ A sugestão por ramo é <b>curadoria</b>, não fonte oficial: não existe mapa oficial
 * ramo→código. Está escrito na V099, e a tela precisa dizer "confirme com seu contador" — a
 * escolha do enquadramento é decisão fiscal, não do software.
 *
 * <p>⚠️ {@code @Tela("produtos")} porque quem consome é o cadastro de Produto: endpoint de apoio
 * herda a tela que o usa, senão quem tem permissão de produto leva 403 citando uma tela que o
 * admin nunca viu.
 */
@RestController
@RequestMapping("/api/v1/servicos/lc116")
@Tela("produtos")
public class ServicoLc116Controller {

    private final JdbcClient jdbc;

    public ServicoLc116Controller(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Busca por texto ou código. Sem termo, devolve vazio — a tela usa a sugestão por ramo nesse
     * caso, e despejar 334 linhas num campo de busca não ajuda ninguém.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public List<ServicoLc116> buscar(@RequestParam(required = false) String busca) {
        String termo = busca == null ? "" : busca.trim();
        if (termo.length() < 2) {
            return List.of();
        }
        String digitos = termo.replaceAll("\\D", "");
        return jdbc.sql("""
                        SELECT codigo, descricao, local_incidencia::text AS local_incidencia,
                               grupo_dps
                          FROM cfg_servico_lc116
                         WHERE unaccent_imutavel(lower(descricao)) LIKE '%' || unaccent_imutavel(lower(?)) || '%'
                            OR (? <> '' AND codigo LIKE ? || '%')
                         ORDER BY codigo
                         LIMIT 50
                        """)
                .params(termo, digitos, digitos)
                .query(ServicoLc116.class)
                .list();
    }

    /**
     * Os códigos que o ramo da loja costuma usar, na ordem em que a tela os oferece.
     *
     * <p>Ramo sem curadoria devolve vazio — e isso é melhor que sugerir errado: a V093 já
     * registrou que <i>"sugerir a partir de código que serve a dezenas de atividades é chutar com
     * cara de certeza"</i>.
     */
    @GetMapping("/sugestoes")
    @Transactional(readOnly = true)
    public List<ServicoLc116> sugestoesDoRamo() {
        // ⚠️ GROUP BY, não JOIN direto: um tenant com matriz e filiais do MESMO ramo traria o
        // código repetido uma vez por empresa. E MIN(ordem) preserva a ordem de exibição quando o
        // tenant tem empresas de ramos diferentes — o primeiro de cada ramo continua em cima.
        return jdbc.sql("""
                        SELECT s.codigo, s.descricao,
                               s.local_incidencia::text AS local_incidencia, s.grupo_dps
                          FROM cfg_ramo_servico_lc116 m
                          JOIN cfg_servico_lc116 s ON s.codigo = m.codigo
                         WHERE m.id_ramo IN (SELECT e.id_ramo FROM empresa e
                                              WHERE e.id_tenant = plataforma.tenant_atual()
                                                AND e.id_ramo IS NOT NULL)
                         GROUP BY s.codigo, s.descricao, s.local_incidencia, s.grupo_dps
                         ORDER BY MIN(m.ordem), s.codigo
                        """)
                .query(ServicoLc116.class)
                .list();
    }

    /**
     * @param localIncidencia PRESTADOR | PRESTACAO | TOMADOR | ESPECIAL | SEM_INCIDENCIA — vem da
     *        fonte oficial e é o que dispensa perguntar ao lojista onde o ISS é devido.
     * @param grupoDps quando preenchido, o serviço exige um bloco extra no layout (obra,
     *        atvEvento, lsadppu, explRod) que o v1 não monta — a tela recusa o código com
     *        mensagem honesta, em vez de deixar montar um XML incompleto.
     */
    public record ServicoLc116(String codigo, String descricao, String localIncidencia,
                               String grupoDps) {
    }
}
