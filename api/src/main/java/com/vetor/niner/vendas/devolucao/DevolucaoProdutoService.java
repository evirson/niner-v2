package com.vetor.niner.vendas.devolucao;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.DevolucaoEfetivadaResponse;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.EfetivarDevolucaoRequest;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.ItemDevolucaoRequest;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.ItemDevolucaoResponse;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.ItemVendaOrigemResponse;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.ValeMercadoriaResponse;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.VendedorDaVendaResponse;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Devolução de Produtos — ver {@code package-info.java} pro resumo de escopo. Toda devolução
 * gera um vale-mercadoria (2026-08-03, pedido do dono do produto): uma linha em {@code
 * venda_devolucao} (número do vale = {@code id_devolucao}, a PK) + o {@code
 * produto_movimento_mestre} da devolução aponta pra ela via {@code id_devolucao} — o valor do
 * vale é sempre derivado somando os itens devolvidos naquele movimento (nunca gravado como
 * coluna própria). O resgate do vale (uso como forma de pagamento) é feito pelo PDV
 * ({@code PdvVendaService}, categoria {@code VALE_MERCADORIA}), que marca {@code vale_usado}/
 * {@code id_venda_debito} nesta mesma tabela.
 */
@Service
public class DevolucaoProdutoService {

    private final JdbcClient jdbc;
    private final ConfiguracaoGeralService configuracaoGeralService;

    public DevolucaoProdutoService(JdbcClient jdbc, ConfiguracaoGeralService configuracaoGeralService) {
        this.jdbc = jdbc;
        this.configuracaoGeralService = configuracaoGeralService;
    }

