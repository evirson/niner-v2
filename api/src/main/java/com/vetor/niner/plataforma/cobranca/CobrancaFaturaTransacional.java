package com.vetor.niner.plataforma.cobranca;

import com.vetor.niner.comum.tempo.FusoDaPlataforma;
import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.plataforma.cobranca.CobrancaDtos.Ciclo;
import com.vetor.niner.plataforma.cobranca.GatewayCobranca.CobrancaPix;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * As <b>duas metades transacionais</b> de {@code CobrancaService.iniciarPagamento} — separadas em
 * outro bean de propósito (auditoria 2026-08-21, item 23).
 *
 * <h2>Por que existe: F2 — nenhuma chamada de rede dentro de transação de banco</h2>
 *
 * <p>{@code iniciarPagamento} chamava {@code gateway.criarPix(...)} <b>dentro</b> da sua
 * {@code @Transactional}, violando a regra que o módulo fiscal inteiro respeita. Se a transação
 * desse rollback <b>depois</b> de o PIX ser criado, ele existiria no gateway apontando para uma
 * fatura inexistente: o cliente pagaria, o webhook não acharia nada, os dois {@code UPDATE} de
 * {@code aplicar()} casariam zero linhas e sobraria um {@code log.warn}. <b>Dinheiro recebido,
 * assinatura não promovida.</b> Probabilidade baixa, custo alto.
 *
 * <p>Com a divisão, a fatura já está <b>commitada</b> quando o PIX é criado — a referência
 * {@code fatura-<id>} que vai para o gateway aponta para uma linha que existe. Se a segunda metade
 * falhar, o pior caso é uma fatura ABERTA sem código de PIX gravado: o cliente refaz o pedido (que
 * reaproveita a mesma fatura da competência) e, se ainda assim ele tiver pago pelo código que
 * recebeu na tela, o webhook <b>acha a fatura</b> pela referência. Estritamente melhor que antes.
 *
 * <h2>Por que em outro bean, e não dois métodos aqui do lado</h2>
 *
 * <p>Método {@code @Transactional} chamado de dentro do próprio bein ({@code this.preparar()}) não
 * passa pelo proxy do Spring e roda <b>sem transação nenhuma</b> — o mesmo defeito documentado em
 * {@code CobrancaWebhookJob} × {@code CobrancaWebhookProcessador}, e que aqui seria pior: o
 * {@code INSERT} da fatura rodaria em autocommit e a divisão não teria servido para nada. Bean
 * separado é a única forma de a anotação valer.
 */
@Service
public class CobrancaFaturaTransacional {

    private final JdbcClient jdbc;

    public CobrancaFaturaTransacional(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Primeira metade: valida, cria/atualiza a fatura da competência e <b>commita</b>. Nenhuma
     * chamada de rede aqui dentro.
     */
    @Transactional
    public FaturaPreparada preparar(long idPlanoPedido, Ciclo ciclo) {
        var plano = jdbc.sql("""
                        SELECT id_plano, nome, preco_mensal, preco_anual
                          FROM plataforma.plano
                         WHERE id_plano = ? AND ativo AND NOT gratuito AND faixa_ordem IS NOT NULL
                        """)
                .param(idPlanoPedido)
                .query((rs, n) -> new Plano(rs.getLong("id_plano"), rs.getString("nome"),
                        rs.getBigDecimal("preco_mensal"), rs.getBigDecimal("preco_anual")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Faixa de plano não encontrada."));

        // Valor SEMPRE do servidor (nunca do request) — ADR-016.
        BigDecimal valor = ciclo == Ciclo.ANUAL ? plano.precoAnual() : plano.precoMensal();

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
                .orElseThrow(() -> new ResponseStatusException(FORBIDDEN, "Conta sem assinatura ativa."));

        LocalDate competencia = LocalDate.now(FusoDaPlataforma.ZONA).withDayOfMonth(1);
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
                .params(assinatura.idAssinatura(), competencia, valor,
                        LocalDate.now(FusoDaPlataforma.ZONA).plusDays(3), plano.idPlano(), ciclo.name())
                .query(Long.class).single();

        return new FaturaPreparada(idFatura, plano.idPlano(), plano.nome(), valor, competencia,
                assinatura.emailContato());
    }

    /**
     * Segunda metade: grava na fatura o que o gateway devolveu e registra a tentativa de pagamento.
     * Chamada <b>depois</b> de a rede ter respondido.
     */
    @Transactional
    public void registrarCobranca(long idFatura, BigDecimal valor, String nomeGateway, CobrancaPix pix) {
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
                .params(idFatura, nomeGateway, pix.idTransacao(), valor)
                .update();
    }

    /** O que a primeira metade apurou, para a chamada de rede e a segunda metade usarem. */
    public record FaturaPreparada(long idFatura, long idPlano, String nomePlano, BigDecimal valor,
                                  LocalDate competencia, String emailContato) {
    }

    private record Plano(long idPlano, String nome, BigDecimal precoMensal, BigDecimal precoAnual) {
    }

    private record Assinatura(long idAssinatura, String emailContato) {
    }
}
