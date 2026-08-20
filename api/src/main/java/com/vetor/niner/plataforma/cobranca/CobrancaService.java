package com.vetor.niner.plataforma.cobranca;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.Ciclo;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.FaturaResponse;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.IniciarPagamentoRequest;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.PagamentoPixResponse;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.SituacaoFaturaResponse;
import com.vetor.niner.plataforma.cobranca.GatewayCobranca.CobrancaPix;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Assinatura paga: gera a fatura da competência e a cobrança PIX (ADR-015/016).
 *
 * <p><b>O plano só troca quando a fatura é paga</b> — este serviço nunca mexe em
 * {@code assinatura.id_plano}. Quem promove é o worker, depois de <b>consultar o gateway</b>
 * ({@link CobrancaWebhookJob}). Assim um webhook forjado, ou um cliente chamando o endpoint em
 * laço, não consegue mudar de faixa sem pagar.
 *
 * <p><b>Uma fatura por competência</b> ({@code fatura_assinatura_competencia_uk}): pedir o PIX de
 * novo no mesmo mês reaproveita a fatura aberta e regrava o código — não empilha cobrança. Trocar
 * a faixa escolhida antes de pagar também só atualiza a fatura aberta.
 */
@Service
public class CobrancaService {

    private final JdbcClient jdbc;
    private final GatewayCobranca gateway;

