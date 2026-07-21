package com.vetor.niner.cadastros.fornecedor;

import com.vetor.niner.cadastros.cliente.Documentos;
import com.vetor.niner.cadastros.fornecedor.FornecedorDtos.ExclusaoFornecedorResponse;
import com.vetor.niner.cadastros.fornecedor.FornecedorDtos.FornecedorRequest;
import com.vetor.niner.cadastros.fornecedor.FornecedorDtos.FornecedorResponse;
import com.vetor.niner.cadastros.fornecedor.FornecedorDtos.PaginaFornecedores;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaDtos.ConfiguracaoCampoResponse;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaService;
import com.vetor.niner.comum.web.ConflitoDadosException;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de fornecedores (docs/telas/fornecedor.md). Tabela {@code fornecedor} sob RLS de
 * tenant (V016/V024) — mesmo padrão de {@code cadastros.cliente}. {@code id_plano_contas} é
 * obrigatório (FK composta para {@code cfg_plano_contas}); o JOIN da listagem traz a
 * descrição do plano e **inclui o tenant** na condição (a PK do plano é composta — sem isso,
 * códigos iguais de tenants diferentes duplicariam linhas no ambiente de teste, que conecta
 * sem RLS).
 */
@Service
public class FornecedorService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;
    private static final String CHAVE_TELA_FORM = "cadastros.fornecedor.form";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Colunas ordenáveis da listagem — chave da API -> expressão SQL. */
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "razaoSocial", "f.razao_social",
            "cnpj", "f.cnpj",
            "planoContas", "pc.descricao",
            "telefone", "f.telefone",
            "cidade", "f.cidade",
            "status", "f.ativo");

    private static final Map<String, String> ROTULOS_CAMPO = Map.ofEntries(
            Map.entry("nomeFantasia", "Nome Fantasia"), Map.entry("cnpj", "CNPJ"),
            Map.entry("inscricaoEstadual", "Inscrição Estadual"), Map.entry("email", "E-mail"),
            Map.entry("telefone", "Telefone"), Map.entry("cep", "CEP"),
            Map.entry("endereco", "Endereço"), Map.entry("numero", "Número"),
            Map.entry("bairro", "Bairro"), Map.entry("cidade", "Cidade"), Map.entry("estado", "UF"));

    private final JdbcClient jdbc;
    private final ConfiguracaoTelaService configuracaoTelaService;

    public FornecedorService(JdbcClient jdbc, ConfiguracaoTelaService configuracaoTelaService) {
        this.jdbc = jdbc;
        this.configuracaoTelaService = configuracaoTelaService;
    }

    @Transactional(readOnly = true)
    public PaginaFornecedores listar(String razaoSocial, String cnpj, String idPlanoContas,
                                      String status, Integer pagina, Integer limite,
                                      String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao =
                ordenarPor == null ? "f.razao_social" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "f.razao_social");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (razaoSocial != null && !razaoSocial.isBlank()) {
            // Busca cobre razão social e nome fantasia — o lojista conhece o fornecedor
            // muitas vezes só pelo fantasia.
            filtro.append(" AND (f.razao_social ILIKE ? OR f.nome_fantasia ILIKE ?)");
            String padrao = "%" + razaoSocial.trim() + "%";
            params.add(padrao);
            params.add(padrao);
        }
        if (cnpj != null && !cnpj.isBlank()) {
            filtro.append(" AND f.cnpj = ?");
            params.add(Documentos.somenteAlfanumerico(cnpj));
        }
        if (idPlanoContas != null && !idPlanoContas.isBlank()) {
            filtro.append(" AND f.id_plano_contas = ?");
            params.add(idPlanoContas.trim().toUpperCase(Locale.ROOT));
        }
        switch (status == null ? "ATIVOS" : status.toUpperCase(Locale.ROOT)) {
            case "INATIVOS" -> filtro.append(" AND f.ativo = false");
            case "TODOS" -> { /* sem filtro de status */ }
            default -> filtro.append(" AND f.ativo = true");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM fornecedor f" + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", f.id_fornecedor " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<FornecedorResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
                .params(paramsPagina)
                .query(FornecedorService::mapear)
                .list();

        return new PaginaFornecedores(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public FornecedorResponse buscar(long id) {
        return jdbc.sql(SELECT_BASE + " WHERE f.id_fornecedor = ?")
                .param(id)
                .query(FornecedorService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Fornecedor não encontrado."));
    }

    @Transactional
    public FornecedorResponse criar(FornecedorRequest req) {
        validar(req);
        List<Object> params = new ArrayList<>();
        params.add(req.razaoSocial().trim().toUpperCase(Locale.ROOT));
        params.add(req.idPlanoContas().trim().toUpperCase(Locale.ROOT));
        adicionarCamposComuns(params, req);
        try {
            long id = jdbc.sql("""
                            INSERT INTO fornecedor (id_tenant, razao_social, id_plano_contas, nome_fantasia,
                                cnpj, inscricao_estadual, email, telefone, cep, endereco, numero, bairro,
                                cidade, estado, ativo)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING id_fornecedor
                            """)
                    .params(params)
                    .query(Long.class).single();
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um fornecedor com esse CNPJ.");
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Plano de contas informado não existe.");
        }
    }

    @Transactional
    public FornecedorResponse atualizar(long id, FornecedorRequest req) {
        validar(req);
        List<Object> params = new ArrayList<>();
        params.add(req.razaoSocial().trim().toUpperCase(Locale.ROOT));
        params.add(req.idPlanoContas().trim().toUpperCase(Locale.ROOT));
        adicionarCamposComuns(params, req);
        params.add(id);
        try {
            int linhas = jdbc.sql("""
                            UPDATE fornecedor SET
                                razao_social = ?, id_plano_contas = ?, nome_fantasia = ?, cnpj = ?,
                                inscricao_estadual = ?, email = ?, telefone = ?, cep = ?, endereco = ?,
                                numero = ?, bairro = ?, cidade = ?, estado = ?, ativo = ?,
                                atualizado_em = now()
                            WHERE id_fornecedor = ?
                            """)
                    .params(params)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Fornecedor não encontrado.");
            }
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um fornecedor com esse CNPJ.");
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Plano de contas informado não existe.");
        }
    }

    /**
     * Exclui, ou inativa em vez de excluir se houver vínculo — aqui os vínculos possíveis
     * são {@code produto_movimento_mestre.id_fornecedor} (compras/entradas de estoque, V019)
     * e {@code contas_pagar.id_fornecedor} (V026). Checagem antes do DELETE pelo mesmo
     * motivo das demais telas (FK violada aborta o resto da transação no Postgres).
     */
    @Transactional
    public ExclusaoFornecedorResponse excluir(long id) {
        boolean temVinculo = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM produto_movimento_mestre WHERE id_fornecedor = ?)
                            OR EXISTS (SELECT 1 FROM contas_pagar WHERE id_fornecedor = ?)
                        """)
                .params(id, id)
                .query(Boolean.class).single());

        if (temVinculo) {
            int linhas = jdbc.sql("UPDATE fornecedor SET ativo = false, atualizado_em = now() WHERE id_fornecedor = ?")
                    .param(id).update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Fornecedor não encontrado.");
            }
            return new ExclusaoFornecedorResponse("inativado",
                    "Fornecedor possui movimentações ou contas a pagar associadas.");
        }

        int linhas = jdbc.sql("DELETE FROM fornecedor WHERE id_fornecedor = ?").param(id).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Fornecedor não encontrado.");
        }
        return new ExclusaoFornecedorResponse("excluido", null);
    }

    /**
     * Validação de servidor (defesa em profundidade, padrão do módulo): CNPJ alfanumérico
     * com dígito verificador (sempre 14 caracteres — fornecedor é pessoa jurídica, CPF não
     * é aceito aqui), e-mail, telefone (10–11 dígitos, fixo ou celular — regra mais frouxa
     * que a do cliente, que exige celular), CEP e a obrigatoriedade configurável por tenant.
     */
    private void validar(FornecedorRequest req) {
        String cnpj = Documentos.somenteAlfanumerico(req.cnpj());
        if (cnpj != null && !cnpj.isEmpty() && (cnpj.length() != 14 || !Documentos.valido(cnpj))) {
            throw new IllegalArgumentException("CNPJ inválido (dígito verificador não confere).");
        }
        if (req.email() != null && !req.email().isBlank() && !EMAIL_PATTERN.matcher(req.email()).matches()) {
            throw new IllegalArgumentException("E-mail inválido.");
        }
        String telefone = Documentos.somenteDigitos(req.telefone());
        if (telefone != null && !telefone.isEmpty() && (telefone.length() < 10 || telefone.length() > 11)) {
            throw new IllegalArgumentException("Telefone deve ter 10 ou 11 dígitos (com DDD).");
        }
        String cepDigitos = Documentos.somenteDigitos(req.cep());
        if (cepDigitos != null && !cepDigitos.isEmpty() && cepDigitos.length() != 8) {
            throw new IllegalArgumentException("CEP inválido.");
        }

        Map<String, ConfiguracaoCampoResponse> config = configuracaoTelaService.listar(CHAVE_TELA_FORM).stream()
                .collect(Collectors.toMap(ConfiguracaoCampoResponse::campo, c -> c));
        exigirSeObrigatorio(config, "nomeFantasia", req.nomeFantasia());
        exigirSeObrigatorio(config, "cnpj", req.cnpj());
        exigirSeObrigatorio(config, "inscricaoEstadual", req.inscricaoEstadual());
        exigirSeObrigatorio(config, "email", req.email());
        exigirSeObrigatorio(config, "telefone", req.telefone());
        exigirSeObrigatorio(config, "cep", req.cep());
        exigirSeObrigatorio(config, "endereco", req.endereco());
        exigirSeObrigatorio(config, "numero", req.numero());
        exigirSeObrigatorio(config, "bairro", req.bairro());
        exigirSeObrigatorio(config, "cidade", req.cidade());
        exigirSeObrigatorio(config, "estado", req.estado());
    }

    private static void exigirSeObrigatorio(Map<String, ConfiguracaoCampoResponse> config, String campo, String valor) {
        ConfiguracaoCampoResponse c = config.get(campo);
        if (c != null && c.obrigatorio() && (valor == null || valor.isBlank())) {
            throw new IllegalArgumentException(ROTULOS_CAMPO.getOrDefault(campo, campo) + " é obrigatório.");
        }
    }

    /**
     * Campos comuns a INSERT/UPDATE (após razão social e plano de contas), na mesma ordem
     * das SQLs. Texto livre em MAIÚSCULAS (convenção do projeto); exceção: e-mail. CNPJ com
     * {@code somenteAlfanumerico} — convenção do CNPJ alfanumérico (CLAUDE.md).
     */
    private static void adicionarCamposComuns(List<Object> params, FornecedorRequest r) {
        params.add(trimMaiusculoOuNulo(r.nomeFantasia()));
        params.add(Documentos.somenteAlfanumerico(vazioParaNulo(r.cnpj())));
        params.add(trimMaiusculoOuNulo(r.inscricaoEstadual()));
        params.add(trimOuNulo(r.email()));
        params.add(vazioParaNulo(Documentos.somenteDigitos(r.telefone())));
        params.add(vazioParaNulo(Documentos.somenteDigitos(r.cep())));
        params.add(trimMaiusculoOuNulo(r.endereco()));
        params.add(trimMaiusculoOuNulo(r.numero()));
        params.add(trimMaiusculoOuNulo(r.bairro()));
        params.add(trimMaiusculoOuNulo(r.cidade()));
        params.add(r.estado() == null || r.estado().isBlank() ? null : r.estado().trim().toUpperCase(Locale.ROOT));
        params.add(r.ativo() == null || r.ativo());
    }

    private static String trimOuNulo(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String trimMaiusculoOuNulo(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * CNPJ/telefone/CEP vazios devem virar NULL, não string vazia — string vazia colidiria
     * na unique de CNPJ ({@code fornecedor_cnpj_uk}) entre fornecedores sem CNPJ.
     */
    private static String vazioParaNulo(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static final String SELECT_BASE = """
            SELECT f.id_fornecedor, f.razao_social, f.id_plano_contas, pc.descricao AS descricao_plano_contas,
                   f.nome_fantasia, f.cnpj, f.inscricao_estadual, f.email, f.telefone, f.cep,
                   f.endereco, f.numero, f.bairro, f.cidade, f.estado, f.ativo, f.criado_em, f.atualizado_em
            FROM fornecedor f
            JOIN cfg_plano_contas pc
              ON pc.id_tenant = f.id_tenant AND pc.id_plano_contas = f.id_plano_contas
            """;

    private static FornecedorResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new FornecedorResponse(
                rs.getLong("id_fornecedor"),
                rs.getString("razao_social"),
                rs.getString("id_plano_contas"),
                rs.getString("descricao_plano_contas"),
                rs.getString("nome_fantasia"),
                rs.getString("cnpj"),
                rs.getString("inscricao_estadual"),
                rs.getString("email"),
                rs.getString("telefone"),
                rs.getString("cep"),
                rs.getString("endereco"),
                rs.getString("numero"),
                rs.getString("bairro"),
                rs.getString("cidade"),
                rs.getString("estado"),
                rs.getBoolean("ativo"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
