package com.vetor.niner.financeiro.caixa;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.financeiro.caixa.CaixaDtos.AbrirCaixaRequest;
import com.vetor.niner.financeiro.caixa.CaixaDtos.CaixaStatusResponse;
import com.vetor.niner.financeiro.caixa.CaixaDtos.CarteiraParaAberturaResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

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

    private static long idEmpresa(Jwt jwt) {
        return ((Number) jwt.getClaim("eid")).longValue();
    }

    private static long idUsuario(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
