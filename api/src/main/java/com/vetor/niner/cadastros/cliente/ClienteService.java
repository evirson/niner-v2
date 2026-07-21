package com.vetor.niner.cadastros.cliente;

import com.vetor.niner.cadastros.cliente.ClienteDtos.ClienteRequest;
import com.vetor.niner.cadastros.cliente.ClienteDtos.ClienteResponse;
import com.vetor.niner.cadastros.cliente.ClienteDtos.ExclusaoClienteResponse;
import com.vetor.niner.cadastros.cliente.ClienteDtos.Genero;
import com.vetor.niner.cadastros.cliente.ClienteDtos.PaginaClientes;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaDtos.ConfiguracaoCampoResponse;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaService;
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
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private static final String CHAVE_TELA_FORM = "cadastros.cliente.form";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Colunas ordenáveis da listagem (§ tela de listagem) — chave da API -> expressão SQL. */
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "nome", "c.nome",
            "cpfCnpj", "c.cpf_cnpj",
            "categoria", "cc.nome_categoria",
            "telefone", "c.telefone",
            "cidade", "c.cidade",
            "status", "c.ativo");

    private static final Map<String, String> ROTULOS_CAMPO = Map.ofEntries(
            Map.entry("cpfCnpj", "CPF/CNPJ"), Map.entry("rgIe", "RG/Inscrição Estadual"),
            Map.entry("email", "E-mail"), Map.entry("telefone", "Celular"),
            Map.entry("whatsapp", "Id. WhatsApp"), Map.entry("instagram", "Instagram"),
            Map.entry("facebook", "Facebook"), Map.entry("tiktok", "TikTok"), Map.entry("cep", "CEP"),
            Map.entry("endereco", "Endereço"), Map.entry("numero", "Número"),
            Map.entry("complemento", "Complemento"), Map.entry("bairro", "Bairro"),
            Map.entry("cidade", "Cidade"), Map.entry("estado", "UF"));

    private final JdbcClient jdbc;
    private final ConfiguracaoTelaService configuracaoTelaService;

    public ClienteService(JdbcClient jdbc, ConfiguracaoTelaService configuracaoTelaService) {
        this.jdbc = jdbc;
        this.configuracaoTelaService = configuracaoTelaService;
    }

    /**
     * Listagem paginada por número de página (não mais por cursor — revisão de 2026-07-21:
     * a navegação "1 2 3 … 10 20 >" pedida pelo dono do produto exige saber o total de
     * páginas e pular direto para qualquer uma, o que um cursor opaco não permite). O
     * volume de clientes por tenant é pequeno o bastante para {@code OFFSET} não pesar.
     */
    @Transactional(readOnly = true)
    public PaginaClientes listar(String nome, String cpfCnpj, Long idCategoriaCliente,
                                  String status, Integer pagina, Integer limite,
                                  String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        // Map.of() é imutável e não aceita chave nula em getOrDefault — checar antes (ordenarPor
        // é opcional na API, vem nulo quando o cliente não pede ordenação específica).
        String colunaOrdenacao = ordenarPor == null ? "c.nome" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "c.nome");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (nome != null && !nome.isBlank()) {
            filtro.append(" AND c.nome ILIKE ?");
            params.add("%" + nome.trim() + "%");
        }
        if (cpfCnpj != null && !cpfCnpj.isBlank()) {
            filtro.append(" AND c.cpf_cnpj = ?");
            params.add(Documentos.somenteAlfanumerico(cpfCnpj));
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
        // Colunas fixas no whitelist (COLUNAS_ORDENAVEIS) — nunca vem do cliente sem passar por
        // esse mapa, então não há risco de injeção mesmo concatenando direto na SQL. Desempate
        // sempre por id_cliente, na mesma direção, para a paginação ficar estável entre páginas.
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", c.id_cliente " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<ClienteResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
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

    /**
     * Validação de servidor (defesa em profundidade — o formulário já valida no frontend,
     * mas a API nunca deve confiar só nisso: 2026-07-21, revisão pedida pelo dono do
     * produto). Cobre formato (e-mail/celular/WhatsApp/CEP) e a obrigatoriedade configurável
     * por tenant (`cfg_tela_campo`, mesma fonte usada pelo frontend em
     * `ConfiguracaoTelaCliente`/`ClienteForm`) — campo obrigatório vazio é rejeitado aqui
     * mesmo que o cliente da API não seja o `web/` (ex.: integração futura).
     */
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
        if (req.email() != null && !req.email().isBlank() && !EMAIL_PATTERN.matcher(req.email()).matches()) {
            throw new IllegalArgumentException("E-mail inválido.");
        }
        if (!celularValidoOuVazio(req.telefone())) {
            throw new IllegalArgumentException("Celular deve ter 11 dígitos (DDD + 9XXXX-XXXX).");
        }
        if (!celularValidoOuVazio(req.whatsapp())) {
            throw new IllegalArgumentException("Id. WhatsApp deve ter 11 dígitos (DDD + 9XXXX-XXXX).");
        }
        String cepDigitos = Documentos.somenteDigitos(req.cep());
        if (cepDigitos != null && !cepDigitos.isEmpty() && cepDigitos.length() != 8) {
            throw new IllegalArgumentException("CEP inválido.");
        }

        Map<String, ConfiguracaoCampoResponse> config = configuracaoTelaService.listar(CHAVE_TELA_FORM).stream()
                .collect(Collectors.toMap(ConfiguracaoCampoResponse::campo, c -> c));
        exigirSeObrigatorio(config, "cpfCnpj", req.cpfCnpj());
        exigirSeObrigatorio(config, "rgIe", req.rgIe());
        exigirSeObrigatorio(config, "email", req.email());
        exigirSeObrigatorio(config, "telefone", req.telefone());
        exigirSeObrigatorio(config, "whatsapp", req.whatsapp());
        exigirSeObrigatorio(config, "instagram", req.instagram());
        exigirSeObrigatorio(config, "facebook", req.facebook());
        exigirSeObrigatorio(config, "tiktok", req.tiktok());
        exigirSeObrigatorio(config, "cep", req.cep());
        exigirSeObrigatorio(config, "endereco", req.endereco());
        exigirSeObrigatorio(config, "numero", req.numero());
        exigirSeObrigatorio(config, "complemento", req.complemento());
        exigirSeObrigatorio(config, "bairro", req.bairro());
        exigirSeObrigatorio(config, "cidade", req.cidade());
        exigirSeObrigatorio(config, "estado", req.estado());
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
            throw new IllegalArgumentException(
                    ROTULOS_CAMPO.getOrDefault(campo, campo) + " é obrigatório.");
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
        // CNPJ é alfanumérico (Receita Federal, a partir de julho/2026) — não usar
        // somenteDigitos aqui, senão as letras da raiz/ordem seriam descartadas. CPF
        // continua só dígitos.
        params.add(r.fisicaJuridica() ? Documentos.somenteDigitos(r.cpfCnpj()) : Documentos.somenteAlfanumerico(r.cpfCnpj()));
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
