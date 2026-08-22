package com.vetor.niner.plataforma.aquisicao;

import com.vetor.niner.plataforma.aquisicao.AquisicaoDtos.EventoBeacon;
import com.vetor.niner.plataforma.aquisicao.AquisicaoDtos.LeadRequest;
import com.vetor.niner.plataforma.aquisicao.AquisicaoDtos.LoteEventosRequest;
import com.vetor.niner.plataforma.aquisicao.AquisicaoDtos.Origem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Grava o funil de aquisição (ADR-017): pageview, evento de interesse e lead.
 *
 * <p><b>Tolerante por construção.</b> É a única escrita pública de volume do produto: lote grande
 * demais é cortado, evento malformado é descartado em silêncio e nada aqui dispara efeito de
 * negócio. Medição não pode virar vetor de erro — nem para o visitante, nem para o banco.
 *
 * <p><b>Sem PII no anônimo:</b> IP não é gravado (nem derivado, por ora), e o beacon nunca manda
 * conteúdo de formulário. E-mail e telefone só existem em {@code lead}, com
 * {@code consentimento_em} preenchido no ato.
 */
@Service
public class AquisicaoService {

    private static final Logger log = LoggerFactory.getLogger(AquisicaoService.class);

    /** Lote maior que isto é corte, não erro: beacon com defeito não derruba a API. */
    private static final int MAX_EVENTOS = 50;

    /** Teto de `evento_marketing.valor numeric(12,2)`: 10 digitos inteiros. Valor a partir daqui
     *  estoura a coluna e abortaria a transacao do lote inteiro. */
    private static final java.math.BigDecimal VALOR_MAXIMO_EVENTO = new java.math.BigDecimal("10000000000");
    private static final String TIPO_PAGEVIEW = "PAGEVIEW";

    private final JdbcClient jdbc;

    public AquisicaoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarLote(LoteEventosRequest req, boolean mobile) {
        UUID visitante = uuid(req.visitanteId());
        if (visitante == null || req.eventos() == null || req.eventos().isEmpty()) {
            return;
        }
        UUID sessao = uuid(req.sessaoId());
        Origem o = req.origem() == null
                ? new Origem(null, null, null, null, null, null, null)
                : req.origem();

        List<EventoBeacon> eventos = req.eventos().size() > MAX_EVENTOS
                ? req.eventos().subList(0, MAX_EVENTOS)
                : req.eventos();

        for (EventoBeacon e : eventos) {
            if (e == null || e.tipo() == null || e.tipo().isBlank()) {
                continue;
            }
            // ⚠️ Descartar ANTES do INSERT, não depois (achado de auditoria, 2026-08-21). Um valor
            // que estoura `numeric(12,2)` aborta a transação inteira no Postgres, e daí em diante
            // nada mais do lote grava — ver o `catch` lá embaixo. O endpoint é público, então o
            // conteúdo aqui é de terceiro: filtrar o que o schema não aceita é a defesa barata.
            if (e.valor() != null && e.valor().abs().compareTo(VALOR_MAXIMO_EVENTO) >= 0) {
                log.warn("Evento de marketing com valor fora da faixa descartado: {}", e.valor());
                continue;
            }
            try {
                if (TIPO_PAGEVIEW.equals(e.tipo())) {
                    jdbc.sql("""
                                    INSERT INTO plataforma.visita_site
                                        (visitante_id, sessao_id, caminho, referrer, utm_source, utm_medium,
                                         utm_campaign, utm_content, utm_term, dispositivo)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                    """)
                            .params(visitante, sessao, caminho(e), o.referrer(), o.utmSource(), o.utmMedium(),
                                    o.utmCampaign(), o.utmContent(), o.utmTerm(), mobile ? "MOBILE" : "DESKTOP")
                            .update();
                } else {
                    jdbc.sql("""
                                    INSERT INTO plataforma.evento_marketing
                                        (visitante_id, sessao_id, tipo, rotulo, caminho, valor)
                                    VALUES (?, ?, ?, ?, ?, ?)
                                    """)
                            .params(visitante, sessao, e.tipo(), e.rotulo(), caminho(e), e.valor())
                            .update();
                }
            } catch (RuntimeException ex) {
                // ⚠️ Este catch NÃO cumpria o que promete (achado de auditoria, 2026-08-21), e é a
                // armadilha que este repositório já documentou duas vezes: quando o erro vem do
                // BANCO, o Postgres aborta a transação inteira (25P02), e capturar em Java **não a
                // reabre**. Os INSERTs seguintes falhavam todos com "current transaction is
                // aborted", eram engolidos por este mesmo catch em DEBUG, e o COMMIT final era
                // tratado como ROLLBACK — devolvendo 204. Um único evento ruim (o endpoint é
                // público e `valor` é numeric(12,2): basta um número grande demais) descartava o
                // lote inteiro em silêncio.
                //
                // A validação antes do INSERT é o que evita chegar aqui pelo caminho conhecido; o
                // log sobe para WARN porque em DEBUG o sintoma ficava invisível em produção.
                log.warn("Evento de marketing descartado: {}", ex.getMessage());
            }
        }
    }

