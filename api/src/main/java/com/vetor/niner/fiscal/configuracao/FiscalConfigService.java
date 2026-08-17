package com.vetor.niner.fiscal.configuracao;

import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.AmbienteFiscal;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.EmpresaFiscalResponse;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.FiscalConfigRequest;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.FiscalConfigResponse;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.PendenciaAtivacao;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.RegimeApuracao;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuração fiscal <b>por empresa</b> (docs/telas/fiscal-configuracao.md) — não por tenant,
 * diferente de {@code cfg_geral}: cada loja tem IE, série e numeração próprias, e o ambiente
 * (homologação/produção) é por empresa, senão uma filial em teste derruba a nota da outra.
 *
 * <p>Duas diferenças de comportamento em relação a {@code ConfiguracaoGeralService}:
 * <ol>
 *   <li>A linha <b>pode não existir</b> — o signup não a semeia, e é assim que o F12 se cumpre
 *       ("fiscal desligado não muda o ERP"). O GET devolve 200 com defaults e
 *       {@code configurado=false}; o primeiro PUT cria (upsert).</li>
 *   <li>Ligar {@code emite_nfce}/{@code emite_nfe} passa pelo <b>gate do F11</b>: as precondições
 *       são conferidas no servidor e a recusa é 409 com a lista do que falta — nunca deixar a
 *       rejeição chegar no caixa.</li>
 * </ol>
 *
 * <p>P8/F8: toda query filtra {@code id_tenant = plataforma.tenant_atual()} <b>explicitamente no
 * texto do SQL</b>, além do RLS — inclusive os lookups por {@code id_empresa} vindos do path e os
 * {@code EXISTS} de certificado e numeração. Ver docs/infra/isolamento-tenant-rls.md.
 */
@Service
public class FiscalConfigService {

    private static final String SELECT_BASE = """
            SELECT e.id_empresa, e.razao_social,
                   c.id_fiscal_config, c.crt, c.regime_apuracao::text AS regime_apuracao,
                   c.emite_nfce, c.emite_nfe, c.ambiente::text AS ambiente,
                   c.serie_nfce, c.serie_nfe, c.serie_contingencia,
                   c.equiparado_industrial, c.inscricao_estadual_st, c.suframa,
                   c.csc_id, c.csc_token_cifrado, c.versao_tabela_ibpt,
                   c.criado_em, c.atualizado_em
            FROM empresa e
            LEFT JOIN fiscal_config_empresa c
                   ON c.id_tenant = e.id_tenant AND c.id_empresa = e.id_empresa
            WHERE e.id_tenant = plataforma.tenant_atual()
              AND e.id_empresa = ?
            """;

    private final JdbcClient jdbc;

    public FiscalConfigService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public FiscalConfigResponse buscar(Jwt jwt, long idEmpresa) {
        exigirAdmin(jwt);
        return carregar(idEmpresa);
    }

    @Transactional(readOnly = true)
    public List<EmpresaFiscalResponse> listarEmpresas(Jwt jwt) {
        exigirAdmin(jwt);
        return jdbc.sql("""
                        SELECT e.id_empresa, e.razao_social,
                               (c.id_fiscal_config IS NOT NULL) AS configurado,
                               COALESCE(c.emite_nfce, false) AS emite_nfce,
                               COALESCE(c.emite_nfe,  false) AS emite_nfe
                        FROM empresa e
                        LEFT JOIN fiscal_config_empresa c
                               ON c.id_tenant = e.id_tenant AND c.id_empresa = e.id_empresa
                        WHERE e.id_tenant = plataforma.tenant_atual()
                        ORDER BY e.razao_social
                        """)
                .query((rs, n) -> new EmpresaFiscalResponse(
                        rs.getLong("id_empresa"),
                        rs.getString("razao_social"),
                        rs.getBoolean("configurado"),
                        rs.getBoolean("emite_nfce"),
                        rs.getBoolean("emite_nfe")))
                .list();
    }

