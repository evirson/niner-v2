package com.vetor.niner.financeiro.contacorrente;

import com.vetor.niner.financeiro.contacorrente.ContaCorrenteMovimentoDtos.ContaCorrenteMovimentoRequest;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteMovimentoDtos.ContaCorrenteMovimentoResponse;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteMovimentoDtos.ExclusaoContaCorrenteMovimentoResponse;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteMovimentoDtos.PaginaContaCorrenteMovimento;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de lançamentos (extrato manual) de conta corrente (docs/telas/conta-corrente-
 * movimento.md). Tabela {@code conta_corrente_movimento} sob RLS de tenant (V028), FK composta
 * pra {@code conta_corrente} e {@code cfg_plano_contas} — mesmo padrão de {@code
 * cadastros.fornecedor} (JOIN qualificado por tenant nas duas pontas).
 */
@Service
public class ContaCorrenteMovimentoService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;
    private static final Set<String> CREDITO_DEBITO = Set.of("C", "D");

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "dataMovimento", "m.data_movimento",
            "numeroDocumento", "m.numero_documento",
            "idContaCorrente", "m.id_conta_corrente",
            "creditoDebito", "m.credito_debito",
            "valor", "m.valor",
            "compensado", "m.compensado");

    private final JdbcClient jdbc;

    public ContaCorrenteMovimentoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PaginaContaCorrenteMovimento listar(String idContaCorrente, Long idEmpresa, String idPlanoContas, String busca,
                                                LocalDate dataInicial, LocalDate dataFinal, String compensado,
                                                Integer pagina, Integer limite, String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao =
                ordenarPor == null ? "m.data_movimento" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "m.data_movimento");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE m.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();

        if (idEmpresa != null) {
            filtro.append(" AND cc.id_empresa = ?");
            params.add(idEmpresa);
        }
        if (idPlanoContas != null && !idPlanoContas.isBlank()) {
            filtro.append(" AND m.id_plano_contas = ?");
            params.add(idPlanoContas.trim().toUpperCase(Locale.ROOT));
        }
        if (idContaCorrente != null && !idContaCorrente.isBlank()) {
            filtro.append(" AND m.id_conta_corrente = ?");
            params.add(idContaCorrente.trim().toUpperCase(Locale.ROOT));
        }
        if (busca != null && !busca.isBlank()) {
            filtro.append(" AND m.numero_documento ILIKE ?");
            params.add("%" + busca.trim() + "%");
        }
        if (dataInicial != null) {
            filtro.append(" AND m.data_movimento::date >= ?");
            params.add(dataInicial);
        }
        if (dataFinal != null) {
            filtro.append(" AND m.data_movimento::date <= ?");
            params.add(dataFinal);
        }
        switch (compensado == null ? "TODOS" : compensado.toUpperCase(Locale.ROOT)) {
            case "COMPENSADOS" -> filtro.append(" AND m.compensado = true");
            case "PENDENTES" -> filtro.append(" AND m.compensado = false");
            default -> { /* sem filtro */ }
        }

        long totalItens = jdbc.sql("""
                        SELECT count(*) FROM conta_corrente_movimento m
                        JOIN conta_corrente cc ON cc.id_tenant = m.id_tenant AND cc.id_conta_corrente = m.id_conta_corrente
                        """ + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", m.localizador " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<ContaCorrenteMovimentoResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
                .params(paramsPagina)
                .query(ContaCorrenteMovimentoService::mapear)
                .list();

        return new PaginaContaCorrenteMovimento(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public ContaCorrenteMovimentoResponse buscar(long localizador) {
        return jdbc.sql(SELECT_BASE + " WHERE m.id_tenant = plataforma.tenant_atual() AND m.localizador = ?")
                .param(localizador)
                .query(ContaCorrenteMovimentoService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Lançamento não encontrado."));
    }

    @Transactional
    public ContaCorrenteMovimentoResponse criar(ContaCorrenteMovimentoRequest req) {
        validar(req);
        try {
            long localizador = jdbc.sql("""
                            INSERT INTO conta_corrente_movimento
                                (id_tenant, id_conta_corrente, id_plano_contas, data_movimento, numero_documento,
                                 credito_debito, compensado, valor, observacao)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?::credito_debito, ?, ?, ?)
                            RETURNING localizador
                            """)
                    .params(req.idContaCorrente().trim().toUpperCase(Locale.ROOT),
                            req.idPlanoContas().trim().toUpperCase(Locale.ROOT), req.dataMovimento(),
                            req.numeroDocumento().trim(), req.creditoDebito(), Boolean.TRUE.equals(req.compensado()),
                            req.valor(), trimOuNulo(req.observacao()))
                    .query(Long.class).single();
            return buscar(localizador);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Conta corrente ou plano de contas informado não existe.");
        }
    }

    @Transactional
    public ContaCorrenteMovimentoResponse atualizar(long localizador, ContaCorrenteMovimentoRequest req) {
        validar(req);
        try {
            int linhas = jdbc.sql("""
                            UPDATE conta_corrente_movimento SET
                                id_conta_corrente = ?, id_plano_contas = ?, data_movimento = ?, numero_documento = ?,
                                credito_debito = ?::credito_debito, compensado = ?, valor = ?, observacao = ?,
                                atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND localizador = ?
                            """)
                    .params(req.idContaCorrente().trim().toUpperCase(Locale.ROOT),
                            req.idPlanoContas().trim().toUpperCase(Locale.ROOT), req.dataMovimento(),
                            req.numeroDocumento().trim(), req.creditoDebito(), Boolean.TRUE.equals(req.compensado()),
                            req.valor(), trimOuNulo(req.observacao()), localizador)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Lançamento não encontrado.");
            }
            return buscar(localizador);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Conta corrente ou plano de contas informado não existe.");
        }
    }

    /** Sempre exclui de verdade — lançamento não tem {@code ativo}, nada referencia esta tabela. */
    @Transactional
    public ExclusaoContaCorrenteMovimentoResponse excluir(long localizador) {
        int linhas = jdbc.sql(
                        "DELETE FROM conta_corrente_movimento WHERE id_tenant = plataforma.tenant_atual() AND localizador = ?")
                .param(localizador).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Lançamento não encontrado.");
        }
        return new ExclusaoContaCorrenteMovimentoResponse("excluido");
    }

    private static void validar(ContaCorrenteMovimentoRequest req) {
        if (!CREDITO_DEBITO.contains(req.creditoDebito())) {
            throw new IllegalArgumentException("Crédito/Débito deve ser C ou D.");
        }
    }

    private static String trimOuNulo(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static final String SELECT_BASE = """
            SELECT m.localizador, m.id_conta_corrente, cc.descricao AS descricao_conta_corrente,
                   m.id_plano_contas, pc.descricao AS descricao_plano_contas, m.data_movimento,
                   m.numero_documento, m.credito_debito::text AS credito_debito, m.compensado, m.valor,
                   m.observacao, m.criado_em, m.atualizado_em
            FROM conta_corrente_movimento m
            JOIN conta_corrente cc ON cc.id_tenant = m.id_tenant AND cc.id_conta_corrente = m.id_conta_corrente
            JOIN cfg_plano_contas pc ON pc.id_tenant = m.id_tenant AND pc.id_plano_contas = m.id_plano_contas
            """;

    private static ContaCorrenteMovimentoResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new ContaCorrenteMovimentoResponse(
                rs.getLong("localizador"),
                rs.getString("id_conta_corrente"),
                rs.getString("descricao_conta_corrente"),
                rs.getString("id_plano_contas"),
                rs.getString("descricao_plano_contas"),
                rs.getObject("data_movimento", OffsetDateTime.class),
                rs.getString("numero_documento"),
                rs.getString("credito_debito"),
                rs.getBoolean("compensado"),
                rs.getBigDecimal("valor"),
                rs.getString("observacao"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