    /**
     * Cria ou atualiza o lead do formulário. Reenvio do mesmo e-mail <b>não</b> duplica nem
     * rebaixa o status: quem já foi contatado/qualificado continua onde está.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarLead(LeadRequest req) {
        UUID visitante = uuid(req.visitanteId());
        jdbc.sql("""
                        INSERT INTO plataforma.lead
                            (visitante_id, nome, email, telefone_whatsapp, nome_loja, consentimento_em)
                        VALUES (CAST(? AS uuid), ?, ?, ?, ?, now())
                        ON CONFLICT (email) DO UPDATE
                           SET nome = COALESCE(EXCLUDED.nome, plataforma.lead.nome),
                               telefone_whatsapp = COALESCE(EXCLUDED.telefone_whatsapp, plataforma.lead.telefone_whatsapp),
                               nome_loja = COALESCE(EXCLUDED.nome_loja, plataforma.lead.nome_loja),
                               visitante_id = COALESCE(plataforma.lead.visitante_id, EXCLUDED.visitante_id),
                               consentimento_em = COALESCE(plataforma.lead.consentimento_em, now()),
                               atualizado_em = now()
                        """)
                .params(visitante, req.nome(), req.email(), req.telefoneWhatsapp(), req.nomeLoja())
                .update();
        enriquecerComPrimeiraVisita(req.email(), visitante);
    }

    /**
     * Fecha o funil no signup: o lead daquele visitante (ou e-mail) vira {@code CONVERTIDO} e
     * ganha o {@code id_tenant}. Se o visitante nunca deixou lead, cria um já convertido — senão
     * o signup direto (sem formulário) sumiria do funil, que é o caso mais comum.
     *
     * <p><b>{@code REQUIRES_NEW} não é detalhe.</b> Medição roda no meio da criação da conta, e
     * <b>capturar a exceção em Java não desfaz o aborto da transação no Postgres</b>: uma vez que
     * um comando falha, o servidor rejeita o resto e trata o {@code COMMIT} como {@code ROLLBACK}
     * — devolvendo sucesso. O efeito seria o pior possível: o signup responde 201, com token
     * válido, e a conta inteira desaparece (bug real, encontrado aqui em 2026-08-18). Transação
     * separada isola a falha de medição da criação da conta.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void converter(String visitanteId, String email, String nome, String nomeLoja, long idTenant) {
        try {
            UUID visitante = uuid(visitanteId);
            jdbc.sql("""
                            INSERT INTO plataforma.lead
                                (visitante_id, nome, email, nome_loja, status, id_tenant, consentimento_em)
                            VALUES (CAST(? AS uuid), ?, ?, ?, 'CONVERTIDO', ?, now())
                            ON CONFLICT (email) DO UPDATE
                               SET status = 'CONVERTIDO',
                                   id_tenant = EXCLUDED.id_tenant,
                                   visitante_id = COALESCE(plataforma.lead.visitante_id, EXCLUDED.visitante_id),
                                   atualizado_em = now()
                            """)
                    .params(visitante, nome, email, nomeLoja, idTenant)
                    .update();
            enriquecerComPrimeiraVisita(email, visitante);
        } catch (RuntimeException e) {
            log.warn("Não foi possível fechar o funil do lead no signup (tenant {}): {}", idTenant, e.getMessage());
        }
    }

    /**
     * Copia a origem da <b>primeira</b> visita daquele visitante para o lead — primeiro toque, não
     * último: o cadastro costuma acontecer dias depois, em acesso direto, e atribuir pelo último
     * toque creditaria "direto" toda campanha paga (ADR-017). Só preenche o que está vazio.
     */
    private void enriquecerComPrimeiraVisita(String email, UUID visitante) {
        if (visitante == null) {
            return;
        }
        jdbc.sql("""
                        UPDATE plataforma.lead l
                           SET utm_source = COALESCE(l.utm_source, v.utm_source),
                               utm_medium = COALESCE(l.utm_medium, v.utm_medium),
                               utm_campaign = COALESCE(l.utm_campaign, v.utm_campaign),
                               utm_content = COALESCE(l.utm_content, v.utm_content),
                               utm_term = COALESCE(l.utm_term, v.utm_term),
                               referrer = COALESCE(l.referrer, v.referrer),
                               pagina_entrada = COALESCE(l.pagina_entrada, v.caminho),
                               atualizado_em = now()
                          FROM (SELECT utm_source, utm_medium, utm_campaign, utm_content, utm_term,
                                       referrer, caminho
                                  FROM plataforma.visita_site
                                 WHERE visitante_id = CAST(? AS uuid)
                                 ORDER BY criado_em
                                 LIMIT 1) v
                         WHERE l.email = ?
                        """)
                .params(visitante, email)
                .update();
    }

    private static String caminho(EventoBeacon e) {
        return e.caminho() == null || e.caminho().isBlank() ? "/" : e.caminho();
    }

    private static UUID uuid(String valor) {
        try {
            return valor == null || valor.isBlank() ? null : UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
