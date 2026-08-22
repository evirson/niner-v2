package com.vetor.niner.plataforma.cobranca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vetor.niner.plataforma.cobranca.GatewayCobranca.SituacaoPagamento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Aplica os efeitos de cobrança das notificações recebidas (ADR-016, P2). O agendamento fica em
 * {@link CobrancaWebhookJob}; a divisão não é estética: os métodos abaixo são {@code
 * @Transactional} e o proxy do Spring <b>não</b> intercepta auto-invocação — chamados de dentro
 * do próprio bean, rodariam sem transação, e o {@code FOR UPDATE SKIP LOCKED} soltaria o lock
 * imediatamente, deixando dois workers pegarem o mesmo evento.
 *
 * <p><b>Nunca acredita no corpo do webhook.</b> Ele diz apenas <i>qual</i> pagamento mudou; o
 * estado verdadeiro é buscado no gateway. É isso que torna o endpoint público inofensivo.
 *
 * <p>Consumo com {@code FOR UPDATE SKIP LOCKED} (P6 — Postgres como fila, sem broker) e
 * retentativa com espera crescente; passando do teto, a linha vira dead-letter (fica com
 * {@code erro} preenchido e sai da fila) em vez de girar para sempre.
 *
 * <p><b>Não precisa de TenantContext</b> (P8): tudo aqui é {@code plataforma.*}, tabela global
 * sem RLS. Se um dia este worker tocar em tabela de domínio, terá que estabelecer o contexto.
 */
@Component
public class CobrancaWebhookProcessador {

