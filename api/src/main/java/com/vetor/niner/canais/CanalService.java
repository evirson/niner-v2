package com.vetor.niner.canais;

import com.vetor.niner.canais.CanalDtos.CanalRequest;
import com.vetor.niner.canais.CanalDtos.CanalResponse;
import com.vetor.niner.canais.CanalDtos.EventoComFalha;
import com.vetor.niner.canais.CanalDtos.SaudeCanaisResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Canais de Venda — listar, configurar e acompanhar a saúde da integração (R7).
 *
 * <p>⚠️ Toda consulta filtra {@code id_tenant} <b>no texto do SQL</b>, não só pela política de RLS
 * (P8) — ver {@code docs/infra/isolamento-tenant-rls.md}.
 */
@Service
public class CanalService {

    /** Quantas falhas o painel mostra. Passou disso, o problema não é ler item por item. */
    private static final int LIMITE_FALHAS = 100;

    private final JdbcClient jdbc;
    private final ControleEstoqueCanalGuard guardaEstoque;

    public CanalService(JdbcClient jdbc, ControleEstoqueCanalGuard guardaEstoque) {
        this.jdbc = jdbc;
        this.guardaEstoque = guardaEstoque;
    }

    @Transactional(readOnly = true)
    public List<CanalResponse> listar(Jwt jwt) {
        exigirAdmin(jwt);
        return jdbc.sql("""
                        SELECT c.id_canal, c.tipo::text AS tipo, c.nome, c.status::text AS status,
                               c.id_empresa, c.perc_preco, c.criado_em, c.atualizado_em,
                               c.credenciais ->> 'contaExterna' AS conta_externa,
                               (SELECT count(*) FROM anuncio a
                                 WHERE a.id_tenant = c.id_tenant AND a.id_canal = c.id_canal)
                                 AS anuncios_vinculados
                          FROM canal c
                         WHERE c.id_tenant = plataforma.tenant_atual()
                         ORDER BY c.nome
                        """)
                .query((rs, n) -> {
                    String tipo = rs.getString("tipo");
                    return new CanalResponse(
                            rs.getLong("id_canal"), tipo, TipoCanal.valueOf(tipo).rotulo(),
                            rs.getString("nome"), rs.getString("status"), rs.getLong("id_empresa"),
                            rs.getString("conta_externa"), rs.getBigDecimal("perc_preco"),
                            rs.getInt("anuncios_vinculados"),
                            rs.getObject("criado_em", OffsetDateTime.class),
                            rs.getObject("atualizado_em", OffsetDateTime.class));
                })
                .list();
    }

    /**
     * Cria o canal <b>desconectado</b>. Conectar é outro passo (OAuth), e é ele que exige o
     * controle de estoque ligado.
     *
     * <p>⚠️ O guarda de estoque é chamado <b>aqui também</b>, e não só ao conectar: deixar
     * cadastrar o canal para descobrir no passo seguinte que não pode conectar é fazer o lojista
     * preencher um formulário para ouvir "não". Melhor recusar na porta, com a mensagem que diz
     * onde resolver.
     */
    @Transactional
    public CanalResponse criar(Jwt jwt, TipoCanal tipo, CanalRequest req) {
        exigirAdmin(jwt);
        guardaEstoque.exigirControleDeEstoqueLigado();

        // ⚠️ A empresa é conferida contra o tenant do chamador. A FK composta da V067 já barraria
        // um id de outro tenant, mas o erro que ela produz é 409 "registro em uso" — genérico e
        // sobre exclusão, que manda o diagnóstico para o lado errado (2026-08-20).
        Integer daEmpresa = jdbc.sql("""
                        SELECT count(*) FROM empresa
                         WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                        """)
                .params(req.idEmpresa()).query(Integer.class).single();
        if (daEmpresa == null || daEmpresa == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada.");
        }

        long id = jdbc.sql("""
                        INSERT INTO canal (id_tenant, id_empresa, tipo, nome, status, perc_preco)
                        VALUES (plataforma.tenant_atual(), ?, CAST(? AS tipo_canal), ?, 'DESCONECTADO', ?)
                        RETURNING id_canal
                        """)
                .params(req.idEmpresa(), tipo.name(),
                        req.nome().toUpperCase(java.util.Locale.ROOT), req.percPreco())
                .query(Long.class)
                .single();
        return buscar(jwt, id);
    }

