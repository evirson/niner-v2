package com.vetor.niner.vendas.devolucao;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.DevolucaoEfetivadaResponse;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.EfetivarDevolucaoRequest;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.ItemDevolucaoRequest;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.ItemDevolucaoResponse;
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
        return new VendedorDaVendaResponse(numeroVenda, fv.idFuncionario(), fv.nomeFuncionario());
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

        Long idFuncionario = null;
        String nomeFuncionario = null;
        if (req.numeroVenda() != null) {
            FuncionarioVenda fv = buscarFuncionarioDaVenda(req.numeroVenda());
            idFuncionario = fv.idFuncionario();
            nomeFuncionario = fv.nomeFuncionario();
        }

        List<ItemResolvido> itens = resolverItens(req.itens());

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
                                 preco_venda, id_funcionario, origem)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'C', ?, ?, ?, 'devolução manual')
                            """)
                    .params(idMovimento, idEmpresa, item.idVariacao(), item.qtd(), item.precoVenda(), idFuncionario)
                    .update();
            itensResponse.add(new ItemDevolucaoResponse(
                    item.idVariacao(), item.descricaoProduto(), item.variacaoLinha(), item.variacaoColuna(),
                    item.qtd(), item.precoVenda()));
            valorVale = valorVale.add(item.precoVenda().multiply(item.qtd()));
        }

        return new DevolucaoEfetivadaResponse(
                idMovimento, idDevolucao, valorVale, OffsetDateTime.now(), idFuncionario, nomeFuncionario, itensResponse);
    }

    /** Consulta um vale-mercadoria pelo número (`id_devolucao`) — reimpressão e resgate no PDV
     *  (`PdvVendaService`, que faz sua própria query equivalente pra validar/marcar o uso dentro
     *  da mesma transação da venda, em vez de chamar este service). Valor sempre derivado da
     *  soma dos itens do movimento DEVOLUCAO vinculado, nunca gravado como coluna própria. */
    @Transactional(readOnly = true)
    public ValeMercadoriaResponse buscarVale(long idDevolucao) {
        record Cabecalho(long idDevolucao, OffsetDateTime dataDevolucao, boolean valeUsado,
                          Long idVendaCredito, Long idVendaDebito) {
        }
        Cabecalho c = jdbc.sql("""
                        SELECT id_devolucao, data_devolucao, vale_usado, id_venda_credito, id_venda_debito
                        FROM venda_devolucao WHERE id_tenant = plataforma.tenant_atual() AND id_devolucao = ?
                        """)
                .param(idDevolucao)
                .query((rs, n) -> new Cabecalho(
                        rs.getLong("id_devolucao"), rs.getObject("data_devolucao", OffsetDateTime.class),
                        rs.getBoolean("vale_usado"), getLongOuNulo(rs, "id_venda_credito"), getLongOuNulo(rs, "id_venda_debito")))
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
                c.idDevolucao(), valorVale, c.valeUsado(), c.dataDevolucao(), c.idVendaCredito(), c.idVendaDebito());
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

    private record ItemResolvido(long idVariacao, BigDecimal qtd, BigDecimal precoVenda,
                                  String descricaoProduto, String variacaoLinha, String variacaoColuna) {
    }

    /** Resolve descrição/variação/preço de cada item a partir do {@code idVariacao} — a tela
     *  nunca envia preço nem descrição (mesmo princípio do PDV/Transferência). Não checa se a
     *  quantidade devolvida bate com alguma venda (não há vínculo, ver package-info); não checa
     *  saldo — saldo negativo é permitido de propósito em qualquer movimentação (2026-07-29). */
    private List<ItemResolvido> resolverItens(List<ItemDevolucaoRequest> itens) {
        boolean permiteQtdDecimal = configuracaoGeralService.permiteQtdDecimalProduto();
        List<ItemResolvido> resolvidos = new ArrayList<>();
        for (ItemDevolucaoRequest item : itens) {
            if (!permiteQtdDecimal && temParteDecimal(item.qtd())) {
                throw new IllegalArgumentException(
                        "Quantidade deve ser um número inteiro — este tenant não permite quantidade decimal de produtos (Parâmetros do Sistema).");
            }
            LinhaItem linha = jdbc.sql("""
                            SELECT p.descricao AS descricao_produto,
                                   vl.descricao AS variacao_linha, vc.descricao AS variacao_coluna,
                                   p.preco_venda
                            FROM produto_barra pb
                            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                            LEFT JOIN cfg_variante_linha vl
                                   ON vl.id_variante_linha = pb.id_variante_linha AND vl.id_tenant = pb.id_tenant
                            LEFT JOIN cfg_variante_coluna vc
                                   ON vc.id_variante_coluna = pb.id_variante_coluna AND vc.id_tenant = pb.id_tenant
                            WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ? AND p.ativo = true
                            """)
                    .param(item.idVariacao())
                    .query((rs, n) -> new LinhaItem(
                            rs.getString("descricao_produto"), rs.getString("variacao_linha"), rs.getString("variacao_coluna"),
                            rs.getBigDecimal("preco_venda")))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Produto informado não existe ou está inativo."));

            resolvidos.add(new ItemResolvido(item.idVariacao(), item.qtd(), linha.precoVenda(),
                    linha.descricaoProduto(), linha.variacaoLinha(), linha.variacaoColuna()));
        }
        return resolvidos;
    }

    private record LinhaItem(String descricaoProduto, String variacaoLinha, String variacaoColuna, BigDecimal precoVenda) {
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