    /**
     * Cria ou atualiza a configuração da empresa. A ordem importa: valida tudo <b>antes</b> de
     * escrever, para que uma recusa não deixe estado meio gravado.
     */
    @Transactional
    public FiscalConfigResponse salvar(Jwt jwt, long idEmpresa, FiscalConfigRequest req) {
        exigirAdmin(jwt);
        FiscalConfigResponse atual = carregar(idEmpresa);

        validarRegime(req);
        validarSeries(req);
        validarSerieImutavel(atual, req);
        validarGates(idEmpresa, atual, req);

        String cscToken = resolverCscToken(atual, req);

        if (atual.configurado()) {
            jdbc.sql("""
                            UPDATE fiscal_config_empresa SET
                                crt = ?, regime_apuracao = ?::regime_apuracao,
                                emite_nfce = ?, emite_nfe = ?, ambiente = ?::ambiente_fiscal,
                                serie_nfce = ?, serie_nfe = ?, serie_contingencia = ?,
                                equiparado_industrial = ?, inscricao_estadual_st = ?, suframa = ?,
                                csc_id = ?, csc_token_cifrado = ?, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                            """)
                    .params(req.crt(), req.regimeApuracao().name(),
                            req.emiteNfce(), req.emiteNfe(), req.ambiente().name(),
                            req.serieNfce(), req.serieNfe(), req.serieContingencia(),
                            req.equiparadoIndustrial(), req.inscricaoEstadualSt(), req.suframa(),
                            req.cscId(), cscToken, idEmpresa)
                    .update();
        } else {
            jdbc.sql("""
                            INSERT INTO fiscal_config_empresa (
                                id_tenant, id_empresa, crt, regime_apuracao,
                                emite_nfce, emite_nfe, ambiente,
                                serie_nfce, serie_nfe, serie_contingencia,
                                equiparado_industrial, inscricao_estadual_st, suframa,
                                csc_id, csc_token_cifrado)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?::regime_apuracao,
                                    ?, ?, ?::ambiente_fiscal, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(idEmpresa, req.crt(), req.regimeApuracao().name(),
                            req.emiteNfce(), req.emiteNfe(), req.ambiente().name(),
                            req.serieNfce(), req.serieNfe(), req.serieContingencia(),
                            req.equiparadoIndustrial(), req.inscricaoEstadualSt(), req.suframa(),
                            req.cscId(), cscToken)
                    .update();
        }
        return carregar(idEmpresa);
    }

    // ---------------------------------------------------------------- validações

    /**
     * CRT e regime de apuração não são redundantes, e a combinação errada é silenciosa: uma
     * empresa CRT 3 gravada como SIMPLES faria o motor calcular PIS/COFINS zerado numa nota que
     * devia destacar 9,25%. Ver DF36 (docs/MODULOFISCAL.md §7.3/§8.3) — a alíquota vem daqui, não
     * do perfil fiscal do produto, justamente porque CRT 3 cobre Presumido e Real ao mesmo tempo.
     */
    private static void validarRegime(FiscalConfigRequest req) {
        boolean simplesOuMei = req.crt() == 1 || req.crt() == 2 || req.crt() == 4;
        if (simplesOuMei && req.regimeApuracao() != RegimeApuracao.SIMPLES) {
            throw badRequest("CRT %d (Simples Nacional/MEI) exige regime de apuração SIMPLES."
                    .formatted(req.crt()));
        }
        if (req.crt() == 3 && req.regimeApuracao() == RegimeApuracao.SIMPLES) {
            throw badRequest("CRT 3 (Regime Normal) exige regime de apuração PRESUMIDO ou REAL — "
                    + "é o regime que define a alíquota de PIS/COFINS.");
        }
    }

    /** DF33: série 1 normal, série 9 contingência. Iguais, a conferência fica impossível. */
    private static void validarSeries(FiscalConfigRequest req) {
        if (req.serieContingencia().equals(req.serieNfce())) {
            throw badRequest("A série de contingência não pode ser igual à série da NFC-e (%d)."
                    .formatted(req.serieNfce()));
        }
    }

    /**
     * F4: numeração é sequencial por (empresa, modelo, série), sem buraco e nunca reutilizada.
     * Trocar a série depois da primeira nota autorizada quebraria essa garantia — por isso o
     * campo vira somente-leitura na tela e o servidor recusa aqui também (P4).
     */
    private void validarSerieImutavel(FiscalConfigResponse atual, FiscalConfigRequest req) {
        if (!atual.configurado()) {
            return;
        }
        if (atual.serieNfceBloqueada() && atual.serieNfce() != req.serieNfce()) {
            throw conflito("A série da NFC-e não pode ser alterada: já existem notas emitidas na "
                    + "série %d.".formatted(atual.serieNfce()));
        }
        if (atual.serieNfeBloqueada() && atual.serieNfe() != req.serieNfe()) {
            throw conflito("A série da NF-e não pode ser alterada: já existem notas emitidas na "
                    + "série %d.".formatted(atual.serieNfe()));
        }
    }

    /**
     * Gate do F11 — bloqueio preventivo. Só corre quando um gate está sendo <b>ligado</b>:
     * desligar nunca é bloqueado (se o lojista quer parar de emitir, ele para), e manter ligado
     * também não, senão uma pendência superveniente travaria a edição de qualquer outro campo.
     */
    private void validarGates(long idEmpresa, FiscalConfigResponse atual, FiscalConfigRequest req) {
        boolean ligandoNfce = req.emiteNfce() && !atual.emiteNfce();
        boolean ligandoNfe = req.emiteNfe() && !atual.emiteNfe();
        if (!ligandoNfce && !ligandoNfe) {
            return;
        }
        List<PendenciaAtivacao> pendencias = pendenciasDeAtivacao(idEmpresa);
        if (!pendencias.isEmpty()) {
            String itens = pendencias.stream().map(PendenciaAtivacao::descricao)
                    .reduce((a, b) -> a + "; " + b).orElse("");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não é possível ligar a emissão: " + itens
                            + ". Veja a tela de Conformidade Fiscal para corrigir.");
        }
    }

    /**
     * As precondições do F11, numa consulta só — o painel da Conformidade Fiscal reaproveita o
     * mesmo vocabulário de {@code codigo}.
     */
    @Transactional(readOnly = true)
    public List<PendenciaAtivacao> pendenciasDeAtivacao(long idEmpresa) {
        var linha = jdbc.sql("""
                        SELECT e.cnpj, e.inscricao_estadual, e.codigo_municipio_ibge, e.cnae,
                               (SELECT count(*) FROM fiscal_certificado fc
                                 WHERE fc.id_tenant = e.id_tenant AND fc.id_empresa = e.id_empresa
                                   AND fc.ativo AND fc.valido_ate > now()) AS certificados_validos,
                               (SELECT count(*) FROM fiscal_certificado fc
                                 WHERE fc.id_tenant = e.id_tenant AND fc.id_empresa = e.id_empresa
                                   AND fc.ativo AND fc.valido_ate > now()
                                   AND fc.cnpj_titular IS DISTINCT FROM e.cnpj) AS certificados_de_outro_cnpj
                        FROM empresa e
                        WHERE e.id_tenant = plataforma.tenant_atual() AND e.id_empresa = ?
                        """)
                .param(idEmpresa)
                .query((rs, n) -> new Object[]{
                        rs.getString("cnpj"), rs.getString("inscricao_estadual"),
                        rs.getObject("codigo_municipio_ibge"), rs.getString("cnae"),
                        rs.getLong("certificados_validos"), rs.getLong("certificados_de_outro_cnpj")})
                .optional()
                .orElseThrow(() -> naoEncontrada(idEmpresa));

        List<PendenciaAtivacao> pendencias = new ArrayList<>();
        if (vazio((String) linha[0])) {
            pendencias.add(new PendenciaAtivacao("EMPRESA_SEM_CNPJ",
                    "empresa sem CNPJ", "identidade.empresa"));
        }
        if (vazio((String) linha[1])) {
            pendencias.add(new PendenciaAtivacao("EMPRESA_SEM_IE",
                    "empresa sem Inscrição Estadual", "identidade.empresa"));
        }
        if (linha[2] == null) {
            pendencias.add(new PendenciaAtivacao("EMPRESA_SEM_MUNICIPIO_IBGE",
                    "empresa sem código de município IBGE", "identidade.empresa"));
        }
        if (vazio((String) linha[3])) {
            pendencias.add(new PendenciaAtivacao("EMPRESA_SEM_CNAE",
                    "empresa sem CNAE", "identidade.empresa"));
        }
        if ((long) linha[4] == 0L) {
            pendencias.add(new PendenciaAtivacao("SEM_CERTIFICADO_VALIDO",
                    "nenhum certificado digital ativo e dentro da validade", "fiscal.certificado"));
        } else if ((long) linha[5] > 0L) {
            pendencias.add(new PendenciaAtivacao("CERTIFICADO_DE_OUTRO_CNPJ",
                    "o certificado ativo é de outro CNPJ", "fiscal.certificado"));
        }
        return pendencias;
    }

    /**
     * Write-only: token ausente <b>preserva</b> o que já está gravado; apagar exige
     * {@code removerCsc}. Sem essa distinção, um PUT que só muda a série zeraria o CSC em
     * silêncio — e o lojista só descobriria no credenciamento.
     */
    private String resolverCscToken(FiscalConfigResponse atual, FiscalConfigRequest req) {
        if (Boolean.TRUE.equals(req.removerCsc())) {
            return null;
        }
        if (!vazio(req.cscToken())) {
            return req.cscToken();
        }
        if (!atual.configurado() || !atual.cscConfigurado()) {
            return null;
        }
        return jdbc.sql("""
                        SELECT csc_token_cifrado FROM fiscal_config_empresa
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                        """)
                .param(atual.idEmpresa())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    // ---------------------------------------------------------------- leitura

    private FiscalConfigResponse carregar(long idEmpresa) {
        FiscalConfigResponse base = jdbc.sql(SELECT_BASE)
                .param(idEmpresa)
                .query(FiscalConfigService::mapear)
                .optional()
                .orElseThrow(() -> naoEncontrada(idEmpresa));
        if (!base.configurado()) {
            return base;
        }
        boolean nfceBloqueada = existeNumeracao(idEmpresa, 65, base.serieNfce());
        boolean nfeBloqueada = existeNumeracao(idEmpresa, 55, base.serieNfe());
        return new FiscalConfigResponse(
                base.idEmpresa(), base.razaoSocialEmpresa(), true, base.crt(), base.regimeApuracao(),
                base.emiteNfce(), base.emiteNfe(), base.ambiente(),
                base.serieNfce(), base.serieNfe(), base.serieContingencia(),
                base.equiparadoIndustrial(), base.inscricaoEstadualSt(), base.suframa(),
                base.cscId(), base.cscConfigurado(), base.versaoTabelaIbpt(),
                nfceBloqueada, nfeBloqueada, base.criadoEm(), base.atualizadoEm());
    }

    private boolean existeNumeracao(long idEmpresa, int modelo, int serie) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT exists(SELECT 1 FROM fiscal_numeracao
                                       WHERE id_tenant = plataforma.tenant_atual()
                                         AND id_empresa = ? AND modelo = ? AND serie = ?
                                         AND proximo_numero > 1)
                        """)
                .params(idEmpresa, modelo, serie)
                .query(Boolean.class)
                .single());
    }

