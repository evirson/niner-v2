package com.vetor.niner.plataforma.aquisicao;

import com.vetor.niner.comum.tempo.FusoDaPlataforma;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.AtualizarLeadRequest;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.ContaPertoDoLimite;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.Funil;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.LeadDetalhe;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.LeadResumo;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.LinhaOrigem;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.MomentoLead;
import com.vetor.niner.plataforma.aquisicao.MarketingAdminDtos.PaginaLeads;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Gerenciador de marketing da Vetor (backoffice) — ADR-017, docs/telas/admin-marketing.md.
 *
 * <p>A pergunta que este serviço existe para responder não é "quantas visitas tivemos?", e sim
 * <b>"quanto de receita cada origem produziu?"</b>. Ela só é respondível aqui porque visita, lead
 * e assinatura vivem no mesmo banco (ADR-017): nenhuma ferramenta externa conhece
 * {@code plataforma.assinatura}.
 *
 * <p><b>Atribuição de primeiro toque:</b> a origem usada é a gravada no lead (primeira visita).
 * Funil longo — dias entre o anúncio e o cadastro — torna o último toque quase sempre "direto".
 */
@Service
public class MarketingAdminService {

    /** MRR = mensalidade da faixa; no plano anual, a parcela mensal equivalente. */
    private static final String MRR = "CASE a.ciclo WHEN 'ANUAL' THEN p.preco_anual / 12 ELSE p.preco_mensal END";

    private static final Set<String> STATUS_VALIDOS =
            Set.of("NOVO", "CONTATADO", "QUALIFICADO", "CONVERTIDO", "PERDIDO");

    private final JdbcClient jdbc;

    public MarketingAdminService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Funil funil(LocalDate de, LocalDate ate) {
        LocalDate inicio = de != null ? de : LocalDate.now(FusoDaPlataforma.ZONA).minusDays(30);
        LocalDate fim = ate != null ? ate : LocalDate.now(FusoDaPlataforma.ZONA);

        // Cada degrau conta VISITANTE distinto, não evento: recarregar a página não pode inflar o
        // topo do funil e fazer a conversão parecer pior do que é.
        var totais = jdbc.sql("""
                        SELECT (SELECT count(*) FROM plataforma.visita_site
                                 WHERE (criado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?)                       AS visitas,
                               (SELECT count(DISTINCT visitante_id) FROM plataforma.visita_site
                                 WHERE (criado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?)                       AS visitantes,
                               (SELECT count(*) FROM plataforma.lead
                                 WHERE (criado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?)                       AS leads,
                               (SELECT count(*) FROM plataforma.tenant
                                 WHERE (criado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?)                       AS contas,
                               (SELECT count(*) FROM plataforma.uso_venda_mes u
                                 WHERE u.qtd_vendas > 0)                                      AS com_venda_hist,
                               (SELECT count(*) FROM plataforma.uso_tenant
                                 WHERE qtd_vendas_mes > 0)                                    AS com_venda_mes
                        """)
                .params(inicio, fim, inicio, fim, inicio, fim, inicio, fim)
                .query((rs, n) -> new long[] {rs.getLong("visitas"), rs.getLong("visitantes"), rs.getLong("leads"),
                        rs.getLong("contas"), Math.max(rs.getLong("com_venda_hist"), rs.getLong("com_venda_mes"))})
                .single();

        var pagos = jdbc.sql("""
                        SELECT count(*) AS pagantes, COALESCE(SUM(%s), 0) AS mrr
                          FROM plataforma.assinatura a
                          JOIN plataforma.plano p ON p.id_plano = a.id_plano
                         WHERE a.status = 'ATIVA' AND NOT p.gratuito
                        """.formatted(MRR))
                .query((rs, n) -> new Object[] {rs.getLong("pagantes"), rs.getBigDecimal("mrr")})
                .single();

        List<LinhaOrigem> porOrigem = jdbc.sql("""
                        SELECT COALESCE(o.utm_source, 'direto')  AS origem,
                               COALESCE(o.utm_campaign, '—')     AS campanha,
                               count(DISTINCT o.visitante_id)    AS visitantes,
                               count(DISTINCT l.id_lead)         AS leads,
                               count(DISTINCT l.id_tenant)       AS contas,
                               count(DISTINCT CASE WHEN a.status = 'ATIVA' AND NOT p.gratuito
                                                   THEN a.id_assinatura END) AS pagantes,
                               COALESCE(SUM(CASE WHEN a.status = 'ATIVA' AND NOT p.gratuito
                                                 THEN %s END), 0)            AS mrr
                          FROM (SELECT DISTINCT ON (visitante_id) visitante_id, utm_source, utm_campaign
                                  FROM plataforma.visita_site
                                 WHERE (criado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
                                 ORDER BY visitante_id, criado_em) o
                          LEFT JOIN plataforma.lead l       ON l.visitante_id = o.visitante_id
                          LEFT JOIN plataforma.assinatura a ON a.id_tenant = l.id_tenant AND a.status <> 'CANCELADA'
                          LEFT JOIN plataforma.plano p      ON p.id_plano = a.id_plano
                         GROUP BY 1, 2
                         ORDER BY 7 DESC, 3 DESC
                        """.formatted(MRR))
                .params(inicio, fim)
                .query((rs, n) -> new LinhaOrigem(rs.getString("origem"), rs.getString("campanha"),
                        rs.getLong("visitantes"), rs.getLong("leads"), rs.getLong("contas"),
                        rs.getLong("pagantes"), rs.getBigDecimal("mrr")))
                .list();

        return new Funil(inicio, fim, totais[0], totais[1], totais[2], totais[3], totais[4],
                (Long) pagos[0], (BigDecimal) pagos[1], porOrigem);
    }

