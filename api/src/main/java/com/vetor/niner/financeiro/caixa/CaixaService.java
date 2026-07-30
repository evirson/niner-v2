package com.vetor.niner.financeiro.caixa;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.financeiro.caixa.CaixaDtos.AbrirCaixaRequest;
import com.vetor.niner.financeiro.caixa.CaixaDtos.CaixaStatusResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.CarteiraParaAberturaResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.FecharCaixaRequest;
import com.vetor.niner.financeiro.caixa.CaixaDtos.FechamentoCaixaResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.LinhaTotalCarteiraResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Abertura de Caixa (2026-07-30) — um {@code caixa_mestre} por usuário/empresa/dia. PDV e
 * Recebimento de Crediário exigem caixa aberto antes de efetivar (ver {@link
 * #idCaixaAbertoObrigatorio}); antes dessa data, {@code RecebimentoCrediarioService} abria o
 * caixa sozinho, em silêncio, sempre com saldo zero — agora a abertura é sempre um passo
 * explícito (tela dedicada ou popup), pedindo o saldo inicial e a moeda (tipo de carteira).
 */
@Service
public class CaixaService {

    private final JdbcClient jdbc;

    public CaixaService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CaixaStatusResponse status(Jwt jwt) {
        return buscarCaixaAbertoHoje(idEmpresa(jwt), idUsuario(jwt))
                .orElseGet(() -> new CaixaStatusResponse(false, null, null, null, null, null));
    }

    @Transactional(readOnly = true)
    public List<CarteiraParaAberturaResponse> listarCarteirasParaAbertura() {
        return jdbc.sql("""
                        SELECT id_carteira, nome_carteira FROM tipo_carteira
                        WHERE id_tenant = plataforma.tenant_atual()
                        ORDER BY nome_carteira ASC
                        """)
                .query((rs, n) -> new CarteiraParaAberturaResponse(rs.getLong("id_carteira"), rs.getString("nome_carteira")))
                .list();
    }

    @Transactional
    public CaixaStatusResponse abrir(Jwt jwt, AbrirCaixaRequest req) {
        long idEmpresa = idEmpresa(jwt);
        long idUsuario = idUsuario(jwt);
        if (buscarCaixaAbertoHoje(idEmpresa, idUsuario).isPresent()) {
            throw new ConflitoDadosException("Já existe um caixa aberto hoje para este usuário nesta empresa.");
        }
        validarCarteira(req.idCarteira());

        jdbc.sql("""
                        INSERT INTO caixa_mestre (id_tenant, id_empresa, id_usuario, id_carteira, saldo_inicial)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?)
                        """)
                .params(idEmpresa, idUsuario, req.idCarteira(), req.saldoInicial())
                .update();

        return status(jwt);
    }

    /**
     * Usado por PDV/Recebimento de Crediário antes de efetivar — nunca abre o caixa sozinho
     * (diferente do comportamento antigo de {@code RecebimentoCrediarioService}); a tela é
     * responsável por pedir a abertura antes de chegar aqui. Serve de rede de segurança contra
     * chamada direta à API sem passar pela tela.
     */
    @Transactional(readOnly = true)
    public long idCaixaAbertoObrigatorio(long idEmpresa, long idUsuario) {
        return buscarCaixaAbertoHoje(idEmpresa, idUsuario)
                .map(CaixaStatusResponse::idCaixa)
                .orElseThrow(() -> new IllegalStateException(
                        "Não há caixa aberto hoje para este usuário — abra o caixa antes de continuar."));
    }

    private Optional<CaixaStatusResponse> buscarCaixaAbertoHoje(long idEmpresa, long idUsuario) {
        return jdbc.sql("""
                        SELECT cm.id_caixa, cm.data_abertura, cm.id_carteira, tc.nome_carteira, cm.saldo_inicial
                        FROM caixa_mestre cm
                        JOIN tipo_carteira tc ON tc.id_carteira = cm.id_carteira AND tc.id_tenant = cm.id_tenant
                        WHERE cm.id_tenant = plataforma.tenant_atual() AND cm.id_empresa = ? AND cm.id_usuario = ?
                              AND cm.caixa_fechado = false AND cm.data_abertura::date = CURRENT_DATE
                        ORDER BY cm.data_abertura DESC LIMIT 1
                        """)
                .params(idEmpresa, idUsuario)
                .query((rs, n) -> new CaixaStatusResponse(
                        true, rs.getLong("id_caixa"), rs.getObject("data_abertura", OffsetDateTime.class),
                        rs.getLong("id_carteira"), rs.getString("nome_carteira"), rs.getBigDecimal("saldo_inicial")))
                .optional();
    }

    private void validarCarteira(long idCarteira) {
        jdbc.sql("SELECT id_carteira FROM tipo_carteira WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?")
                .param(idCarteira).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Tipo de carteira informado não existe."));
    }

    /**
     * Fechamento de Caixa (2026-07-30) — busca o caixa de um usuário/empresa/dia (aberto ou já
     * fechado, pra permitir reabrir a tela e reimprimir depois de fechado) com os totais por
     * tipo de carteira. ADMIN pode consultar/fechar o caixa de qualquer usuário; OPERADOR só o
     * próprio (mesma checagem de {@code UsuarioService}/{@code ConfiguracaoGeralService}).
     */
    @Transactional(readOnly = true)
    public FechamentoCaixaResponse buscarParaFechamento(Jwt jwt, Long idUsuarioParam, LocalDate data) {
        long idEmpresa = idEmpresa(jwt);
        long idUsuarioAlvo = idUsuarioParam != null ? idUsuarioParam : idUsuario(jwt);
        if (idUsuarioAlvo != idUsuario(jwt)) {
            exigirAdmin(jwt);
        }

        Caixa caixa = buscarCaixaDoDia(idEmpresa, idUsuarioAlvo, data)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Não há caixa aberto ou fechado para este usuário nesta data."));
        return montarFechamento(caixa);
    }

    /**
     * Fecha o caixa: grava o valor contado em dinheiro (conferência, nunca recalculado — é o
     * que o operador realmente contou) e quem fechou (pode ser diferente de quem abriu, se
     * ADMIN fechando o caixa de outro usuário). 409 se já estiver fechado.
     */
    @Transactional
    public FechamentoCaixaResponse fechar(Jwt jwt, FecharCaixaRequest req) {
        Caixa caixa = jdbc.sql("""
                        SELECT id_caixa, id_empresa, id_usuario, id_carteira, saldo_inicial,
                               data_abertura, data_fechamento, caixa_fechado, valor_contado_dinheiro
                        FROM caixa_mestre
                        WHERE id_tenant = plataforma.tenant_atual() AND id_caixa = ?
                        """)
                .param(req.idCaixa())
                .query(CaixaService::mapearCaixa)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Caixa não encontrado."));

        if (caixa.idUsuario() != idUsuario(jwt)) {
            exigirAdmin(jwt);
        }
        if (caixa.fechado()) {
            throw new ConflitoDadosException("Este caixa já foi fechado.");
        }

        jdbc.sql("""
                        UPDATE caixa_mestre SET
                            caixa_fechado = true, data_fechamento = now(),
                            valor_contado_dinheiro = ?, id_usuario_fechamento = ?
                        WHERE id_tenant = plataforma.tenant_atual() AND id_caixa = ?
                        """)
                .params(req.valorContadoDinheiro(), idUsuario(jwt), req.idCaixa())
                .update();

        return buscarParaFechamento(jwt, caixa.idUsuario(), caixa.dataAbertura().toLocalDate());
    }

    private Optional<Caixa> buscarCaixaDoDia(long idEmpresa, long idUsuario, LocalDate data) {
        return jdbc.sql("""
                        SELECT id_caixa, id_empresa, id_usuario, id_carteira, saldo_inicial,
                               data_abertura, data_fechamento, caixa_fechado, valor_contado_dinheiro
                        FROM caixa_mestre
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND id_usuario = ?
                              AND data_abertura::date = ?
                        ORDER BY data_abertura DESC LIMIT 1
                        """)
                .params(idEmpresa, idUsuario, data)
                .query(CaixaService::mapearCaixa)
                .optional();
    }

    /** Monta a resposta completa: cabeçalho do caixa + uma linha por tipo de carteira usado
     *  (mais a carteira de abertura, mesmo sem nenhum lançamento). */
    private FechamentoCaixaResponse montarFechamento(Caixa caixa) {
        record Cabecalho(String nomeUsuario, String nomeEmpresa, String nomeCarteiraAbertura) {
        }
        Cabecalho cabecalho = jdbc.sql("""
                        SELECT u.nome_usuario AS nome_usuario, e.razao_social AS nome_empresa, tc.nome_carteira
                        FROM usuario u, empresa e, tipo_carteira tc
                        WHERE u.id_tenant = plataforma.tenant_atual() AND u.id_usuario = ?
                              AND e.id_tenant = plataforma.tenant_atual() AND e.id_empresa = ?
                              AND tc.id_tenant = plataforma.tenant_atual() AND tc.id_carteira = ?
                        """)
                .params(caixa.idUsuario(), caixa.idEmpresa(), caixa.idCarteira())
                .query((rs, n) -> new Cabecalho(rs.getString("nome_usuario"), rs.getString("nome_empresa"), rs.getString("nome_carteira")))
                .single();

        record Total(long idCarteira, String nomeCarteira, String creditoDebito, BigDecimal total) {
        }
        List<Total> totais = jdbc.sql("""
                        SELECT cd.id_carteira, tc.nome_carteira, cd.credito_debito::text AS credito_debito, SUM(cd.valor) AS total
                        FROM caixa_detalhe cd
                        JOIN tipo_carteira tc ON tc.id_carteira = cd.id_carteira AND tc.id_tenant = cd.id_tenant
                        WHERE cd.id_tenant = plataforma.tenant_atual() AND cd.id_caixa = ?
                        GROUP BY cd.id_carteira, tc.nome_carteira, cd.credito_debito
                        """)
                .param(caixa.idCaixa())
                .query((rs, n) -> new Total(
                        rs.getLong("id_carteira"), rs.getString("nome_carteira"),
                        rs.getString("credito_debito"), rs.getBigDecimal("total")))
                .list();

        record Acumulado(String nomeCarteira, BigDecimal credito, BigDecimal debito) {
        }
        Map<Long, Acumulado> porCarteira = new LinkedHashMap<>();
        // a carteira de abertura sempre aparece, mesmo sem nenhum lançamento (saldo_inicial sozinho).
        porCarteira.put(caixa.idCarteira(), new Acumulado(cabecalho.nomeCarteiraAbertura(), BigDecimal.ZERO, BigDecimal.ZERO));
        for (Total t : totais) {
            Acumulado atual = porCarteira.getOrDefault(t.idCarteira(), new Acumulado(t.nomeCarteira(), BigDecimal.ZERO, BigDecimal.ZERO));
            porCarteira.put(t.idCarteira(), "C".equals(t.creditoDebito())
                    ? new Acumulado(atual.nomeCarteira(), atual.credito().add(t.total()), atual.debito())
                    : new Acumulado(atual.nomeCarteira(), atual.credito(), atual.debito().add(t.total())));
        }

        List<LinhaTotalCarteiraResponse> linhas = new ArrayList<>();
        for (Map.Entry<Long, Acumulado> entrada : porCarteira.entrySet()) {
            BigDecimal saldoInicial = entrada.getKey() == caixa.idCarteira() ? caixa.saldoInicial() : BigDecimal.ZERO;
            BigDecimal esperado = saldoInicial.add(entrada.getValue().credito()).subtract(entrada.getValue().debito());
            linhas.add(new LinhaTotalCarteiraResponse(
                    entrada.getKey(), entrada.getValue().nomeCarteira(), saldoInicial,
                    entrada.getValue().credito(), entrada.getValue().debito(), esperado));
        }
        linhas.sort(Comparator.comparing(LinhaTotalCarteiraResponse::nomeCarteira));

        return new FechamentoCaixaResponse(
                caixa.idCaixa(), caixa.idUsuario(), cabecalho.nomeUsuario(), cabecalho.nomeEmpresa(),
                caixa.dataAbertura(), caixa.dataFechamento(), caixa.fechado(), linhas, caixa.valorContadoDinheiro());
    }

    private record Caixa(long idCaixa, long idEmpresa, long idUsuario, long idCarteira, BigDecimal saldoInicial,
                          OffsetDateTime dataAbertura, OffsetDateTime dataFechamento, boolean fechado,
                          BigDecimal valorContadoDinheiro) {
    }

    private static Caixa mapearCaixa(ResultSet rs, int rowNum) throws SQLException {
        return new Caixa(
                rs.getLong("id_caixa"), rs.getLong("id_empresa"), rs.getLong("id_usuario"), rs.getLong("id_carteira"),
                rs.getBigDecimal("saldo_inicial"), rs.getObject("data_abertura", OffsetDateTime.class),
                rs.getObject("data_fechamento", OffsetDateTime.class), rs.getBoolean("caixa_fechado"),
                rs.getBigDecimal("valor_contado_dinheiro"));
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas administradores podem consultar/fechar o caixa de outro usuário.");
        }
    }

    private static long idEmpresa(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }

    private static long idUsuario(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
