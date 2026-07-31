package com.vetor.niner.cadastros.planocontas;

import com.vetor.niner.cadastros.planocontas.PlanoContasDtos.ExclusaoPlanoContasResponse;
import com.vetor.niner.cadastros.planocontas.PlanoContasDtos.PaginaPlanosContas;
import com.vetor.niner.cadastros.planocontas.PlanoContasDtos.PlanoContasRequest;
import com.vetor.niner.cadastros.planocontas.PlanoContasDtos.PlanoContasResponse;
import com.vetor.niner.comum.web.ConflitoDadosException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de plano de contas (docs/telas/plano-contas.md). Tabela {@code cfg_plano_contas} sob
 * RLS de tenant (V016/V024) — revisado por completo em 2026-07-31: DRE e fluxo de caixa
 * deixaram de ser flags soltas e viraram classificação de verdade (grupo + sinal), com
 * hierarquia de 4 níveis via máscara fixa {@code 9.99.999.999}. PK de negócio {@code text}
 * (o código contábil, digitado pelo usuário e imutável após a criação) continua igual à
 * versão anterior; a novidade estrutural é que a tabela agora tem {@code ativo}, então este
 * cadastro ganhou o mesmo fallback de inativar na exclusão que os demais já tinham (antes
 * era a exceção que só excluía de verdade).
 *
 * <p>Nível ({@code nivel}), pai ({@code id_plano_contas_pai}), {@code sinal} e
 * {@code aceita_lancamento} são sempre derivados no banco/serviço a partir do código e dos
 * campos informados — nunca vêm do cliente (mesmo espírito de "campo calculado, não
 * confiável vindo de fora" já usado em outras telas).
 *
 * <p>Não há registro em {@code cfg_tela_campo} para esta tela: os campos realmente
 * obrigatórios (código/descrição/tipo de movimento/natureza) são estruturalmente NOT NULL,
 * o resto é opcional por natureza — nada configurável.
 */
@Service
public class PlanoContasService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    private static final Pattern MASCARA_CODIGO = Pattern.compile("^[1-9]\\.[0-9]{2}\\.[0-9]{3}\\.[0-9]{3}$");

    /** Valores dos ENUMs do banco (V013) — sem acento desde 2026-07-31. */
    private static final Set<String> TIPOS_MOVIMENTO = Set.of("CREDITO", "DEBITO", "NEUTRO");
    private static final Set<String> NATUREZAS = Set.of("SINTETICA", "ANALITICA");
    private static final Set<String> GRUPOS_DRE = Set.of(
            "RECEITA_BRUTA", "DEDUCOES", "CUSTO_VARIAVEL", "DESPESA_FIXA", "DEPRECIACAO",
            "RESULTADO_FINANCEIRO", "NAO_OPERACIONAL", "TRIBUTO_LUCRO");
    private static final Set<String> GRUPOS_DFC = Set.of("OPERACIONAL", "INVESTIMENTO", "FINANCIAMENTO");

    /** Colunas ordenáveis da listagem — chave da API -> expressão SQL. */
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.ofEntries(
            Map.entry("codigo", "p.id_plano_contas"),
            Map.entry("descricao", "p.descricao"),
            Map.entry("tipoMovimento", "p.tipo_movimento"),
            Map.entry("natureza", "p.natureza"),
            Map.entry("nivel", "p.nivel"),
            Map.entry("incluiDre", "p.inclui_dre"),
            Map.entry("incluiFluxoCaixa", "p.inclui_fluxo_caixa"),
            Map.entry("status", "p.ativo"));

    private final JdbcClient jdbc;

    public PlanoContasService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PaginaPlanosContas listar(String busca, String status, Integer pagina, Integer limite,
                                      String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao =
                ordenarPor == null ? "p.id_plano_contas" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "p.id_plano_contas");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        // Filtro por id_tenant explícito além do RLS — a PK é composta (id_tenant,
        // id_plano_contas), então o MESMO código existe em tenants diferentes; sem este
        // filtro, o ambiente de teste (datasource conecta como superusuário, sem RLS)
        // enxergaria códigos de outros tenants. Mesmo precedente de ConfiguracaoTelaService.
        StringBuilder filtro = new StringBuilder(" WHERE p.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();

        if (busca != null && !busca.isBlank()) {
            // Busca única cobre código e descrição — o usuário pode digitar "3.03" ou "ALUGUEL".
            filtro.append(" AND (p.id_plano_contas ILIKE ? OR p.descricao ILIKE ?)");
            String padrao = "%" + busca.trim() + "%";
            params.add(padrao);
            params.add(padrao);
        }
        switch (status == null ? "ATIVOS" : status.toUpperCase(Locale.ROOT)) {
            case "INATIVOS" -> filtro.append(" AND p.ativo = false");
            case "TODOS" -> { /* sem filtro de status */ }
            default -> filtro.append(" AND p.ativo = true");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM cfg_plano_contas p" + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", p.id_plano_contas " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<PlanoContasResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
                .params(paramsPagina)
                .query(PlanoContasService::mapear)
                .list();

        return new PaginaPlanosContas(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public PlanoContasResponse buscar(String codigo) {
        return jdbc.sql(SELECT_BASE + " WHERE p.id_tenant = plataforma.tenant_atual() AND p.id_plano_contas = ?")
                .param(normalizarCodigo(codigo))
                .query(PlanoContasService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Plano de contas não encontrado."));
    }

    @Transactional
    public PlanoContasResponse criar(PlanoContasRequest req) {
        validar(req);
        String codigo = normalizarCodigo(req.codigo());
        String pai = calcularPai(codigo);
        if (pai != null && !existeConta(pai)) {
            throw new IllegalArgumentException(
                    "Crie primeiro a conta pai " + pai + " (a hierarquia precisa existir de cima para baixo).");
        }

        boolean incluiDre = Boolean.TRUE.equals(req.incluiDre());
        boolean incluiCx = Boolean.TRUE.equals(req.incluiFluxoCaixa());
        String natureza = req.natureza().toUpperCase(Locale.ROOT);

        try {
            jdbc.sql("""
                            INSERT INTO cfg_plano_contas (id_tenant, id_plano_contas, descricao, descricao_curta,
                                tipo_movimento, natureza, inclui_dre, grupo_dre, inclui_fluxo_caixa, grupo_dfc,
                                sinal, aceita_lancamento, exige_centro_custo, exige_contraparte, exige_documento,
                                id_conta_contabil, id_plano_referencial, observacao)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?::tipo_movimento_conta, ?::natureza_conta,
                                ?, ?::grupo_dre_conta, ?, ?::grupo_dfc_conta, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(codigo,
                            req.descricao().trim().toUpperCase(Locale.ROOT),
                            textoOuNulo(req.descricaoCurta()),
                            req.tipoMovimento().toUpperCase(Locale.ROOT),
                            natureza,
                            incluiDre, incluiDre ? req.grupoDre() : "NAO_APLICA",
                            incluiCx, incluiCx ? req.grupoDfc() : "NAO_APLICA",
                            calcularSinal(req.tipoMovimento()),
                            "ANALITICA".equals(natureza),
                            Boolean.TRUE.equals(req.exigeCentroCusto()),
                            Boolean.TRUE.equals(req.exigeContraparte()),
                            Boolean.TRUE.equals(req.exigeDocumento()),
                            textoOuNulo(req.idContaContabil()),
                            textoOuNulo(req.idPlanoReferencial()),
                            textoOuNulo(req.observacao()))
                    .update();
            return buscar(codigo);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um plano de contas com esse código.");
        }
    }

    /** O código (PK) não é editável — o do path prevalece; o do corpo é ignorado. */
    @Transactional
    public PlanoContasResponse atualizar(String codigo, PlanoContasRequest req) {
        validar(req);
        String codigoNormalizado = normalizarCodigo(codigo);
        boolean incluiDre = Boolean.TRUE.equals(req.incluiDre());
        boolean incluiCx = Boolean.TRUE.equals(req.incluiFluxoCaixa());
        String natureza = req.natureza().toUpperCase(Locale.ROOT);

        int linhas = jdbc.sql("""
                        UPDATE cfg_plano_contas SET
                            descricao = ?, descricao_curta = ?, tipo_movimento = ?::tipo_movimento_conta,
                            natureza = ?::natureza_conta, inclui_dre = ?, grupo_dre = ?::grupo_dre_conta,
                            inclui_fluxo_caixa = ?, grupo_dfc = ?::grupo_dfc_conta, sinal = ?,
                            aceita_lancamento = ?, exige_centro_custo = ?, exige_contraparte = ?,
                            exige_documento = ?, id_conta_contabil = ?, id_plano_referencial = ?,
                            observacao = ?
                        WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?
                        """)
                .params(req.descricao().trim().toUpperCase(Locale.ROOT),
                        textoOuNulo(req.descricaoCurta()),
                        req.tipoMovimento().toUpperCase(Locale.ROOT),
                        natureza,
                        incluiDre, incluiDre ? req.grupoDre() : "NAO_APLICA",
                        incluiCx, incluiCx ? req.grupoDfc() : "NAO_APLICA",
                        calcularSinal(req.tipoMovimento()),
                        "ANALITICA".equals(natureza),
                        Boolean.TRUE.equals(req.exigeCentroCusto()),
                        Boolean.TRUE.equals(req.exigeContraparte()),
                        Boolean.TRUE.equals(req.exigeDocumento()),
                        textoOuNulo(req.idContaContabil()),
                        textoOuNulo(req.idPlanoReferencial()),
                        textoOuNulo(req.observacao()),
                        codigoNormalizado)
                .update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Plano de contas não encontrado.");
        }
        return buscar(codigoNormalizado);
    }

    /**
     * Cai para inativar (em vez de excluir de verdade) quando a conta é padrão do sistema,
     * tem contas filhas, ou está vinculada a fornecedor/contas a pagar/caixa/conta corrente —
     * mesmo padrão de fallback já usado em Cliente/Funcionário/Fornecedor. A tabela ganhou
     * {@code ativo} em 2026-07-31; antes desse campo não existir, esta tela era a única
     * exceção que só excluía de verdade (409 sem fallback).
     */
    @Transactional
    public ExclusaoPlanoContasResponse excluir(String codigo) {
        String cod = normalizarCodigo(codigo);

        Boolean padraoSistema = jdbc.sql("""
                        SELECT padrao_sistema FROM cfg_plano_contas
                        WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?
                        """)
                .param(cod).query(Boolean.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Plano de contas não encontrado."));

        String motivo = null;
        if (Boolean.TRUE.equals(padraoSistema)) {
            motivo = "Conta padrão do sistema — não pode ser excluída.";
        } else if (temFilhos(cod)) {
            motivo = "Conta possui contas filhas.";
        } else if (temVinculo(cod)) {
            motivo = "Conta em uso por fornecedor, contas a pagar, caixa ou conta corrente.";
        }

        if (motivo != null) {
            int linhas = jdbc.sql("""
                            UPDATE cfg_plano_contas SET ativo = false
                            WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?
                            """)
                    .param(cod).update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Plano de contas não encontrado.");
            }
            return new ExclusaoPlanoContasResponse("inativado", motivo);
        }

        int linhas = jdbc.sql(
                        "DELETE FROM cfg_plano_contas WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?")
                .param(cod).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Plano de contas não encontrado.");
        }
        return new ExclusaoPlanoContasResponse("excluido", null);
    }

    private boolean temFilhos(String codigo) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM cfg_plano_contas
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas_pai = ?)
                        """)
                .param(codigo).query(Boolean.class).single());
    }

    private boolean temVinculo(String codigo) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM fornecedor
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?)
                            OR EXISTS (SELECT 1 FROM contas_pagar
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?)
                            OR EXISTS (SELECT 1 FROM caixa_detalhe
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?)
                            OR EXISTS (SELECT 1 FROM conta_corrente_movimento
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?)
                        """)
                .params(codigo, codigo, codigo, codigo)
                .query(Boolean.class).single());
    }

    private boolean existeConta(String codigo) {
        return jdbc.sql("""
                        SELECT id_plano_contas FROM cfg_plano_contas
                        WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?
                        """)
                .param(codigo).query(String.class).optional().isPresent();
    }

    private static void validar(PlanoContasRequest req) {
        String codigo = req.codigo() == null ? "" : req.codigo().trim().toUpperCase(Locale.ROOT);
        if (!MASCARA_CODIGO.matcher(codigo).matches()) {
            throw new IllegalArgumentException(
                    "Código deve seguir o formato 9.99.999.999 (conta.subconta.item.subitem).");
        }
        String tipoMovimento = req.tipoMovimento() == null ? "" : req.tipoMovimento().toUpperCase(Locale.ROOT);
        if (!TIPOS_MOVIMENTO.contains(tipoMovimento)) {
            throw new IllegalArgumentException("Tipo de movimento deve ser CREDITO, DEBITO ou NEUTRO.");
        }
        String natureza = req.natureza() == null ? "" : req.natureza().toUpperCase(Locale.ROOT);
        if (!NATUREZAS.contains(natureza)) {
            throw new IllegalArgumentException("Natureza deve ser SINTETICA ou ANALITICA.");
        }

        boolean incluiDre = Boolean.TRUE.equals(req.incluiDre());
        if ("NEUTRO".equals(tipoMovimento) && incluiDre) {
            throw new IllegalArgumentException("Conta com tipo de movimento NEUTRO não pode compor a DRE.");
        }
        if (incluiDre && (req.grupoDre() == null || !GRUPOS_DRE.contains(req.grupoDre()))) {
            throw new IllegalArgumentException("Informe o grupo da DRE (a conta compõe a DRE).");
        }
        boolean incluiCx = Boolean.TRUE.equals(req.incluiFluxoCaixa());
        if (incluiCx && (req.grupoDfc() == null || !GRUPOS_DFC.contains(req.grupoDfc()))) {
            throw new IllegalArgumentException("Informe o grupo do fluxo de caixa (a conta compõe o fluxo de caixa).");
        }
    }

    private static int calcularSinal(String tipoMovimento) {
        return switch (tipoMovimento.toUpperCase(Locale.ROOT)) {
            case "CREDITO" -> 1;
            case "DEBITO" -> -1;
            default -> 0;
        };
    }

    /**
     * Pai derivado da máscara — mesma lógica da coluna gerada {@code id_plano_contas_pai} no
     * banco (V016), reproduzida aqui só para dar um erro amigável ANTES de tentar o INSERT
     * (a FK {@code cfg_plano_contas_pai_fk} sozinha devolveria uma violação de FK crua).
     * {@code null} quando o código já é de nível 1 (conta-raiz de um grupo).
     */
    private static String calcularPai(String codigo) {
        if (codigo.substring(2, 4).equals("00")) return null;
        if (codigo.substring(5, 8).equals("000")) return codigo.substring(0, 1) + ".00.000.000";
        if (codigo.substring(9, 12).equals("000")) return codigo.substring(0, 4) + ".000.000";
        return codigo.substring(0, 8) + ".000";
    }

    /** Código contábil sempre em maiúsculas e sem espaços nas pontas (é a PK de negócio). */
    private static String normalizarCodigo(String codigo) {
        return codigo.trim().toUpperCase(Locale.ROOT);
    }

    private static String textoOuNulo(String valor) {
        if (valor == null) return null;
        String v = valor.trim();
        return v.isEmpty() ? null : v.toUpperCase(Locale.ROOT);
    }

    private static final String SELECT_BASE = """
            SELECT p.id_plano_contas, p.descricao, p.descricao_curta,
                   p.tipo_movimento::text AS tipo_movimento, p.natureza::text AS natureza,
                   p.nivel, p.id_plano_contas_pai,
                   p.inclui_dre, p.grupo_dre::text AS grupo_dre,
                   p.inclui_fluxo_caixa, p.grupo_dfc::text AS grupo_dfc,
                   p.sinal, p.aceita_lancamento,
                   p.exige_centro_custo, p.exige_contraparte, p.exige_documento,
                   p.id_conta_contabil, p.id_plano_referencial,
                   p.padrao_sistema, p.ativo, p.observacao,
                   p.criado_em, p.atualizado_em
            FROM cfg_plano_contas p
            """;

    private static PlanoContasResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new PlanoContasResponse(
                rs.getString("id_plano_contas"),
                rs.getString("descricao"),
                rs.getString("descricao_curta"),
                rs.getString("tipo_movimento"),
                rs.getString("natureza"),
                rs.getInt("nivel"),
                rs.getString("id_plano_contas_pai"),
                rs.getBoolean("inclui_dre"),
                rs.getString("grupo_dre"),
                rs.getBoolean("inclui_fluxo_caixa"),
                rs.getString("grupo_dfc"),
                rs.getInt("sinal"),
                rs.getBoolean("aceita_lancamento"),
                rs.getBoolean("exige_centro_custo"),
                rs.getBoolean("exige_contraparte"),
                rs.getBoolean("exige_documento"),
                rs.getString("id_conta_contabil"),
                rs.getString("id_plano_referencial"),
                rs.getBoolean("padrao_sistema"),
                rs.getBoolean("ativo"),
                rs.getString("observacao"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