    @Transactional(readOnly = true)
    public PaginaLeads listarLeads(String status, String origem, int pagina, int limite) {
        int pag = Math.max(pagina, 1);
        int lim = limite <= 0 || limite > 200 ? 50 : limite;

        // `status`/`origem` entram como PARÂMETRO, nunca concatenados — filtro de tela é entrada
        // do usuário (mesma regra da ordenação por coluna nas telas do ERP).
        String filtro = """
                 WHERE (CAST(? AS text) IS NULL OR l.status::text = ?)
                   AND (CAST(? AS text) IS NULL OR COALESCE(l.utm_source, 'direto') = ?)
                """;
        long total = jdbc.sql("SELECT count(*) FROM plataforma.lead l " + filtro)
                .params(status, status, origem, origem).query(Long.class).single();

        List<LeadResumo> itens = jdbc.sql("""
                        SELECT l.id_lead, l.nome, l.email, l.telefone_whatsapp, l.nome_loja,
                               COALESCE(l.utm_source, 'direto') AS origem, COALESCE(l.utm_campaign, '—') AS campanha,
                               l.status::text AS status, l.id_tenant, t.nome_conta, l.criado_em
                          FROM plataforma.lead l
                          LEFT JOIN plataforma.tenant t ON t.id_tenant = l.id_tenant
                        """ + filtro + " ORDER BY l.criado_em DESC LIMIT ? OFFSET ?")
                .params(status, status, origem, origem, lim, (long) (pag - 1) * lim)
                .query(MarketingAdminService::mapearLead)
                .list();

        return new PaginaLeads(itens, total, pag, lim);
    }

