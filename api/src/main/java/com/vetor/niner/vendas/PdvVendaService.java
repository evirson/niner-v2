package com.vetor.niner.vendas;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.financeiro.TipoCarteiraDtos.CategoriaCarteira;
import com.vetor.niner.financeiro.caixa.CaixaService;
import com.vetor.niner.vendas.PdvDtos.EfetivarVendaRequest;
import com.vetor.niner.vendas.PdvDtos.ItemVendaRequest;
import com.vetor.niner.vendas.PdvDtos.PagamentoGerado;
import com.vetor.niner.vendas.PdvDtos.PagamentoRequest;
import com.vetor.niner.vendas.PdvDtos.ParcelaGerada;
import com.vetor.niner.vendas.PdvDtos.VendaEfetivadaResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Efetiva a venda do PDV (F5, docs/telas/pdv.md) — grava tudo numa única transação: {@code
 * venda} + ledger de estoque ({@code produto_movimento_mestre/detalhe}, débito — a trigger
 * {@code fn_atualiza_estoque_movimento}, V019, baixa {@code produto_estoque} sozinha, P1) e a(s)
 * parcela(s) em {@code contas_receber} a partir do(s) {@code tipo_carteira} escolhido(s). Exige
 * caixa aberto para o usuário/empresa do dia (2026-07-30, {@code financeiro.caixa.CaixaService})
 * antes de gravar qualquer coisa — a tela é responsável por pedir a abertura antes de chegar
 * aqui. Cada linha de pagamento à vista (AVISTA/CARTAO_DEBITO/CARTAO_CREDITO) também lança um
 * crédito em {@code caixa_detalhe} (2026-07-30, revisão — antes só o Recebimento de Crediário
 * gravava lá, o que deixava o Fechamento de Caixa sem nenhum total de venda do PDV). Linha de
 * CREDIARIO fica de fora de propósito: é dinheiro que ainda não circulou — só vira lançamento
 * de caixa quando a parcela for efetivamente recebida depois (RecebimentoCrediarioService),
 * senão contaria em dobro. <b>Entrar no caixa não é o mesmo que já estar recebido</b>
 * (2026-07-30, revisão): só AVISTA (dinheiro/Pix) já nasce com a parcela quitada em {@code
 * contas_receber}; CARTAO_DEBITO/CARTAO_CREDITO entram no caixa na hora (é dinheiro do
 * lojista), mas a parcela em {@code contas_receber} fica em aberto — o prazo de liquidação de
 * cada bandeira já está em {@code tipo_carteira.prazo_pagamento} (débito costuma ser D+1) — até
 * uma futura tela de conciliação de cartões baixar essas parcelas.
 *
 * <p>Preço e variação de cada item são sempre resolvidos aqui a partir do {@code idVariacao} —
 * a tela nunca envia preço. Saldo negativo em {@code produto_estoque} é permitido de propósito
 * (pedido direto do dono do produto, 2026-07-29, vale para toda movimentação de produto, entrada
 * ou saída) — não há checagem de estoque disponível antes da venda, nem CHECK no banco.
 *
 * <p><b>Split-tender + desconto (2026-07-28, revisado):</b> uma venda pode ter várias linhas
 * de pagamento (formas de pagamento diferentes cobrindo pedaços da mesma venda). {@code
 * cfg_geral.percentual_desconto_venda} é o desconto <b>máximo</b> permitido na venda — não é
 * mais aplicado automaticamente; o operador digita o desconto que quiser dar (% ou R$ na tela,
 * convertido para R$ antes de enviar como {@code descontoVenda}), e o servidor rejeita (400)
 * se passar do máximo. O desconto/acréscimo de cada {@code tipo_carteira} forma a "cobertura"
 * de cada linha — o quanto ela abate do saldo a pagar — e a soma das coberturas tem que fechar
 * exatamente o líquido a pagar (produtos − desconto da venda). <b>"X% de desconto/acréscimo" é
 * sempre sobre o valor pago</b> (retificado 2026-07-28: pagar R$78 numa forma com 10% de
 * desconto dá R$7,80 de desconto — {@code descontoLinha = valorPago × percDesconto/100}, não
 * um percentual sobre o valor coberto). O {@code valorPago} máximo de uma linha com desconto
 * próprio é o que fecha o saldo restante sozinho, sem sobra — calculado ao contrário dessa
 * mesma fórmula: {@code saldoRestanteAntesDaLinha ÷ (1 + percDesconto/100)} — processado na
 * ordem em que as linhas chegam no request, mesma ordem em que a tela vai lançando. Todo o
 * desconto/acréscimo apurado é rateado entre os itens vendidos e
 * gravado em {@code produto_movimento_detalhe.valor_desconto/valor_acrescimo} (proporcional ao
 * valor de cada item, resto do arredondamento no último — mesmo truque da parcela) — nunca em
 * {@code contas_receber.valor_desconto}, que é reservado para um recurso futuro de desconto na
 * cobrança de parcela de crediário atrasada, sem relação com desconto de venda. {@code
 * contas_receber} sempre grava o {@code valorPago} literal de cada linha (o dinheiro/cobrança
 * que de fato circula) — por isso a soma de {@code valorPago} das linhas bate exatamente com a
 * soma do valor dos itens já líquido de desconto/acréscimo.
 */
