package com.vetor.niner.financeiro;

import com.vetor.niner.financeiro.MoedaDtos.ExclusaoMoedaResponse;
import com.vetor.niner.financeiro.MoedaDtos.MoedaRequest;
import com.vetor.niner.financeiro.MoedaDtos.MoedaResponse;
import com.vetor.niner.financeiro.MoedaDtos.PaginaMoedas;
import com.vetor.niner.comum.web.ConflitoDadosException;
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
 * CRUD de moeda (forma de recebimento — dinheiro, PIX, cartão, crediário...), tabela {@code
 * moeda} sob RLS de tenant (V025/V024). Já nasce com 7 linhas por tenant (seed no signup,
 * {@code SignupService}) — esta tela edita essas e permite criar novas. Sem coluna {@code
 * ativo}: exclusão sem fallback de inativar (mesmo padrão de {@code PlanoContasService}).
 * O vínculo com tipo de carteira ({@code moeda_detalhe}) é gerido pela tela de Tipo de
 * Carteira (2026-07-23) — aqui só os campos próprios da moeda.
 *
 * <p>Filtro por {@code id_tenant} explícito em toda consulta, além do RLS — indispensável
 * porque o ambiente de teste (Testcontainers) conecta como superusuário do container, que
 * ignora RLS mesmo com {@code FORCE} (mesmo motivo documentado em {@code PlanoContasService}).
 */
@Service
public class MoedaService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "nomeMoeda", "m.nome_moeda",
            "percDesconto", "m.perc_desconto",
            "percAcrescimo", "m.perc_acrescimo");

    private final JdbcClient jdbc;

    public MoedaService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PaginaMoedas listar(String busca, Integer pagina, Integer limite, String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao = ordenarPor == null ? "m.nome_moeda" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "m.nome_moeda");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE m.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();
        if (busca != null && !busca.isBlank()) {
            filtro.append(" AND m.nome_moeda ILIKE ?");
            params.add("%" + busca.trim() + "%");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM moeda m" + filtro).params(params).query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", m.id_moeda " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<MoedaResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
                .params(paramsPagina)
                .query(MoedaService::mapear)
                .list();

        return new PaginaMoedas(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public MoedaResponse buscar(long id) {
        return jdbc.sql(SELECT_BASE + " WHERE m.id_tenant = plataforma.tenant_atual() AND m.id_moeda = ?")
                .param(id)
                .query(MoedaService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Moeda não encontrada."));
    }

    @Transactional
    public MoedaResponse criar(MoedaRequest req) {
        validar(req);
        try {
            long id = jdbc.sql("""
                            INSERT INTO moeda (id_tenant, nome_moeda, perc_desconto, perc_acrescimo)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?)
                            RETURNING id_moeda
                            """)
                    .params(req.nomeMoeda().trim().toUpperCase(Locale.ROOT), req.percDesconto(), req.percAcrescimo())
                    .query(Long.class).single();
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma moeda com esse nome.");
        }
    }

    @Transactional
    public MoedaResponse atualizar(long id, MoedaRequest req) {
        validar(req);
        try {
            int linhas = jdbc.sql("""
                            UPDATE moeda SET nome_moeda = ?, perc_desconto = ?, perc_acrescimo = ?, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_moeda = ?
                            """)
                    .params(req.nomeMoeda().trim().toUpperCase(Locale.ROOT), req.percDesconto(), req.percAcrescimo(), id)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Moeda não encontrada.");
            }
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma moeda com esse nome.");
        }
    }

    /**
     * Exclui de verdade — sem fallback de inativar ({@code moeda} não tem coluna {@code
     * ativo}). Com vínculo em {@code moeda_detalhe} (algum tipo de carteira a usa) ou {@code
     * caixa_detalhe} (já usada num lançamento de caixa), responde 409 e nada muda.
     */
    @Transactional
    public ExclusaoMoedaResponse excluir(long id) {
        boolean temVinculo = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM moeda_detalhe
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_moeda = ?)
                            OR EXISTS (SELECT 1 FROM caixa_detalhe
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_moeda = ?)
                        """)
                .params(id, id).query(Boolean.class).single());
        if (temVinculo) {
            throw new ConflitoDadosException(
                    "Moeda em uso por um tipo de carteira ou lançamento de caixa — não pode ser excluída.");
        }

        int linhas = jdbc.sql("DELETE FROM moeda WHERE id_tenant = plataforma.tenant_atual() AND id_moeda = ?")
                .param(id).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Moeda não encontrada.");
        }
        return new ExclusaoMoedaResponse("excluido", null);
    }

    /**
     * Desconto e acréscimo nunca coexistem *de verdade* no mesmo registro (pedido do dono do
     * produto, 2026-07-23): a checagem é por **valor positivo**, não por presença — 0/0 (ou
     * um dos dois em branco) é o estado neutro normal de toda moeda semeada no signup, então
     * não pode disparar o erro. Só bloqueia quando os dois têm, ao mesmo tempo, um valor > 0.
     */
    private static void validar(MoedaRequest req) {
        boolean temDesconto = req.percDesconto() != null && req.percDesconto().compareTo(BigDecimal.ZERO) > 0;
        boolean temAcrescimo = req.percAcrescimo() != null && req.percAcrescimo().compareTo(BigDecimal.ZERO) > 0;
        if (temDesconto && temAcrescimo) {
            throw new IllegalArgumentException("Informe % de desconto OU % de acréscimo — nunca os dois.");
        }
        exigirNaoNegativoSePresente(req.percDesconto(), "% de desconto");
        exigirNaoNegativoSePresente(req.percAcrescimo(), "% de acréscimo");
    }

    private static void exigirNaoNegativoSePresente(BigDecimal valor, String rotulo) {
        if (valor != null && valor.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(rotulo + " não pode ser negativo.");
        }
    }

    private static final String SELECT_BASE = """
            SELECT m.id_moeda, m.nome_moeda, m.perc_desconto, m.perc_acrescimo, m.criado_em, m.atualizado_em
            FROM moeda m
            """;

    private static MoedaResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new MoedaResponse(
                rs.getLong("id_moeda"),
                rs.getString("nome_moeda"),
                rs.getBigDecimal("perc_desconto"),
                rs.getBigDecimal("perc_acrescimo"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
