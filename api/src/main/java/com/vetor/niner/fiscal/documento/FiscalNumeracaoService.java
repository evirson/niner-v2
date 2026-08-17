package com.vetor.niner.fiscal.documento;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Alocação de número de documento fiscal (F4 — docs/MODULOFISCAL.md §9.2).
 *
 * <p><b>A numeração não pode ter buraco.</b> Buraco só se resolve por inutilização formal, com
 * prazo até o dia 10 do mês seguinte — ou seja, um número perdido vira obrigação acessória. Por
 * isso duas regras de desenho:
 *
 * <ol>
 *   <li><b>A alocação é atômica</b> e acontece sob trava da linha de {@code fiscal_numeracao}.
 *       Duas vendas simultâneas no mesmo caixa (ou em dois caixas da mesma empresa) nunca podem
 *       receber o mesmo número — seria rejeição por duplicidade na segunda.</li>
 *   <li><b>Transação curta e própria</b> ({@code REQUIRES_NEW}): a alocação <b>não</b> participa
 *       da transação da emissão. Se a transmissão demorar 10 s, a linha de numeração não fica
 *       travada esse tempo todo bloqueando os outros caixas (F2 — nenhum I/O de rede dentro de
 *       transação de banco).</li>
 * </ol>
 *
 * <p>A contrapartida consciente dessa segunda regra: se a emissão falhar <b>depois</b> de alocar,
 * o número foi gasto e vira candidato a inutilização. É o lado certo do trade-off — perder um
 * número é papelada; repetir um número é rejeição no caixa com cliente na frente.
 */
@Service
public class FiscalNumeracaoService {

    private final JdbcClient jdbc;
    private final SecureRandom aleatorio = new SecureRandom();

    public FiscalNumeracaoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserva o próximo número da série e devolve o que foi reservado.
     *
     * <p>{@code INSERT … ON CONFLICT DO UPDATE … RETURNING} resolve num só comando os dois casos
     * — primeira nota da série (a linha nem existe) e as demais — <b>e</b> é atômico: o
     * {@code ON CONFLICT DO UPDATE} trava a linha, então dois caixas concorrentes serializam
     * naturalmente, sem {@code SELECT … FOR UPDATE} explícito e sem janela entre ler e gravar.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NumeroReservado reservar(long idEmpresa, int modelo, int serie) {
        Integer numero = jdbc.sql("""
                        INSERT INTO fiscal_numeracao (id_tenant, id_empresa, modelo, serie, proximo_numero)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, 2)
                        ON CONFLICT (id_tenant, id_empresa, modelo, serie) DO UPDATE
                            SET proximo_numero = fiscal_numeracao.proximo_numero + 1,
                                atualizado_em = now()
                        RETURNING proximo_numero - 1
                        """)
                .params(idEmpresa, modelo, serie)
                .query(Integer.class)
                .single();

        return new NumeroReservado(serie, numero, codigoNumerico());
    }

    /** Último número já usado na série ({@code proximo_numero - 1}); 0 se nada foi emitido. */
    @Transactional(readOnly = true)
    public int ultimoNumeroUsado(long idEmpresa, int modelo, int serie) {
        Integer proximo = jdbc.sql("""
                        SELECT proximo_numero FROM fiscal_numeracao
                        WHERE id_tenant = plataforma.tenant_atual()
                          AND id_empresa = ? AND modelo = ? AND serie = ?
                        """)
                .params(idEmpresa, modelo, serie)
                .query(Integer.class)
                .optional()
                .orElse(1);
        return proximo - 1;
    }

    /**
     * {@code cNF} — código numérico de 8 dígitos que entra na chave de acesso. <b>Não é</b> o
     * número da nota: é aleatório, e existe para dificultar que alguém adivinhe chaves de acesso
     * a partir de uma conhecida. Por isso {@link SecureRandom}, não {@code Math.random()}.
     *
     * <p>⚠️ O MOC proíbe {@code cNF} igual ao {@code nNF} — daí o intervalo começar longe de 0.
     */
    private int codigoNumerico() {
        return 10_000_000 + aleatorio.nextInt(89_999_999);
    }

    /** O que foi reservado para uma nota específica. */
    public record NumeroReservado(int serie, int numero, int codigoNumerico) {
    }
}
