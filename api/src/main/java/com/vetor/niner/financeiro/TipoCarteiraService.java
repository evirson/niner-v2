package com.vetor.niner.financeiro;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.financeiro.TipoCarteiraDtos.ExclusaoTipoCarteiraResponse;
import com.vetor.niner.financeiro.TipoCarteiraDtos.MoedaSelecionada;
import com.vetor.niner.financeiro.TipoCarteiraDtos.PaginaTiposCarteira;
import com.vetor.niner.financeiro.TipoCarteiraDtos.TipoCarteiraRequest;
import com.vetor.niner.financeiro.TipoCarteiraDtos.TipoCarteiraResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de tipo de carteira (prazo/parcelas/taxa do crediário, cartão etc.), tabela {@code
 * tipo_carteira} sob RLS de tenant (V025/V024). Tela que também gerencia o vínculo N:N com
 * moeda ({@code moeda_detalhe}, 2026-07-23 — decisão de produto: o fluxo natural é "criar um
 * tipo de carteira e escolher em quais moedas ele vale", não o inverso); {@code moeda_detalhe}
 * é substituída por inteiro a cada criação/atualização (apaga tudo e reinsere), sem índice —
 * diferente de {@code produto_categoria}, esta relação não tem ordem. Sem coluna {@code
 * ativo}: exclusão sem fallback de inativar.
 *
 * <p>Filtro por {@code id_tenant} explícito em toda consulta, além do RLS — indispensável
 * porque o ambiente de teste (Testcontainers) conecta como superusuário do container, que
 * ignora RLS mesmo com {@code FORCE} (mesmo motivo documentado em {@code PlanoContasService}).
 */
@Service
public class TipoCarteiraService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "nomeCarteira", "tc.nome_carteira",
            "prazoPagamento", "tc.prazo_pagamento",
            "taxaAdministradora", "tc.taxa_administradora");

    private final JdbcClient jdbc;

    public TipoCarteiraService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PaginaTiposCarteira listar(String busca, Integer pagina, Integer limite, String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao = ordenarPor == null ? "tc.nome_carteira" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "tc.nome_carteira");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE tc.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();
        if (busca != null && !busca.isBlank()) {
            filtro.append(" AND tc.nome_carteira ILIKE ?");
            params.add("%" + busca.trim() + "%");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM tipo_carteira tc" + filtro).params(params).query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", tc.id_carteira " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<TipoCarteiraResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
                .params(paramsPagina)
                .query(this::mapear)
                .list();

        return new PaginaTiposCarteira(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public TipoCarteiraResponse buscar(long id) {
        return jdbc.sql(SELECT_BASE + " WHERE tc.id_tenant = plataforma.tenant_atual() AND tc.id_carteira = ?")
                .param(id)
                .query(this::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Tipo de carteira não encontrado."));
    }

    @Transactional
    public TipoCarteiraResponse criar(TipoCarteiraRequest req) {
        validar(req);
        try {
            long id = jdbc.sql("""
                            INSERT INTO tipo_carteira
                                (id_tenant, nome_carteira, prazo_pagamento, pc_minima, pc_maxima, taxa_administradora)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?)
                            RETURNING id_carteira
                            """)
                    .params(req.nomeCarteira().trim().toUpperCase(Locale.ROOT), req.prazoPagamento(),
                            req.pcMinima(), req.pcMaxima(), req.taxaAdministradora())
                    .query(Long.class).single();
            salvarMoedas(id, req.moedas());
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um tipo de carteira com esse nome.");
        } catch (DataIntegrityViolationException e) {
            throw erroDeVinculo(e);
        }
    }

    @Transactional
    public TipoCarteiraResponse atualizar(long id, TipoCarteiraRequest req) {
        validar(req);
        try {
            int linhas = jdbc.sql("""
                            UPDATE tipo_carteira SET
                                nome_carteira = ?, prazo_pagamento = ?, pc_minima = ?, pc_maxima = ?,
                                taxa_administradora = ?, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?
                            """)
                    .params(req.nomeCarteira().trim().toUpperCase(Locale.ROOT), req.prazoPagamento(),
                            req.pcMinima(), req.pcMaxima(), req.taxaAdministradora(), id)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Tipo de carteira não encontrado.");
            }
            salvarMoedas(id, req.moedas());
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um tipo de carteira com esse nome.");
        } catch (DataIntegrityViolationException e) {
            throw erroDeVinculo(e);
        }
    }

    /**
     * Exclui de verdade — sem fallback de inativar ({@code tipo_carteira} não tem coluna
     * {@code ativo}). Com vínculo em {@code contas_receber} (já usado numa venda), responde
     * 409 e nada muda; vínculo em {@code moeda_detalhe} é removido junto (a relação só existe
     * por causa do tipo de carteira, mesmo princípio de {@code produto_categoria}).
     */
    @Transactional
    public ExclusaoTipoCarteiraResponse excluir(long id) {
        boolean temDependente = Boolean.TRUE.equals(
                jdbc.sql("""
                                SELECT EXISTS (SELECT 1 FROM contas_receber
                                               WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?)
                                """)
                        .param(id).query(Boolean.class).single());
        if (temDependente) {
            throw new ConflitoDadosException(
                    "Tipo de carteira em uso em contas a receber — não pode ser excluído.");
        }

        jdbc.sql("DELETE FROM moeda_detalhe WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?")
                .param(id).update();
        int linhas = jdbc.sql("DELETE FROM tipo_carteira WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?")
                .param(id).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Tipo de carteira não encontrado.");
        }
        return new ExclusaoTipoCarteiraResponse("excluido", null);
    }

    /**
     * Apaga todos os vínculos e reinsere a lista recebida — sem índice (a relação não tem
     * ordem, diferente de {@code produto_categoria}).
     */
    private void salvarMoedas(long idCarteira, List<Long> moedas) {
        jdbc.sql("DELETE FROM moeda_detalhe WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?")
                .param(idCarteira).update();
        if (moedas == null) {
            return;
        }
        for (Long idMoeda : moedas) {
            jdbc.sql("""
                            INSERT INTO moeda_detalhe (id_tenant, id_moeda, id_carteira)
                            VALUES (plataforma.tenant_atual(), ?, ?)
                            """)
                    .params(idMoeda, idCarteira)
                    .update();
        }
    }

    /**
     * Validação de servidor (defesa em profundidade): parcelas mínima/máxima (mesmo CHECK do
     * banco, mensagem amigável em vez de 500), percentual da taxa administradora e moeda
     * duplicada na lista (rejeitada aqui porque só apareceria depois de já ter apagado os
     * vínculos antigos).
     */
    private static void validar(TipoCarteiraRequest req) {
        if (req.pcMinima() < 1) {
            throw new IllegalArgumentException("Nº mínimo de parcelas deve ser pelo menos 1.");
        }
        if (req.pcMaxima() < req.pcMinima()) {
            throw new IllegalArgumentException("Nº máximo de parcelas deve ser maior ou igual ao mínimo.");
        }
        if (req.prazoPagamento() < 0) {
            throw new IllegalArgumentException("Prazo de pagamento não pode ser negativo.");
        }
        // Opcional (2026-07-23) — nem todo tipo de carteira cobra taxa administradora.
        if (req.taxaAdministradora() != null && req.taxaAdministradora().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Taxa administradora não pode ser negativa.");
        }
        if (req.moedas() != null) {
            long distintas = req.moedas().stream().distinct().count();
            if (distintas != req.moedas().size()) {
                throw new IllegalArgumentException("Moeda duplicada na lista.");
            }
        }
    }

    /**
     * Traduz a violação de FK crua (moeda_detalhe→moeda inexistente, ou de outro tenant) numa
     * mensagem amigável (400, não 500) — mesmo princípio de {@code ProdutoService.erroDeVinculo}.
     */
    private static IllegalArgumentException erroDeVinculo(DataIntegrityViolationException e) {
        return new IllegalArgumentException("Moeda informada não existe.");
    }

    private List<MoedaSelecionada> buscarMoedas(long idCarteira) {
        return jdbc.sql("""
                        SELECT m.id_moeda, m.nome_moeda
                        FROM moeda_detalhe md
                        JOIN moeda m ON m.id_moeda = md.id_moeda AND m.id_tenant = md.id_tenant
                        WHERE md.id_tenant = plataforma.tenant_atual() AND md.id_carteira = ?
                        ORDER BY m.nome_moeda
                        """)
                .param(idCarteira)
                .query((rs, n) -> new MoedaSelecionada(rs.getLong("id_moeda"), rs.getString("nome_moeda")))
                .list();
    }

    private static final String SELECT_BASE = """
            SELECT tc.id_carteira, tc.nome_carteira, tc.prazo_pagamento, tc.pc_minima, tc.pc_maxima,
                   tc.taxa_administradora, tc.criado_em, tc.atualizado_em
            FROM tipo_carteira tc
            """;

    private TipoCarteiraResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id_carteira");
        return new TipoCarteiraResponse(
                id,
                rs.getString("nome_carteira"),
                rs.getInt("prazo_pagamento"),
                rs.getInt("pc_minima"),
                rs.getInt("pc_maxima"),
                rs.getBigDecimal("taxa_administradora"),
                buscarMoedas(id),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