    @Transactional(readOnly = true)
    public LeadDetalhe detalharLead(long idLead) {
        LeadResumo lead = jdbc.sql("""
                        SELECT l.id_lead, l.nome, l.email, l.telefone_whatsapp, l.nome_loja,
                               COALESCE(l.utm_source, 'direto') AS origem, COALESCE(l.utm_campaign, '—') AS campanha,
                               l.status::text AS status, l.id_tenant, t.nome_conta, l.criado_em
                          FROM plataforma.lead l
                          LEFT JOIN plataforma.tenant t ON t.id_tenant = l.id_tenant
                         WHERE l.id_lead = ?
                        """)
                .param(idLead)
                .query(MarketingAdminService::mapearLead)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lead não encontrado."));

        List<MomentoLead> linha = jdbc.sql("""
                        SELECT criado_em, 'VISITA' AS tipo, caminho AS detalhe
                          FROM plataforma.visita_site
                         WHERE visitante_id = (SELECT visitante_id FROM plataforma.lead WHERE id_lead = ?)
                        UNION ALL
                        SELECT criado_em, tipo, COALESCE(rotulo, caminho)
                          FROM plataforma.evento_marketing
                         WHERE visitante_id = (SELECT visitante_id FROM plataforma.lead WHERE id_lead = ?)
                         ORDER BY 1
                        """)
                .params(idLead, idLead)
                .query((rs, n) -> new MomentoLead(rs.getObject("criado_em", OffsetDateTime.class),
                        rs.getString("tipo"), rs.getString("detalhe")))
                .list();

        return new LeadDetalhe(lead, linha);
    }

    @Transactional
    public void atualizarLead(long idLead, AtualizarLeadRequest req) {
        if (req.status() != null && !STATUS_VALIDOS.contains(req.status())) {
            throw new IllegalArgumentException("Status de lead inválido: " + req.status());
        }
        int linhas = jdbc.sql("""
                        UPDATE plataforma.lead
                           SET status = COALESCE(CAST(? AS plataforma.status_lead), status),
                               anotacao = COALESCE(?, anotacao),
                               atualizado_em = now()
                         WHERE id_lead = ?
                        """)
                .params(req.status(), req.anotacao(), idLead)
                .update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Lead não encontrado.");
        }
    }

    /**
     * Contas gratuitas com 80% ou mais da cota consumida no mês — a fila do contato comercial.
     * É o sinal de compra mais forte que o produto tem: quem está aqui vai precisar decidir em
     * dias, não em meses.
     */
    @Transactional(readOnly = true)
    public List<ContaPertoDoLimite> contasPertoDoLimite() {
        return jdbc.sql("""
                        SELECT t.id_tenant, t.nome_conta, t.email_contato,
                               u.qtd_vendas_mes, p.limite_vendas_mes,
                               (u.qtd_vendas_mes * 100 / p.limite_vendas_mes) AS percentual
                          FROM plataforma.uso_tenant u
                          JOIN plataforma.tenant t      ON t.id_tenant = u.id_tenant
                          JOIN plataforma.assinatura a  ON a.id_tenant = u.id_tenant AND a.status <> 'CANCELADA'
                          JOIN plataforma.plano p       ON p.id_plano = a.id_plano
                         WHERE p.gratuito AND p.limite_vendas_mes IS NOT NULL
                           AND u.competencia_vendas = date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo')::date
                           AND u.qtd_vendas_mes * 100 >= p.limite_vendas_mes * 80
                         ORDER BY percentual DESC
                        """)
                .query((rs, n) -> new ContaPertoDoLimite(rs.getLong("id_tenant"), rs.getString("nome_conta"),
                        rs.getString("email_contato"), rs.getInt("qtd_vendas_mes"),
                        rs.getInt("limite_vendas_mes"), rs.getInt("percentual")))
                .list();
    }

    private static LeadResumo mapearLead(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        // id_tenant é smallint no banco: o driver devolve Integer, e o cast direto para Long
        // estoura em runtime só quando existe lead convertido (o caso que mais importa aqui).
        Number tenant = (Number) rs.getObject("id_tenant");
        Long idTenant = tenant == null ? null : tenant.longValue();
        return new LeadResumo(rs.getLong("id_lead"), rs.getString("nome"), rs.getString("email"),
                rs.getString("telefone_whatsapp"), rs.getString("nome_loja"), rs.getString("origem"),
                rs.getString("campanha"), rs.getString("status"), idTenant, rs.getString("nome_conta"),
                rs.getObject("criado_em", OffsetDateTime.class));
    }
}
