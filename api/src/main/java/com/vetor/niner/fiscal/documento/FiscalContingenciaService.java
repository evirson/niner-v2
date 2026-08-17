package com.vetor.niner.fiscal.documento;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contingência offline da NFC-e (docs/MODULOFISCAL.md §9.7).
 *
 * <p><b>Para que serve:</b> quando a SEFAZ não responde, o PDV <b>não</b> pode parar. A nota sai
 * com {@code tpEmis = 9}, o cupom leva "EMITIDA EM CONTINGÊNCIA", e a transmissão acontece depois
 * — no PR, dentro de 24 h (o prazo varia por UF e por isso vive em {@code cfg_uf_autorizador},
 * nunca numa constante — F10).
 *
 * <h2>Entrada automática (DF19): 2 falhas consecutivas em 60 s</h2>
 *
 * <p>Uma falha isolada não vira contingência — a SEFAZ oscila, e entrar em contingência tem custo
 * (fila para drenar, prazo correndo, cupom que o consumidor não consegue consultar na hora). Duas
 * falhas seguidas dentro da janela já são padrão, não azar.
 *
 * <p>O contador de falhas é <b>em memória</b>, de propósito: é heurística de disparo, não dado
 * fiscal. Reiniciar a API zera o contador, e a única consequência é precisar de duas novas falhas
 * para reentrar — barato. Já o <b>estado</b> de contingência é persistido em
 * {@code fiscal_config_empresa}, porque dele dependem o prazo, a fila e o que foi entregue ao
 * consumidor.
 *
 * <p>Série própria (DF33): a contingência numera na série 9, separada da série 1 normal. Sem isso,
 * a fila de contingência e as notas normais disputam a mesma sequência e a conferência do contador
 * vira adivinhação.
 */
@Service
public class FiscalContingenciaService {

    /** DF19 — quantas falhas consecutivas disparam a contingência automática. */
    static final int FALHAS_PARA_ENTRAR = 2;
    /** DF19 — janela em que as falhas precisam acontecer para contarem como consecutivas. */
    static final Duration JANELA_DE_FALHAS = Duration.ofSeconds(60);

    private final JdbcClient jdbc;

    /** Falhas recentes por empresa. Chave = {@code id_tenant:id_empresa} (P8 vale até em cache). */
    private final Map<String, Deque<Instant>> falhasRecentes = new ConcurrentHashMap<>();

    public FiscalContingenciaService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Estado consultar(long idEmpresa) {
        return jdbc.sql("""
                        SELECT c.contingencia_ativa, c.contingencia_desde, c.contingencia_justificativa,
                               c.serie_contingencia,
                               (SELECT count(*) FROM documento_fiscal d
                                 WHERE d.id_tenant = plataforma.tenant_atual()
                                   AND d.id_empresa = c.id_empresa
                                   AND d.tipo_emissao = 9
                                   AND d.situacao IN ('ASSINADO', 'CONTINGENCIA', 'TRANSMITINDO')) AS pendentes
                          FROM fiscal_config_empresa c
                         WHERE c.id_tenant = plataforma.tenant_atual() AND c.id_empresa = ?
                        """)
                .param(idEmpresa)
                .query((rs, n) -> new Estado(
                        rs.getBoolean("contingencia_ativa"),
                        Optional.ofNullable(rs.getTimestamp("contingencia_desde"))
                                .map(java.sql.Timestamp::toInstant).orElse(null),
                        rs.getString("contingencia_justificativa"),
                        rs.getInt("serie_contingencia"),
                        rs.getInt("pendentes")))
                .optional()
                .orElse(Estado.DESLIGADA);
    }

    /**
     * ⚠️ Anotado mesmo só delegando para {@link #consultar}: a chamada interna <b>não</b> passa
     * pelo proxy do Spring, então sem transação própria aqui o {@code app.id_tenant} não existe,
     * a consulta não acha a linha e este método devolve {@code false} <b>sempre</b> — a
     * contingência ficaria invisível para o job de drenagem, sem erro nenhum.
     */
    @Transactional(readOnly = true)
    public boolean ativa(long idEmpresa) {
        return consultar(idEmpresa).ativa();
    }