    public CobrancaService(JdbcClient jdbc, GatewayCobranca gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    @Transactional
    public PagamentoPixResponse iniciarPagamento(Jwt jwt, IniciarPagamentoRequest req) {
        exigirAdmin(jwt);

        var plano = jdbc.sql("""
                        SELECT id_plano, nome, preco_mensal, preco_anual
                          FROM plataforma.plano
                         WHERE id_plano = ? AND ativo AND NOT gratuito AND faixa_ordem IS NOT NULL
                        """)
                .param(req.idPlano())
                .query((rs, n) -> new Plano(rs.getLong("id_plano"), rs.getString("nome"),
                        rs.getBigDecimal("preco_mensal"), rs.getBigDecimal("preco_anual")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Faixa de plano não encontrada."));

        // Valor SEMPRE do servidor (nunca do request) — ADR-016.
        BigDecimal valor = req.ciclo() == Ciclo.ANUAL ? plano.precoAnual() : plano.precoMensal();

        var assinatura = jdbc.sql("""
                        SELECT a.id_assinatura, t.email_contato
                          FROM plataforma.assinatura a
                          JOIN plataforma.tenant t ON t.id_tenant = a.id_tenant
                         WHERE a.id_tenant = plataforma.tenant_atual() AND a.status <> 'CANCELADA'
                         ORDER BY a.id_assinatura DESC
                         LIMIT 1
                        """)
                .query((rs, n) -> new Assinatura(rs.getLong("id_assinatura"), rs.getString("email_contato")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(FORBIDDEN, "Conta sem assinatura ativa."));        LocalDate competencia = LocalDate.now().withDayOfMonth(1);
        String statusFatura = jdbc.sql("""
                        SELECT status::text FROM plataforma.fatura
                         WHERE id_assinatura = ? AND competencia = ?
                        """)
                .params(assinatura.idAssinatura(), competencia)
                .query(String.class).optional().orElse(null);
        if ("PAGA".equals(statusFatura)) {
            throw new ConflitoDadosException("A fatura desta competência já está paga.");
        }

        long idFatura = jdbc.sql("""
                        INSERT INTO plataforma.fatura
                            (id_assinatura, id_tenant, competencia, valor, vencimento, status, id_plano, ciclo)
                        VALUES (?, plataforma.tenant_atual(), ?, ?, ?, 'ABERTA', ?, ?::plataforma.ciclo_cobranca)
                        ON CONFLICT (id_assinatura, competencia) DO UPDATE
                           SET valor = EXCLUDED.valor, vencimento = EXCLUDED.vencimento,
                               id_plano = EXCLUDED.id_plano, ciclo = EXCLUDED.ciclo, status = 'ABERTA'
                        RETURNING id_fatura
                        """)
                .params(assinatura.idAssinatura(), competencia, valor, LocalDate.now().plusDays(3),
                        plano.idPlano(), req.ciclo().name())
                .query(Long.class).single();

        // Chave de idempotência muda quando muda o que está sendo cobrado — senão o gateway
        // devolveria a cobrança antiga (com o valor antigo) para a faixa nova.
        String idempotencia = "niner-fatura-%d-%d-%s-%s".formatted(
                idFatura, plano.idPlano(), req.ciclo().name(), valor.toPlainString());

        CobrancaPix pix = gateway.criarPix(
                "fatura-" + idFatura, valor,
                "Nainer — assinatura %s (%s)".formatted(plano.nome(), req.ciclo().name().toLowerCase()),
                assinatura.emailContato(), idempotencia);

        jdbc.sql("""
                        UPDATE plataforma.fatura
                           SET id_gateway_cobranca = ?, pix_copia_cola = ?, qr_code_base64 = ?,
                               link_pagamento = ?, expira_em = ?
                         WHERE id_fatura = ? AND id_tenant = plataforma.tenant_atual()
                        """)
                .params(pix.idTransacao(), pix.copiaECola(), pix.qrCodeBase64(), pix.linkPagamento(),
                        pix.expiraEm(), idFatura)
                .update();

        // Uma tentativa de pagamento por transação do gateway (o índice único de pagamento cobre
        // a repetição — P2).
        jdbc.sql("""
                        INSERT INTO plataforma.pagamento
                            (id_fatura, metodo, gateway, id_gateway_transacao, valor, status)
                        VALUES (?, 'PIX', ?, ?, ?, 'PENDENTE')
                        -- O índice de idempotência é PARCIAL (V007): a inferência do ON CONFLICT
                        -- precisa repetir o mesmo predicado, senão o Postgres não acha o índice.
                        ON CONFLICT (gateway, id_gateway_transacao)
                            WHERE gateway IS NOT NULL AND id_gateway_transacao IS NOT NULL
                        DO NOTHING
                        """)
                .params(idFatura, gateway.nome(), pix.idTransacao(), valor)
                .update();

        return new PagamentoPixResponse(idFatura, plano.nome(), req.ciclo(), valor, competencia,
                pix.copiaECola(), pix.qrCodeBase64(), pix.linkPagamento(), pix.expiraEm(), "ABERTA");
    }

    /** Polling da tela enquanto o cliente paga — lê o estado local, que o worker atualiza. */
    @Transactional(readOnly = true)
    public SituacaoFaturaResponse situacao(Jwt jwt, long idFatura) {
        exigirAdmin(jwt);
        return jdbc.sql("""
                        SELECT f.id_fatura, f.status::text AS situacao, f.pago_em, p.nome AS plano_atual
                          FROM plataforma.fatura f
                          JOIN plataforma.assinatura a ON a.id_assinatura = f.id_assinatura
                          JOIN plataforma.plano p      ON p.id_plano = a.id_plano
                         WHERE f.id_fatura = ? AND f.id_tenant = plataforma.tenant_atual()
                        """)
                .param(idFatura)
                .query((rs, n) -> new SituacaoFaturaResponse(rs.getLong("id_fatura"), rs.getString("situacao"),
                        rs.getObject("pago_em", OffsetDateTime.class), rs.getString("plano_atual")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Fatura não encontrada."));
    }

    @Transactional(readOnly = true)
    public List<FaturaResponse> listarFaturas(Jwt jwt) {
        exigirAdmin(jwt);
        return jdbc.sql("""
                        SELECT f.id_fatura, f.competencia, COALESCE(p.nome, '—') AS plano, f.ciclo::text AS ciclo,
                               f.valor, f.vencimento, f.status::text AS situacao, f.pago_em
                          FROM plataforma.fatura f
                          LEFT JOIN plataforma.plano p ON p.id_plano = f.id_plano
                         WHERE f.id_tenant = plataforma.tenant_atual()
                         ORDER BY f.competencia DESC
                        """)
                .query((rs, n) -> new FaturaResponse(
                        rs.getLong("id_fatura"), rs.getObject("competencia", LocalDate.class), rs.getString("plano"),
                        Ciclo.valueOf(rs.getString("ciclo")), rs.getBigDecimal("valor"),
                        rs.getObject("vencimento", LocalDate.class), rs.getString("situacao"),
                        rs.getObject("pago_em", OffsetDateTime.class)))
                .list();
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas ADMIN pode tratar da assinatura.");
        }
    }

    private record Plano(long idPlano, String nome, BigDecimal precoMensal, BigDecimal precoAnual) {
    }

    private record Assinatura(long idAssinatura, String emailContato) {
    }
}
