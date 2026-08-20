package com.vetor.niner.cadastros.crm;

import com.vetor.niner.cadastros.crm.CrmDtos.ClienteCrmResponse;
import com.vetor.niner.cadastros.crm.CrmDtos.OpcaoCrm;
import com.vetor.niner.cadastros.crm.CrmDtos.OpcoesCrmResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CRM — segmentação de clientes (ver {@code package-info.java} pro resumo de escopo/decisões).
 */
@Service
public class CrmService {

    /** "dd-mm" (dia primeiro — mesma convenção `dd/mm/aaaa` do resto do sistema, §3.7). */
    private static final Pattern DIA_MES = Pattern.compile("^(0[1-9]|[12]\\d|3[01])-(0[1-9]|1[0-2])$");
    private static final List<String> GENEROS_VALIDOS = List.of("MASCULINO", "FEMININO", "OUTROS");

    private final JdbcClient jdbc;

    public CrmService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record FiltrosCrm(
            String clienteInicial, String clienteFinal,
            List<String> generos,
            Integer idadeDe, Integer idadeAte,
            String aniversarioDe, String aniversarioAte,
            List<Long> idsCategoriaCliente,
            LocalDate cadastroDe, LocalDate cadastroAte,
            Integer diasSemComprasMinimo,
            LocalDate comprasDe, LocalDate comprasAte,
            List<Long> idsCategoriaProduto,
            List<Long> idsCor,
            List<Long> idsTamanho) {
    }

    @Transactional(readOnly = true)
    public OpcoesCrmResponse opcoes() {
        return new OpcoesCrmResponse(
                buscarOpcoes("SELECT id_categoria_cliente AS id, nome_categoria AS rotulo FROM cfg_categoria_cliente "
                        + "WHERE id_tenant = plataforma.tenant_atual() ORDER BY nome_categoria"),
                buscarOpcoes("SELECT id_categoria AS id, nome_categoria AS rotulo FROM cfg_categoria_produto "
                        + "WHERE id_tenant = plataforma.tenant_atual() ORDER BY nome_categoria"),
                buscarOpcoes("SELECT id_cor AS id, descricao AS rotulo FROM cfg_cor "
                        + "WHERE id_tenant = plataforma.tenant_atual() AND id_cor <> 1 ORDER BY descricao"),
                buscarOpcoes("SELECT id_tamanho AS id, descricao AS rotulo FROM cfg_tamanho "
                        + "WHERE id_tenant = plataforma.tenant_atual() AND id_tamanho <> 1 ORDER BY descricao"));
    }

    private List<OpcaoCrm> buscarOpcoes(String sql) {
        return jdbc.sql(sql).query((rs, n) -> new OpcaoCrm(rs.getLong("id"), rs.getString("rotulo"))).list();
    }