    private static FiscalConfigResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        boolean configurado = rs.getObject("id_fiscal_config") != null;
        if (!configurado) {
            // Defaults do banco (V035), para a tela renderizar sem 404 nem campo vazio.
            return new FiscalConfigResponse(
                    rs.getLong("id_empresa"), rs.getString("razao_social"), false,
                    1, RegimeApuracao.SIMPLES, false, false, AmbienteFiscal.HOMOLOGACAO,
                    1, 1, 9, false, null, null, null, false, null,
                    false, false, null, null);
        }
        return new FiscalConfigResponse(
                rs.getLong("id_empresa"), rs.getString("razao_social"), true,
                rs.getInt("crt"),
                RegimeApuracao.valueOf(rs.getString("regime_apuracao")),
                rs.getBoolean("emite_nfce"), rs.getBoolean("emite_nfe"),
                AmbienteFiscal.valueOf(rs.getString("ambiente")),
                rs.getInt("serie_nfce"), rs.getInt("serie_nfe"), rs.getInt("serie_contingencia"),
                rs.getBoolean("equiparado_industrial"),
                rs.getString("inscricao_estadual_st"), rs.getString("suframa"),
                rs.getString("csc_id"),
                rs.getString("csc_token_cifrado") != null,   // F7: nunca o token, só se existe
                rs.getString("versao_tabela_ibpt"),
                false, false,
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }

    // ---------------------------------------------------------------- auxiliares

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem acessar a configuração fiscal.");
        }
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException badRequest(String motivo) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, motivo);
    }

    private static ResponseStatusException conflito(String motivo) {
        return new ResponseStatusException(HttpStatus.CONFLICT, motivo);
    }

    private static ResponseStatusException naoEncontrada(long idEmpresa) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Empresa %d não encontrada neste tenant.".formatted(idEmpresa));
    }
}
