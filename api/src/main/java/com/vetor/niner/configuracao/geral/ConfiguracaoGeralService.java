package com.vetor.niner.configuracao.geral;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralRequest;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
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
                   multa_crediario_dias, multa_crediario, cfg_usa_cor_grade,
                   cfg_permite_qtd_decimal, cfg_exige_numero_venda_devolucao,
                   cfg_rateia_frete_entrada, cfg_reajusta_preco_entrada,
                   id_plano_contas_compra_mercadoria, atualizado_em
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

    /**
     * Só a flag de cor/grade, sem checagem de papel — usado por {@code catalogo.Produto}
     * (qualquer papel que cadastra produto precisa saber se o campo "Grade" aparece no
     * formulário, diferente do restante de {@code cfg_geral}, que é ADMIN-only) e pela Emissão
     * de Etiqueta. {@code cfg_geral} nasce no signup (`SignupService`), mas o fallback
     * {@code false} evita 404 caso a linha nunca tenha sido criada (e preserva o padrão: sem
     * configuração explícita, o tenant não usa cor/grade).
     */
    @Transactional(readOnly = true)
    public boolean usaCorGrade() {
        return jdbc.sql("""
                        SELECT cfg_usa_cor_grade FROM cfg_geral
                        WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * Só o percentual de desconto promocional, sem checagem de papel — usado pelo PDV (F5,
     * `PdvVendaService`) pra exibir "Desconto Promocional" antes de efetivar a venda. Mesmo
     * fallback de {@code usaCorGrade}: {@code 0} evita 404 caso a linha nunca tenha sido criada.
     */
    @Transactional(readOnly = true)
    public BigDecimal percentualDescontoVenda() {
        return jdbc.sql("""
                        SELECT percentual_desconto_venda FROM cfg_geral
                        WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query(BigDecimal.class)
                .optional()
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Só a flag de quantidade decimal, sem checagem de papel — usada por PDV, Transferência de
     * Produtos e Histórico do Cliente pra formatar/validar quantidade de produto. Mesmo
     * fallback de {@code usaCorGrade}/{@code percentualDescontoVenda}: {@code true} evita 404
     * caso a linha nunca tenha sido criada (e preserva o comportamento de hoje, que já aceita
     * quantidade decimal em qualquer lugar).
     */
    @Transactional(readOnly = true)
    public boolean permiteQtdDecimalProduto() {
        return jdbc.sql("""
                        SELECT cfg_permite_qtd_decimal FROM cfg_geral
                        WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query(Boolean.class)
                .optional()
                .orElse(true);
    }

    /**
     * Só a flag de exigência do número da venda na Devolução de Produtos, sem checagem de papel —
     * usada pela própria tela (`vendas.devolucao`, front e back) pra saber se o campo "Número da
     * Venda" é obrigatório. Mesmo fallback das outras flags leves: {@code false} evita 404 caso a
     * linha nunca tenha sido criada, e preserva o comportamento de sempre (opcional).
     */
    @Transactional(readOnly = true)
    public boolean exigeNumeroVendaDevolucao() {
        return jdbc.sql("""
                        SELECT cfg_exige_numero_venda_devolucao FROM cfg_geral
                        WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * Só a flag de rateio de frete/IPI/ICMS-ST no custo, sem checagem de papel — usada pela
     * Entrada de Produtos por Compra. Mesmo fallback das outras flags leves: {@code false}
     * preserva o comportamento de sempre (sem rateio) quando a linha nunca foi criada.
     */
    @Transactional(readOnly = true)
    public boolean rateiaFreteEntrada() {
        return jdbc.sql("""
                        SELECT cfg_rateia_frete_entrada FROM cfg_geral
                        WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * Só a flag de reajuste automático de preço na entrada, sem checagem de papel — usada pela
     * Entrada de Produtos por Compra. Mesmo fallback: {@code false} preserva o comportamento de
     * sempre (preço do produto não muda sozinho) quando a linha nunca foi criada.
     */
    @Transactional(readOnly = true)
    public boolean reajustaPrecoEntrada() {
        return jdbc.sql("""
                        SELECT cfg_reajusta_preco_entrada FROM cfg_geral
                        WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * Só o plano de contas usado nas contas a pagar geradas pela Entrada de Produtos por
     * Compra, sem checagem de papel — a linha nasce no signup/migration V032 (nunca deveria
     * faltar), mas o fallback preserva a conta padrão do sistema caso, por algum motivo, a
     * linha ainda não exista.
     */
    @Transactional(readOnly = true)
    public String idPlanoContasCompraMercadoria() {
        return jdbc.sql("""
                        SELECT id_plano_contas_compra_mercadoria FROM cfg_geral
                        WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query(String.class)
                .optional()
                .orElse("3.03.001");
    }

    @Transactional
    public ConfiguracaoGeralResponse atualizar(Jwt jwt, ConfiguracaoGeralRequest req) {
        exigirAdmin(jwt);
        int linhas = jdbc.sql("""
                        UPDATE cfg_geral SET
                            percentual_desconto_venda = ?, juros_crediario_dias = ?, juros_crediario = ?,
                            multa_crediario_dias = ?, multa_crediario = ?, cfg_usa_cor_grade = ?,
                            cfg_permite_qtd_decimal = ?, cfg_exige_numero_venda_devolucao = ?,
                            cfg_rateia_frete_entrada = ?, cfg_reajusta_preco_entrada = ?,
                            id_plano_contas_compra_mercadoria = ?, atualizado_em = now()
                        WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .params(List.of(
                        req.percentualDescontoVenda(), req.jurosCrediarioDias(), req.jurosCrediario(),
                        req.multaCrediarioDias(), req.multaCrediario(), req.cfgUsaCorGrade(),
                        req.cfgPermiteQtdDecimal(), req.cfgExigeNumeroVendaDevolucao(),
                        req.cfgRateiaFreteEntrada(), req.cfgReajustaPrecoEntrada(),
                        req.idPlanoContasCompraMercadoria()))
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
                rs.getBoolean("cfg_usa_cor_grade"),
                rs.getBoolean("cfg_permite_qtd_decimal"),
                rs.getBoolean("cfg_exige_numero_venda_devolucao"),
                rs.getBoolean("cfg_rateia_frete_entrada"),
                rs.getBoolean("cfg_reajusta_preco_entrada"),
                rs.getString("id_plano_contas_compra_mercadoria"),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
