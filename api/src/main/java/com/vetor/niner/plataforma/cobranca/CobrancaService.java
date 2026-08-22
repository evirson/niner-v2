package com.vetor.niner.plataforma.cobranca;

import com.vetor.niner.plataforma.cobranca.CobrancaDtos.Ciclo;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.FaturaResponse;
import com.vetor.niner.plataforma.cobranca.CobrancaFaturaTransacional.FaturaPreparada;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.IniciarPagamentoRequest;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.PagamentoPixResponse;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.SituacaoFaturaResponse;
import com.vetor.niner.plataforma.cobranca.GatewayCobranca.CobrancaPix;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private final CobrancaFaturaTransacional faturas;

    public CobrancaService(JdbcClient jdbc, GatewayCobranca gateway, CobrancaFaturaTransacional faturas) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.faturas = faturas;
    }

    /**
     * Escolhe a faixa + ciclo e devolve o PIX para pagar.
     *
     * <p>⚠️ <b>Sem {@code @Transactional} — de propósito</b> (auditoria 2026-08-21, item 23). Este
     * método <b>orquestra</b>: transação → rede → transação. A chamada ao gateway não pode
     * acontecer dentro de uma transação de banco (F2), senão um rollback posterior deixa um PIX
     * vivo no gateway apontando para uma fatura que não existe — o cliente paga e a assinatura
     * nunca é promovida. As duas metades transacionais estão em {@link CobrancaFaturaTransacional},
     * <b>em outro bean</b>, porque método anotado chamado de dentro do próprio bean não passa pelo
     * proxy do Spring e rodaria sem transação nenhuma.
     */
    public PagamentoPixResponse iniciarPagamento(Jwt jwt, IniciarPagamentoRequest req) {
        exigirAdmin(jwt);

        // 1. transação curta: valida e cria/atualiza a fatura da competência. COMMITA aqui.
        FaturaPreparada fatura = faturas.preparar(req.idPlano(), req.ciclo());

        // 2. rede, FORA de qualquer transação. A chave de idempotência muda quando muda o que está
        //    sendo cobrado — senão o gateway devolveria a cobrança antiga (com o valor antigo)
        //    para a faixa nova.
        String idempotencia = "niner-fatura-%d-%d-%s-%s".formatted(
                fatura.idFatura(), fatura.idPlano(), req.ciclo().name(), fatura.valor().toPlainString());
        CobrancaPix pix = gateway.criarPix(
                "fatura-" + fatura.idFatura(), fatura.valor(),
                "Nainer — assinatura %s (%s)".formatted(fatura.nomePlano(), req.ciclo().name().toLowerCase()),
                fatura.emailContato(), idempotencia);

        // 3. transação curta: grava o que o gateway devolveu.
        faturas.registrarCobranca(fatura.idFatura(), fatura.valor(), gateway.nome(), pix);

        return new PagamentoPixResponse(fatura.idFatura(), fatura.nomePlano(), req.ciclo(), fatura.valor(),
                fatura.competencia(), pix.copiaECola(), pix.qrCodeBase64(), pix.linkPagamento(),
                pix.expiraEm(), "ABERTA");
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

    // Os records Plano/Assinatura saíram daqui em 2026-08-22 junto com as consultas que os
    // preenchiam — hoje vivem em CobrancaFaturaTransacional, que é quem lê o banco.
}