@Service
public class PdvVendaService {

    private static final BigDecimal TOLERANCIA_SALDO = new BigDecimal("0.01");

    private final JdbcClient jdbc;
    private final ConfiguracaoGeralService configuracaoGeralService;
    private final CaixaService caixaService;

    public PdvVendaService(JdbcClient jdbc, ConfiguracaoGeralService configuracaoGeralService, CaixaService caixaService) {
        this.jdbc = jdbc;
        this.configuracaoGeralService = configuracaoGeralService;
        this.caixaService = caixaService;
    }

    @Transactional
    public VendaEfetivadaResponse efetivarVenda(Jwt jwt, EfetivarVendaRequest req) {
        long idEmpresa = ((Number) jwt.getClaim("eid")).longValue();
        long idUsuario = Long.parseLong(jwt.getSubject());
        long idCaixa = caixaService.idCaixaAbertoObrigatorio(idEmpresa, idUsuario);
        long idCliente = validarCliente(req.idCliente());
        long idFuncionario = validarFuncionario(req.idFuncionario());
        List<ItemResolvido> itens = resolverItens(req.itens(), idEmpresa);

        BigDecimal valorTotalProdutos = itens.stream()
                .map(ItemResolvido::valorItem)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal percentualMaximoDesconto = buscarPercentualDescontoVenda();
        BigDecimal descontoMaximoPermitido = percentualMaximoDesconto.compareTo(BigDecimal.ZERO) > 0
                ? valorTotalProdutos.multiply(percentualMaximoDesconto)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN)
                : BigDecimal.ZERO;
        BigDecimal descontoVenda = req.descontoVenda();
        if (descontoVenda.compareTo(descontoMaximoPermitido.add(TOLERANCIA_SALDO)) > 0) {
            throw new IllegalArgumentException(
                    "Desconto informado (R$ " + descontoVenda + ") excede o máximo permitido de "
                            + percentualMaximoDesconto + "% (R$ " + descontoMaximoPermitido + ").");
        }
        BigDecimal valorLiquido = valorTotalProdutos.subtract(descontoVenda);

        List<LinhaPagamentoResolvida> linhas = resolverPagamentos(req.pagamentos(), valorLiquido);

        BigDecimal totalCobertura = linhas.stream().map(LinhaPagamentoResolvida::cobertura)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diferenca = valorLiquido.subtract(totalCobertura);
        if (diferenca.abs().compareTo(TOLERANCIA_SALDO) > 0) {
            throw new IllegalArgumentException(
                    "Os pagamentos informados (R$ " + totalCobertura + ") não fecham o saldo a pagar (R$ " + valorLiquido + ").");
        }

