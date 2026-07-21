package com.vetor.niner.cadastros.funcionario;

import com.vetor.niner.cadastros.cliente.Documentos;
import com.vetor.niner.cadastros.funcionario.FuncionarioDtos.ExclusaoFuncionarioResponse;
import com.vetor.niner.cadastros.funcionario.FuncionarioDtos.FuncionarioRequest;
import com.vetor.niner.cadastros.funcionario.FuncionarioDtos.FuncionarioResponse;
import com.vetor.niner.cadastros.funcionario.FuncionarioDtos.PaginaFuncionarios;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaDtos.ConfiguracaoCampoResponse;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de funcionários (docs/telas/funcionario.md). Tabela {@code funcionario} sob RLS de
 * tenant (V016/V024) — mesmo padrão consolidado em {@code cadastros.cliente}: toda leitura
 * já é restrita ao tenant do contexto atual (P8); o INSERT usa
 * {@code plataforma.tenant_atual()} explicitamente porque a política WITH CHECK exige o
 * valor (RLS não o preenche sozinho). {@code id_empresa} é preenchido automaticamente com a
 * única empresa do tenant (Q6 — tenant 1:N empresa, 1:1 no v1) — não existe seletor de
 * empresa no formulário ainda, não há necessidade enquanto só houver uma.
 *
 * <p>Diferenças deliberadas em relação a cliente: {@code cpf} não é único por tenant
 * (decisão de produto registrada em V016/§3.3.9 — "CPF deixou de ser único"), então não há
 * checagem de duplicidade aqui; não há CNPJ (funcionário é sempre pessoa física) nem
 * categoria (não existe {@code cfg_categoria_funcionario}).
 */
