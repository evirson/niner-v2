package com.vetor.niner.cadastros.cliente;

import com.vetor.niner.cadastros.cliente.ClienteDtos.ClienteRequest;
import com.vetor.niner.cadastros.cliente.ClienteDtos.ClienteResponse;
import com.vetor.niner.cadastros.cliente.ClienteDtos.ExclusaoClienteResponse;
import com.vetor.niner.cadastros.cliente.ClienteDtos.Genero;
import com.vetor.niner.cadastros.cliente.ClienteDtos.PaginaClientes;
import com.vetor.niner.comum.web.ConflitoDadosException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de clientes (docs/telas/cliente.md). Tabela {@code cliente} sob RLS de tenant
 * (V016/V024) — toda leitura já é restrita ao tenant do contexto atual (P8); o INSERT usa
 * {@code plataforma.tenant_atual()} explicitamente porque a política WITH CHECK exige o
 * valor (RLS não o preenche sozinho).
 */
@Service
public class ClienteService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    private final JdbcClient jdbc;

    public ClienteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Listagem paginada por número de página (não mais por cursor — revisão de 2026-07-21:
     * a navegação "1 2 3 … 10 20 >" pedida pelo dono do produto exige saber o total de
     * páginas e pular direto para qualquer uma, o que um cursor opaco não permite). O
     * volume de clientes por tenant é pequeno o bastante para {@code OFFSET} não pesar.
     */
    @Transactional(readOnly = true)
    public PaginaClientes listar(String nome, String cpfCnpj, Long idCategoriaCliente,
                                  String status, Integer pagina, Integer limite) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);

        StringBuilder filtro = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (nome != null && !nome.isBlank()) {
            filtro.append(" AND c.nome ILIKE ?");
            params.add("%" + nome.trim() + "%");
        }
        if (cpfCnpj != null && !cpfCnpj.isBlank()) {
            filtro.append(" AND c.cpf_cnpj = ?");
            params.add(Documentos.somenteDigitos(cpfCnpj));
        }
        if (idCategoriaCliente != null) {
            filtro.append(" AND c.id_categoria_cliente = ?");
            params.add(idCategoriaCliente);
        }
        switch (status == null ? "ATIVOS" : status.toUpperCase(Locale.ROOT)) {
            case "INATIVOS" -> filtro.append(" AND c.ativo = false");
            case "TODOS" -> { /* sem filtro de status */ }
            default -> filtro.append(" AND c.ativo = true");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM cliente c" + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        List<ClienteResponse> itens = jdbc.sql(SELECT_BASE + filtro + " ORDER BY c.nome, c.id_cliente LIMIT ? OFFSET ?")
                .params(paramsPagina)
                .query(ClienteService::mapear)
                .list();

        return new PaginaClientes(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public ClienteResponse buscar(long id) {
        return jdbc.sql(SELECT_BASE + " WHERE c.id_cliente = ?")
                .param(id)
                .query(ClienteService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Cliente não encontrado."));
    }

    @Transactional
    public ClienteResponse criar(ClienteRequest req) {
        validar(req);
        List<Object> params = new ArrayList<>();
        params.add(req.fisicaJuridica());
        params.add(req.nome().trim().toUpperCase(Locale.ROOT));
        params.add(req.idCategoriaCliente());
        adicionarCamposComuns(params, req);

        try {
            long id = jdbc.sql("""
                            INSERT INTO cliente (id_tenant, fisica_juridica, nome, id_categoria_cliente,
                                cpf_cnpj, rg_ie, data_nascimento, genero, email, telefone, whatsapp,
                                instagram, facebook, tiktok, cep, endereco, numero, complemento, bairro,
                                cidade, estado, limite_credito, ativo)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?::genero_cliente, ?, ?,
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING id_cliente
                            """)
                    .params(params)
                    .query(Long.class).single();
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw duplicidade(e);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Categoria informada não existe.");
        }
    }

    @Transactional
    public ClienteResponse atualizar(long id, ClienteRequest req) {
        validar(req);
        List<Object> params = new ArrayList<>();
        params.add(req.fisicaJuridica());
        params.add(req.nome().trim().toUpperCase(Locale.ROOT));
        params.add(req.idCategoriaCliente());
        adicionarCamposComuns(params, req);
        params.add(id);

        try {
            int linhas = jdbc.sql("""
                            UPDATE cliente SET
                                fisica_juridica = ?, nome = ?, id_categoria_cliente = ?, cpf_cnpj = ?,
                                rg_ie = ?, data_nascimento = ?, genero = ?::genero_cliente, email = ?,
                                telefone = ?, whatsapp = ?, instagram = ?, facebook = ?, tiktok = ?,
                                cep = ?, endereco = ?, numero = ?, complemento = ?, bairro = ?,
                                cidade = ?, estado = ?, limite_credito = ?, ativo = ?, atualizado_em = now()
                            WHERE id_cliente = ?
                            """)
                    .params(params)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Cliente não encontrado.");
            }
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw duplicidade(e);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Categoria informada não existe.");
        }
    }

    /**
     * Exclui, ou inativa em vez de excluir se houver vínculo (ex.: venda) —
     * docs/telas/cliente.md. Verifica o vínculo ANTES de tentar o DELETE: uma vez que uma
     * instrução falha por violação de FK, o Postgres aborta o resto da transação (não dá
     * pra simplesmente capturar a exceção e seguir com um UPDATE na mesma transação sem um
     * SAVEPOINT) — checar antes evita a complexidade de transação aninhada.
     */
    @Transactional
    public ExclusaoClienteResponse excluir(long id) {
        boolean temVenda = Boolean.TRUE.equals(
                jdbc.sql("SELECT EXISTS (SELECT 1 FROM venda WHERE id_cliente = ?)")
                        .param(id).query(Boolean.class).single());

        if (temVenda) {
            int linhas = jdbc.sql("UPDATE cliente SET ativo = false, atualizado_em = now() WHERE id_cliente = ?")
                    .param(id).update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Cliente não encontrado.");
            }
            return new ExclusaoClienteResponse("inativado", "Cliente possui vendas associadas.");
        }

        int linhas = jdbc.sql("DELETE FROM cliente WHERE id_cliente = ?").param(id).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Cliente não encontrado.");
        }
        return new ExclusaoClienteResponse("excluido", null);
    }

    private void validar(ClienteRequest req) {
        if (req.fisicaJuridica() && req.genero() == null) {
            throw new IllegalArgumentException("Gênero é obrigatório para pessoa física.");
        }
        // Data de nascimento é sempre opcional (2026-07-21); quando preenchida, não pode ser
        // hoje nem no futuro.
        if (req.dataNascimento() != null && !req.dataNascimento().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Data de nascimento não pode ser hoje ou no futuro.");
        }
        if (req.cpfCnpj() != null && !req.cpfCnpj().isBlank() && !Documentos.valido(req.cpfCnpj())) {
            throw new IllegalArgumentException("CPF/CNPJ inválido (dígito verificador não confere).");
        }
    }

    /**
     * Campos comuns a INSERT/UPDATE, na mesma ordem em que aparecem nas duas SQLs acima.
     * Todo campo de texto livre é normalizado para MAIÚSCULAS aqui — mesma convenção
     * aplicada no frontend (independe do estado do teclado do usuário), reforçada no
     * backend como defesa em profundidade contra outros clientes da API. Exceção: e-mail
     * (caixa preservada, convenção usual de e-mail).
     */
    private static void adicionarCamposComuns(List<Object> params, ClienteRequest r) {
        params.add(Documentos.somenteDigitos(r.cpfCnpj()));
        params.add(trimMaiusculoOuNulo(r.rgIe()));
        params.add(r.dataNascimento());
        params.add(r.genero() == null ? null : r.genero().name());
        params.add(trimOuNulo(r.email()));
        params.add(Documentos.somenteDigitos(r.telefone()));
        params.add(Documentos.somenteDigitos(r.whatsapp()));
        params.add(trimMaiusculoOuNulo(r.instagram()));
        params.add(trimMaiusculoOuNulo(r.facebook()));
        params.add(trimMaiusculoOuNulo(r.tiktok()));
        params.add(Documentos.somenteDigitos(r.cep()));
        params.add(trimMaiusculoOuNulo(r.endereco()));
        params.add(trimMaiusculoOuNulo(r.numero()));
        params.add(trimMaiusculoOuNulo(r.complemento()));
        params.add(trimMaiusculoOuNulo(r.bairro()));
        params.add(trimMaiusculoOuNulo(r.cidade()));
        params.add(r.estado() == null || r.estado().isBlank() ? null : r.estado().trim().toUpperCase(Locale.ROOT));
        params.add(r.limiteCredito() == null ? BigDecimal.ZERO : r.limiteCredito());
        params.add(r.ativo() == null || r.ativo());
    }

    private static ConflitoDadosException duplicidade(DuplicateKeyException e) {
        String causa = String.valueOf(e.getRootCause());
        if (causa.contains("cliente_documento_uk")) {
            return new ConflitoDadosException("Já existe um cliente com esse CPF/CNPJ.");
        }
        return new ConflitoDadosException("Já existe um cliente com esses dados.");
    }

    private static String trimOuNulo(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String trimMaiusculoOuNulo(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static final String SELECT_BASE = """
            SELECT c.id_cliente, c.fisica_juridica, c.nome, c.id_categoria_cliente,
                   cc.nome_categoria, c.cpf_cnpj, c.rg_ie, c.data_nascimento,
                   c.genero::text AS genero, c.email, c.telefone, c.whatsapp, c.instagram,
                   c.facebook, c.tiktok, c.cep, c.endereco, c.numero, c.complemento, c.bairro,
                   c.cidade, c.estado, c.limite_credito, c.ativo, c.criado_em, c.atualizado_em
            FROM cliente c
            JOIN cfg_categoria_cliente cc ON cc.id_categoria_cliente = c.id_categoria_cliente
            """;

    private static ClienteResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        String generoTxt = rs.getString("genero");
        return new ClienteResponse(
                rs.getLong("id_cliente"),
                rs.getBoolean("fisica_juridica"),
                rs.getString("nome"),
                rs.getInt("id_categoria_cliente"),
                rs.getString("nome_categoria"),
                rs.getString("cpf_cnpj"),
                rs.getString("rg_ie"),
                rs.getObject("data_nascimento", LocalDate.class),
                generoTxt == null ? null : Genero.valueOf(generoTxt),
                rs.getString("email"),
                rs.getString("telefone"),
                rs.getString("whatsapp"),
                rs.getString("instagram"),
                rs.getString("facebook"),
                rs.getString("tiktok"),
                rs.getString("cep"),
                rs.getString("endereco"),
                rs.getString("numero"),
                rs.getString("complemento"),
                rs.getString("bairro"),
                rs.getString("cidade"),
                rs.getString("estado"),
                rs.getBigDecimal("limite_credito"),
                rs.getBoolean("ativo"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
