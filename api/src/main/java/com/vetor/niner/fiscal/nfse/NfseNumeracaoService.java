package com.vetor.niner.fiscal.nfse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alocação do {@code nDPS} — o número sequencial da DPS, que é <b>nosso</b>.
 *
 * <p>Espelha o {@link com.vetor.niner.fiscal.documento.FiscalNumeracaoService} no desenho, com as
 * diferenças que o layout impõe: sem {@code modelo} (a NFS-e não tem), e {@code long} porque o
 * {@code nDPS} vai até 15 dígitos — o {@code int} da NF-e não serve.
 *
 * <h2>Por que transação curta e própria ({@code REQUIRES_NEW})</h2>
 *
 * <p>A alocação <b>não</b> participa da transação da emissão. Se a transmissão ao SEFIN demorar,
 * a linha de numeração não fica travada esse tempo todo bloqueando os outros atendimentos (F2 —
 * nenhum I/O de rede dentro de transação de banco). O {@code INSERT … ON CONFLICT DO UPDATE …
 * RETURNING} resolve num comando só os dois casos (primeira DPS da série e as demais) e trava a
 * linha, então dois atendimentos simultâneos serializam sem janela entre ler e gravar.
 *
 * <h2>⭐ E aqui a contrapartida é mais leve que na NF-e</h2>
 *
 * <p>Na NF-e, número gasto sem nota vira buraco na sequência e exige <b>inutilização formal</b>,
 * com prazo legal. Na NFS-e não: o {@code nDPS} é o nosso controle, e o que a prefeitura numera é
 * o {@code nNFSe}, que ela mesma atribui. Buraco no {@code nDPS} não gera obrigação acessória.
 *
 * <p>⭐ <b>Melhor ainda: rejeição não consome número.</b> Medido em produção — a DPS 2001000 tomou
 * {@code E0712}, e a mesma DPS 2001000 foi aceita minutos depois. Por isso o reenvio depois de
 * rejeição <b>reusa a linha e o mesmo número</b> ({@code nfse_documento_venda_servico_uk}), em vez
 * de alocar outro. ⚠️ O que <b>não</b> se pode reusar cego é depois de um <b>timeout</b>: ali a
 * nota pode ter sido gerada do outro lado, e reenviar daria {@code E0014} — o caminho é consultar
 * {@code GET /dps/{id}}.
 */
@Service
public class NfseNumeracaoService {

    private final JdbcClient jdbc;

    public NfseNumeracaoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Reserva e devolve o próximo {@code nDPS} da série daquela empresa. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long reservar(long idEmpresa, int serie) {
        return jdbc.sql("""
                        INSERT INTO nfse_numeracao (id_tenant, id_empresa, serie, proximo_numero)
                        VALUES (plataforma.tenant_atual(), ?, ?, 2)
                        ON CONFLICT (id_tenant, id_empresa, serie) DO UPDATE
                            SET proximo_numero = nfse_numeracao.proximo_numero + 1,
                                atualizado_em  = now()
                        RETURNING proximo_numero - 1
                        """)
                .params(idEmpresa, serie)
                .query(Long.class)
                .single();
    }

    /**
     * Último número já usado na série; 0 se nada foi emitido.
     *
     * <p>⚠️ Filtro de {@code id_tenant} explícito no texto do SQL, não só pela RLS (P8): um
     * {@code SELECT} que confia apenas na política já vazou linha de outro tenant neste
     * repositório, e o padrão desde a auditoria de 2026-08-08 é escrever o filtro.
     */
    @Transactional(readOnly = true)
    public long ultimoNumeroUsado(long idEmpresa, int serie) {
        return jdbc.sql("""
                        SELECT proximo_numero FROM nfse_numeracao
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_empresa = ?
                           AND serie = ?
                        """)
                .params(idEmpresa, serie)
                .query(Long.class)
                .optional()
                .orElse(1L) - 1;
    }

    /**
     * Avança a numeração para um ponto conhecido, sem nunca RECUAR.
     *
     * <p>Existe para o caso de a loja já ter emitido NFS-e por outro sistema com o mesmo CNPJ e
     * série: começar do 1 daria {@code E0014} (DPS duplicada) em toda emissão até alcançar o que
     * já existe. ⛔ Recuar é proibido de propósito — devolveria números já usados no SEFIN, e o
     * erro só apareceria na emissão seguinte.
     */
    @Transactional
    public long avancarPara(long idEmpresa, int serie, long proximoNumero) {
        if (proximoNumero < 1) {
            throw new IllegalArgumentException("O próximo número da DPS não pode ser menor que 1");
        }
        return jdbc.sql("""
                        INSERT INTO nfse_numeracao (id_tenant, id_empresa, serie, proximo_numero)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?)
                        ON CONFLICT (id_tenant, id_empresa, serie) DO UPDATE
                            SET proximo_numero = GREATEST(nfse_numeracao.proximo_numero,
                                                          EXCLUDED.proximo_numero),
                                atualizado_em  = now()
                        RETURNING proximo_numero
                        """)
                .params(idEmpresa, serie, proximoNumero)
                .query(Long.class)
                .single();
    }
}
