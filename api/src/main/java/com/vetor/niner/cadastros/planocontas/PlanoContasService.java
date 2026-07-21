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

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de plano de contas (docs/telas/plano-contas.md). Tabela {@code cfg_plano_contas} sob
 * RLS de tenant (V016/V024) — mesmo padrão de {@code cadastros.cliente}, com as diferenças
 * estruturais descritas no {@code package-info}: PK de negócio {@code text} (o código
 * contábil, digitado pelo usuário e imutável após a criação) e ausência de {@code ativo}
 * (sem fallback de inativar na exclusão).
 *
 * <p>Não há registro em {@code cfg_tela_campo} para esta tela: todos os campos da tabela
 * são NOT NULL — estruturalmente obrigatórios não são configuráveis
 * (docs/telas/configuracao-tela.md), então não sobra nada para configurar.
 */
@Service
public class PlanoContasService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    /** Valores do ENUM {@code tipo_movimento_conta} (V013) — com acentos, como no banco. */
    private static final Set<String> TIPOS_MOVIMENTO = Set.of("CRÉDITO", "DÉBITO", "NEUTRO");

    /** Colunas ordenáveis da listagem — chave da API -> expressão SQL. */
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "codigo", "p.id_plano_contas",
            "descricao", "p.descricao",
            "tipoMovimento", "p.tipo_movimento",
            "incluiDre", "p.inclui_dre",
            "incluiFluxoCaixa", "p.inclui_fluxo_caixa");

    private final JdbcClient jdbc;

    public PlanoContasService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PaginaPlanosContas listar(String busca, Integer pagina, Integer limite,
                                      String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao =
                ordenarPor == null ? "p.id_plano_contas" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "p.id_plano_contas");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        // Filtro por id_tenant explícito além do RLS — indispensável AQUI, não só defesa em
        // profundidade: a PK é composta (id_tenant, id_plano_contas), então o MESMO código
        // existe em tenants diferentes; sem este filtro, o ambiente de teste (datasource
        // conecta como superusuário, sem RLS) enxergaria códigos de outros tenants. Mesmo
        // precedente do ConfiguracaoTelaService.
        StringBuilder filtro = new StringBuilder(" WHERE p.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();

        if (busca != null && !busca.isBlank()) {
            // Busca única cobre código e descrição — o usuário pode digitar "3.1" ou "ALUGUEL".
            filtro.append(" AND (p.id_plano_contas ILIKE ? OR p.descricao ILIKE ?)");
            String padrao = "%" + busca.trim() + "%";
            params.add(padrao);
            params.add(padrao);
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM cfg_plano_contas p" + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        // Coluna fixa no allowlist (COLUNAS_ORDENAVEIS). O desempate das outras telas
        // (id surrogate) aqui é o próprio código — a PK já é a coluna default de ordenação.
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
        // Tenant explícito obrigatório: o mesmo código existe em outros tenants (PK composta).
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
        try {
            jdbc.sql("""
                            INSERT INTO cfg_plano_contas (id_tenant, id_plano_contas, descricao,
                                tipo_movimento, inclui_dre, inclui_fluxo_caixa)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?::tipo_movimento_conta, ?, ?)
                            """)
                    .params(codigo,
                            req.descricao().trim().toUpperCase(Locale.ROOT),
                            req.tipoMovimento(),
                            Boolean.TRUE.equals(req.incluiDre()),
                            Boolean.TRUE.equals(req.incluiFluxoCaixa()))
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
        int linhas = jdbc.sql("""
                        UPDATE cfg_plano_contas SET
                            descricao = ?, tipo_movimento = ?::tipo_movimento_conta,
                            inclui_dre = ?, inclui_fluxo_caixa = ?, atualizado_em = now()
                        WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?
                        """)
                .params(req.descricao().trim().toUpperCase(Locale.ROOT),
                        req.tipoMovimento(),
                        Boolean.TRUE.equals(req.incluiDre()),
                        Boolean.TRUE.equals(req.incluiFluxoCaixa()),
                        normalizarCodigo(codigo))
                .update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Plano de contas não encontrado.");
        }
        return buscar(codigo);
    }

    /**
     * Exclui de verdade — sem fallback de inativar ({@code cfg_plano_contas} não tem coluna
     * {@code ativo}). Com vínculo em {@code fornecedor} ou {@code contas_pagar}, responde
     * 409 e nada muda (checado antes do DELETE pelo mesmo motivo das outras telas: FK
     * violada aborta o resto da transação no Postgres).
     */
    @Transactional
    public ExclusaoPlanoContasResponse excluir(String codigo) {
        String codigoNormalizado = normalizarCodigo(codigo);
        boolean temVinculo = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM fornecedor
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?)
                            OR EXISTS (SELECT 1 FROM contas_pagar
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?)
                        """)
                .params(codigoNormalizado, codigoNormalizado)
                .query(Boolean.class).single());
        if (temVinculo) {
            throw new ConflitoDadosException(
                    "Plano de contas em uso por fornecedor ou contas a pagar — não pode ser excluído.");
        }

        int linhas = jdbc.sql(
                        "DELETE FROM cfg_plano_contas WHERE id_tenant = plataforma.tenant_atual() AND id_plano_contas = ?")
                .param(codigoNormalizado).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Plano de contas não encontrado.");
        }
        return new ExclusaoPlanoContasResponse("excluido", null);
    }

    private static void validar(PlanoContasRequest req) {
        if (!TIPOS_MOVIMENTO.contains(req.tipoMovimento())) {
            throw new IllegalArgumentException("Tipo de movimento deve ser CRÉDITO, DÉBITO ou NEUTRO.");
        }
    }

    /** Código contábil sempre em maiúsculas e sem espaços nas pontas (é a PK de negócio). */
    private static String normalizarCodigo(String codigo) {
        return codigo.trim().toUpperCase(Locale.ROOT);
    }

    private static final String SELECT_BASE = """
            SELECT p.id_plano_contas, p.descricao, p.tipo_movimento::text AS tipo_movimento,
                   p.inclui_dre, p.inclui_fluxo_caixa, p.criado_em, p.atualizado_em
            FROM cfg_plano_contas p
            """;

    private static PlanoContasResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new PlanoContasResponse(
                rs.getString("id_plano_contas"),
                rs.getString("descricao"),
                rs.getString("tipo_movimento"),
                rs.getBoolean("inclui_dre"),
                rs.getBoolean("inclui_fluxo_caixa"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