        BigDecimal totalDesconto = descontoVenda.add(linhas.stream()
                .map(LinhaPagamentoResolvida::descontoLinha).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal totalAcrescimo = linhas.stream()
                .map(LinhaPagamentoResolvida::acrescimoLinha).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BigDecimal> pesos = itens.stream().map(ItemResolvido::valorItem).toList();
        List<BigDecimal> descontoPorItem = ratear(totalDesconto, pesos, valorTotalProdutos);
        List<BigDecimal> acrescimoPorItem = ratear(totalAcrescimo, pesos, valorTotalProdutos);

        long idVenda = jdbc.sql("""
                        INSERT INTO venda (id_tenant, id_empresa, id_cliente) VALUES (plataforma.tenant_atual(), ?, ?)
                        RETURNING id_venda
                        """)
                .params(idEmpresa, idCliente).query(Long.class).single();

        long idMovimento = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_venda)
                        VALUES (plataforma.tenant_atual(), ?, 'VENDA', ?)
                        RETURNING id_movimento
                        """)
                .params(idEmpresa, idVenda).query(Long.class).single();

        for (int i = 0; i < itens.size(); i++) {
            ItemResolvido item = itens.get(i);
            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto,
                                 preco_venda, valor_desconto, valor_acrescimo, id_funcionario)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'D', ?, ?, ?, ?, ?)
                            """)
                    .params(idMovimento, idEmpresa, item.idVariacao(), item.qtd(), item.precoVenda(),
                            descontoPorItem.get(i), acrescimoPorItem.get(i), idFuncionario)
                    .update();
        }

        List<PagamentoGerado> pagamentos = new ArrayList<>();
        for (LinhaPagamentoResolvida linha : linhas) {
            List<ParcelaGerada> parcelas = gerarEInserirParcelas(idVenda, linha, idEmpresa);
            pagamentos.add(new PagamentoGerado(linha.carteira().idCarteira(), linha.carteira().nomeCarteira(),
                    linha.valorPago(), parcelas));

            if (linha.carteira().categoria() != CategoriaCarteira.CREDIARIO) {
                jdbc.sql("""
                                INSERT INTO caixa_detalhe
                                    (id_tenant, id_caixa, id_carteira, id_venda, valor, tipo_operacao, credito_debito)
                                VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, 'RECEBIMENTO_VENDA', 'C')
                                """)
                        .params(idCaixa, linha.carteira().idCarteira(), idVenda, linha.valorPago())
                        .update();
            }
        }

        return new VendaEfetivadaResponse(idVenda, valorTotalProdutos, descontoVenda, valorLiquido, pagamentos);
    }

    private BigDecimal buscarPercentualDescontoVenda() {
        return jdbc.sql("""
                        SELECT percentual_desconto_venda FROM cfg_geral
                        WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query(BigDecimal.class)
                .optional()
                .orElse(BigDecimal.ZERO);
    }

    private record CarteiraInfo(long idCarteira, String nomeCarteira, CategoriaCarteira categoria,
                                 int prazoPagamento, int pcMinima, int pcMaxima,
                                 BigDecimal percDesconto, BigDecimal percAcrescimo) {
    }

    private CarteiraInfo buscarCarteira(long idCarteira) {
        return jdbc.sql("""
                        SELECT id_carteira, nome_carteira, categoria_carteira::text AS categoria_carteira,
                               prazo_pagamento, pc_minima, pc_maxima, perc_desconto, perc_acrescimo
                        FROM tipo_carteira WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?
                        """)
                .param(idCarteira)
                .query((rs, n) -> new CarteiraInfo(
                        rs.getLong("id_carteira"), rs.getString("nome_carteira"),
                        CategoriaCarteira.valueOf(rs.getString("categoria_carteira")),
                        rs.getInt("prazo_pagamento"), rs.getInt("pc_minima"), rs.getInt("pc_maxima"),
                        rs.getBigDecimal("perc_desconto"), rs.getBigDecimal("perc_acrescimo")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Tipo de carteira informado não existe."));
    }

    /** {@code true} se o valor tiver parte fracionária (ex.: 2.5), não importa a escala/zeros à direita. */
    private static boolean temParteDecimal(BigDecimal valor) {
        return valor.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0;
    }

    /** AVISTA/CARTAO_DEBITO só aceitam pagamento em parcela única — não tem relação com a
     *  parcela já estar recebida ou não em {@code contas_receber} (ver {@link
     *  #gerarEInserirParcelas}). */
    private static boolean aceitaApenasUmaParcela(CategoriaCarteira categoria) {
        return categoria == CategoriaCarteira.AVISTA || categoria == CategoriaCarteira.CARTAO_DEBITO;
    }

    private static void validarParcelas(CarteiraInfo carteira, boolean aceitaApenasUmaParcela, int numeroParcelas) {
        if (aceitaApenasUmaParcela && numeroParcelas != 1) {
            throw new IllegalArgumentException("Forma de pagamento à vista aceita apenas 1 parcela.");
        }
        if (numeroParcelas < carteira.pcMinima() || numeroParcelas > carteira.pcMaxima()) {
            throw new IllegalArgumentException(
                    "Número de parcelas deve estar entre " + carteira.pcMinima() + " e " + carteira.pcMaxima() + ".");
        }
    }

    /**
     * Cobertura de uma linha: {@code valorPago + valorPago×percDesconto/100 −
     * valorPago×percAcrescimo/100} — o desconto/acréscimo incide sobre o valor pago
     * (retificado 2026-07-28: pagar R$78 numa forma com 10% de desconto dá R$7,80 de desconto,
     * não um percentual sobre o que está sendo coberto). Desconto faz a linha cobrir mais saldo
     * do que o valor tendido; acréscimo cobre menos (a diferença é custo da forma de pagamento,
     * não abate dívida). `tipo_carteira` nunca tem os dois setados ao mesmo tempo (validado no
     * cadastro).
     */
    private record LinhaPagamentoResolvida(CarteiraInfo carteira, BigDecimal valorPago, int numeroParcelas,
                                            BigDecimal descontoLinha, BigDecimal acrescimoLinha,
                                            BigDecimal cobertura) {
    }

    /**
     * Processa as linhas na ordem em que chegam (mesma ordem em que a tela vai lançando),
     * mantendo o saldo restante corrente — necessário porque o limite de {@code valorPago}
     * de uma linha com desconto próprio depende do saldo que ainda falta cobrir antes dela.
     */
    private List<LinhaPagamentoResolvida> resolverPagamentos(List<PagamentoRequest> pagamentos, BigDecimal valorLiquido) {
        List<LinhaPagamentoResolvida> resolvidas = new ArrayList<>();
        BigDecimal saldoRestante = valorLiquido;
        for (PagamentoRequest pgto : pagamentos) {
            CarteiraInfo carteira = buscarCarteira(pgto.idCarteira());
            validarParcelas(carteira, aceitaApenasUmaParcela(carteira.categoria()), pgto.numeroParcelas());

            BigDecimal percDesconto = carteira.percDesconto() == null ? BigDecimal.ZERO : carteira.percDesconto();
            BigDecimal percAcrescimo = carteira.percAcrescimo() == null ? BigDecimal.ZERO : carteira.percAcrescimo();

            // "X% de desconto/acréscimo" é sempre sobre o valor PAGO (2026-07-28, retificado —
            // era a fórmula original): descontoLinha = valorPago × percDesconto/100. O máximo
            // pagável numa linha com desconto é o que fecha o saldo restante sozinho, sem
            // sobra — precisa ser calculado ao contrário dessa mesma fórmula: valorPago ×
            // (1+percDesconto/100) = saldoRestante ⟹ valorPagoMaximo = saldoRestante ÷
            // (1+percDesconto/100).
            if (percDesconto.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal fator = BigDecimal.ONE.add(
                        percDesconto.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                BigDecimal valorPagoMaximo = saldoRestante.max(BigDecimal.ZERO).divide(fator, 2, RoundingMode.HALF_UP);
                if (pgto.valorPago().compareTo(valorPagoMaximo.add(TOLERANCIA_SALDO)) > 0) {
                    throw new IllegalArgumentException(
                            "Valor pago (R$ " + pgto.valorPago() + ") em " + carteira.nomeCarteira()
                                    + " excede o máximo permitido de R$ " + valorPagoMaximo
                                    + " (desconto de " + percDesconto + "% sobre o saldo de R$ " + saldoRestante + ").");
                }
            }

            BigDecimal descontoLinha = percDesconto.compareTo(BigDecimal.ZERO) > 0
                    ? pgto.valorPago().multiply(percDesconto).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal acrescimoLinha = percAcrescimo.compareTo(BigDecimal.ZERO) > 0
                    ? pgto.valorPago().multiply(percAcrescimo).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal cobertura = pgto.valorPago().add(descontoLinha).subtract(acrescimoLinha);

            resolvidas.add(new LinhaPagamentoResolvida(
                    carteira, pgto.valorPago(), pgto.numeroParcelas(), descontoLinha, acrescimoLinha, cobertura));
            saldoRestante = saldoRestante.subtract(cobertura);
        }
        return resolvidas;
    }

    /** Cliente e vendedor são obrigatórios em toda venda do PDV (2026-07-28) — não é mais
     *  venda de balcão anônima. */
    private long validarCliente(long idCliente) {
        return jdbc.sql("""
                        SELECT id_cliente FROM cliente
                        WHERE id_tenant = plataforma.tenant_atual() AND id_cliente = ? AND ativo = true
                        """)
                .param(idCliente).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Cliente informado não existe ou está inativo."));
    }

    private long validarFuncionario(long idFuncionario) {
        return jdbc.sql("""
                        SELECT id_funcionario FROM funcionario
                        WHERE id_tenant = plataforma.tenant_atual() AND id_funcionario = ? AND ativo = true
                        """)
                .param(idFuncionario).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Vendedor informado não existe ou está inativo."));
    }

    private record ItemResolvido(long idVariacao, BigDecimal qtd, BigDecimal precoVenda) {
        BigDecimal valorItem() {
            return precoVenda.multiply(qtd);
        }
    }

    private record LinhaItem(String descricaoProduto, String variacaoLinha, String variacaoColuna, BigDecimal precoVenda) {
    }

    /**
     * Resolve preço/variação de TODOS os itens antes de gravar. Não checa estoque disponível —
     * saldo negativo é permitido de propósito em qualquer movimentação (2026-07-29). Quantidade
     * decimal só é aceita se {@code cfg_geral.cfg_permite_qtd_decimal} estiver ligado
     * (Parâmetros do Sistema) — mesma regra em qualquer lugar que grava {@code qtd_produto}.
     */
    private List<ItemResolvido> resolverItens(List<ItemVendaRequest> itens, long idEmpresa) {
        boolean permiteQtdDecimal = configuracaoGeralService.permiteQtdDecimalProduto();
        List<ItemResolvido> resolvidos = new ArrayList<>();
        for (ItemVendaRequest item : itens) {
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
                    .params(item.idVariacao())
                    .query((rs, n) -> new LinhaItem(
                            rs.getString("descricao_produto"), rs.getString("variacao_linha"), rs.getString("variacao_coluna"),
                            rs.getBigDecimal("preco_venda")))
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("Produto informado não existe ou está inativo."));

            resolvidos.add(new ItemResolvido(item.idVariacao(), item.qtd(), linha.precoVenda()));
        }
        return resolvidos;
    }

    /**
     * Rateia {@code total} entre os pesos proporcionalmente (divisão truncada em 2 casas), com
     * o resto do arredondamento absorvido no último item — mesmo padrão de {@code
     * gerarEInserirParcelas}, garante que a soma das partes bate exatamente com {@code total}.
     */
    private static List<BigDecimal> ratear(BigDecimal total, List<BigDecimal> pesos, BigDecimal pesoTotal) {
        List<BigDecimal> partes = new ArrayList<>();
        if (total.compareTo(BigDecimal.ZERO) == 0 || pesoTotal.compareTo(BigDecimal.ZERO) == 0) {
            pesos.forEach(p -> partes.add(BigDecimal.ZERO));
            return partes;
        }
        BigDecimal acumulado = BigDecimal.ZERO;
        for (int i = 0; i < pesos.size(); i++) {
            if (i == pesos.size() - 1) {
                partes.add(total.subtract(acumulado));
            } else {
                BigDecimal parte = total.multiply(pesos.get(i)).divide(pesoTotal, 2, RoundingMode.DOWN);
                partes.add(parte);
                acumulado = acumulado.add(parte);
            }
        }
        return partes;
    }

    /**
     * Parcela paga na hora (AVISTA/CARTAO_DEBITO): 1 linha já com {@code dataRecebimento}
     * preenchida. Demais categorias: N linhas em aberto, vencimento espaçado por {@code
     * prazoPagamento} dias; o resto do arredondamento (divisão truncada em 2 casas) vai pra
     * última parcela — a soma bate exatamente com {@code valorPago}, nunca perde nem sobra centavo.
     */
    private List<ParcelaGerada> gerarEInserirParcelas(long idVenda, LinhaPagamentoResolvida linha, long idEmpresa) {
        int numeroParcelas = linha.numeroParcelas();
        BigDecimal valorPago = linha.valorPago();
        BigDecimal valorBase = valorPago.divide(BigDecimal.valueOf(numeroParcelas), 2, RoundingMode.DOWN);
        BigDecimal ajusteUltima = valorPago.subtract(valorBase.multiply(BigDecimal.valueOf(numeroParcelas)));
        OffsetDateTime agora = OffsetDateTime.now();
        // Só AVISTA (dinheiro/Pix) já nasce quitada. CARTAO_DEBITO/CARTAO_CREDITO já entraram no
        // caixa (ver efetivarVenda), mas o dinheiro em si ainda não liquidou na conta do lojista
        // — o prazo de cada bandeira é `tipo_carteira.prazo_pagamento` (débito costuma ser D+1,
        // já usado abaixo no vencimento) — a parcela fica em aberto até a conciliação de cartões
        // (ainda não construída) baixá-la. CREDIARIO segue em aberto até o Recebimento de
        // Crediário baixar. (2026-07-30, revisão — antes CARTAO_DEBITO nascia quitado junto com
        // AVISTA, mas entrar no caixa não é o mesmo que já estar recebido.)
        boolean jaRecebido = linha.carteira().categoria() == CategoriaCarteira.AVISTA;

        List<ParcelaGerada> parcelas = new ArrayList<>();
        for (int n = 1; n <= numeroParcelas; n++) {
            BigDecimal valorParcela = n == numeroParcelas ? valorBase.add(ajusteUltima) : valorBase;
            OffsetDateTime vencimento = agora.plusDays((long) linha.carteira().prazoPagamento() * n);
            OffsetDateTime dataRecebimento = jaRecebido ? agora : null;
            BigDecimal valorRecebido = jaRecebido ? valorParcela : BigDecimal.ZERO;
            Long idEmpresaPagamento = jaRecebido ? idEmpresa : null;

            jdbc.sql("""
                            INSERT INTO contas_receber
                                (id_tenant, id_venda, id_carteira, numero_parcela, data_vencimento, data_recebimento,
                                 valor_receber, valor_recebido, id_empresa_pagamento)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(idVenda, linha.carteira().idCarteira(), n, vencimento, dataRecebimento, valorParcela, valorRecebido, idEmpresaPagamento)
                    .update();

            parcelas.add(new ParcelaGerada(n, vencimento, valorParcela, jaRecebido));
        }
        return parcelas;
    }
}
