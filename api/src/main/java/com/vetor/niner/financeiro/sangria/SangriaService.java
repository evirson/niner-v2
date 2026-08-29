package com.vetor.niner.financeiro.sangria;

import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.financeiro.sangria.SangriaDtos.SangriaContextoResponse;
import com.vetor.niner.financeiro.sangria.SangriaDtos.SangriaRequest;
import com.vetor.niner.financeiro.sangria.SangriaDtos.SangriaResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sangria de Caixa — tirar dinheiro da gaveta e mandar para uma conta bancária.
 *
 * <h2>⛔ Por que isto não existia, e por que a falta doía</h2>
 *
 * <p>Não havia <b>nenhuma</b> forma de o operador tirar dinheiro do caixa. O valor
 * {@code DEBITO_CAIXA} está no enum desde a V025, mas o único que o emite é a baixa de Contas a
 * Pagar — que paga uma dívida, não move o dinheiro para o banco. A loja vendia R$ 5.000 em
 * dinheiro, depositava de tarde, e o sistema seguia afirmando que os R$ 5.000 estavam na gaveta.
 *
 * <h2>⭐ Sangria é TRANSFERÊNCIA, nunca saída</h2>
 *
 * <p>Decisão do dono do produto (2026-08-29): <i>"esta sangria tem que ter um destino: sempre será
 * depositada numa conta bancária, ou vai pro caixa central que tb está definido como uma conta
 * bancária"</i>. Por isso toda sangria escreve os <b>dois</b> lados na mesma transação — débito em
 * {@code caixa_detalhe}, crédito em {@code conta_corrente_movimento} — mais o mestre
 * {@code caixa_sangria}, que é quem os liga. Dinheiro que "sai" sem destino desaparece do fluxo.
 *
 * <h2>⚠️ Três coisas que o desenho evita de propósito</h2>
 *
 * <ol>
 *   <li><b>O caixa vem do servidor, não do corpo.</b> {@link SangriaRequest} não tem
 *       {@code idCaixa}: é sempre o caixa aberto do próprio usuário. Aceitá-lo pelo corpo deixaria
 *       um operador tirar dinheiro do caixa de outra pessoa por chamada direta à API — e a tela
 *       nunca teria como oferecer isso.</li>
 *   <li><b>Trava o caixa antes de ler o saldo.</b> Sem {@code FOR UPDATE}, dois cliques leem o
 *       mesmo disponível e sangram duas vezes o que só cabia uma — é a mesma corrida que as quatro
 *       rotinas de dinheiro corrigidas nesta data já tinham.</li>
 *   <li><b>Não sangra mais do que tem.</b> O disponível é da <b>carteira de abertura</b> (o
 *       dinheiro físico), não do total do caixa: cartão e crediário não estão na gaveta e não se
 *       depositam.</li>
 * </ol>
 */
@Service
public class SangriaService {

    private final JdbcClient jdbc;
    private final FusoDaLoja fusoDaLoja;

    public SangriaService(JdbcClient jdbc, FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.fusoDaLoja = fusoDaLoja;
    }

    private record CaixaAberto(long idCaixa, long idCarteira, String nomeCarteira, BigDecimal saldoInicial) {
    }

    /**
     * O caixa aberto do usuário na empresa da sessão.
     *
     * <p>⚠️ {@code FOR UPDATE} opcional: a leitura de contexto (GET) não pode travar nada, senão um
     * refresh de tela segura a linha; o POST usa a versão travada.
     */
    private Optional<CaixaAberto> buscarCaixaAberto(Jwt jwt, boolean travar) {
        long idEmpresa = ((Number) jwt.getClaim("eid")).longValue();
        long idUsuario = Long.parseLong(jwt.getSubject());
        return jdbc.sql("""
                        SELECT cm.id_caixa, cm.id_carteira, tc.nome_carteira, cm.saldo_inicial
                          FROM caixa_mestre cm
                          JOIN tipo_carteira tc ON tc.id_carteira = cm.id_carteira AND tc.id_tenant = cm.id_tenant
                         WHERE cm.id_tenant = plataforma.tenant_atual()
                           AND cm.id_empresa = ? AND cm.id_usuario = ? AND cm.caixa_fechado = false
                         ORDER BY cm.id_caixa DESC
                         LIMIT 1
                        """ + (travar ? " FOR UPDATE OF cm" : ""))
                .params(idEmpresa, idUsuario)
                .query((rs, n) -> new CaixaAberto(rs.getLong("id_caixa"), rs.getLong("id_carteira"),
                        rs.getString("nome_carteira"), rs.getBigDecimal("saldo_inicial")))
                .optional();
    }