    /** Resolve o vendedor de uma venda pelo número — usado pela tela antes de gravar a devolução,
     *  só para exibir/confirmar quem vai ter a devolução descontada da comissão no futuro. */
    @Transactional(readOnly = true)
    public VendedorDaVendaResponse buscarVendedorDaVenda(long numeroVenda) {
        boolean existe = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM venda WHERE id_tenant = plataforma.tenant_atual() AND id_venda = ?)
                        """)
                .param(numeroVenda).query(Boolean.class).single());
        if (!existe) {
            throw new ResponseStatusException(NOT_FOUND, "Venda não encontrada.");
        }
        FuncionarioVenda fv = buscarFuncionarioDaVenda(numeroVenda);
        List<ItemVendaOrigemResponse> itens = buscarItensDisponiveisParaDevolucao(numeroVenda);
        return new VendedorDaVendaResponse(numeroVenda, fv.idFuncionario(), fv.nomeFuncionario(), itens);
    }

    /**
     * Efetiva a devolução: primeiro uma linha em {@code venda_devolucao} (o vale-mercadoria —
     * {@code id_venda_credito} = número da venda informado, se houver; {@code id_devolucao}
     * gerado é o número do vale), depois um {@code produto_movimento_mestre} ({@code
     * tipo_movimento = 'DEVOLUCAO'}, {@code id_devolucao} apontando pra ela) + um {@code
     * produto_movimento_detalhe} ({@code credito_debito = 'C'}) por item, com {@code
     * id_funcionario} = vendedor resolvido pelo número da venda (se informado). A trigger
     * {@code fn_atualiza_estoque_movimento} (já existente) soma a quantidade de volta em
     * {@code produto_estoque} sozinha, mesmo mecanismo do PDV/Transferência/Cancelamento. Se o
     * número da venda informado não resolver a nenhum vendedor (venda inexistente ou sem item),
     * a devolução segue sem vendedor — é só um dado auxiliar, não bloqueia a gravação.
     */
    @Transactional
    public DevolucaoEfetivadaResponse efetivar(Jwt jwt, EfetivarDevolucaoRequest req) {
        long idEmpresa = ((Number) jwt.getClaim("eid")).longValue();

        if (req.numeroVenda() == null && configuracaoGeralService.exigeNumeroVendaDevolucao()) {
            throw new IllegalArgumentException(
                    "Informe o número da venda de origem — obrigatório neste tenant (Parâmetros do Sistema).");
        }

        Long idFuncionario = null;
        String nomeFuncionario = null;
        Map<Long, BigDecimal> disponivelPorVariacao = null;
        Map<Long, PrecoOriginal> precosOriginais = Map.of();
        if (req.numeroVenda() != null) {
            FuncionarioVenda fv = buscarFuncionarioDaVenda(req.numeroVenda());
            idFuncionario = fv.idFuncionario();
            nomeFuncionario = fv.nomeFuncionario();
            disponivelPorVariacao = buscarItensDisponiveisParaDevolucao(req.numeroVenda()).stream()
                    .collect(Collectors.toMap(ItemVendaOrigemResponse::idVariacao, ItemVendaOrigemResponse::qtdDisponivelDevolucao));
            precosOriginais = buscarPrecosOriginaisDaVenda(req.numeroVenda());
        }

        List<ItemResolvido> itens = resolverItens(req.itens(), precosOriginais);

        // Quando a venda de origem é informada, só é permitido devolver produtos que ela vendeu,
        // até o que ainda não foi devolvido dela — validado aqui (não só na tela, P4) porque é a
        // única linha de defesa real contra uma chamada direta à API.
        if (disponivelPorVariacao != null) {
            for (ItemResolvido item : itens) {
                BigDecimal disponivel = disponivelPorVariacao.get(item.idVariacao());
                if (disponivel == null) {
                    throw new IllegalArgumentException(
                            "O produto \"%s\" não faz parte da venda nº %d.".formatted(item.descricaoProduto(), req.numeroVenda()));
                }
                if (item.qtd().compareTo(disponivel) > 0) {
                    throw new IllegalArgumentException(
                            "Quantidade a devolver do produto \"%s\" (%s) maior que a disponível na venda nº %d (%s)."
                                    .formatted(item.descricaoProduto(), item.qtd().stripTrailingZeros().toPlainString(),
                                            req.numeroVenda(), disponivel.stripTrailingZeros().toPlainString()));
                }
            }
        }

        long idDevolucao = jdbc.sql("""
                        INSERT INTO venda_devolucao (id_tenant, id_empresa, id_venda_credito)
                        VALUES (plataforma.tenant_atual(), ?, ?)
                        RETURNING id_devolucao
                        """)
                .params(idEmpresa, req.numeroVenda()).query(Long.class).single();

        long idMovimento = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_devolucao)
                        VALUES (plataforma.tenant_atual(), ?, 'DEVOLUCAO', ?)
                        RETURNING id_movimento
                        """)
                .params(idEmpresa, idDevolucao).query(Long.class).single();

        List<ItemDevolucaoResponse> itensResponse = new ArrayList<>();
        BigDecimal valorVale = BigDecimal.ZERO;
        for (ItemResolvido item : itens) {
            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto,
                                 preco_venda, preco_custo, id_funcionario, origem)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'C', ?, ?, ?, ?, 'devolução manual')
                            """)
                    .params(idMovimento, idEmpresa, item.idVariacao(), item.qtd(), item.precoVenda(), item.precoCusto(), idFuncionario)
                    .update();
            BigDecimal valorTotalItem = item.precoVenda().multiply(item.qtd());
            itensResponse.add(new ItemDevolucaoResponse(
                    item.idVariacao(), item.sku(), item.descricaoProduto(), item.variacaoCor(), item.variacaoTamanho(),
                    item.qtd(), item.precoVenda(), valorTotalItem));
            valorVale = valorVale.add(valorTotalItem);
        }

        return new DevolucaoEfetivadaResponse(
                idMovimento, idDevolucao, valorVale, OffsetDateTime.now(), idFuncionario, nomeFuncionario,
                itensResponse, null);
    }

    /** Consulta um vale-mercadoria pelo número (`id_devolucao`) — reimpressão e resgate no PDV
     *  (`PdvVendaService`, que faz sua própria query equivalente pra validar/marcar o uso dentro
     *  da mesma transação da venda, em vez de chamar este service). Valor sempre derivado da
     *  soma dos itens do movimento DEVOLUCAO vinculado, nunca gravado como coluna própria. */
    @Transactional(readOnly = true)
    public ValeMercadoriaResponse buscarVale(long idDevolucao) {
        record Cabecalho(long idDevolucao, OffsetDateTime dataDevolucao, boolean valeUsado, boolean cancelada,
                          Long idVendaCredito, Long idVendaDebito) {
        }
        Cabecalho c = jdbc.sql("""
                        SELECT id_devolucao, data_devolucao, vale_usado, cancelada, id_venda_credito, id_venda_debito
                        FROM venda_devolucao WHERE id_tenant = plataforma.tenant_atual() AND id_devolucao = ?
                        """)
                .param(idDevolucao)
                .query((rs, n) -> new Cabecalho(
                        rs.getLong("id_devolucao"), rs.getObject("data_devolucao", OffsetDateTime.class),
                        rs.getBoolean("vale_usado"), rs.getBoolean("cancelada"),
                        getLongOuNulo(rs, "id_venda_credito"), getLongOuNulo(rs, "id_venda_debito")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Vale-mercadoria não encontrado."));

        BigDecimal valorVale = jdbc.sql("""
                        SELECT COALESCE(SUM(pmd.qtd_produto * pmd.preco_venda), 0)
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_devolucao = ?
                              AND pmm.tipo_movimento = 'DEVOLUCAO'
                        """)
                .param(idDevolucao).query(BigDecimal.class).single();

        return new ValeMercadoriaResponse(
                c.idDevolucao(), valorVale, c.valeUsado(), c.cancelada(), c.dataDevolucao(), c.idVendaCredito(), c.idVendaDebito());
    }

    /** Itens vendidos numa venda, com quanto ainda pode ser devolvido de cada um — quantidade
     *  vendida menos o que já foi devolvido em devoluções **não canceladas** da mesma venda
     *  ({@code venda_devolucao.id_venda_credito}, cancelamento reverte o estoque então não deve
     *  contar contra o limite). Base tanto pra alimentar a tela (via {@link #buscarVendedorDaVenda})
     *  quanto pra validar de verdade em {@link #efetivar}. Venda inexistente ou sem itens de
     *  venda devolve lista vazia (não lança erro aqui — quem chama decide o que fazer). */
    private List<ItemVendaOrigemResponse> buscarItensDisponiveisParaDevolucao(long idVenda) {
        record ItemVendido(long idVariacao, String sku, String descricaoProduto, String variacaoCor,
                            String variacaoTamanho, BigDecimal qtdVendida, BigDecimal valorTotalVendido) {
        }
        // valor_total_vendido = SUM(qtd × preço) da(s) linha(s) desta variação na venda — preço
        // UNITÁRIO é derivado como média ponderada (valor_total / qtd) mais abaixo, não lido
        // direto de uma linha só: a mesma variação pode aparecer em mais de uma linha da venda
        // com preços diferentes (raro, mas possível — split de desconto por linha).
        List<ItemVendido> vendidos = jdbc.sql("""
                        SELECT pb.id_variacao, pb.sku, p.descricao AS descricao_produto,
                               co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho,
                               SUM(pmd.qtd_produto) AS qtd_vendida,
                               SUM(pmd.qtd_produto * pmd.preco_venda) AS valor_total_vendido
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                        JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                        LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                        GROUP BY pb.id_variacao, pb.sku, p.descricao, co.descricao, ta.descricao
                        """)
                .param(idVenda)
                .query((rs, n) -> new ItemVendido(
                        rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("descricao_produto"),
                        rs.getString("variacao_cor"), rs.getString("variacao_tamanho"), rs.getBigDecimal("qtd_vendida"),
                        rs.getBigDecimal("valor_total_vendido")))
                .list();
        if (vendidos.isEmpty()) {
            return List.of();
        }

        record ItemDevolvido(long idVariacao, BigDecimal qtdDevolvida) {
        }
        Map<Long, BigDecimal> jaDevolvido = jdbc.sql("""
                        SELECT pmd.id_variacao, SUM(pmd.qtd_produto) AS qtd_devolvida
                        FROM venda_devolucao vd
                        JOIN produto_movimento_mestre pmm
                               ON pmm.id_devolucao = vd.id_devolucao AND pmm.id_tenant = vd.id_tenant
                               AND pmm.tipo_movimento = 'DEVOLUCAO'
                        JOIN produto_movimento_detalhe pmd ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        WHERE vd.id_tenant = plataforma.tenant_atual() AND vd.id_venda_credito = ? AND vd.cancelada = false
                        GROUP BY pmd.id_variacao
                        """)
                .param(idVenda)
                .query((rs, n) -> new ItemDevolvido(rs.getLong("id_variacao"), rs.getBigDecimal("qtd_devolvida")))
                .list()
                .stream()
                .collect(Collectors.toMap(ItemDevolvido::idVariacao, ItemDevolvido::qtdDevolvida));

        List<ItemVendaOrigemResponse> resultado = new ArrayList<>();
        for (ItemVendido v : vendidos) {
            BigDecimal devolvido = jaDevolvido.getOrDefault(v.idVariacao(), BigDecimal.ZERO);
            BigDecimal disponivel = v.qtdVendida().subtract(devolvido).max(BigDecimal.ZERO);
            // Preço unitário = média ponderada da venda (valor total ÷ qtd vendida) — nunca o
            // preço atual do cadastro. `HALF_UP`/escala 2 casas: é o valor que vai pro vale e,
            // futuramente, pro XML da NF-e de devolução, sempre em BigDecimal monetário (P7).
            BigDecimal precoUnitario = v.valorTotalVendido().divide(v.qtdVendida(), 2, java.math.RoundingMode.HALF_UP);
            resultado.add(new ItemVendaOrigemResponse(
                    v.idVariacao(), v.sku(), v.descricaoProduto(), v.variacaoCor(), v.variacaoTamanho(),
                    v.qtdVendida(), disponivel, precoUnitario, v.valorTotalVendido()));
        }
        return resultado;
    }

    private record FuncionarioVenda(Long idFuncionario, String nomeFuncionario) {
    }

    /** Mesma query de {@code CancelamentoVendaService.buscarFuncionarioDaVenda} — um vendedor por
     *  venda (não por item), gravado igual em toda linha de {@code produto_movimento_detalhe}
     *  da venda original. Devolve {@code (null, null)} se a venda não tiver item de venda. */
    private FuncionarioVenda buscarFuncionarioDaVenda(long idVenda) {
        return jdbc.sql("""
                        SELECT pmd.id_funcionario, fn.nome AS nome_funcionario
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        LEFT JOIN funcionario fn ON fn.id_funcionario = pmd.id_funcionario AND fn.id_tenant = pmd.id_tenant
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                        LIMIT 1
                        """)
                .param(idVenda)
                .query((rs, n) -> new FuncionarioVenda(getLongOuNulo(rs, "id_funcionario"), rs.getString("nome_funcionario")))
                .optional()
                .orElse(new FuncionarioVenda(null, null));
    }

    /**
     * Preços que a <b>venda original</b> praticou, por variação — média ponderada quando a mesma
     * variação aparece em mais de uma linha da venda (raro, mas possível). ⚠️ <b>Não</b> é o preço
     * do cadastro: pelo mesmo motivo documentado em {@code CancelamentoVendaService.estornarEstoque}
     * ("o custo tem que ser o mesmo que saiu, mesmo que o cadastro já tenha mudado de preço desde
     * então"), a devolução é a reversão daquela venda e tem que reverter pelos valores dela —
     * senão o vale-mercadoria paga um valor diferente do que o cliente pagou, e o CMV/DRE fica
     * torto. Vale duplamente a partir da NF-e de devolução (revisão 2026-08-19 da spec), que
     * precisa espelhar os valores da nota de saída original.
     */
    private record PrecoOriginal(BigDecimal precoVenda, BigDecimal precoCusto) {
    }

    private Map<Long, PrecoOriginal> buscarPrecosOriginaisDaVenda(long idVenda) {
        record Linha(long idVariacao, BigDecimal precoVenda, BigDecimal precoCusto) {
        }
        return jdbc.sql("""
                        SELECT pmd.id_variacao,
                               SUM(pmd.qtd_produto * pmd.preco_venda) / SUM(pmd.qtd_produto) AS preco_venda,
                               SUM(pmd.qtd_produto * pmd.preco_custo) / SUM(pmd.qtd_produto) AS preco_custo
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ?
                              AND pmm.tipo_movimento = 'VENDA' AND pmd.qtd_produto > 0
                        GROUP BY pmd.id_variacao
                        """)
                .param(idVenda)
                .query((rs, n) -> new Linha(rs.getLong("id_variacao"),
                        rs.getBigDecimal("preco_venda").setScale(2, java.math.RoundingMode.HALF_UP),
                        rs.getBigDecimal("preco_custo").setScale(2, java.math.RoundingMode.HALF_UP)))
                .list()
                .stream()
                .collect(Collectors.toMap(Linha::idVariacao, l -> new PrecoOriginal(l.precoVenda(), l.precoCusto())));
    }

    private record ItemResolvido(long idVariacao, BigDecimal qtd, BigDecimal precoVenda, BigDecimal precoCusto,
                                  String sku, String descricaoProduto, String variacaoCor, String variacaoTamanho) {
    }

    /** Resolve descrição/variação/preço de cada item a partir do {@code idVariacao} — a tela
     *  nunca envia preço nem descrição (mesmo princípio do PDV/Transferência). Não checa
     *  saldo — saldo negativo é permitido de propósito em qualquer movimentação (2026-07-29).
     *
     *  <p>{@code precosOriginais} (2026-08-19) tem prioridade sobre o preço do cadastro sempre que
     *  a variação estiver lá (ou seja, quando a devolução está amarrada a uma venda — ver
     *  {@link PrecoOriginal} pro porquê). Vazio quando a devolução não informa venda de origem:
     *  aí não há outro valor possível, cai no cadastro como sempre foi. */
    private List<ItemResolvido> resolverItens(List<ItemDevolucaoRequest> itens,
                                              Map<Long, PrecoOriginal> precosOriginais) {
        boolean permiteQtdDecimal = configuracaoGeralService.permiteQtdDecimalProduto();
        List<ItemResolvido> resolvidos = new ArrayList<>();
        for (ItemDevolucaoRequest item : itens) {
            if (!permiteQtdDecimal && temParteDecimal(item.qtd())) {
                throw new IllegalArgumentException(
                        "Quantidade deve ser um número inteiro — este tenant não permite quantidade decimal de produtos (Parâmetros do Sistema).");
            }
            LinhaItem linha = jdbc.sql("""
                            SELECT pb.sku, p.descricao AS descricao_produto,
                                   co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho,
                                   p.preco_venda, p.preco_custo
                            FROM produto_barra pb
                            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                            LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                            LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                            WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ? AND p.ativo = true
                            """)
                    .param(item.idVariacao())
                    .query((rs, n) -> new LinhaItem(
                            rs.getString("sku"), rs.getString("descricao_produto"),
                            rs.getString("variacao_cor"), rs.getString("variacao_tamanho"),
                            rs.getBigDecimal("preco_venda"), rs.getBigDecimal("preco_custo")))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Produto informado não existe ou está inativo."));

            PrecoOriginal original = precosOriginais.get(item.idVariacao());
            BigDecimal precoVenda = original != null ? original.precoVenda() : linha.precoVenda();
            BigDecimal precoCusto = original != null ? original.precoCusto() : linha.precoCusto();

            resolvidos.add(new ItemResolvido(item.idVariacao(), item.qtd(), precoVenda, precoCusto,
                    linha.sku(), linha.descricaoProduto(), linha.variacaoCor(), linha.variacaoTamanho()));
        }
        return resolvidos;
    }

    private record LinhaItem(String sku, String descricaoProduto, String variacaoCor, String variacaoTamanho, BigDecimal precoVenda, BigDecimal precoCusto) {
    }

    /** {@code true} se o valor tiver parte fracionária (ex.: 2.5), não importa a escala/zeros à direita. */
    private static boolean temParteDecimal(BigDecimal valor) {
        return valor.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0;
    }

    /** {@code rs.getObject(coluna, Long.class)} não funciona pra colunas {@code integer}
     *  (driver do Postgres só converte pro tipo exato) — {@code getLong}+{@code wasNull} é o
     *  jeito seguro de ler uma coluna {@code integer} nullable como {@code Long}. */
    private static Long getLongOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }
}