@Service
public class FuncionarioService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;
    private static final String CHAVE_TELA_FORM = "cadastros.funcionario.form";

    /** Colunas ordenáveis da listagem — chave da API -> expressão SQL. */
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "nome", "f.nome",
            "cpf", "f.cpf",
            "telefone", "f.telefone",
            "cargo", "f.cargo",
            "percComissao", "f.perc_comissao",
            "status", "f.ativo");

    private static final Map<String, String> ROTULOS_CAMPO = Map.of(
            "cpf", "CPF", "telefone", "Celular", "cargo", "Cargo",
            "percComissao", "Percentual de comissão");

    private final JdbcClient jdbc;
    private final ConfiguracaoTelaService configuracaoTelaService;

    public FuncionarioService(JdbcClient jdbc, ConfiguracaoTelaService configuracaoTelaService) {
        this.jdbc = jdbc;
        this.configuracaoTelaService = configuracaoTelaService;
    }

    @Transactional(readOnly = true)
    public PaginaFuncionarios listar(String nome, String status, Integer pagina, Integer limite,
                                      String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao = ordenarPor == null ? "f.nome" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "f.nome");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (nome != null && !nome.isBlank()) {
            filtro.append(" AND f.nome ILIKE ?");
            params.add("%" + nome.trim() + "%");
        }
        switch (status == null ? "ATIVOS" : status.toUpperCase(Locale.ROOT)) {
            case "INATIVOS" -> filtro.append(" AND f.ativo = false");
            case "TODOS" -> { /* sem filtro de status */ }
            default -> filtro.append(" AND f.ativo = true");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM funcionario f" + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        // Coluna fixa no whitelist (COLUNAS_ORDENAVEIS) — nunca vem do cliente sem passar por
        // esse mapa. Desempate sempre por id_funcionario, na mesma direção.
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", f.id_funcionario " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<FuncionarioResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
                .params(paramsPagina)
                .query(FuncionarioService::mapear)
                .list();

        return new PaginaFuncionarios(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public FuncionarioResponse buscar(long id) {
        return jdbc.sql(SELECT_BASE + " WHERE f.id_funcionario = ?")
                .param(id)
                .query(FuncionarioService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Funcionário não encontrado."));
    }

    @Transactional
    public FuncionarioResponse criar(FuncionarioRequest req) {
        validar(req);
        try {
            long id = jdbc.sql("""
                            INSERT INTO funcionario (id_tenant, id_empresa, nome, cpf, telefone, cargo,
                                perc_comissao, ativo)
                            VALUES (plataforma.tenant_atual(),
                                (SELECT id_empresa FROM empresa WHERE id_tenant = plataforma.tenant_atual() LIMIT 1),
                                ?, ?, ?, ?, ?, ?)
                            RETURNING id_funcionario
                            """)
                    .params(camposComuns(req))
                    .query(Long.class).single();
            return buscar(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Não foi possível salvar o funcionário.");
        }
    }

    @Transactional
    public FuncionarioResponse atualizar(long id, FuncionarioRequest req) {
        validar(req);
        List<Object> params = new ArrayList<>(camposComuns(req));
        params.add(id);
        try {
            int linhas = jdbc.sql("""
                            UPDATE funcionario SET
                                nome = ?, cpf = ?, telefone = ?, cargo = ?, perc_comissao = ?, ativo = ?,
                                atualizado_em = now()
                            WHERE id_funcionario = ?
                            """)
                    .params(params)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Funcionário não encontrado.");
            }
            return buscar(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Não foi possível salvar o funcionário.");
        }
    }

    /**
     * Exclui, ou inativa em vez de excluir se houver vínculo com movimentação de estoque
     * ({@code produto_movimento_detalhe.id_funcionario}, V019) — mesmo padrão de
     * {@code cadastros.cliente} (checa antes do DELETE: uma FK violada aborta o resto da
     * transação no Postgres).
     */
    @Transactional
    public ExclusaoFuncionarioResponse excluir(long id) {
        boolean temMovimento = Boolean.TRUE.equals(
                jdbc.sql("SELECT EXISTS (SELECT 1 FROM produto_movimento_detalhe WHERE id_funcionario = ?)")
                        .param(id).query(Boolean.class).single());

        if (temMovimento) {
            int linhas = jdbc.sql(
                            "UPDATE funcionario SET ativo = false, atualizado_em = now() WHERE id_funcionario = ?")
                    .param(id).update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Funcionário não encontrado.");
            }
            return new ExclusaoFuncionarioResponse("inativado", "Funcionário possui movimentações associadas.");
        }

        int linhas = jdbc.sql("DELETE FROM funcionario WHERE id_funcionario = ?").param(id).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Funcionário não encontrado.");
        }
        return new ExclusaoFuncionarioResponse("excluido", null);
    }

    /**
     * Validação de servidor (defesa em profundidade, mesmo padrão de
     * {@code ClienteService.validar}): formato de CPF/celular e a obrigatoriedade
     * configurável por tenant ({@code cfg_tela_campo}).
     */
    private void validar(FuncionarioRequest req) {
        if (req.cpf() != null && !req.cpf().isBlank() && !Documentos.valido(req.cpf())) {
            throw new IllegalArgumentException("CPF inválido (dígito verificador não confere).");
        }
        if (!celularValidoOuVazio(req.telefone())) {
            throw new IllegalArgumentException("Celular deve ter 11 dígitos (DDD + 9XXXX-XXXX).");
        }
        if (req.percComissao() != null
                && (req.percComissao().compareTo(BigDecimal.ZERO) < 0
                    || req.percComissao().compareTo(new BigDecimal("100")) > 0)) {
            throw new IllegalArgumentException("Percentual de comissão deve estar entre 0 e 100.");
        }

        Map<String, ConfiguracaoCampoResponse> config = configuracaoTelaService.listar(CHAVE_TELA_FORM).stream()
                .collect(Collectors.toMap(ConfiguracaoCampoResponse::campo, c -> c));
        exigirSeObrigatorio(config, "cpf", req.cpf());
        exigirSeObrigatorio(config, "telefone", req.telefone());
        exigirSeObrigatorio(config, "cargo", req.cargo());
    }

    private static boolean celularValidoOuVazio(String valor) {
        String d = Documentos.somenteDigitos(valor);
        if (d == null || d.isEmpty()) {
            return true;
        }
        return d.length() == 11 && d.charAt(2) == '9';
    }

    private static void exigirSeObrigatorio(Map<String, ConfiguracaoCampoResponse> config, String campo, String valor) {
        ConfiguracaoCampoResponse c = config.get(campo);
        if (c != null && c.obrigatorio() && (valor == null || valor.isBlank())) {
            throw new IllegalArgumentException(ROTULOS_CAMPO.getOrDefault(campo, campo) + " é obrigatório.");
        }
    }

    /**
     * Campos comuns a INSERT/UPDATE, na mesma ordem em que aparecem nas duas SQLs acima.
     * Nome/cargo normalizados para MAIÚSCULAS aqui — mesma convenção de
     * {@code cadastros.cliente} (defesa em profundidade além do frontend).
     */
    private static List<Object> camposComuns(FuncionarioRequest r) {
        List<Object> params = new ArrayList<>();
        params.add(r.nome().trim().toUpperCase(Locale.ROOT));
        params.add(Documentos.somenteDigitos(r.cpf()));
        params.add(Documentos.somenteDigitos(r.telefone()));
        params.add(trimMaiusculoOuNulo(r.cargo()));
        params.add(r.percComissao() == null ? BigDecimal.ZERO : r.percComissao());
        params.add(r.ativo() == null || r.ativo());
        return params;
    }

    private static String trimMaiusculoOuNulo(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static final String SELECT_BASE = """
            SELECT f.id_funcionario, f.nome, f.cpf, f.telefone, f.cargo, f.perc_comissao,
                   f.ativo, f.criado_em, f.atualizado_em
            FROM funcionario f
            """;

    private static FuncionarioResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new FuncionarioResponse(
                rs.getLong("id_funcionario"),
                rs.getString("nome"),
                rs.getString("cpf"),
                rs.getString("telefone"),
                rs.getString("cargo"),
                rs.getBigDecimal("perc_comissao"),
                rs.getBoolean("ativo"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