    /**
     * Dinheiro que a sangria pode tirar: saldo inicial + entradas − saídas <b>da carteira de
     * abertura</b>.
     *
     * <p>⚠️ Só dessa carteira, e é o ponto principal: um caixa que vendeu R$ 3.000 no cartão e
     * R$ 200 em dinheiro tem R$ 200 na gaveta. Somar o total deixaria o operador "depositar"
     * dinheiro que o adquirente ainda nem repassou.
     */
    private BigDecimal disponivel(CaixaAberto caixa) {
        BigDecimal movimento = jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN credito_debito = 'C' THEN valor ELSE -valor END), 0)
                          FROM caixa_detalhe
                         WHERE id_tenant = plataforma.tenant_atual() AND id_caixa = ? AND id_carteira = ?
                        """)
                .params(caixa.idCaixa(), caixa.idCarteira())
                .query(BigDecimal.class)
                .single();
        return caixa.saldoInicial().add(movimento);
    }

    @Transactional(readOnly = true)
    public SangriaContextoResponse contexto(Jwt jwt) {
        return buscarCaixaAberto(jwt, false)
                .map(c -> new SangriaContextoResponse(true, c.idCaixa(), c.nomeCarteira(), disponivel(c)))
                .orElseGet(() -> new SangriaContextoResponse(false, null, null, BigDecimal.ZERO));
    }

    /** As sangrias do caixa aberto — a grid da tela, para o operador conferir o que já mandou. */
    @Transactional(readOnly = true)
    public List<SangriaResponse> listarDoCaixaAberto(Jwt jwt) {
        Optional<CaixaAberto> caixa = buscarCaixaAberto(jwt, false);
        if (caixa.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT s.id_sangria, s.data_sangria, s.valor, s.id_conta_corrente,
                               cc.descricao AS descricao_conta, u.nome_usuario, s.observacao
                          FROM caixa_sangria s
                          JOIN conta_corrente cc ON cc.id_conta_corrente = s.id_conta_corrente
                                                AND cc.id_tenant = s.id_tenant
                          JOIN usuario u ON u.id_usuario = s.id_usuario AND u.id_tenant = s.id_tenant
                         WHERE s.id_tenant = plataforma.tenant_atual() AND s.id_caixa = ?
                         ORDER BY s.id_sangria DESC
                        """)
                .param(caixa.get().idCaixa())
                .query((rs, n) -> new SangriaResponse(
                        rs.getLong("id_sangria"),
                        rs.getObject("data_sangria", OffsetDateTime.class),
                        rs.getBigDecimal("valor"),
                        rs.getString("id_conta_corrente"),
                        rs.getString("descricao_conta"),
                        rs.getString("nome_usuario"),
                        rs.getString("observacao")))
                .list();
    }

    /**
     * Registra a sangria: mestre + débito no caixa + crédito no banco, na mesma transação.
     *
     * <p>⚠️ A ordem importa — o mestre primeiro, porque os outros dois apontam para ele por FK de
     * verdade. Aqui a FK é deliberada, ao contrário de {@code id_conta_pagar} nas mesmas duas
     * tabelas: aquele vínculo nasceu sem FK e foi assim que um {@code excluir()} passou meses
     * deixando movimento órfão sem o banco reclamar.
     */
    @Transactional
    public SangriaResponse registrar(Jwt jwt, SangriaRequest req) {
        CaixaAberto caixa = buscarCaixaAberto(jwt, true).orElseThrow(() -> new ConflitoDadosException(
                "Não há caixa aberto para você agora. Abra o caixa antes de registrar uma sangria."));

        BigDecimal valor = req.valor().setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal disponivel = disponivel(caixa);
        if (valor.compareTo(disponivel) > 0) {
            throw new ConflitoDadosException(
                    "A sangria de R$ " + valor + " é maior que o dinheiro disponível no caixa (R$ "
                            + disponivel + " em " + caixa.nomeCarteira() + "). Confira o valor.");
        }

        String idConta = req.idContaCorrente().trim().toUpperCase(Locale.ROOT);
        // ⚠️ Filtro explícito de tenant no texto do SQL (P8) — RLS sozinho já vazou linha de outro
        // tenant neste projeto, com teste reproduzindo.
        boolean contaExiste = jdbc.sql("""
                        SELECT 1 FROM conta_corrente
                         WHERE id_tenant = plataforma.tenant_atual() AND id_conta_corrente = ? AND ativo = true
                        """)
                .param(idConta).query(Integer.class).optional().isPresent();
        if (!contaExiste) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Conta corrente não encontrada ou inativa.");
        }

        long idUsuario = Long.parseLong(jwt.getSubject());
        String observacao = req.observacao() == null || req.observacao().isBlank()
                ? null : req.observacao().trim().toUpperCase(Locale.ROOT);

        long idSangria = jdbc.sql("""
                        INSERT INTO caixa_sangria
                            (id_tenant, id_caixa, id_conta_corrente, id_usuario, valor, observacao)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?)
                        RETURNING id_sangria
                        """)
                .params(caixa.idCaixa(), idConta, idUsuario, valor, observacao)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        INSERT INTO caixa_detalhe
                            (id_tenant, id_caixa, id_carteira, id_sangria, id_plano_contas, valor,
                             tipo_operacao, credito_debito, observacoes)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, 'DEBITO_CAIXA', 'D', ?)
                        """)
                .params(caixa.idCaixa(), caixa.idCarteira(), idSangria, req.idPlanoContas().trim(), valor,
                        "Sangria nº " + idSangria + " para a conta " + idConta)
                .update();

        // A data do movimento bancário é HOJE no fuso da LOJA — `now()` cru cairia no dia seguinte
        // depois das 21h de Brasília, e o extrato mostraria o depósito num dia em que a loja
        // estava fechada.
        OffsetDateTime agora = OffsetDateTime.now();
        jdbc.sql("""
                        INSERT INTO conta_corrente_movimento
                            (id_tenant, id_conta_corrente, id_sangria, id_plano_contas, data_movimento,
                             numero_documento, credito_debito, valor, observacao)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, 'C', ?, ?)
                        """)
                .params(idConta, idSangria, req.idPlanoContas().trim(), agora,
                        "SANGRIA " + idSangria, valor,
                        "Sangria do caixa nº " + caixa.idCaixa()
                                + " em " + fusoDaLoja.hoje(jwt))
                .update();

        return listarDoCaixaAberto(jwt).stream()
                .filter(s -> s.idSangria() == idSangria)
                .findFirst()
                .orElseThrow();
    }
}
