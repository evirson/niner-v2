package com.vetor.niner.fiscal.perfil;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.ExclusaoPerfilFiscalResponse;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PaginaPerfisFiscais;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PerfilFiscalListaResponse;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PerfilFiscalOpcaoResponse;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PerfilFiscalRegraRequest;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PerfilFiscalRegraResponse;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PerfilFiscalRequest;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.PerfilFiscalResponse;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.TipoDestinatarioFiscal;
import com.vetor.niner.fiscal.perfil.PerfilFiscalDtos.TipoOperacaoFiscal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de Perfis Fiscais (docs/telas/fiscal-perfil.md) — cabeçalho ({@code cfg_perfil_fiscal}) +
 * coleção filha ordenada de regras ({@code cfg_perfil_fiscal_regra}), mesmo mecanismo de
 * "apaga e reinsere a cada save" de {@code ProdutoService.salvarCategorias} e
 * {@code TipoCarteiraService}.
 *
 * <p><b>Somente ADMIN</b> — uma regra errada aqui vira nota errada em todos os produtos que
 * apontam para o perfil, não é edição de operador. O {@code /opcoes} é a exceção deliberada
 * (sem checagem de papel): o cadastro de Produto é operado por {@code OPERADOR} e precisa do
 * {@code <select>} de perfis — mesmo padrão de {@code ContaCorrenteService.listarOpcoes}.
 *
 * <p>P8/F8: {@code id_tenant} explícito no texto de toda consulta, além do RLS — inclusive no
 * {@code JOIN} regra↔perfil e no {@code EXISTS} de produto vinculado na exclusão.
 */
