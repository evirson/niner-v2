package com.vetor.niner.vendas.orcamento;

import com.vetor.niner.comum.tempo.FusoDaLoja;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * O trabalho transacional do vencimento de orçamentos — bean separado de
 * {@link OrcamentoVencimentoJob} porque {@code @Transactional} em auto-invocação não passa pelo
 * proxy do Spring e roda sem transação.
 */
@Component
public class OrcamentoVencimentoProcessador {

    private final JdbcClient jdbc;
    private final FusoDaLoja fusoDaLoja;

    public OrcamentoVencimentoProcessador(JdbcClient jdbc, FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.fusoDaLoja = fusoDaLoja;
    }

    /**
     * Marca {@code VENCIDO} todo orçamento {@code ABERTO} cuja validade já passou <b>no fuso da
     * empresa que o emitiu</b>.
     *
     * <h2>⚠️ O "hoje" vem da EMPRESA, uma empresa por vez — nunca do banco</h2>
     *
     * <p>A sessão do Postgres roda em {@code Etc/UTC}: comparar contra {@code CURRENT_DATE} venceria
     * o orçamento <b>às 21:00 de Brasília do último dia de validade</b> — o cliente que voltasse às
     * 21:30 encontraria o orçamento morto. É o mesmo defeito que fez o caixa aberto de manhã
     * "sumir" às 21h em 2026-08-19.
     *
     * <p>E aqui <b>não há JWT</b>, então o fuso não pode vir da sessão. Vem da UF de cada empresa
     * ({@link FusoDaLoja#da}) — e é por isso que a varredura é <b>por empresa</b>, não uma única
     * consulta com um fuso carimbado: uma loja no Acre (UTC−5) e uma em Recife (UTC−3) viram o dia
     * em momentos diferentes, e um `'America/Sao_Paulo'` fixo mataria o orçamento do lojista do
     * Acre duas horas antes da hora.
     *
     * <p>O corte é {@code data_validade < hoje}, não {@code <=}: o orçamento vale <b>até</b> o dia
     * da validade, inclusive.
     *
     * @return quantos foram vencidos nesta rodada
     */
    @Transactional
    public int vencerDoTenant() {
        // Só as empresas que de fato têm orçamento aberto — na maioria das rodadas, nenhuma.
        List<Long> empresas = jdbc.sql("""
                        SELECT DISTINCT o.id_empresa
                          FROM orcamento o
                         WHERE o.id_tenant = plataforma.tenant_atual() AND o.situacao = 'ABERTO'
                        """)
                .query(Long.class).list();

        int vencidos = 0;
        for (Long idEmpresa : empresas) {
            LocalDate hoje = OffsetDateTime.now().atZoneSameInstant(fusoDaLoja.da(idEmpresa)).toLocalDate();
            vencidos += jdbc.sql("""
                            UPDATE orcamento
                               SET situacao = 'VENCIDO', atualizado_em = now()
                             WHERE id_tenant = plataforma.tenant_atual()
                               AND id_empresa = ?
                               AND situacao = 'ABERTO'
                               AND data_validade < ?
                            """)
                    .params(idEmpresa, hoje)
                    .update();
        }
        return vencidos;
    }
}