    /**
     * Registra uma falha de comunicação e diz se ela <b>disparou</b> a entrada em contingência.
     *
     * <p>⚠️ {@code @Transactional} aqui não é enfeite: este método chama {@link #ativa} e
     * {@link #entrar}, que são anotados mas <b>não passam pelo proxy</b> quando invocados de
     * dentro da própria classe. Sem uma transação aberta por fora, o
     * {@code TenantAwareTransactionManager} nunca define {@code app.id_tenant},
     * {@code plataforma.tenant_atual()} devolve {@code null} e o {@code UPDATE} não encontra
     * linha nenhuma — a contingência simplesmente não ligaria, em silêncio. Sem I/O de rede aqui
     * dentro, então a transação é curta (F2).
     *
     * @return {@code true} apenas na transição desligada → ligada, para que o chamador avise o
     *         operador uma vez só em vez de a cada venda
     */
    @Transactional
    public boolean registrarFalhaEavaliarEntrada(long idTenant, long idEmpresa, String motivo) {
        Instant agora = Instant.now();
        Deque<Instant> janela = falhasRecentes.computeIfAbsent(
                idTenant + ":" + idEmpresa, ignorado -> new ArrayDeque<>());

        boolean atingiuLimiar;
        synchronized (janela) {
            janela.addLast(agora);
            // Descarta o que saiu da janela: 2 falhas separadas por uma hora não são um padrão.
            while (!janela.isEmpty()
                    && Duration.between(janela.peekFirst(), agora).compareTo(JANELA_DE_FALHAS) > 0) {
                janela.removeFirst();
            }
            atingiuLimiar = janela.size() >= FALHAS_PARA_ENTRAR;
        }

        if (!atingiuLimiar || ativa(idEmpresa)) {
            return false;
        }
        entrar(idEmpresa, "Automática após %d falhas de comunicação em %d s. Última: %s"
                .formatted(FALHAS_PARA_ENTRAR, JANELA_DE_FALHAS.toSeconds(), motivo));
        return true;
    }

    /** A SEFAZ respondeu — o padrão de falha se quebrou, então o contador recomeça do zero. */
    public void registrarSucesso(long idTenant, long idEmpresa) {
        Deque<Instant> janela = falhasRecentes.get(idTenant + ":" + idEmpresa);
        if (janela != null) {
            synchronized (janela) {
                janela.clear();
            }
        }
    }

    /** P3 — entrar em contingência sempre grava justificativa e horário; é decisão auditável. */
    @Transactional
    public void entrar(long idEmpresa, String justificativa) {
        jdbc.sql("""
                        UPDATE fiscal_config_empresa
                           SET contingencia_ativa = true, contingencia_desde = now(),
                               contingencia_justificativa = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                           AND contingencia_ativa = false
                        """)
                .params(justificativa, idEmpresa).update();
    }

    /**
     * Sai da contingência. <b>Não</b> apaga {@code contingencia_desde} num movimento só com o
     * flag: o histórico de quando a empresa esteve em contingência é o que sustenta o prazo de
     * transmissão posterior, e é a primeira coisa que o fisco pergunta.
     */
    @Transactional
    public void sair(long idEmpresa, String justificativa) {
        jdbc.sql("""
                        UPDATE fiscal_config_empresa
                           SET contingencia_ativa = false,
                               contingencia_justificativa = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                        """)
                .params(justificativa, idEmpresa).update();
    }

    /**
     * Estado da contingência de uma empresa.
     *
     * @param pendentes notas emitidas em contingência que ainda não foram autorizadas — é este
     *                  número, não o flag, que diz se ainda há prazo correndo
     */
    public record Estado(boolean ativa, Instant desde, String justificativa,
                         int serieContingencia, int pendentes) {

        static final Estado DESLIGADA = new Estado(false, null, null, 9, 0);

        /** Há quanto tempo a empresa está em contingência — o painel do §9.7 mostra isso. */
        public Duration duracao() {
            return desde == null ? Duration.ZERO : Duration.between(desde, Instant.now());
        }
    }
}
