package com.vetor.niner.estoque.transferencia;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.CriarTransferenciaRequest;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.EmpresaResumo;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.ItemTransferenciaRequest;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.ItemTransferenciaResponse;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.PaginaTransferencias;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.TransferenciaResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
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
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Transferência de produtos entre empresas (docs/telas/transferencia-estoque.md). A empresa de
 * origem é sempre a empresa ativa da sessão (claim {@code eid} do JWT) — o operador só escolhe
 * o destino. Aberta a ADMIN e OPERADOR (operação do dia a dia de loja com mais de uma filial,
 * mesma decisão de produto de {@code cadastros.cliente}/PDV — não é sensível como
 * {@code identidade.usuario}).
 */
@Service
public class TransferenciaService {

    private static final int TAMANHO_PAGINA_PADRAO = 50;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    /**
     * Colunas ordenáveis da listagem — chave da API -> expressão SQL. São expressões (não
     * aliases do SELECT) porque a query de ids só seleciona {@code t.id_transferencia}
     * ({@code JdbcClient.query(Long.class)} exige uma única coluna) — o ORDER BY referencia as
     * tabelas dos JOINs diretamente, o que o Postgres aceita mesmo fora do SELECT list.
     */
    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "numero", "t.id_transferencia",
            "data", "t.data_transferencia",
            "origem", "COALESCE(eo.nome_fantasia, eo.razao_social)",
            "destino", "COALESCE(ed.nome_fantasia, ed.razao_social)",
            "usuario", "u.nome_usuario",
            "itens", """
                    (SELECT count(*) FROM produto_movimento_mestre pm
                       JOIN produto_movimento_detalhe pmd
                              ON pmd.id_movimento = pm.id_movimento AND pmd.id_tenant = pm.id_tenant
                     WHERE pm.id_tenant = t.id_tenant AND pm.id_transferencia = t.id_transferencia
                           AND pmd.credito_debito = 'D')
                    """);

    private final JdbcClient jdbc;
    private final ConfiguracaoGeralService configuracaoGeralService;

    public TransferenciaService(JdbcClient jdbc, ConfiguracaoGeralService configuracaoGeralService) {
        this.jdbc = jdbc;
        this.configuracaoGeralService = configuracaoGeralService;
    }

    @Transactional
    public TransferenciaResponse criar(Jwt jwt, CriarTransferenciaRequest req) {
        long idEmpresaOrigem = ((Number) jwt.getClaim("eid")).longValue();

        if (req.idEmpresaDestino() == idEmpresaOrigem) {
            throw new IllegalArgumentException("A empresa de destino precisa ser diferente da empresa de origem.");
        }
        exigirEmpresaAtiva(req.idEmpresaDestino());

        List<ItemResolvido> itens = resolverItens(req.itens(), idEmpresaOrigem);

        long idTransferencia = jdbc.sql("""
                        INSERT INTO produto_transferencia
                            (id_tenant, id_empresa_origem, id_empresa_destino, id_usuario, observacoes)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?)
                        RETURNING id_transferencia
                        """)
                .params(idEmpresaOrigem, req.idEmpresaDestino(), Long.parseLong(jwt.getSubject()), req.observacoes())
                .query(Long.class).single();

        long idMovimentoOrigem = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_transferencia)
                        VALUES (plataforma.tenant_atual(), ?, 'TRANSFERENCIA', ?)
                        RETURNING id_movimento
                        """)
                .params(idEmpresaOrigem, idTransferencia).query(Long.class).single();
        for (ItemResolvido item : itens) {
            inserirDetalhe(idMovimentoOrigem, idEmpresaOrigem, item.idVariacao(), "D", item.qtd(), item.precoCusto());
        }

        long idMovimentoDestino = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_transferencia)
                        VALUES (plataforma.tenant_atual(), ?, 'TRANSFERENCIA', ?)
                        RETURNING id_movimento
                        """)
                .params(req.idEmpresaDestino(), idTransferencia).query(Long.class).single();
        for (ItemResolvido item : itens) {
            inserirDetalhe(idMovimentoDestino, req.idEmpresaDestino(), item.idVariacao(), "C", item.qtd(), item.precoCusto());
        }

        return montar(idTransferencia);
    }

    @Transactional(readOnly = true)
    public PaginaTransferencias listar(Long idTransferencia, Long idEmpresaOrigem, Long idEmpresaDestino,
                                        LocalDate dataInicial, LocalDate dataFinal, Integer pagina, Integer limite,
                                        String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao =
                ordenarPor == null ? "t.data_transferencia" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "t.data_transferencia");
        // Sem ordenarPor explícito, mantém o padrão histórico (mais recente primeiro); com
        // coluna escolhida e sem direção, começa ascendente — mesmo padrão de clique em coluna
        // do resto do projeto (FornecedorService etc.).
        String direcaoOrdenacao = direcao != null
                ? ("DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC")
                : (ordenarPor == null ? "DESC" : "ASC");

        StringBuilder filtro = new StringBuilder(" WHERE t.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();
        if (idTransferencia != null) {
            filtro.append(" AND t.id_transferencia = ?");
            params.add(idTransferencia);
        }
        if (idEmpresaOrigem != null) {
            filtro.append(" AND t.id_empresa_origem = ?");
            params.add(idEmpresaOrigem);
        }
        if (idEmpresaDestino != null) {
            filtro.append(" AND t.id_empresa_destino = ?");
            params.add(idEmpresaDestino);
        }
        if (dataInicial != null) {
            filtro.append(" AND (t.data_transferencia AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            params.add(dataInicial);
        }
        if (dataFinal != null) {
            filtro.append(" AND (t.data_transferencia AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            params.add(dataFinal);
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM produto_transferencia t" + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        List<Long> ids = jdbc.sql("""
                        SELECT t.id_transferencia
                        FROM produto_transferencia t
                        JOIN empresa eo ON eo.id_empresa = t.id_empresa_origem AND eo.id_tenant = t.id_tenant
                        JOIN empresa ed ON ed.id_empresa = t.id_empresa_destino AND ed.id_tenant = t.id_tenant
                        JOIN usuario u ON u.id_usuario = t.id_usuario AND u.id_tenant = t.id_tenant
                        """ + filtro
                        + " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                        + ", t.id_transferencia " + direcaoOrdenacao + " LIMIT ? OFFSET ?")
                .params(paramsPagina)
                .query(Long.class).list();

        List<TransferenciaResponse> itens = ids.stream().map(this::montar).toList();
        return new PaginaTransferencias(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public TransferenciaResponse buscar(long id) {
        return montar(id);
    }

    /**
     * Exclusão completa de uma transferência (pedido direto do dono do produto, 2026-07-29) —
     * reverte o non-goal original da spec ("sem cancelamento/estorno pela tela"). Apaga as
     * linhas de {@code produto_movimento_detalhe} da transferência (origem 'D' e destino 'C');
     * a trigger {@code fn_atualiza_estoque_movimento} (V019) devolve a quantidade à origem e
     * retira do destino sozinha, sem checagem de saldo (pedido explícito: devolver pra origem
     * mesmo que o destino já tenha saído do saldo transferido — {@code produto_estoque} não tem
     * CHECK contra negativo). {@code produto_movimento_mestre} continua imutável por grant
     * (REVOKE UPDATE, DELETE, V024) — os dois cabeçalhos de movimento ficam órfãos, sem linha de
     * detalhe, o que é o mesmo mecanismo já usado pra qualquer correção de ledger neste projeto.
     */
    @Transactional
    public void excluir(long id) {
        boolean existe = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM produto_transferencia
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_transferencia = ?)
                        """)
                .param(id).query(Boolean.class).single());
        if (!existe) {
            throw new ResponseStatusException(NOT_FOUND, "Transferência não encontrada.");
        }

        jdbc.sql("""
                        DELETE FROM produto_movimento_detalhe
                        WHERE id_tenant = plataforma.tenant_atual()
                              AND id_movimento IN (
                                  SELECT id_movimento FROM produto_movimento_mestre
                                  WHERE id_tenant = plataforma.tenant_atual() AND id_transferencia = ?
                              )
                        """)
                .param(id)
                .update();

        jdbc.sql("DELETE FROM produto_transferencia WHERE id_tenant = plataforma.tenant_atual() AND id_transferencia = ?")
                .param(id)
                .update();
    }

    private void inserirDetalhe(long idMovimento, long idEmpresa, long idVariacao, String creditoDebito, BigDecimal qtd, BigDecimal precoCusto) {
        // credito_debito é um ENUM do Postgres — precisa de cast explícito, o driver não
        // converte um bind param de String sozinho (diferente de um literal 'D'/'C' na SQL,
        // que o Postgres já infere pelo tipo da coluna — por isso aqui não dá pra ser literal
        // porque o valor varia por chamada, origem 'D' × destino 'C').
        jdbc.sql("""
                        INSERT INTO produto_movimento_detalhe
                            (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto, preco_custo, origem)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?::credito_debito, ?, ?, 'transferência entre empresas')
                        """)
                .params(idMovimento, idEmpresa, idVariacao, creditoDebito, qtd, precoCusto)
                .update();
    }

    private void exigirEmpresaAtiva(long idEmpresa) {
        boolean existe = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM empresa
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND ativo = true)
                        """)
                .param(idEmpresa).query(Boolean.class).single());
        if (!existe) {
            throw new IllegalArgumentException("Empresa de destino inválida ou inativa.");
        }
    }

    private record ItemResolvido(long idVariacao, BigDecimal qtd, BigDecimal precoCusto) {
    }

    /**
     * Valida que cada item (variação) existe e está ativo, e resolve o custo atual do cadastro
     * (gravado no ledger — 2026-08-04).
     *
     * <p>⚠️ <b>Não checa saldo na origem, e não deve passar a checar.</b> Desde a V054 quem barra
     * o débito é a trigger `fn_atualiza_estoque_movimento`, no único caminho por onde
     * `produto_estoque` se mexe — e só quando o lojista <b>desliga</b>
     * `cfg_permite_estoque_negativo` (que nasce ligado, V055). Duplicar a checagem aqui criaria
     * duas verdades sobre a mesma regra; a de 2026-07-29 ("saldo negativo permitido em qualquer
     * movimentação") deixou de ser incondicional em 2026-08-20. Ver
     * `docs/telas/configuracao-geral.md`.
     */
    private List<ItemResolvido> resolverItens(List<ItemTransferenciaRequest> itens, long idEmpresaOrigem) {
        boolean permiteQtdDecimal = configuracaoGeralService.permiteQtdDecimalProduto();
        List<ItemResolvido> resolvidos = new ArrayList<>();
        for (ItemTransferenciaRequest item : itens) {
            if (!permiteQtdDecimal && temParteDecimal(item.qtd())) {
                throw new IllegalArgumentException(
                        "Quantidade deve ser um número inteiro — este tenant não permite quantidade decimal de produtos (Parâmetros do Sistema).");
            }
            BigDecimal precoCusto = jdbc.sql("""
                            SELECT p.preco_custo FROM produto_barra pb
                            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                            WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ? AND p.ativo = true
                            """)
                    .param(item.idVariacao())
                    .query(BigDecimal.class)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Produto não encontrado para a variação informada."));
            resolvidos.add(new ItemResolvido(item.idVariacao(), item.qtd(), precoCusto));
        }
        return resolvidos;
    }

    private TransferenciaResponse montar(long idTransferencia) {
        record Cabecalho(long idTransferencia, EmpresaResumo origem, EmpresaResumo destino, String nomeUsuario,
                          OffsetDateTime data, String observacoes) {
        }

        Cabecalho cabecalho = jdbc.sql("""
                        SELECT t.id_transferencia, t.observacoes, t.data_transferencia,
                               eo.id_empresa AS id_empresa_origem,
                               COALESCE(eo.nome_fantasia, eo.razao_social) AS nome_origem,
                               ed.id_empresa AS id_empresa_destino,
                               COALESCE(ed.nome_fantasia, ed.razao_social) AS nome_destino,
                               u.nome_usuario
                        FROM produto_transferencia t
                        JOIN empresa eo ON eo.id_empresa = t.id_empresa_origem AND eo.id_tenant = t.id_tenant
                        JOIN empresa ed ON ed.id_empresa = t.id_empresa_destino AND ed.id_tenant = t.id_tenant
                        JOIN usuario u ON u.id_usuario = t.id_usuario AND u.id_tenant = t.id_tenant
                        WHERE t.id_transferencia = ? AND t.id_tenant = plataforma.tenant_atual()
                        """)
                .param(idTransferencia)
                .query((rs, n) -> new Cabecalho(
                        rs.getLong("id_transferencia"),
                        new EmpresaResumo(rs.getLong("id_empresa_origem"), rs.getString("nome_origem")),
                        new EmpresaResumo(rs.getLong("id_empresa_destino"), rs.getString("nome_destino")),
                        rs.getString("nome_usuario"),
                        rs.getObject("data_transferencia", OffsetDateTime.class),
                        rs.getString("observacoes")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transferência não encontrada."));

        List<ItemTransferenciaResponse> itens = jdbc.sql("""
                        SELECT pb.id_variacao, p.descricao AS descricao_produto, co.descricao AS variacao_cor,
                               ta.descricao AS variacao_tamanho, pb.sku, pmd.qtd_produto
                        FROM produto_movimento_mestre pm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pm.id_movimento AND pmd.id_tenant = pm.id_tenant
                        JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                        JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                        LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                        WHERE pm.id_tenant = plataforma.tenant_atual() AND pm.id_transferencia = ?
                              AND pmd.credito_debito = 'D'
                        ORDER BY pmd.id_movimento_detalhe ASC
                        """)
                .param(idTransferencia)
                .query(TransferenciaService::mapearItem)
                .list();

        return new TransferenciaResponse(cabecalho.idTransferencia(), cabecalho.origem(), cabecalho.destino(),
                cabecalho.nomeUsuario(), cabecalho.data(), cabecalho.observacoes(), itens);
    }

    /** {@code true} se o valor tiver parte fracionária (ex.: 2.5), não importa a escala/zeros à direita. */
    private static boolean temParteDecimal(BigDecimal valor) {
        return valor.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0;
    }

    private static ItemTransferenciaResponse mapearItem(ResultSet rs, int rowNum) throws SQLException {
        return new ItemTransferenciaResponse(
                rs.getLong("id_variacao"), rs.getString("descricao_produto"), rs.getString("variacao_cor"),
                rs.getString("variacao_tamanho"), rs.getString("sku"), rs.getBigDecimal("qtd_produto"));
    }
}