    /**
     * ⛔ <b>A empresa do canal não pode ser trocada.</b> Trocá-la mudaria, de um instante para o
     * outro, <i>qual estoque</i> abastece anúncios que já estão publicados — os saldos da filial
     * nova iriam para anúncios vinculados pensando na antiga, sem nada na tela dizendo isso. Quem
     * quiser vender pela outra filial cria outro canal.
     */
    @Transactional
    public CanalResponse atualizar(Jwt jwt, long idCanal, CanalRequest req) {
        exigirAdmin(jwt);
        CanalResponse atual = buscar(jwt, idCanal);
        if (req.idEmpresa() != null && req.idEmpresa() != atual.idEmpresa()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não é possível trocar a empresa de um canal já criado: os anúncios vinculados "
                            + "passariam a publicar o estoque de outra loja. Crie outro canal para a "
                            + "outra empresa.");
        }
        int linhas = jdbc.sql("""
                        UPDATE canal SET nome = ?, perc_preco = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(req.nome().toUpperCase(java.util.Locale.ROOT), req.percPreco(), idCanal)
                .update();
        if (linhas == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Canal não encontrado.");
        }
        return buscar(jwt, idCanal);
    }

    @Transactional(readOnly = true)
    public CanalResponse buscar(Jwt jwt, long idCanal) {
        exigirAdmin(jwt);
        return listar(jwt).stream()
                .filter(c -> c.idCanal() == idCanal)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Canal não encontrado."));
    }

    /**
     * Desconecta: apaga a credencial e marca o canal.
     *
     * <p>⚠️ <b>Apaga a credencial de verdade</b> (`credenciais = NULL`), não só troca o status.
     * Guardar um token de acesso de quem pediu para desconectar é manter uma chave da casa de
     * alguém que pediu a chave de volta. Se reconectar, o OAuth emite outro.
     *
     * <p>Os <b>anúncios vinculados continuam</b>: o de-para é trabalho do lojista, e apagá-lo
     * faria uma desconexão temporária custar todo o trabalho de vínculo de novo.
     */
    @Transactional
    public CanalResponse desconectar(Jwt jwt, long idCanal) {
        exigirAdmin(jwt);
        int linhas = jdbc.sql("""
                        UPDATE canal
                           SET status = 'DESCONECTADO', credenciais = NULL, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(idCanal).update();
        if (linhas == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Canal não encontrado.");
        }
        return buscar(jwt, idCanal);
    }

    /**
     * Exclui um canal.
     *
     * <p>Recusa quando existem anúncios vinculados — apagar levaria junto o de-para inteiro, e o
     * lojista quase sempre queria só <b>desconectar</b>. Mesmo padrão dos cadastros do produto:
     * quando há dependente, oferecer a ação certa em vez de destruir.
     */
    @Transactional
    public void excluir(Jwt jwt, long idCanal) {
        exigirAdmin(jwt);
        Integer vinculados = jdbc.sql("""
                        SELECT count(*) FROM anuncio
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(idCanal).query(Integer.class).single();
        if (vinculados != null && vinculados > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este canal tem %d anúncio(s) vinculado(s). Use \"Desconectar\" para parar a sincronização sem perder os vínculos."
                            .formatted(vinculados));
        }
        int linhas = jdbc.sql("""
                        DELETE FROM canal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(idCanal).update();
        if (linhas == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Canal não encontrado.");
        }
    }

    // ------------------------------------------------------------------ painel de saúde (R7)

    /**
     * Situação da fila de sincronização.
     *
     * <p>⚠️ Conta {@code PENDENTE}, {@code ERRO} e {@code DEAD_LETTER} <b>separadamente</b>, e não
     * um "total de problemas": os três significam coisas diferentes para quem olha. Pendente é
     * normal (vai sair em segundos); erro está tentando sozinho; dead-letter <b>parou e espera
     * gente</b>. Somar os três produziria um número alarmante num dia perfeitamente saudável.
     */
    @Transactional(readOnly = true)
    public SaudeCanaisResponse saude(Jwt jwt) {
        exigirAdmin(jwt);
        var contagem = jdbc.sql("""
                        SELECT
                          count(*) FILTER (WHERE status = 'PENDENTE')    AS pendentes,
                          count(*) FILTER (WHERE status = 'ERRO')        AS com_erro,
                          count(*) FILTER (WHERE status = 'DEAD_LETTER') AS dead_letter
                          FROM outbox_evento
                         WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query((rs, n) -> new int[]{rs.getInt("pendentes"), rs.getInt("com_erro"),
                        rs.getInt("dead_letter")})
                .single();

        List<EventoComFalha> falhas = jdbc.sql("""
                        SELECT id, tipo, agregado_id, status::text AS status, tentativas, erro,
                               proximo_retry, criado_em
                          FROM outbox_evento
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND status IN ('ERRO', 'DEAD_LETTER')
                         ORDER BY CASE status WHEN 'DEAD_LETTER' THEN 0 ELSE 1 END, id DESC
                         LIMIT ?
                        """)
                .param(LIMITE_FALHAS)
                .query((rs, n) -> new EventoComFalha(
                        rs.getLong("id"), rs.getString("tipo"), rs.getString("agregado_id"),
                        rs.getString("status"), rs.getInt("tentativas"), rs.getString("erro"),
                        rs.getObject("proximo_retry", OffsetDateTime.class),
                        rs.getObject("criado_em", OffsetDateTime.class)))
                .list();

        return new SaudeCanaisResponse(contagem[0], contagem[1], contagem[2], falhas);
    }

    /**
     * Reprocessar manualmente (R7) — devolve o evento à fila <b>agora</b>.
     *
     * <p>⚠️ <b>Zera o contador de tentativas.</b> Sem isso, um evento que chegou ao dead-letter
     * com 12 falhas voltaria e morreria na primeira tentativa seguinte: o botão pareceria não
     * funcionar. Reprocessar manual é a afirmação "a causa foi resolvida", e merece a série
     * inteira de novo.
     */
    @Transactional
    public void reprocessar(Jwt jwt, long idEvento) {
        exigirAdmin(jwt);
        int linhas = jdbc.sql("""
                        UPDATE outbox_evento
                           SET status = 'PENDENTE', tentativas = 0, proximo_retry = now(), erro = NULL
                         WHERE id_tenant = plataforma.tenant_atual() AND id = ?
                           AND status IN ('ERRO', 'DEAD_LETTER')
                        """)
                .params(idEvento).update();
        if (linhas == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Evento não encontrado, ou já processado — só falhas podem ser reprocessadas.");
        }
    }

    /** Mesma leitura do resto do produto: a claim é {@code roles} (lista), não um papel único. */
    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem acessar os canais de venda.");
        }
    }
}
