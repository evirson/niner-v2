package com.vetor.niner.canais;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * ⛔ <b>"Se vende em marketplace, não pode existir estoque negativo"</b> — decisão do dono do
 * produto em 2026-08-25 ({@code docs/MODULOMARKETPLACE.md} §8.1).
 *
 * <p>Ela supera, <b>para quem tem canal conectado</b>, o padrão de
 * {@code cfg_permite_estoque_negativo}, que nasce <b>ligado</b> (V055) porque a loja típica do
 * produto não faz gestão de estoque e travar o caixa por um número que ninguém alimenta seria
 * pior. Vender em marketplace muda a conta: o saldo do ERP deixa de ser conveniência interna e
 * vira <b>promessa pública</b> no anúncio (P1 — o ERP é dono do estoque, o canal é réplica).
 * Sincronizar saldo de quem não controla estoque publica número errado, e o efeito é <b>pior que
 * não sincronizar</b>: o marketplace pune reputação por cancelamento.
 *
 * <h2>⚠️ São DOIS guardas, e o segundo é o que se esquece</h2>
 *
 * <ol>
 *   <li>{@link #exigirControleDeEstoqueLigado()} — não deixa <b>conectar canal</b> com o
 *       parâmetro ligado;</li>
 *   <li>{@link #exigirNenhumCanalConectado()} — não deixa <b>religar o parâmetro</b> enquanto
 *       existir canal conectado.</li>
 * </ol>
 *
 * Só o primeiro seria trava decorativa: a loja conectaria o canal com o controle ligado e, no dia
 * seguinte, religaria o parâmetro em Parâmetros do Sistema — destravando o overselling pela porta
 * dos fundos, com o anúncio no marketplace seguindo em frente prometendo estoque que não existe.
 * Proteger a porta da frente e deixar a de trás aberta dá a <i>sensação</i> de proteção sem a
 * proteção.
 *
 * <p>⚠️ Ambas as consultas filtram {@code id_tenant} <b>no texto do SQL</b>, não só pela política
 * de RLS (P8) — ver {@code docs/infra/isolamento-tenant-rls.md}.
 */
@Service
public class ControleEstoqueCanalGuard {

    private final JdbcClient jdbc;

    public ControleEstoqueCanalGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * O tenant tem algum canal conectado? Usado pelo guarda do parâmetro e pelas telas que
     * precisam avisar que a loja opera em marketplace.
     *
     * <p>⚠️ Conta só {@code CONECTADO}: um canal em {@code ERRO} ou {@code DESCONECTADO} não está
     * publicando saldo em lugar nenhum, e travar o parâmetro por causa dele deixaria o lojista
     * preso a uma configuração por causa de uma integração que ele já abandonou.
     */
    @Transactional(readOnly = true)
    public boolean existeCanalConectado() {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM canal
                           WHERE id_tenant = plataforma.tenant_atual()
                             AND status = 'CONECTADO'
                        )
                        """)
                .query(Boolean.class)
                .single());
    }

    /**
     * Guarda 1 — chamado ao <b>conectar</b> um canal.
     *
     * @throws ResponseStatusException 409 quando {@code cfg_permite_estoque_negativo} está ligado
     */
    @Transactional(readOnly = true)
    public void exigirControleDeEstoqueLigado() {
        boolean permiteNegativo = jdbc.sql("""
                        SELECT cfg_permite_estoque_negativo FROM cfg_geral
                         WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query(Boolean.class)
                .optional()
                // Sem linha de cfg_geral (não deveria acontecer — nasce no signup) o padrão do
                // produto é "permite negativo", e é ele que barra a conexão. Falhar fechado.
                .orElse(true);

        if (permiteNegativo) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Para vender em marketplace é preciso controlar o estoque. "
                            + "Desligue \"Permite estoque negativo\" em Parâmetros do Sistema → Estoque "
                            + "antes de conectar o canal.");
        }
    }

    /**
     * Guarda 2 — chamado ao tentar <b>religar</b> {@code cfg_permite_estoque_negativo}.
     *
     * <p>Só barra a transição para {@code true}: desligar o parâmetro é sempre permitido (aperta
     * o controle, nunca afrouxa), e salvar a tela sem mexer nele também.
     *
     * @throws ResponseStatusException 409 quando existe canal conectado
     */
    @Transactional(readOnly = true)
    public void exigirNenhumCanalConectado() {
        if (existeCanalConectado()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não é possível permitir estoque negativo enquanto houver canal de venda conectado: "
                            + "o saldo do ERP é o que abastece os anúncios, e um número errado gera "
                            + "cancelamento e perda de reputação no marketplace. "
                            + "Desconecte o canal antes, em Canais de Venda.");
        }
    }
}
