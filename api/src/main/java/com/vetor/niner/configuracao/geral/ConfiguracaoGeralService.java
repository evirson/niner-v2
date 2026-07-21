package com.vetor.niner.configuracao.geral;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralRequest;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Leitura/atualização de {@code cfg_geral} (docs/telas/configuracao-geral.md) — singleton por
 * tenant, criado no signup (`SignupService.assinar`). Sem criação/exclusão: só existe
 * {@code buscar}/{@code atualizar}, ambos restritos a ADMIN (decisão de produto — diferente
 * das telas de cadastro, onde OPERADOR também tem acesso).
 */
@Service
public class ConfiguracaoGeralService {

    private static final String SELECT_BASE = """
            SELECT percentual_desconto_venda, juros_crediario_dias, juros_crediario,
                   multa_crediario_dias, multa_crediario, cfg_usa_variante_linha,
                   cfg_usa_variante_coluna, atualizado_em
            FROM cfg_geral
            WHERE id_tenant = plataforma.tenant_atual()
            """;

    private final JdbcClient jdbc;

    public ConfiguracaoGeralService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ConfiguracaoGeralResponse buscar(Jwt jwt) {
        exigirAdmin(jwt);
        return buscarSemChecarPapel();
    }

    @Transactional
    public ConfiguracaoGeralResponse atualizar(Jwt jwt, ConfiguracaoGeralRequest req) {
        exigirAdmin(jwt);
        int linhas = jdbc.sql("""
                        UPDATE cfg_geral SET
                            percentual_desconto_venda = ?, juros_crediario_dias = ?, juros_crediario = ?,
                            multa_crediario_dias = ?, multa_crediario = ?, cfg_usa_variante_linha = ?,
                            cfg_usa_variante_coluna = ?, atualizado_em = now()
                        WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .params(List.of(
                        req.percentualDescontoVenda(), req.jurosCrediarioDias(), req.jurosCrediario(),
                        req.multaCrediarioDias(), req.multaCrediario(), req.cfgUsaVarianteLinha(),
                        req.cfgUsaVarianteColuna()))
                .update();
        // Não deveria acontecer — a linha nasce no signup — mas 404 é mais honesto que
        // seguir em frente como se tivesse atualizado algo.
        if (linhas == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Configuração geral não encontrada para este tenant.");
        }
        return buscarSemChecarPapel();
    }

    private ConfiguracaoGeralResponse buscarSemChecarPapel() {
        return jdbc.sql(SELECT_BASE)
                .query(ConfiguracaoGeralService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Configuração geral não encontrada para este tenant."));
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem acessar os parâmetros do sistema.");
        }
    }

    private static ConfiguracaoGeralResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new ConfiguracaoGeralResponse(
                rs.getBigDecimal("percentual_desconto_venda"),
                rs.getInt("juros_crediario_dias"),
                rs.getBigDecimal("juros_crediario"),
                rs.getInt("multa_crediario_dias"),
                rs.getBigDecimal("multa_crediario"),
                rs.getBoolean("cfg_usa_variante_linha"),
                rs.getBoolean("cfg_usa_variante_coluna"),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
