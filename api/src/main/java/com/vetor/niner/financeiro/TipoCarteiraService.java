package com.vetor.niner.financeiro;

import com.vetor.niner.cadastros.cliente.Documentos;
import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.financeiro.TipoCarteiraDtos.CategoriaCarteira;
import com.vetor.niner.financeiro.TipoCarteiraDtos.ExclusaoTipoCarteiraResponse;
import com.vetor.niner.financeiro.TipoCarteiraDtos.PaginaTiposCarteira;
import com.vetor.niner.financeiro.TipoCarteiraDtos.TipoCarteiraRequest;
import com.vetor.niner.financeiro.TipoCarteiraDtos.TipoCarteiraResponse;
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
 * CRUD de tipo de carteira (categoria/prazo/parcelas/taxa/desconto/acréscimo de cada forma de
 * pagamento), tabela {@code tipo_carteira} sob RLS de tenant (V025/V024). Absorveu o cadastro
 * de {@code moeda} em 2026-07-28 (motivo completo no comentário de topo de V025): a mesma
 * bandeira podia estar vinculada a mais de uma categoria via {@code moeda_detalhe}, mas
 * prazo/parcelas/taxa são diferentes entre categorias — agora cada combinação categoria×nome é
 * sua própria linha (chave única inclui a categoria). Sem coluna {@code ativo}: exclusão sem
 * fallback de inativar.
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
            "taxaAdministradora", "tc.taxa_administradora",
            "categoriaCarteira", "tc.categoria_carteira",
            "percDesconto", "tc.perc_desconto",
            "percAcrescimo", "tc.perc_acrescimo");

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
                .query(TipoCarteiraService::mapear)
                .list();

        return new PaginaTiposCarteira(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public TipoCarteiraResponse buscar(long id) {
        return jdbc.sql(SELECT_BASE + " WHERE tc.id_tenant = plataforma.tenant_atual() AND tc.id_carteira = ?")
                .param(id)
                .query(TipoCarteiraService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Tipo de carteira não encontrado."));
    }

    @Transactional
    public TipoCarteiraResponse criar(TipoCarteiraRequest req) {
        validar(req);
        try {
            long id = jdbc.sql("""
                            INSERT INTO tipo_carteira
                                (id_tenant, nome_carteira, categoria_carteira, prazo_pagamento, pc_minima, pc_maxima,
                                 taxa_administradora, perc_desconto, perc_acrescimo, permite_receber_crediario,
                                 codigo_tpag, codigo_bandeira, cnpj_credenciadora)
                            VALUES (plataforma.tenant_atual(), ?, ?::categoria_carteira, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING id_carteira
                            """)
                    .params(req.nomeCarteira().trim().toUpperCase(Locale.ROOT), req.categoriaCarteira().name(),
                            req.prazoPagamento(), req.pcMinima(), req.pcMaxima(), req.taxaAdministradora(),
                            req.percDesconto(), req.percAcrescimo(), req.permiteReceberCrediario(),
                            normalizarCodigo(req.codigoTpag()), normalizarCodigo(req.codigoBandeira()),
                            Documentos.somenteAlfanumerico(req.cnpjCredenciadora()))
                    .query(Long.class).single();
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um tipo de carteira com esse nome nesta categoria.");
        }
    }

    @Transactional
    public TipoCarteiraResponse atualizar(long id, TipoCarteiraRequest req) {
        validar(req);
        try {
            int linhas = jdbc.sql("""
                            UPDATE tipo_carteira SET
                                nome_carteira = ?, categoria_carteira = ?::categoria_carteira, prazo_pagamento = ?,
                                pc_minima = ?, pc_maxima = ?, taxa_administradora = ?,
                                perc_desconto = ?, perc_acrescimo = ?, permite_receber_crediario = ?,
                                codigo_tpag = ?, codigo_bandeira = ?, cnpj_credenciadora = ?, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?
                            """)
                    .params(req.nomeCarteira().trim().toUpperCase(Locale.ROOT), req.categoriaCarteira().name(),
                            req.prazoPagamento(), req.pcMinima(), req.pcMaxima(), req.taxaAdministradora(),
                            req.percDesconto(), req.percAcrescimo(), req.permiteReceberCrediario(),
                            normalizarCodigo(req.codigoTpag()), normalizarCodigo(req.codigoBandeira()),
                            Documentos.somenteAlfanumerico(req.cnpjCredenciadora()), id)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Tipo de carteira não encontrado.");
            }
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um tipo de carteira com esse nome nesta categoria.");
        }
    }

    /** {@code codigoTpag}/{@code codigoBandeira} vêm de um {@code <select>} de códigos fixos no
     *  front — normaliza só pra evitar string vazia virando {@code ''} em vez de {@code NULL}
     *  (a Conformidade Fiscal checa {@code IS NULL}, não {@code = ''}). */
    private static String normalizarCodigo(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    /**
     * Exclui de verdade — sem fallback de inativar ({@code tipo_carteira} não tem coluna
     * {@code ativo}). Com vínculo em {@code contas_receber} (já usado numa venda) ou {@code
     * caixa_detalhe} (já usado num lançamento de caixa), responde 409 e nada muda.
     */
    @Transactional
    public ExclusaoTipoCarteiraResponse excluir(long id) {
        boolean temDependente = Boolean.TRUE.equals(
                jdbc.sql("""
                                SELECT EXISTS (SELECT 1 FROM contas_receber
                                               WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?)
                                    OR EXISTS (SELECT 1 FROM caixa_detalhe
                                               WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?)
                                """)
                        .params(id, id).query(Boolean.class).single());
        if (temDependente) {
            throw new ConflitoDadosException(
                    "Tipo de carteira em uso em contas a receber ou lançamento de caixa — não pode ser excluído.");
        }

        int linhas = jdbc.sql("DELETE FROM tipo_carteira WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?")
                .param(id).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Tipo de carteira não encontrado.");
        }
        return new ExclusaoTipoCarteiraResponse("excluido", null);
    }

    /**
     * Validação de servidor (defesa em profundidade): parcelas mínima/máxima (mesmo CHECK do
     * banco, mensagem amigável em vez de 500), taxa administradora, e desconto/acréscimo
     * (herdado de {@code MoedaService}, 2026-07-28) — nunca coexistem *de verdade* no mesmo
     * registro; a checagem é por **valor positivo**, não por presença — 0/0 (ou um dos dois em
     * branco) é o estado neutro normal, não pode disparar o erro.
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

        boolean temDesconto = req.percDesconto() != null && req.percDesconto().compareTo(BigDecimal.ZERO) > 0;
        boolean temAcrescimo = req.percAcrescimo() != null && req.percAcrescimo().compareTo(BigDecimal.ZERO) > 0;
        if (temDesconto && temAcrescimo) {
            throw new IllegalArgumentException("Informe % de desconto OU % de acréscimo — nunca os dois.");
        }
        exigirNaoNegativoSePresente(req.percDesconto(), "% de desconto");
        exigirNaoNegativoSePresente(req.percAcrescimo(), "% de acréscimo");

        // Fiscal (2026-08-18) — opcional no cadastro (a Conformidade Fiscal cobra sem bloquear),
        // mas quando preenchido tem que ser um CNPJ de verdade (dígito verificador).
        if (req.cnpjCredenciadora() != null && !req.cnpjCredenciadora().isBlank()
                && !Documentos.valido(req.cnpjCredenciadora())) {
            throw new IllegalArgumentException("CNPJ da credenciadora inválido.");
        }
    }

    private static void exigirNaoNegativoSePresente(BigDecimal valor, String rotulo) {
        if (valor != null && valor.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(rotulo + " não pode ser negativo.");
        }
    }

    private static final String SELECT_BASE = """
            SELECT tc.id_carteira, tc.nome_carteira, tc.categoria_carteira::text AS categoria_carteira,
                   tc.prazo_pagamento, tc.pc_minima, tc.pc_maxima,
                   tc.taxa_administradora, tc.perc_desconto, tc.perc_acrescimo, tc.permite_receber_crediario,
                   tc.codigo_tpag, tc.codigo_bandeira, tc.cnpj_credenciadora,
                   tc.criado_em, tc.atualizado_em
            FROM tipo_carteira tc
            """;

    private static TipoCarteiraResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new TipoCarteiraResponse(
                rs.getLong("id_carteira"),
                rs.getString("nome_carteira"),
                CategoriaCarteira.valueOf(rs.getString("categoria_carteira")),
                rs.getInt("prazo_pagamento"),
                rs.getInt("pc_minima"),
                rs.getInt("pc_maxima"),
                rs.getBigDecimal("taxa_administradora"),
                rs.getBigDecimal("perc_desconto"),
                rs.getBigDecimal("perc_acrescimo"),
                rs.getBoolean("permite_receber_crediario"),
                rs.getString("codigo_tpag"),
                rs.getString("codigo_bandeira"),
                rs.getString("cnpj_credenciadora"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