    @Transactional(readOnly = true)
    public List<ClienteCrmResponse> buscarClientes(Jwt jwt, FiltrosCrm f) {
        validar(f);

        List<Object> paramsCompras = new ArrayList<>();
        String filtroEmpresaCompras = filtroEmpresa(jwt, "v", paramsCompras);

        StringBuilder sql = new StringBuilder("""
                WITH compras AS (
                    SELECT v.id_cliente,
                           MIN(v.data_venda AT TIME ZONE 'America/Sao_Paulo')::date AS primeira_compra,
                           MAX(v.data_venda AT TIME ZONE 'America/Sao_Paulo')::date AS ultima_compra,
                           COUNT(DISTINCT v.id_venda) AS numero_compras,
                           COALESCE(SUM(pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo), 0)
                               AS valor_total
                    FROM venda v
                    JOIN produto_movimento_mestre pmm
                           ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant
                          AND pmm.tipo_movimento = 'VENDA'
                    JOIN produto_movimento_detalhe pmd
                           ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                          AND pmd.credito_debito = 'D'
                    WHERE v.id_tenant = plataforma.tenant_atual() AND v.cancelada = false AND v.id_cliente IS NOT NULL
                """);
        sql.append(filtroEmpresaCompras);
        sql.append("""
                    GROUP BY v.id_cliente
                )
                SELECT c.id_cliente, c.nome, c.data_nascimento, c.genero::text AS genero, c.email, c.telefone AS celular,
                       co.primeira_compra, co.ultima_compra, COALESCE(co.numero_compras, 0) AS numero_compras,
                       COALESCE(co.valor_total, 0) AS valor_total_compras,
                       ROUND(co.valor_total / NULLIF(co.numero_compras, 0), 2) AS ticket_medio,
                       ((now() AT TIME ZONE 'America/Sao_Paulo')::date - co.ultima_compra) AS dias_sem_ultima_compra
                FROM cliente c
                LEFT JOIN compras co ON co.id_cliente = c.id_cliente
                WHERE c.id_tenant = plataforma.tenant_atual() AND c.ativo
                """);
        List<Object> params = new ArrayList<>(paramsCompras);

        if (f.clienteInicial() != null && !f.clienteInicial().isBlank()) {
            sql.append(" AND upper(left(c.nome, 1)) >= upper(left(?, 1))");
            params.add(f.clienteInicial());
        }
        if (f.clienteFinal() != null && !f.clienteFinal().isBlank()) {
            sql.append(" AND upper(left(c.nome, 1)) <= upper(left(?, 1))");
            params.add(f.clienteFinal());
        }
        if (f.generos() != null && !f.generos().isEmpty()) {
            sql.append(" AND c.genero::text IN (").append(placeholders(f.generos().size())).append(")");
            params.addAll(f.generos());
        }
        if (f.idadeDe() != null) {
            sql.append(" AND EXTRACT(YEAR FROM age((now() AT TIME ZONE 'America/Sao_Paulo')::date, c.data_nascimento)) >= ?");
            params.add(f.idadeDe());
        }
        if (f.idadeAte() != null) {
            sql.append(" AND EXTRACT(YEAR FROM age((now() AT TIME ZONE 'America/Sao_Paulo')::date, c.data_nascimento)) <= ?");
            params.add(f.idadeAte());
        }
        if (f.aniversarioDe() != null && f.aniversarioAte() != null) {
            // Entrada é "dd-mm" (mesma convenção dia-primeiro de dd/mm/aaaa, §3.7); convertida pra
            // "mm-dd" pra comparar em ordem cronológica de calendário (mês pesa mais que dia) e
            // bater com `to_char(..., 'MM-DD')` da query.
            String mesDiaDe = paraMesDia(f.aniversarioDe());
            String mesDiaAte = paraMesDia(f.aniversarioAte());
            if (mesDiaDe.compareTo(mesDiaAte) <= 0) {
                sql.append(" AND to_char(c.data_nascimento, 'MM-DD') BETWEEN ? AND ?");
                params.add(mesDiaDe);
                params.add(mesDiaAte);
            } else {
                // Intervalo cruza a virada do ano (ex.: 20/12 a 10/01) — não dá pra usar BETWEEN.
                sql.append(" AND (to_char(c.data_nascimento, 'MM-DD') >= ? OR to_char(c.data_nascimento, 'MM-DD') <= ?)");
                params.add(mesDiaDe);
                params.add(mesDiaAte);
            }
        }
        if (f.idsCategoriaCliente() != null && !f.idsCategoriaCliente().isEmpty()) {
            sql.append(" AND c.id_categoria_cliente IN (").append(placeholders(f.idsCategoriaCliente().size())).append(")");
            params.addAll(f.idsCategoriaCliente());
        }
        if (f.cadastroDe() != null && f.cadastroAte() != null) {
            sql.append(" AND (c.criado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?");
            params.add(f.cadastroDe());
            params.add(f.cadastroAte());
        }
        if (f.diasSemComprasMinimo() != null) {
            sql.append(" AND (co.ultima_compra IS NULL OR (now() AT TIME ZONE 'America/Sao_Paulo')::date - co.ultima_compra >= ?)");
            params.add(f.diasSemComprasMinimo());
        }

        boolean filtroProdutos = temFiltroProdutos(f);
        if (filtroProdutos) {
            List<Object> paramsExists = new ArrayList<>();
            StringBuilder exists = new StringBuilder("""
                     AND EXISTS (
                        SELECT 1
                        FROM venda v2
                        JOIN produto_movimento_mestre pmm2
                               ON pmm2.id_venda = v2.id_venda AND pmm2.id_tenant = v2.id_tenant
                              AND pmm2.tipo_movimento = 'VENDA'
                        JOIN produto_movimento_detalhe pmd2
                               ON pmd2.id_movimento = pmm2.id_movimento AND pmd2.id_tenant = pmm2.id_tenant
                              AND pmd2.credito_debito = 'D'
                        JOIN produto_barra pb2 ON pb2.id_variacao = pmd2.id_variacao AND pb2.id_tenant = pmd2.id_tenant
                    """);
            if (f.idsCategoriaProduto() != null && !f.idsCategoriaProduto().isEmpty()) {
                exists.append("""
                            JOIN produto_categoria pc2 ON pc2.id_produto = pb2.id_produto AND pc2.id_tenant = pb2.id_tenant
                        """);
            }
            exists.append(" WHERE v2.id_tenant = plataforma.tenant_atual() AND v2.id_cliente = c.id_cliente AND v2.cancelada = false");
            exists.append(filtroEmpresa(jwt, "v2", paramsExists));
            if (f.comprasDe() != null && f.comprasAte() != null) {
                exists.append(" AND (v2.data_venda AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?");
                paramsExists.add(f.comprasDe());
                paramsExists.add(f.comprasAte());
            }
            if (f.idsCategoriaProduto() != null && !f.idsCategoriaProduto().isEmpty()) {
                exists.append(" AND pc2.id_categoria IN (").append(placeholders(f.idsCategoriaProduto().size())).append(")");
                paramsExists.addAll(f.idsCategoriaProduto());
            }
            if (f.idsCor() != null && !f.idsCor().isEmpty()) {
                exists.append(" AND pb2.id_cor IN (").append(placeholders(f.idsCor().size())).append(")");
                paramsExists.addAll(f.idsCor());
            }
            if (f.idsTamanho() != null && !f.idsTamanho().isEmpty()) {
                exists.append(" AND pb2.id_tamanho IN (").append(placeholders(f.idsTamanho().size())).append(")");
                paramsExists.addAll(f.idsTamanho());
            }
            exists.append(")");
            sql.append(exists);
            params.addAll(paramsExists);
        }

        sql.append(" ORDER BY c.nome");

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new ClienteCrmResponse(
                        rs.getLong("id_cliente"),
                        rs.getString("nome"),
                        rs.getObject("data_nascimento", LocalDate.class),
                        rs.getString("genero"),
                        rs.getString("email"),
                        rs.getString("celular"),
                        rs.getObject("primeira_compra", LocalDate.class),
                        rs.getObject("ultima_compra", LocalDate.class),
                        rs.getLong("numero_compras"),
                        rs.getBigDecimal("valor_total_compras"),
                        rs.getBigDecimal("ticket_medio"),
                        getLongOuNulo(rs, "dias_sem_ultima_compra")))
                .list();
    }

    private boolean temFiltroProdutos(FiltrosCrm f) {
        return (f.comprasDe() != null && f.comprasAte() != null)
                || (f.idsCategoriaProduto() != null && !f.idsCategoriaProduto().isEmpty())
                || (f.idsCor() != null && !f.idsCor().isEmpty())
                || (f.idsTamanho() != null && !f.idsTamanho().isEmpty());
    }

    /** OPERADOR só vê compras da própria empresa ativa (claim {@code eid}); ADMIN vê todas —
     * mesma regra já aplicada em todo relatório do sistema (ver package-info.java), aqui sem
     * seletor na tela (não pedido). */
    private String filtroEmpresa(Jwt jwt, String aliasVenda, List<Object> params) {
        if (ehAdmin(jwt)) {
            return "";
        }
        params.add(idEmpresaSessao(jwt));
        return " AND " + aliasVenda + ".id_empresa = ?";
    }

    private void validar(FiltrosCrm f) {
        exigirParOuNenhum("aniversário", f.aniversarioDe(), f.aniversarioAte());
        exigirParOuNenhum("período de cadastro", f.cadastroDe(), f.cadastroAte());
        exigirParOuNenhum("período de compras", f.comprasDe(), f.comprasAte());
        if (f.aniversarioDe() != null && !DIA_MES.matcher(f.aniversarioDe()).matches()) {
            throw new IllegalArgumentException("Aniversário 'De' deve estar no formato dd-mm.");
        }
        if (f.aniversarioAte() != null && !DIA_MES.matcher(f.aniversarioAte()).matches()) {
            throw new IllegalArgumentException("Aniversário 'Até' deve estar no formato dd-mm.");
        }
        if (f.idadeDe() != null && f.idadeAte() != null && f.idadeDe() > f.idadeAte()) {
            throw new IllegalArgumentException("Idade 'De' não pode ser maior que a 'Até'.");
        }
        if (f.cadastroDe() != null && f.cadastroAte() != null && f.cadastroDe().isAfter(f.cadastroAte())) {
            throw new IllegalArgumentException("Período de cadastro: data inicial não pode ser maior que a final.");
        }
        if (f.comprasDe() != null && f.comprasAte() != null && f.comprasDe().isAfter(f.comprasAte())) {
            throw new IllegalArgumentException("Período de compras: data inicial não pode ser maior que a final.");
        }
        if (f.diasSemComprasMinimo() != null && f.diasSemComprasMinimo() < 0) {
            throw new IllegalArgumentException("Nº de dias sem compras não pode ser negativo.");
        }
        if (f.generos() != null) {
            for (String g : f.generos()) {
                if (!GENEROS_VALIDOS.contains(g)) {
                    throw new IllegalArgumentException("Gênero inválido: " + g);
                }
            }
        }
    }

    private void exigirParOuNenhum(String nomeFiltro, Object de, Object ate) {
        if ((de == null) != (ate == null)) {
            throw new IllegalArgumentException("Informe início e fim de " + nomeFiltro + ", ou deixe os dois em branco.");
        }
    }

    /** {@code rs.getObject(coluna, Long.class)} não converte `integer` (o tipo real de `current_date
     * - data`) pra `Long` de forma confiável no driver — mesmo padrão já usado em
     * {@code RelatorioContasReceberService.getLongOuNulo}. */
    private static Long getLongOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    /** "dd-mm" (entrada validada por {@link #DIA_MES}) -> "mm-dd" (ordem cronológica de calendário,
     * pro comparar/bater com {@code to_char(..., 'MM-DD')}). */
    private static String paraMesDia(String diaMes) {
        String[] partes = diaMes.split("-");
        return partes[1] + "-" + partes[0];
    }

    private static String placeholders(int quantidade) {
        return String.join(",", java.util.Collections.nCopies(quantidade, "?"));
    }

    private static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }

    private static long idEmpresaSessao(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }
}