    private static final Logger log = LoggerFactory.getLogger(CobrancaWebhookJob.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TENTATIVAS = 8;
    private static final int LOTE = 20;

    private final JdbcClient jdbc;
    private final GatewayCobranca gateway;

    public CobrancaWebhookProcessador(JdbcClient jdbc, GatewayCobranca gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    @Transactional
    public List<Long> pegarLote() {
        return jdbc.sql("""
                        SELECT id FROM plataforma.webhook_gateway
                         WHERE gateway = ? AND processado_em IS NULL AND proxima_tentativa <= now()
                           AND tentativas < ?
                         ORDER BY recebido_em
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED
                        """)
                .params(gateway.nome(), MAX_TENTATIVAS, LOTE)
                .query(Long.class)
                .list();
    }

    /** Uma transação por evento: um pagamento problemático não segura os outros. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processar(long idEvento) {
        String payload = jdbc.sql("SELECT payload::text FROM plataforma.webhook_gateway WHERE id = ?")
                .param(idEvento).query(String.class).single();

        String idPagamento = null;
        try {
            JsonNode no = JSON.readTree(payload);
            JsonNode dataId = no.path("data").path("id");
            if (!dataId.isMissingNode() && !dataId.isNull()) {
                idPagamento = dataId.asText();
            }
        } catch (Exception e) {
            marcarProcessado(idEvento, "payload ilegível");
            return;
        }
        if (idPagamento == null || idPagamento.isBlank()) {
            marcarProcessado(idEvento, "notificação sem data.id");
            return;
        }

        // A fonte de verdade é o gateway, não a notificação.
        SituacaoPagamento situacao = gateway.consultarPagamento(idPagamento);
        aplicar(situacao);
        marcarProcessado(idEvento, null);
    }

    /**
     * ⚠️ <b>DÍVIDA CONHECIDA E ACEITA — estorno/chargeback não revoga a assinatura</b>
     * (auditoria 2026-08-21 item 27; decisão do dono do produto em 2026-08-22:
     * <i>"documente e avise sempre que formos revisar o ERP à procura de falhas"</i>).
     *
     * <p>{@code refunded}/{@code charged_back} viram {@link GatewayCobranca.Situacao#ESTORNADO}, e
     * o {@code return} abaixo — escrito para FALHOU/PENDENTE, casos em que a fatura <b>nunca</b>
     * foi paga — engole também esse. Só que no estorno a fatura <b>está</b> paga: o que fica é
     *
     * <ul>
     *   <li>{@code pagamento.status = ESTORNADO} ← a única coisa que muda;</li>
     *   <li>{@code fatura.status} continua <b>PAGA</b>;</li>
     *   <li>{@code assinatura.status} continua <b>ATIVA</b>;</li>
     *   <li>{@code assinatura.proxima_cobranca} continua <b>empurrada um ciclo à frente</b>.</li>
     * </ul>
     *
     * <p>Custo concreto: lojista paga o plano <b>anual</b> por PIX e obtém o estorno semanas
     * depois — nenhuma fatura nova é gerada por <b>doze meses</b>, e o backoffice mostra tudo em
     * dia. O único rastro é o status do pagamento, que nenhuma tela exibe.
     *
     * <p>Por que não foi corrigido junto: <b>a política é comercial, não técnica</b> (reabrir a
     * fatura é claro; suspender, dar prazo ou marcar inadimplente é decisão da Vetor). E o efeito
     * prático hoje é menor do que parece — <b>nenhum status corta acesso</b>: o login
     * ({@code SignupService#login}) confere slug, e-mail, senha, {@code usuario.ativo} e horário
     * de acesso, e não olha {@code tenant.status} nem {@code assinatura.status}; não há um único
     * uso de {@code SUSPENSA}/{@code INADIMPLENTE} em {@code api/src/main}. Os status são rótulo
     * no backoffice, não fechadura.
     *
     * <p>⚠️ Quando a correção for feita, ela precisa de <b>três</b> passos, não um: reabrir a
     * fatura, <b>recuar</b> {@code proxima_cobranca} para o valor anterior, e decidir o estado da
     * assinatura. Recuar é o que costuma ser esquecido, e é o que mais custa.
     */
    private void aplicar(SituacaoPagamento s) {
        int atualizados = jdbc.sql("""
                        UPDATE plataforma.pagamento
                           SET status = ?::plataforma.status_pagamento,
                               pago_em = CASE WHEN ? = 'CONFIRMADO' THEN now() ELSE pago_em END
                         WHERE gateway = ? AND id_gateway_transacao = ?
                        """)
                .params(s.situacao().name(), s.situacao().name(), gateway.nome(), s.idTransacao())
                .update();
        if (atualizados == 0) {
            log.warn("Pagamento {} do gateway não corresponde a nenhuma tentativa registrada", s.idTransacao());
        }
        if (s.situacao() != GatewayCobranca.Situacao.CONFIRMADO) {
            // FALHOU/PENDENTE: fatura segue ABERTA, o cliente tenta de novo — correto.
            // ESTORNADO: ver o javadoc deste método. Dívida conhecida, aceita e a ser lembrada em
            // toda revisão do ERP — NÃO é um `return` que "está certo" para os três casos.
            return;
        }

        // Confirmado: a fatura fecha e a assinatura passa a valer a faixa que ela pagou.
        // A referência externa (fatura-<id>) é o que amarra pagamento → fatura sem depender de
        // estado em memória entre a criação da cobrança e a notificação, que vem minutos depois.
        Long idFatura = jdbc.sql("""
                        SELECT f.id_fatura
                          FROM plataforma.fatura f
                          JOIN plataforma.pagamento p ON p.id_fatura = f.id_fatura
                         WHERE p.gateway = ? AND p.id_gateway_transacao = ?
                        """)
                .params(gateway.nome(), s.idTransacao())
                .query(Long.class).optional().orElse(null);
        if (idFatura == null && s.referencia() != null && s.referencia().startsWith("fatura-")) {
            idFatura = Long.parseLong(s.referencia().substring("fatura-".length()));
        }
        if (idFatura == null) {
            log.warn("Pagamento {} confirmado sem fatura correspondente", s.idTransacao());
            return;
        }

        // ⚠️ Esta linha é a TRAVA DE IDEMPOTÊNCIA de tudo o que vem abaixo (auditoria 2026-08-21,
        // item 7). O `AND status <> 'PAGA'` faz o UPDATE casar exatamente UMA vez por fatura, então
        // `fechouAgora` distingue a primeira aplicação de uma reaplicação.
        //
        // Por que fazia falta: o javadoc de `pegarLote` promete que a separação em dois beans
        // impede que "dois workers peguem o mesmo evento" — mas a transação de `pegarLote` commita
        // ao retornar, soltando os locks do FOR UPDATE SKIP LOCKED; `processar` roda depois em
        // REQUIRES_NEW, sem lock nenhum. Todo o resto de `aplicar` é idempotente por construção (o
        // UPDATE do pagamento é, e a fatura tem este guard) — EXCETO o UPDATE da assinatura, que
        // empurra `proxima_cobranca` mais um ciclo A CADA reaplicação: uma notificação duplicada
        // dava um mês (ou um ano) de graça, silenciosamente.
        //
        // Reachability honesta: com UMA instância de API e @Scheduled isso não acontece hoje. Vira
        // real no dia em que existirem duas instâncias — cenário que o CLAUDE.md prevê.
        //
        // A trava é a fatura, não uma comparação de datas em `proxima_cobranca`: o valor novo
        // depende do ciclo, e comparar data acerta por acaso. Aqui a condição é exata, e vale
        // porque este é o ÚNICO ponto do sistema que marca fatura como PAGA (conferido).
        boolean fechouAgora = jdbc.sql("""
                        UPDATE plataforma.fatura SET status = 'PAGA', pago_em = now()
                         WHERE id_fatura = ? AND status <> 'PAGA'
                        """)
                .param(idFatura).update() > 0;
        if (!fechouAgora) {
            log.info("Fatura {} já estava paga — notificação reaplicada, assinatura intacta.", idFatura);
            return;
        }

        jdbc.sql("""
                        UPDATE plataforma.assinatura a
                           SET id_plano = f.id_plano,
                               ciclo = f.ciclo,
                               status = 'ATIVA',
                               -- ⚠️ Dia da VETOR, não o do banco: CURRENT_DATE em UTC vira o dia
                               -- seguinte às 21:00 de Brasília, e pagamento aprovado à noite datava
                               -- a vigência um dia à frente — de novo a cada renovação.
                               inicio_vigencia = COALESCE(a.inicio_vigencia,
                                   (now() AT TIME ZONE 'America/Sao_Paulo')::date),
                               proxima_cobranca = CASE f.ciclo
                                   WHEN 'ANUAL' THEN (now() AT TIME ZONE 'America/Sao_Paulo')::date + INTERVAL '1 year'
                                   ELSE (now() AT TIME ZONE 'America/Sao_Paulo')::date + INTERVAL '1 month' END,
                               atualizado_em = now()
                          FROM plataforma.fatura f
                         WHERE f.id_fatura = ? AND a.id_assinatura = f.id_assinatura AND f.id_plano IS NOT NULL
                        """)
                .param(idFatura).update();

        log.info("Fatura {} paga; assinatura promovida para a faixa da fatura", idFatura);
    }

    private void marcarProcessado(long idEvento, String erro) {
        jdbc.sql("UPDATE plataforma.webhook_gateway SET processado_em = now(), erro = ? WHERE id = ?")
                .params(erro, idEvento).update();
    }

    /** Espera crescente: 1, 2, 4… minutos. No teto, vira dead-letter visível pelo `erro`. */
    @Transactional
    public void adiar(long idEvento, String erro) {
        jdbc.sql("""
                        UPDATE plataforma.webhook_gateway
                           SET tentativas = tentativas + 1,
                               erro = ?,
                               proxima_tentativa = now() + make_interval(mins => power(2, LEAST(tentativas, 6))::int)
                         WHERE id = ?
                        """)
                .params(erro, idEvento).update();
        log.warn("Evento de cobrança {} adiado: {}", idEvento, erro);
    }
}