@Service
public class PerfilFiscalService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    /** DF37: só os CRT que o produto atende. Mesmo domínio do CHECK do banco e do motor. */
    private static final Set<Integer> CRT_ATENDIDOS = Set.of(1, 2, 4);

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "nome", "p.nome",
            "ativo", "p.ativo",
            "criadoEm", "p.criado_em");

    private final JdbcClient jdbc;

    public PerfilFiscalService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PaginaPerfisFiscais listar(Jwt jwt, String busca, Integer pagina, Integer limite,
                                      String ordenarPor, String direcao) {
        exigirAdmin(jwt);
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String coluna = ordenarPor == null ? "p.nome" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "p.nome");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE p.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();
        if (busca != null && !busca.isBlank()) {
            filtro.append(" AND p.nome ILIKE ?");
            params.add("%" + busca.trim() + "%");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM cfg_perfil_fiscal p" + filtro)
                .params(params).query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        String ordenacao = " ORDER BY " + coluna + " " + direcaoOrdenacao
                + ", p.id_perfil_fiscal " + direcaoOrdenacao + " LIMIT ? OFFSET ?";

        List<PerfilFiscalListaResponse> itens = jdbc.sql("""
                        SELECT p.id_perfil_fiscal, p.nome, p.descricao, p.ativo, p.criado_em, p.atualizado_em,
                               (SELECT count(*) FROM cfg_perfil_fiscal_regra r
                                 WHERE r.id_tenant = p.id_tenant AND r.id_perfil_fiscal = p.id_perfil_fiscal) AS qtd_regras
                        FROM cfg_perfil_fiscal p
                        """ + filtro + ordenacao)
                .params(paramsPagina)
                .query(PerfilFiscalService::mapearLista)
                .list();

        return new PaginaPerfisFiscais(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public PerfilFiscalResponse buscar(Jwt jwt, long id) {
        exigirAdmin(jwt);
        return carregar(id);
    }

    /** Sem checagem de papel — alimenta o {@code <select>} de Produto, operado por OPERADOR. */
    @Transactional(readOnly = true)
    public List<PerfilFiscalOpcaoResponse> listarOpcoes() {
        return jdbc.sql("""
                        SELECT id_perfil_fiscal, nome FROM cfg_perfil_fiscal
                        WHERE id_tenant = plataforma.tenant_atual() AND ativo = true
                        ORDER BY nome ASC
                        """)
                .query((rs, n) -> new PerfilFiscalOpcaoResponse(rs.getLong("id_perfil_fiscal"), rs.getString("nome")))
                .list();
    }

    @Transactional
    public PerfilFiscalResponse criar(Jwt jwt, PerfilFiscalRequest req) {
        exigirAdmin(jwt);
        validarRegras(req.regras());
        try {
            long id = jdbc.sql("""
                            INSERT INTO cfg_perfil_fiscal (id_tenant, nome, descricao, ativo)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?)
                            RETURNING id_perfil_fiscal
                            """)
                    .params(req.nome().trim().toUpperCase(Locale.ROOT), tratarDescricao(req.descricao()), req.ativo())
                    .query(Long.class).single();
            salvarRegras(id, req.regras());
            return carregar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um perfil fiscal com esse nome.");
        }
    }

    @Transactional
    public PerfilFiscalResponse atualizar(Jwt jwt, long id, PerfilFiscalRequest req) {
        exigirAdmin(jwt);
        validarRegras(req.regras());
        try {
            int linhas = jdbc.sql("""
                            UPDATE cfg_perfil_fiscal SET nome = ?, descricao = ?, ativo = ?, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_perfil_fiscal = ?
                            """)
                    .params(req.nome().trim().toUpperCase(Locale.ROOT), tratarDescricao(req.descricao()),
                            req.ativo(), id)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Perfil fiscal não encontrado.");
            }
            salvarRegras(id, req.regras());
            return carregar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um perfil fiscal com esse nome.");
        }
    }

    /**
     * Exclui de verdade quando nenhum produto aponta para o perfil; cai para inativar quando há
     * vínculo — mesmo mecanismo de {@code FornecedorService.excluir}. Perfil inativo some do
     * {@code /opcoes} mas continua valendo para quem já aponta para ele (não quebra emissão de
     * quem já estava emitindo).
     */
    @Transactional
    public ExclusaoPerfilFiscalResponse excluir(Jwt jwt, long id) {
        exigirAdmin(jwt);
        boolean temProduto = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM produto
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_perfil_fiscal = ?)
                        """)
                .param(id).query(Boolean.class).single());

        if (temProduto) {
            int linhas = jdbc.sql("""
                            UPDATE cfg_perfil_fiscal SET ativo = false, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_perfil_fiscal = ?
                            """)
                    .param(id).update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Perfil fiscal não encontrado.");
            }
            return new ExclusaoPerfilFiscalResponse("inativado", "Perfil fiscal em uso por um ou mais produtos.");
        }

        // Sem ON DELETE CASCADE em cfg_perfil_fiscal_regra_perfil_fk (de propósito — apagar
        // regra é sempre decisão explícita desta service, nunca efeito colateral de FK); as
        // regras vão junto por SQL explícito, na mesma transação.
        jdbc.sql("DELETE FROM cfg_perfil_fiscal_regra WHERE id_tenant = plataforma.tenant_atual() AND id_perfil_fiscal = ?")
                .param(id).update();
        int linhas = jdbc.sql("""
                        DELETE FROM cfg_perfil_fiscal
                        WHERE id_tenant = plataforma.tenant_atual() AND id_perfil_fiscal = ?
                        """)
                .param(id).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Perfil fiscal não encontrado.");
        }
        return new ExclusaoPerfilFiscalResponse("excluido", null);
    }

    // ---------------------------------------------------------------- regras

    /**
     * Validação de servidor (defesa em profundidade, mesmo espírito do CHECK do banco, mas com
     * mensagem legível): CST xor CSOSN, CRT dentro do escopo do produto (DF37), CST de ICMS só
     * no CRT 2, e nenhuma combinação de contexto repetida dentro do próprio perfil.
     */
    private static void validarRegras(List<PerfilFiscalRegraRequest> regras) {
        Set<String> combinacoesVistas = new HashSet<>();
        for (PerfilFiscalRegraRequest r : regras) {
            if (!CRT_ATENDIDOS.contains(r.crt())) {
                throw new IllegalArgumentException(
                        ("CRT %d fora do escopo do produto: o Niner atende Simples Nacional (CRT 1 e 2) e "
                                + "MEI (CRT 4). Lucro Real e Lucro Presumido não são atendidos.").formatted(r.crt()));
            }
            boolean temCst = r.cstIcms() != null && !r.cstIcms().isBlank();
            boolean temCsosn = r.csosn() != null && !r.csosn().isBlank();
            if (temCst == temCsosn) {
                throw new IllegalArgumentException(
                        "Informe CST de ICMS OU CSOSN — nunca os dois, nunca nenhum (CRT %d).".formatted(r.crt()));
            }
            if (temCst && r.crt() != 2) {
                throw new IllegalArgumentException(
                        ("CST de ICMS só é aceito no CRT 2 (Simples com excesso de sublimite). "
                                + "CRT %d emite com CSOSN.").formatted(r.crt()));
            }

            String chave = r.crt() + "|" + normalizarUf(r.ufDestino()) + "|" + r.tipoDestinatario() + "|" + r.tipoOperacao();
            if (!combinacoesVistas.add(chave)) {
                throw new IllegalArgumentException(
                        ("Regra duplicada: CRT %d, UF %s, destinatário %s, operação %s já aparece outra vez "
                                + "neste perfil.").formatted(r.crt(), normalizarUf(r.ufDestino()),
                                r.tipoDestinatario(), r.tipoOperacao()));
            }
        }
    }

    /**
     * Apaga todas as regras do perfil e reinsere na ordem recebida — mesmo mecanismo de
     * {@code ProdutoService.salvarCategorias}, não um PATCH por linha.
     */
    private void salvarRegras(long idPerfilFiscal, List<PerfilFiscalRegraRequest> regras) {
        jdbc.sql("""
                        DELETE FROM cfg_perfil_fiscal_regra
                        WHERE id_tenant = plataforma.tenant_atual() AND id_perfil_fiscal = ?
                        """)
                .param(idPerfilFiscal).update();

        try {
            for (PerfilFiscalRegraRequest r : regras) {
                jdbc.sql("""
                                INSERT INTO cfg_perfil_fiscal_regra (
                                    id_tenant, id_perfil_fiscal, crt, uf_destino, tipo_destinatario, tipo_operacao,
                                    cfop, cst_icms, csosn, aliquota_icms, perc_reducao_bc, mva_st, aliquota_fcp,
                                    cst_pis, aliquota_pis, cst_cofins, aliquota_cofins,
                                    cst_ibscbs, cclasstrib, codigo_beneficio)
                                VALUES (plataforma.tenant_atual(), ?, ?, ?, ?::tipo_destinatario_fiscal,
                                        ?::tipo_operacao_fiscal, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """)
                        .params(idPerfilFiscal, r.crt(), normalizarUf(r.ufDestino()),
                                r.tipoDestinatario().name(), r.tipoOperacao().name(),
                                r.cfop(), r.cstIcms(), r.csosn(),
                                nz(r.aliquotaIcms()), nz(r.percReducaoBc()), nz(r.mvaSt()), nz(r.aliquotaFcp()),
                                r.cstPis(), nz(r.aliquotaPis()), r.cstCofins(), nz(r.aliquotaCofins()),
                                r.cstIbscbs(), r.cclasstrib(), r.codigoBeneficio())
                        .update();
            }
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage() != null && e.getMessage().contains("cfg_perfil_fiscal_regra_uk")) {
                throw new ConflitoDadosException(
                        "Duas regras deste perfil coincidem em CRT, UF, destinatário e operação.");
            }
            throw e;
        }
    }

    private static String normalizarUf(String uf) {
        return uf == null || uf.isBlank() ? "*" : uf.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String tratarDescricao(String descricao) {
        return descricao == null || descricao.isBlank() ? null : descricao.trim().toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- leitura

    private PerfilFiscalResponse carregar(long id) {
        PerfilFiscalResponse base = jdbc.sql("""
                        SELECT id_perfil_fiscal, nome, descricao, ativo, criado_em, atualizado_em
                        FROM cfg_perfil_fiscal
                        WHERE id_tenant = plataforma.tenant_atual() AND id_perfil_fiscal = ?
                        """)
                .param(id)
                .query((rs, n) -> new PerfilFiscalResponse(
                        rs.getLong("id_perfil_fiscal"), rs.getString("nome"), rs.getString("descricao"),
                        rs.getBoolean("ativo"), List.of(),
                        rs.getObject("criado_em", OffsetDateTime.class),
                        rs.getObject("atualizado_em", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Perfil fiscal não encontrado."));

        List<PerfilFiscalRegraResponse> regras = jdbc.sql("""
                        SELECT id_regra, crt, uf_destino, tipo_destinatario::text AS tipo_destinatario,
                               tipo_operacao::text AS tipo_operacao, cfop, cst_icms, csosn,
                               aliquota_icms, perc_reducao_bc, mva_st, aliquota_fcp,
                               cst_pis, aliquota_pis, cst_cofins, aliquota_cofins,
                               cst_ibscbs, cclasstrib, codigo_beneficio
                        FROM cfg_perfil_fiscal_regra
                        WHERE id_tenant = plataforma.tenant_atual() AND id_perfil_fiscal = ?
                        ORDER BY (uf_destino = '*') ASC, uf_destino, crt, tipo_destinatario, tipo_operacao
                        """)
                .param(id)
                .query(PerfilFiscalService::mapearRegra)
                .list();

        return new PerfilFiscalResponse(base.idPerfilFiscal(), base.nome(), base.descricao(), base.ativo(),
                regras, base.criadoEm(), base.atualizadoEm());
    }

    private static PerfilFiscalListaResponse mapearLista(ResultSet rs, int rowNum) throws SQLException {
        return new PerfilFiscalListaResponse(
                rs.getLong("id_perfil_fiscal"), rs.getString("nome"), rs.getString("descricao"),
                rs.getBoolean("ativo"), rs.getInt("qtd_regras"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }

    private static PerfilFiscalRegraResponse mapearRegra(ResultSet rs, int rowNum) throws SQLException {
        return new PerfilFiscalRegraResponse(
                rs.getLong("id_regra"), rs.getInt("crt"), rs.getString("uf_destino"),
                TipoDestinatarioFiscal.valueOf(rs.getString("tipo_destinatario")),
                TipoOperacaoFiscal.valueOf(rs.getString("tipo_operacao")),
                rs.getString("cfop"), rs.getString("cst_icms"), rs.getString("csosn"),
                rs.getBigDecimal("aliquota_icms"), rs.getBigDecimal("perc_reducao_bc"),
                rs.getBigDecimal("mva_st"), rs.getBigDecimal("aliquota_fcp"),
                rs.getString("cst_pis"), rs.getBigDecimal("aliquota_pis"),
                rs.getString("cst_cofins"), rs.getBigDecimal("aliquota_cofins"),
                rs.getString("cst_ibscbs"), rs.getString("cclasstrib"), rs.getString("codigo_beneficio"));
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas ADMIN pode gerenciar perfis fiscais.");
        }
    }
}
