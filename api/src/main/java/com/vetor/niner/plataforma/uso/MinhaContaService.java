package com.vetor.niner.plataforma.uso;

import com.vetor.niner.comum.tempo.FusoDaPlataforma;
import com.vetor.niner.plataforma.uso.MinhaContaDtos.EmpresaDoTenant;
import com.vetor.niner.plataforma.uso.MinhaContaDtos.FaixaSugerida;
import com.vetor.niner.plataforma.uso.MinhaContaDtos.MinhaContaResponse;
import com.vetor.niner.plataforma.uso.MinhaContaDtos.PlanoAtual;
import com.vetor.niner.plataforma.uso.MinhaContaDtos.SituacaoUso;
import com.vetor.niner.plataforma.uso.MinhaContaDtos.UsoAtual;
import com.vetor.niner.plataforma.uso.MinhaContaDtos.UsoMes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Painel <i>Minha Conta</i> (docs/telas/painel-assinatura.md): plano, cota do mês, histórico de
 * 12 meses, faixa recomendada e as empresas/CNPJs do tenant.
 *
 * <p>Fronteira de vocabulário (Anexo A do plano de negócio): aqui é <b>o que a loja paga à
 * Vetor</b> — nunca o caixa/crediário da loja, que é o módulo {@code financeiro}.
 *
 * <p>ADMIN-only, mesmo critério de {@code configuracao.geral} e {@code identidade.empresa}: é
 * dado comercial da conta, não operação de balcão.
 */
@Service
public class MinhaContaService {

    private final JdbcClient jdbc;

    public MinhaContaService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public MinhaContaResponse consultar(Jwt jwt) {
        exigirAdmin(jwt);

        PlanoAtual plano = jdbc.sql("""
                        SELECT p.id_plano, p.nome, p.gratuito, p.preco_mensal, p.preco_anual,
                               a.ciclo::text AS ciclo, p.limite_vendas_mes
                          FROM plataforma.assinatura a
                          JOIN plataforma.plano p ON p.id_plano = a.id_plano
                         WHERE a.id_tenant = plataforma.tenant_atual() AND a.status <> 'CANCELADA'
                         ORDER BY a.id_assinatura DESC
                         LIMIT 1
                        """)
                .query((rs, n) -> new PlanoAtual(
                        rs.getLong("id_plano"), rs.getString("nome"), rs.getBoolean("gratuito"),
                        rs.getBigDecimal("preco_mensal"), rs.getBigDecimal("preco_anual"),
                        rs.getString("ciclo"), (Integer) rs.getObject("limite_vendas_mes")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(FORBIDDEN, "Conta sem assinatura ativa."));

        // Uso da competência corrente. A linha pode estar com a competência do mês passado (o
        // reset é lazy, feito na primeira venda do mês) — nesse caso o mês corrente vale zero.
        Uso bruto = jdbc.sql("""
                        SELECT CASE WHEN u.competencia_vendas = date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo')::date
                                    THEN u.qtd_vendas_mes ELSE 0 END AS qtd_vendas,
                               COALESCE(a.tolerancia_vendas, pc.tolerancia_vendas) AS tolerancia
                          FROM plataforma.parametro_comercial pc
                          LEFT JOIN plataforma.uso_tenant u  ON u.id_tenant = plataforma.tenant_atual()
                          LEFT JOIN plataforma.assinatura a  ON a.id_tenant = plataforma.tenant_atual()
                                                            AND a.status <> 'CANCELADA'
                         WHERE pc.id = 1
                         LIMIT 1
                        """)
                .query((rs, n) -> new Uso(rs.getInt("qtd_vendas"), rs.getInt("tolerancia")))
                .optional()
                .orElse(new Uso(0, 0));

        LocalDate competencia = LocalDate.now(FusoDaPlataforma.ZONA).withDayOfMonth(1);
        UsoAtual uso = montarUso(bruto, plano.limiteVendasMes(), competencia);

        List<UsoMes> historico = jdbc.sql("""
                        SELECT competencia, qtd_vendas
                          FROM plataforma.uso_venda_mes
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND competencia >= (date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo') - interval '11 months')::date
                         ORDER BY competencia
                        """)
                .query((rs, n) -> new UsoMes(rs.getObject("competencia", LocalDate.class), rs.getInt("qtd_vendas")))
                .list();

        // Recomendação: a menor faixa que comporta o pico dos últimos 12 meses (não o mês
        // corrente) — assinar pela média deixa o lojista bloqueado no primeiro mês forte.
        int pico = Math.max(uso.qtdVendas(), historico.stream().mapToInt(UsoMes::qtdVendas).max().orElse(0));
        FaixaSugerida faixa = jdbc.sql("""
                        SELECT nome, limite_vendas_mes, preco_mensal, preco_anual
                          FROM plataforma.plano
                         WHERE ativo AND NOT gratuito AND faixa_ordem IS NOT NULL
                           AND (limite_vendas_mes IS NULL OR limite_vendas_mes >= ?)
                         ORDER BY faixa_ordem
                         LIMIT 1
                        """)
                .param(Math.max(pico, 1))
                .query((rs, n) -> new FaixaSugerida(rs.getString("nome"), (Integer) rs.getObject("limite_vendas_mes"),
                        rs.getBigDecimal("preco_mensal"), rs.getBigDecimal("preco_anual")))
                .optional()
                .orElse(null);

        List<EmpresaDoTenant> empresas = jdbc.sql("""
                        SELECT id_empresa, codigo_empresa, razao_social, nome_fantasia, cnpj,
                               cidade, estado, matriz, ativo
                          FROM empresa
                         WHERE id_tenant = plataforma.tenant_atual()
                         ORDER BY codigo_empresa
                        """)
                .query((rs, n) -> new EmpresaDoTenant(
                        rs.getLong("id_empresa"), rs.getInt("codigo_empresa"), rs.getString("razao_social"),
                        rs.getString("nome_fantasia"), rs.getString("cnpj"), rs.getString("cidade"),
                        rs.getString("estado"), rs.getBoolean("matriz"), rs.getBoolean("ativo")))
                .list();

        return new MinhaContaResponse(plano, uso, historico, faixa, empresas);
    }

    /** Regra de exibição do medidor — a mesma escada que a tela pinta (normal → bloqueado). */
    private static UsoAtual montarUso(Uso bruto, Integer limite, LocalDate competencia) {
        LocalDate zeraEm = competencia.plusMonths(1);
        if (limite == null) {
            return new UsoAtual(competencia, bruto.qtdVendas(), null, null,
                    bruto.tolerancia(), null, SituacaoUso.NORMAL, zeraEm);
        }
        int restantes = Math.max(0, limite - bruto.qtdVendas());
        int excedente = Math.max(0, bruto.qtdVendas() - limite);
        int toleranciaRestante = Math.max(0, bruto.tolerancia() - excedente);

        SituacaoUso situacao;
        if (bruto.qtdVendas() >= limite + bruto.tolerancia()) {
            situacao = SituacaoUso.BLOQUEADO;
        } else if (bruto.qtdVendas() >= limite) {
            situacao = SituacaoUso.TOLERANCIA;
        } else if (bruto.qtdVendas() * 100 >= limite * 80) {
            situacao = SituacaoUso.ATENCAO;
        } else {
            situacao = SituacaoUso.NORMAL;
        }
        return new UsoAtual(competencia, bruto.qtdVendas(), limite, restantes,
                bruto.tolerancia(), toleranciaRestante, situacao, zeraEm);
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas ADMIN pode ver os dados da assinatura.");
        }
    }

    private record Uso(int qtdVendas, int tolerancia) {
    }
}
