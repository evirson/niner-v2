package com.vetor.niner.plataforma.tenants;

import com.vetor.niner.plataforma.tenants.TenantAdminDtos.EmpresaResumo;
import com.vetor.niner.plataforma.tenants.TenantAdminDtos.FaturaResumo;
import com.vetor.niner.plataforma.tenants.TenantAdminDtos.PaginaTenants;
import com.vetor.niner.plataforma.tenants.TenantAdminDtos.TenantDetalhe;
import com.vetor.niner.plataforma.tenants.TenantAdminDtos.TenantResumo;
import com.vetor.niner.plataforma.tenants.TenantAdminDtos.UsoMensal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Lista e ficha de tenants para o staff (R17).
 *
 * <p><b>Leitura cross-tenant sem impersonação, e só do que é do control-plane:</b> nome da conta,
 * plano, uso, faturas e a identificação das empresas. Nada de dado operacional do lojista (venda,
 * cliente, produto) — para isso existe a impersonação auditada (R21), que é outro caminho.
 *
 * <p>As empresas aparecem porque o suporte precisa saber "é esse CNPJ mesmo?" numa ligação; ainda
 * assim é identificação, não movimento.
 */
@Service
public class TenantAdminService {

    private static final String MRR =
            "CASE WHEN a.status = 'ATIVA' AND NOT p.gratuito "
                    + "THEN CASE a.ciclo WHEN 'ANUAL' THEN p.preco_anual / 12 ELSE p.preco_mensal END ELSE 0 END";

    /** A linha base da lista e da ficha é a mesma consulta — a ficha só acrescenta detalhe. */
    private static final String SELECT_RESUMO = """
            SELECT t.id_tenant, t.nome_conta, t.slug, t.email_contato, t.status::text AS status,
                   p.nome AS plano, p.gratuito, p.limite_vendas_mes,
                   CASE WHEN u.competencia_vendas = date_trunc('month', now())::date
                        THEN u.qtd_vendas_mes ELSE 0 END AS vendas_no_mes,
                   COALESCE(u.qtd_empresas, 0) AS qtd_empresas,
                   COALESCE(u.qtd_usuarios, 0) AS qtd_usuarios,
                   %s AS mrr,
                   t.criado_em
              FROM plataforma.tenant t
              LEFT JOIN plataforma.assinatura a ON a.id_tenant = t.id_tenant AND a.status <> 'CANCELADA'
              LEFT JOIN plataforma.plano p      ON p.id_plano = a.id_plano
              LEFT JOIN plataforma.uso_tenant u ON u.id_tenant = t.id_tenant
            """.formatted(MRR);

    private final JdbcClient jdbc;

    public TenantAdminService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PaginaTenants listar(String busca, String status, int pagina, int limite) {
        int pag = Math.max(pagina, 1);
        int lim = limite <= 0 || limite > 200 ? 50 : limite;
        // Busca e status entram como PARÂMETRO — nunca concatenados no texto do SQL.
        String filtro = """
                 WHERE (CAST(? AS text) IS NULL OR t.nome_conta ILIKE '%' || ? || '%'
                                                OR t.slug ILIKE '%' || ? || '%'
                                                OR t.email_contato ILIKE '%' || ? || '%')
                   AND (CAST(? AS text) IS NULL OR t.status::text = ?)
                """;

        long total = jdbc.sql("SELECT count(*) FROM plataforma.tenant t " + filtro)
                .params(busca, busca, busca, busca, status, status)
                .query(Long.class).single();

        List<TenantResumo> itens = jdbc.sql(SELECT_RESUMO + filtro + " ORDER BY t.criado_em DESC LIMIT ? OFFSET ?")
                .params(busca, busca, busca, busca, status, status, lim, (long) (pag - 1) * lim)
                .query(TenantAdminService::mapear)
                .list();

        return new PaginaTenants(itens, total, pag, lim);
    }

    @Transactional(readOnly = true)
    public TenantDetalhe detalhar(long idTenant) {
        TenantResumo resumo = jdbc.sql(SELECT_RESUMO + " WHERE t.id_tenant = ?")
                .param(idTenant)
                .query(TenantAdminService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Conta não encontrada."));

        List<UsoMensal> historico = jdbc.sql("""
                        SELECT competencia, qtd_vendas FROM plataforma.uso_venda_mes
                         WHERE id_tenant = ? ORDER BY competencia DESC LIMIT 12
                        """)
                .param(idTenant)
                .query((rs, n) -> new UsoMensal(rs.getObject("competencia", LocalDate.class), rs.getInt("qtd_vendas")))
                .list();

        List<FaturaResumo> faturas = jdbc.sql("""
                        SELECT f.id_fatura, f.competencia, COALESCE(p.nome, '—') AS plano, f.valor,
                               f.status::text AS situacao, f.pago_em
                          FROM plataforma.fatura f
                          LEFT JOIN plataforma.plano p ON p.id_plano = f.id_plano
                         WHERE f.id_tenant = ? ORDER BY f.competencia DESC LIMIT 24
                        """)
                .param(idTenant)
                .query((rs, n) -> new FaturaResumo(rs.getLong("id_fatura"),
                        rs.getObject("competencia", LocalDate.class), rs.getString("plano"),
                        rs.getBigDecimal("valor"), rs.getString("situacao"),
                        rs.getObject("pago_em", OffsetDateTime.class)))
                .list();

        // `empresa` é tabela de DOMÍNIO, com RLS: sem contexto de tenant o staff não leria nada.
        // Estabelecer o contexto aqui é leitura de identificação (CNPJ/cidade), não de movimento —
        // e fica restrito a esta consulta.
        jdbc.sql("SELECT set_config('app.id_tenant', ?, true)")
                .param(Long.toString(idTenant)).query(String.class).single();
        List<EmpresaResumo> empresas = jdbc.sql("""
                        SELECT codigo_empresa, razao_social, cnpj, cidade, estado
                          FROM empresa WHERE id_tenant = ? ORDER BY codigo_empresa
                        """)
                .param(idTenant)
                .query((rs, n) -> new EmpresaResumo(rs.getInt("codigo_empresa"), rs.getString("razao_social"),
                        rs.getString("cnpj"), rs.getString("cidade"), rs.getString("estado")))
                .list();

        return new TenantDetalhe(resumo, historico, faturas, empresas);
    }

    private static TenantResumo mapear(ResultSet rs, int n) throws SQLException {
        Integer limite = (Integer) rs.getObject("limite_vendas_mes");
        int vendas = rs.getInt("vendas_no_mes");
        Integer percentual = limite == null || limite == 0 ? null : (vendas * 100) / limite;
        return new TenantResumo(
                rs.getLong("id_tenant"), rs.getString("nome_conta"), rs.getString("slug"),
                rs.getString("email_contato"), rs.getString("status"), rs.getString("plano"),
                rs.getBoolean("gratuito"), limite, vendas, percentual,
                rs.getInt("qtd_empresas"), rs.getInt("qtd_usuarios"), rs.getBigDecimal("mrr"),
                rs.getObject("criado_em", OffsetDateTime.class));
    }
}
