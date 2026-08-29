package com.vetor.niner.fiscal.configuracao;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.comum.seguranca.SegredoCifrador;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.AmbienteFiscal;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.CscParaEmissao;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.EmpresaFiscalResponse;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.FiscalConfigRequest;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.FiscalConfigResponse;
import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.PendenciaAtivacao;

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
                   c.id_fiscal_config, c.crt,
                   c.emite_nfce, c.emite_nfe, c.ambiente::text AS ambiente,
                   c.serie_nfce, c.serie_nfe, c.serie_contingencia,
                   c.inscricao_estadual_st, c.suframa,
                   c.csc_id, c.csc_token_cifrado, c.versao_tabela_ibpt,
                   c.criado_em, c.atualizado_em
            FROM empresa e
            LEFT JOIN fiscal_config_empresa c
                   ON c.id_tenant = e.id_tenant AND c.id_empresa = e.id_empresa
            WHERE e.id_tenant = plataforma.tenant_atual()
              AND e.id_empresa = ?
            """;

    private final JdbcClient jdbc;
    private final SegredoCifrador cifrador;
    private final NinerProperties props;

    public FiscalConfigService(JdbcClient jdbc, SegredoCifrador cifrador, NinerProperties props) {
        this.props = props;
        this.jdbc = jdbc;
        this.cifrador = cifrador;
    }

    @Transactional(readOnly = true)
    public FiscalConfigResponse buscar(Jwt jwt, long idEmpresa) {
        return carregar(idEmpresa);
    }

    @Transactional(readOnly = true)
    public List<EmpresaFiscalResponse> listarEmpresas(Jwt jwt) {
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
        FiscalConfigResponse atual = carregar(idEmpresa);

        validarRegime(req);
        validarSeries(req);
        validarSerieImutavel(atual, req);

        String cscToken = resolverCscToken(atual, req);
        validarGates(idEmpresa, atual, req, cscToken);

        if (atual.configurado()) {
            jdbc.sql("""
                            UPDATE fiscal_config_empresa SET
                                crt = ?,
                                emite_nfce = ?, emite_nfe = ?, ambiente = ?::ambiente_fiscal,
                                serie_nfce = ?, serie_nfe = ?, serie_contingencia = ?,
                                inscricao_estadual_st = ?, suframa = ?,
                                csc_id = ?, csc_token_cifrado = ?, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                            """)
                    .params(req.crt(),
                            req.emiteNfce(), req.emiteNfe(), ambienteParaGravar(req).name(),
                            req.serieNfce(), req.serieNfe(), req.serieContingencia(),
                            req.inscricaoEstadualSt(), req.suframa(),
                            trimOuNulo(req.cscId()), cscToken, idEmpresa)
                    .update();
        } else {
            jdbc.sql("""
                            INSERT INTO fiscal_config_empresa (
                                id_tenant, id_empresa, crt,
                                emite_nfce, emite_nfe, ambiente,
                                serie_nfce, serie_nfe, serie_contingencia,
                                inscricao_estadual_st, suframa,
                                csc_id, csc_token_cifrado)
                            VALUES (plataforma.tenant_atual(), ?, ?,
                                    ?, ?, ?::ambiente_fiscal, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(idEmpresa, req.crt(),
                            req.emiteNfce(), req.emiteNfe(), ambienteParaGravar(req).name(),
                            req.serieNfce(), req.serieNfe(), req.serieContingencia(),
                            req.inscricaoEstadualSt(), req.suframa(),
                            trimOuNulo(req.cscId()), cscToken)
                    .update();
        }
        return carregar(idEmpresa);
    }

    // ---------------------------------------------------------------- validações

    /**
     * DF37: o Niner atende <b>Simples Nacional (CRT 1 e 2) e MEI (CRT 4)</b>. O CRT 3, Regime
     * Normal, é recusado com 400 — e a mensagem diz que é escopo de produto, não funcionalidade
     * faltando. Sem esse cuidado, alguém do Lucro Presumido cadastraria CRT 1 para "destravar" a
     * tela e passaria a emitir toda nota com CSOSN e PIS/COFINS zerado.
     *
     * <p>O mesmo domínio está no CHECK do banco (V035). Aqui é para a mensagem; lá é para valer.
     */
    private static void validarRegime(FiscalConfigRequest req) {
        if (!FiscalConfigDtos.CRT_ATENDIDOS.contains(req.crt())) {
            throw badRequest(("CRT %d fora do escopo do produto: o Niner atende Simples Nacional "
                    + "(CRT 1 e 2) e MEI (CRT 4). Lucro Real e Lucro Presumido não são atendidos.")
                    .formatted(req.crt()));
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
    /**
     * ⭐ <b>Instalação em produção não oferece escolha de ambiente</b> (decisão do dono do produto,
     * 2026-08-27): <i>"quando o sistema estiver em produção, o sistema de emissão de notas fiscais
     * não deverá ter a opção homologação ou produção — sempre vai ter que estar em produção,
     * travado nisso"</i>.
     *
     * <p>⚠️ <b>Por que travar isto importa mais do que parece.</b> A série já era imutável depois
     * da primeira nota autorizada; o ambiente não era, e trocá-lo faz as vendas seguintes saírem
     * com {@code tpAmb=2} — <b>sem valor jurídico</b> — enquanto o PDV segue dizendo "Nota
     * autorizada". Pior: {@code fiscal_numeracao} tem PK {@code (tenant, empresa, modelo, série)},
     * <b>sem ambiente</b>, então as notas de teste consomem números da sequência de produção e
     * abrem buracos que depois exigem inutilização formal.
     *
     * <p>Enquanto {@code niner.fiscal.ambiente-fixo} está vazio — que é o caso hoje, com o produto
     * homologando junto às SEFAZ dos estados — a escolha continua livre.
     */
    private AmbienteFiscal ambientePadrao() {
        String fixo = props.fiscal().ambienteFixo();
        return fixo == null || fixo.isBlank()
                ? AmbienteFiscal.HOMOLOGACAO
                : AmbienteFiscal.valueOf(fixo.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private boolean ambienteTravado() {
        String fixo = props.fiscal().ambienteFixo();
        return fixo != null && !fixo.isBlank();
    }

    /**
     * O ambiente que vai para o banco: o do request enquanto a instalação deixa escolher, o fixo
     * quando não deixa.
     *
     * <p>⚠️ <b>Sobrescreve em vez de recusar</b>, de propósito: recusar travaria a edição de
     * qualquer outro campo para quem tivesse ficado com HOMOLOGACAO gravado antes da virada — e a
     * resposta devolve o ambiente real, então a tela mostra a verdade na hora.
     */
    private AmbienteFiscal ambienteParaGravar(FiscalConfigRequest req) {
        return ambienteTravado() ? ambientePadrao() : req.ambiente();
    }

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
    private void validarGates(long idEmpresa, FiscalConfigResponse atual, FiscalConfigRequest req,
                              String cscToken) {
        boolean ligandoNfce = req.emiteNfce() && !atual.emiteNfce();
        boolean ligandoNfe = req.emiteNfe() && !atual.emiteNfe();
        if (!ligandoNfce && !ligandoNfe) {
            return;
        }
        // CSC só existe pro modelo 65 (QR Code da NFC-e) — a NF-e (modelo 55) não imprime QR pro
        // consumidor, então não trava por isso. Achado em 2026-08-19: sem este gate, dava pra
        // ligar emite_nfce sem CSC e todo QR Code impresso saía com "URL mal formatado" na SEFAZ.
        if (ligandoNfce && (vazio(trimOuNulo(req.cscId())) || cscToken == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não é possível ligar a emissão de NFC-e: CSC (Código de Segurança do "
                            + "Contribuinte) não configurado — obrigatório para o QR Code do cupom.");
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
     *
     * <p>⚠️ Até 2026-08-19 este método gravava {@code req.cscToken()} em texto puro — a coluna
     * chama {@code csc_token_cifrado}, mas nada cifrava (achado ao investigar o QR Code inválido
     * da NFC-e, que precisou ler o CSC de volta pela primeira vez). Agora cifra com
     * {@link SegredoCifrador} (P7/F7), mesmo padrão de {@code fiscal_certificado.senha_cifrada}.
     */
    private String resolverCscToken(FiscalConfigResponse atual, FiscalConfigRequest req) {
        if (Boolean.TRUE.equals(req.removerCsc())) {
            return null;
        }
        if (!vazio(req.cscToken())) {
            // ⚠️ **`trim` antes de cifrar** (auditoria 2026-08-29, rodada 3). O CSC tem 36
            // caracteres e é colado do portal da SEFAZ; levar um espaço ou uma quebra de linha
            // na seleção é o modo NORMAL de errar num campo desses. O campo é `type="password"`,
            // então nada aparece na tela, `cscConfigurado` vira `true`, e TODA NFC-e volta com
            // `cStat 464 — hash do QR Code difere do calculado`, que não menciona CSC em lugar
            // nenhum. Era o único campo do request que não passava por `trim` (os outros três
            // passam já no front).
            return cifrador.cifrar(req.cscToken().trim());
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

    /**
     * CSC decifrado, pronto para montar o QR Code online da NFC-e (NT 2015.002 v2:
     * {@code hashQRCode = SHA-1(chave+token)}) — único caminho de leitura do token, existe para
     * {@code EmissaoNfceService} (B7). Nunca exposto por endpoint (mesmo espírito do certificado).
     */
    @Transactional(readOnly = true)
    public CscParaEmissao carregarCscParaEmissao(long idEmpresa) {
        return jdbc.sql("""
                        SELECT csc_id, csc_token_cifrado FROM fiscal_config_empresa
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                        """)
                .param(idEmpresa)
                .query((rs, n) -> {
                    String id = rs.getString("csc_id");
                    String tokenCifrado = rs.getString("csc_token_cifrado");
                    if (vazio(id) || tokenCifrado == null) {
                        throw cscNaoConfigurado();
                    }
                    return new CscParaEmissao(id, cifrador.decifrar(tokenCifrado));
                })
                .optional()
                .orElseThrow(this::cscNaoConfigurado);
    }

    private ResponseStatusException cscNaoConfigurado() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "CSC (Código de Segurança do Contribuinte) não configurado para esta empresa — "
                        + "obrigatório para o QR Code da NFC-e. Configure em Configurações Fiscais.");
    }

    // ---------------------------------------------------------------- leitura

    private FiscalConfigResponse carregar(long idEmpresa) {
        FiscalConfigResponse base = jdbc.sql(SELECT_BASE)
                .param(idEmpresa)
                .query(FiscalConfigService::mapear)
                .optional()
                .orElseThrow(() -> naoEncontrada(idEmpresa));
        if (!base.configurado()) {
            return new FiscalConfigResponse(
                    base.idEmpresa(), base.razaoSocialEmpresa(), false, base.crt(),
                    base.emiteNfce(), base.emiteNfe(), ambientePadrao(),
                    base.serieNfce(), base.serieNfe(), base.serieContingencia(),
                    base.inscricaoEstadualSt(), base.suframa(),
                    base.cscId(), base.cscConfigurado(), base.versaoTabelaIbpt(),
                    false, false, ambienteTravado(), base.criadoEm(), base.atualizadoEm());
        }
        boolean nfceBloqueada = existeNumeracao(idEmpresa, 65, base.serieNfce());
        boolean nfeBloqueada = existeNumeracao(idEmpresa, 55, base.serieNfe());
        return new FiscalConfigResponse(
                base.idEmpresa(), base.razaoSocialEmpresa(), true, base.crt(),
                base.emiteNfce(), base.emiteNfe(), base.ambiente(),
                base.serieNfce(), base.serieNfe(), base.serieContingencia(),
                base.inscricaoEstadualSt(), base.suframa(),
                base.cscId(), base.cscConfigurado(), base.versaoTabelaIbpt(),
                nfceBloqueada, nfeBloqueada, ambienteTravado(), base.criadoEm(), base.atualizadoEm());
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
                    1, false, false, AmbienteFiscal.HOMOLOGACAO,
                    1, 1, 9, null, null, null, false, null,
                    false, false, false, null, null);
        }
        return new FiscalConfigResponse(
                rs.getLong("id_empresa"), rs.getString("razao_social"), true,
                rs.getInt("crt"),
                rs.getBoolean("emite_nfce"), rs.getBoolean("emite_nfe"),
                AmbienteFiscal.valueOf(rs.getString("ambiente")),
                rs.getInt("serie_nfce"), rs.getInt("serie_nfe"), rs.getInt("serie_contingencia"),
                rs.getString("inscricao_estadual_st"), rs.getString("suframa"),
                rs.getString("csc_id"),
                rs.getString("csc_token_cifrado") != null,   // F7: nunca o token, só se existe
                rs.getString("versao_tabela_ibpt"),
                false, false, false,
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

    /** {@code trim} que preserva {@code null} — o ID do CSC é colado do portal como o token. */
    private static String trimOuNulo(String valor) {
        return valor == null ? null : valor.trim();
    }
}
