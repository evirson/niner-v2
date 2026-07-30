package com.vetor.niner.financeiro.contacorrente;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteDtos.ContaCorrenteOpcaoResponse;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteDtos.ContaCorrenteRequest;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteDtos.ContaCorrenteResponse;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteDtos.ExclusaoContaCorrenteResponse;
import com.vetor.niner.financeiro.contacorrente.ContaCorrenteDtos.PaginaContasCorrente;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de conta corrente (docs/telas/conta-corrente.md). Tabela {@code conta_corrente} sob RLS
 * de tenant (V028) — PK de negócio {@code text} (o número/código da conta bancária, digitado
 * pelo usuário e imutável após a criação), mesmo padrão estrutural de {@code
 * cadastros.planocontas}, mas com {@code ativo} (aqui a exclusão com vínculo em {@code
 * conta_corrente_movimento} inativa em vez de excluir, mesmo padrão de {@code
 * cadastros.fornecedor}).
 */
@Service
public class ContaCorrenteService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "idContaCorrente", "cc.id_conta_corrente",
            "descricao", "cc.descricao",
            "nomeEmpresa", "e.razao_social",
            "idBanco", "cc.id_banco",
            "status", "cc.ativo");

    private final JdbcClient jdbc;

    public ContaCorrenteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PaginaContasCorrente listar(String busca, String status, Integer pagina, Integer limite,
                                        String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao =
                ordenarPor == null ? "cc.descricao" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "cc.descricao");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        // Filtro por id_tenant explícito além do RLS — indispensável (PK composta, mesmo
        // motivo de PlanoContasService): o mesmo código de conta existe em tenants diferentes.
        StringBuilder filtro = new StringBuilder(" WHERE cc.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();

        if (busca != null && !busca.isBlank()) {
            filtro.append(" AND (cc.id_conta_corrente ILIKE ? OR cc.descricao ILIKE ?)");
            String padrao = "%" + busca.trim() + "%";
            params.add(padrao);
            params.add(padrao);
        }
        switch (status == null ? "ATIVOS" : status.toUpperCase(Locale.ROOT)) {
            case "INATIVOS" -> filtro.append(" AND cc.ativo = false");
            case "TODOS" -> { /* sem filtro de status */ }
            default -> filtro.append(" AND cc.ativo = true");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM conta_corrente cc" + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", cc.id_conta_corrente " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<ContaCorrenteResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
                .params(paramsPagina)
                .query(ContaCorrenteService::mapear)
                .list();

        return new PaginaContasCorrente(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    /** Opções enxutas (ativas) pra select de outras telas — Movimentação de Conta Corrente. */
    @Transactional(readOnly = true)
    public List<ContaCorrenteOpcaoResponse> listarOpcoes() {
        return jdbc.sql("""
                        SELECT id_conta_corrente, descricao FROM conta_corrente
                        WHERE id_tenant = plataforma.tenant_atual() AND ativo = true
                        ORDER BY descricao ASC
                        """)
                .query((rs, n) -> new ContaCorrenteOpcaoResponse(rs.getString("id_conta_corrente"), rs.getString("descricao")))
                .list();
    }

    @Transactional(readOnly = true)
    public ContaCorrenteResponse buscar(String idContaCorrente) {
        return jdbc.sql(SELECT_BASE + " WHERE cc.id_tenant = plataforma.tenant_atual() AND cc.id_conta_corrente = ?")
                .param(normalizarCodigo(idContaCorrente))
                .query(ContaCorrenteService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Conta corrente não encontrada."));
    }

    @Transactional
    public ContaCorrenteResponse criar(ContaCorrenteRequest req) {
        String codigo = normalizarCodigo(req.idContaCorrente());
        validarBanco(req.idBanco());
        try {
            jdbc.sql("""
                            INSERT INTO conta_corrente
                                (id_tenant, id_conta_corrente, id_empresa, id_banco, id_agencia, descricao, ativo, data_abertura)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(codigo, req.idEmpresa(), req.idBanco().trim().toUpperCase(Locale.ROOT),
                            req.idAgencia().trim().toUpperCase(Locale.ROOT), req.descricao().trim().toUpperCase(Locale.ROOT),
                            req.ativo() == null || req.ativo(), req.dataAbertura())
                    .update();
            return buscar(codigo);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma conta corrente com esse código.");
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Empresa informada não existe.");
        }
    }

    /** O código (PK) não é editável — o do path prevalece; o do corpo é ignorado. */
    @Transactional
    public ContaCorrenteResponse atualizar(String idContaCorrente, ContaCorrenteRequest req) {
        String codigo = normalizarCodigo(idContaCorrente);
        validarBanco(req.idBanco());
        try {
            int linhas = jdbc.sql("""
                            UPDATE conta_corrente SET
                                id_empresa = ?, id_banco = ?, id_agencia = ?, descricao = ?, ativo = ?,
                                data_abertura = ?, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_conta_corrente = ?
                            """)
                    .params(req.idEmpresa(), req.idBanco().trim().toUpperCase(Locale.ROOT),
                            req.idAgencia().trim().toUpperCase(Locale.ROOT), req.descricao().trim().toUpperCase(Locale.ROOT),
                            req.ativo() == null || req.ativo(), req.dataAbertura(), codigo)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Conta corrente não encontrada.");
            }
            return buscar(codigo);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Empresa informada não existe.");
        }
    }

    /**
     * Exclui, ou inativa em vez de excluir se houver vínculo — o único vínculo possível é
     * {@code conta_corrente_movimento.id_conta_corrente} (V028). Checagem antes do DELETE pelo
     * mesmo motivo das demais telas (FK violada aborta o resto da transação no Postgres).
     */
    @Transactional
    public ExclusaoContaCorrenteResponse excluir(String idContaCorrente) {
        String codigo = normalizarCodigo(idContaCorrente);
        boolean temVinculo = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM conta_corrente_movimento
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_conta_corrente = ?)
                        """)
                .param(codigo)
                .query(Boolean.class).single());

        if (temVinculo) {
            int linhas = jdbc.sql("""
                            UPDATE conta_corrente SET ativo = false, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_conta_corrente = ?
                            """)
                    .param(codigo).update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Conta corrente não encontrada.");
            }
            return new ExclusaoContaCorrenteResponse("inativado", "Conta corrente possui movimentações associadas.");
        }

        int linhas = jdbc.sql(
                        "DELETE FROM conta_corrente WHERE id_tenant = plataforma.tenant_atual() AND id_conta_corrente = ?")
                .param(codigo).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Conta corrente não encontrada.");
        }
        return new ExclusaoContaCorrenteResponse("excluido", null);
    }

    /** Código da conta sempre em maiúsculas e sem espaços nas pontas (é a PK de negócio). */
    private static String normalizarCodigo(String codigo) {
        return codigo.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * {@code cfg_banco} é global (sem RLS) — validado explicitamente aqui (mensagem específica)
     * em vez de deixar a violação de FK cair no catch genérico de {@code empresa}, que agora
     * não seria mais preciso com duas FKs possíveis na mesma tabela.
     */
    private void validarBanco(String idBanco) {
        boolean existe = Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM cfg_banco WHERE codigo_banco = ?)")
                .param(idBanco.trim().toUpperCase(Locale.ROOT))
                .query(Boolean.class).single());
        if (!existe) {
            throw new IllegalArgumentException("Banco informado não existe.");
        }
    }

    private static final String SELECT_BASE = """
            SELECT cc.id_conta_corrente, cc.id_empresa, e.razao_social AS nome_empresa, cc.id_banco,
                   cc.id_agencia, cc.descricao, cc.ativo, cc.data_abertura, cc.criado_em, cc.atualizado_em
            FROM conta_corrente cc
            JOIN empresa e ON e.id_tenant = cc.id_tenant AND e.id_empresa = cc.id_empresa
            """;

    private static ContaCorrenteResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new ContaCorrenteResponse(
                rs.getString("id_conta_corrente"),
                rs.getLong("id_empresa"),
                rs.getString("nome_empresa"),
                rs.getString("id_banco"),
                rs.getString("id_agencia"),
                rs.getString("descricao"),
                rs.getBoolean("ativo"),
                rs.getObject("data_abertura", LocalDate.class),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
