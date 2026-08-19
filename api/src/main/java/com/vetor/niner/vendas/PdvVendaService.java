package com.vetor.niner.vendas;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.financeiro.TipoCarteiraDtos.CategoriaCarteira;
import com.vetor.niner.financeiro.caixa.CaixaService;
import com.vetor.niner.vendas.PdvDtos.ComprovanteVendaResponse;
import com.vetor.niner.vendas.PdvDtos.DadosFiscaisComprovante;
import com.vetor.niner.vendas.PdvDtos.EfetivarVendaRequest;
import com.vetor.niner.vendas.PdvDtos.EmpresaComprovante;
import com.vetor.niner.vendas.PdvDtos.ItemComprovanteVenda;
import com.vetor.niner.vendas.PdvDtos.ItemVendaRequest;
import com.vetor.niner.vendas.PdvDtos.PagamentoComprovanteVenda;
import com.vetor.niner.vendas.PdvDtos.PagamentoGerado;
import com.vetor.niner.vendas.PdvDtos.PagamentoRequest;
import com.vetor.niner.vendas.PdvDtos.ParcelaComprovanteVenda;
import com.vetor.niner.vendas.PdvDtos.ParcelaGerada;
import com.vetor.niner.vendas.PdvDtos.VendaEfetivadaResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

        validarLimiteCredito(idCliente, linhas);

        BigDecimal totalDesconto = descontoVenda.add(linhas.stream()
                .map(LinhaPagamentoResolvida::descontoLinha).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal totalAcrescimo = linhas.stream()
                .map(LinhaPagamentoResolvida::acrescimoLinha).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BigDecimal> pesos = itens.stream().map(ItemResolvido::valorItem).toList();
        List<BigDecimal> descontoPorItem = ratear(totalDesconto, pesos, valorTotalProdutos);
        List<BigDecimal> acrescimoPorItem = ratear(totalAcrescimo, pesos, valorTotalProdutos);

        long idVenda = jdbc.sql("""
                        INSERT INTO venda (id_tenant, id_empresa, id_cliente, id_caixa)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?)
                        RETURNING id_venda
                        """)
                .params(idEmpresa, idCliente, idCaixa).query(Long.class).single();

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
                                 preco_venda, preco_custo, valor_desconto, valor_acrescimo, id_funcionario)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'D', ?, ?, ?, ?, ?, ?)
                            """)
                    .params(idMovimento, idEmpresa, item.idVariacao(), item.qtd(), item.precoVenda(), item.precoCusto(),
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

            // Marca o vale como usado só agora (idVenda já existe pra virar id_venda_debito) —
            // WHERE vale_usado = false funciona como trava otimista contra resgate concorrente do
            // mesmo vale em duas vendas simultâneas: se outra transação já consumiu o vale entre
            // resolverPagamentos (leitura) e aqui, esta atualização afeta 0 linhas e a venda inteira
            // falha (2026-08-03).
            if (linha.idDevolucao() != null) {
                int atualizados = jdbc.sql("""
                                UPDATE venda_devolucao SET vale_usado = true, id_venda_debito = ?
                                WHERE id_tenant = plataforma.tenant_atual() AND id_devolucao = ? AND vale_usado = false
                                """)
                        .params(idVenda, linha.idDevolucao())
                        .update();
                if (atualizados == 0) {
                    throw new ConflitoDadosException(
                            "O vale-mercadoria nº " + linha.idDevolucao() + " já foi usado em outra venda.");
                }
            }
        }

        return new VendaEfetivadaResponse(idVenda, valorTotalProdutos, descontoVenda, valorLiquido, pagamentos);
    }

    /**
     * Papeleta de venda pra impressão térmica 80mm (2026-08-06, docs/telas/pdv.md), aberta
     * automaticamente após {@link #efetivarVenda}. Só lê o que já foi persistido — nenhuma conta
     * de desconto/acréscimo é refeita aqui; {@code subtotal}/{@code descontos}/{@code
     * acrescimos} vêm somados direto de {@code produto_movimento_detalhe} (rateio já gravado por
     * {@code efetivarVenda}), e {@code totalAPagar = subtotal − descontos + acrescimos} bate
     * matematicamente com a soma de {@code pagamentos} (que vem de {@code contas_receber.
     * valor_receber} — cobre AVISTA/CARTAO/CREDIARIO/VALE_MERCADORIA igualmente, já que toda
     * linha de pagamento grava lá, mesmo as que não entram em {@code caixa_detalhe}).
     */
    @Transactional(readOnly = true)
    public ComprovanteVendaResponse buscarComprovante(long idVenda) {
        record Cabecalho(String nomeEmpresa, int codigoEmpresa, OffsetDateTime dataVenda,
                          String nomeCliente, String telefoneCliente, String documentoCliente, String nomeOperador,
                          Integer numeroCaixa, String cnpjEmpresa, String inscricaoEstadualEmpresa,
                          String enderecoEmpresa, String telefoneEmpresa, boolean cancelada) {
        }

        Cabecalho cabecalho = jdbc.sql("""
                        SELECT e.razao_social AS nome_empresa, e.codigo_empresa, v.data_venda,
                               c.nome AS nome_cliente, c.telefone AS telefone_cliente, c.cpf_cnpj AS documento_cliente,
                               uop.nome_usuario AS nome_operador, v.id_caixa, v.cancelada,
                               e.cnpj, e.inscricao_estadual, e.telefone AS telefone_empresa,
                               e.endereco, e.numero AS numero_empresa, e.bairro, e.cidade, e.estado
                        FROM venda v
                        JOIN empresa e ON e.id_tenant = v.id_tenant AND e.id_empresa = v.id_empresa
                        LEFT JOIN cliente c ON c.id_tenant = v.id_tenant AND c.id_cliente = v.id_cliente
                        LEFT JOIN caixa_mestre cm ON cm.id_tenant = v.id_tenant AND cm.id_caixa = v.id_caixa
                        LEFT JOIN usuario uop ON uop.id_tenant = v.id_tenant AND uop.id_usuario = cm.id_usuario
                        WHERE v.id_tenant = plataforma.tenant_atual() AND v.id_venda = ?
                        """)
                .param(idVenda)
                .query((rs, n) -> new Cabecalho(
                        rs.getString("nome_empresa"), rs.getInt("codigo_empresa"),
                        rs.getObject("data_venda", OffsetDateTime.class),
                        rs.getString("nome_cliente"), rs.getString("telefone_cliente"),
                        rs.getString("documento_cliente"), rs.getString("nome_operador"),
                        (Integer) rs.getObject("id_caixa"), rs.getString("cnpj"),
                        rs.getString("inscricao_estadual"), rs.getString("telefone_empresa"),
                        enderecoCompleto(rs.getString("endereco"), rs.getString("numero_empresa"),
                                rs.getString("bairro"), rs.getString("cidade"), rs.getString("estado")),
                        rs.getBoolean("cancelada")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "A venda #" + idVenda + " não existe."));

        record Vendedor(String nome, Integer codigo) {
        }
        Vendedor vendedor = jdbc.sql("""
                        SELECT f.nome, f.id_funcionario
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_tenant = pmm.id_tenant AND pmd.id_movimento = pmm.id_movimento
                        JOIN funcionario f ON f.id_tenant = pmd.id_tenant AND f.id_funcionario = pmd.id_funcionario
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                        LIMIT 1
                        """)
                .param(idVenda)
                .query((rs, n) -> new Vendedor(rs.getString("nome"), rs.getInt("id_funcionario")))
                .optional()
                .orElse(new Vendedor(null, null));

        List<ItemComprovanteVenda> itens = jdbc.sql("""
                        SELECT pb.sku, p.descricao AS descricao_produto,
                               co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho,
                               pmd.qtd_produto, p.unidade_comercial, pmd.preco_venda,
                               (pmd.qtd_produto * pmd.preco_venda) AS valor_total
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_tenant = pmm.id_tenant AND pmd.id_movimento = pmm.id_movimento
                        JOIN produto_barra pb ON pb.id_tenant = pmd.id_tenant AND pb.id_variacao = pmd.id_variacao
                        JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                        LEFT JOIN cfg_cor co ON co.id_tenant = pb.id_tenant AND co.id_cor = pb.id_cor AND co.id_cor <> 1
                        LEFT JOIN cfg_tamanho ta ON ta.id_tenant = pb.id_tenant AND ta.id_tamanho = pb.id_tamanho AND ta.id_tamanho <> 1
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                        ORDER BY pmd.id_movimento_detalhe
                        """)
                .param(idVenda)
                .query((rs, n) -> new ItemComprovanteVenda(
                        rs.getString("sku"), rs.getString("descricao_produto"),
                        rs.getString("variacao_cor"), rs.getString("variacao_tamanho"),
                        rs.getBigDecimal("qtd_produto"), rs.getString("unidade_comercial"),
                        rs.getBigDecimal("preco_venda"), rs.getBigDecimal("valor_total")))
                .list();

        record Totais(BigDecimal subtotal, BigDecimal descontos, BigDecimal acrescimos) {
        }
        Totais totais = jdbc.sql("""
                        SELECT COALESCE(SUM(pmd.qtd_produto * pmd.preco_venda), 0) AS subtotal,
                               COALESCE(SUM(pmd.valor_desconto), 0) AS descontos,
                               COALESCE(SUM(pmd.valor_acrescimo), 0) AS acrescimos
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_tenant = pmm.id_tenant AND pmd.id_movimento = pmm.id_movimento
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                        """)
                .param(idVenda)
                .query((rs, n) -> new Totais(
                        rs.getBigDecimal("subtotal"), rs.getBigDecimal("descontos"), rs.getBigDecimal("acrescimos")))
                .single();

        List<PagamentoComprovanteVenda> pagamentos = jdbc.sql("""
                        SELECT tc.nome_carteira, tc.categoria_carteira::text AS categoria_carteira,
                               SUM(cr.valor_receber) AS valor_pago
                        FROM contas_receber cr
                        JOIN tipo_carteira tc ON tc.id_tenant = cr.id_tenant AND tc.id_carteira = cr.id_carteira
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_venda = ?
                        GROUP BY tc.id_carteira, tc.nome_carteira, tc.categoria_carteira
                        ORDER BY MIN(cr.id_conta_receber)
                        """)
                .param(idVenda)
                .query((rs, n) -> new PagamentoComprovanteVenda(
                        rs.getString("nome_carteira"), "CREDIARIO".equals(rs.getString("categoria_carteira")),
                        rs.getBigDecimal("valor_pago")))
                .list();

        // Parcelas de CREDIARIO (2026-08-06) — a papeleta lista as parcelas em aberto pro
        // consumidor conferir vencimento/valor; filtra por categoria (não por data_recebimento
        // NULL) porque CARTAO_DEBITO/CARTAO_CREDITO também ficam em aberto até a conciliação
        // (ver PdvVendaService.efetivarVenda), mas isso não é "crediário" pro cliente.
        List<ParcelaComprovanteVenda> parcelasCrediario = jdbc.sql("""
                        SELECT cr.numero_parcela,
                               (SELECT count(*) FROM contas_receber cr2
                                WHERE cr2.id_tenant = cr.id_tenant AND cr2.id_venda = cr.id_venda
                                      AND cr2.id_carteira = cr.id_carteira) AS total_parcelas,
                               cr.data_vencimento, cr.valor_receber
                        FROM contas_receber cr
                        JOIN tipo_carteira tc ON tc.id_tenant = cr.id_tenant AND tc.id_carteira = cr.id_carteira
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_venda = ?
                              AND tc.categoria_carteira = 'CREDIARIO'
                        ORDER BY cr.numero_parcela
                        """)
                .param(idVenda)
                .query((rs, n) -> new ParcelaComprovanteVenda(
                        rs.getInt("numero_parcela"), rs.getInt("total_parcelas"),
                        rs.getObject("data_vencimento", OffsetDateTime.class), rs.getBigDecimal("valor_receber")))
                .list();

        BigDecimal totalAPagar = totais.subtotal().subtract(totais.descontos()).add(totais.acrescimos());
        DadosFiscaisComprovante dadosFiscais = buscarDadosFiscais(idVenda);
        EmpresaComprovante empresaFiscal = dadosFiscais == null ? null : new EmpresaComprovante(
                cabecalho.nomeEmpresa(), cabecalho.cnpjEmpresa(), cabecalho.inscricaoEstadualEmpresa(),
                cabecalho.enderecoEmpresa(), cabecalho.telefoneEmpresa());

        return new ComprovanteVendaResponse(
                idVenda, cabecalho.nomeEmpresa(), cabecalho.codigoEmpresa(), cabecalho.dataVenda(),
                cabecalho.nomeCliente(), cabecalho.telefoneCliente(), cabecalho.documentoCliente(),
                vendedor.nome(), vendedor.codigo(), cabecalho.numeroCaixa(), cabecalho.nomeOperador(),
                itens, totais.subtotal(), totais.descontos(), totais.acrescimos(), totalAPagar,
                pagamentos, parcelasCrediario, dadosFiscais, empresaFiscal, cabecalho.cancelada());
    }

    /** "RODOVIA BR-116, 15338 - XAXIM - CURITIBA - PR" (2026-08-19, DANFE) — pedaços nulos somem
     *  em vez de deixar " - " sobrando (endereço incompleto é comum enquanto o cadastro da
     *  empresa não foi completado, F11 não bloqueia a venda por isso). */
    private static String enderecoCompleto(String logradouro, String numero, String bairro,
                                           String cidade, String estado) {
        List<String> partes = new ArrayList<>();
        if (logradouro != null && !logradouro.isBlank()) {
            partes.add(numero != null && !numero.isBlank() ? logradouro + ", " + numero : logradouro);
        }
        if (bairro != null && !bairro.isBlank()) partes.add(bairro);
        if (cidade != null && !cidade.isBlank()) partes.add(cidade);
        if (estado != null && !estado.isBlank()) partes.add(estado);
        return partes.isEmpty() ? null : String.join(" - ", partes);
    }

    /**
     * §9.6/B7: a papeleta vira DANFCE quando existe NFC-e autorizada ou em contingência para esta
     * venda. {@code null} em qualquer outro caso (F12, rejeição, falha) — a papeleta segue como
     * sempre foi. Pega a mais recente por {@code criado_em}: uma venda pode ter mais de um
     * {@code documento_fiscal} se a primeira tentativa foi rejeitada e o operador reemitiu.
     */
    private DadosFiscaisComprovante buscarDadosFiscais(long idVenda) {
        return jdbc.sql("""
                        SELECT chave_acesso, protocolo, data_autorizacao, ambiente::text AS ambiente,
                               tipo_emissao, valor_total_tributos, xml_assinado, numero, serie,
                               base_ibs_cbs, valor_ibs_uf, valor_ibs_mun, valor_cbs,
                               valor_trib_federal, valor_trib_estadual, valor_trib_municipal
                          FROM documento_fiscal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_venda = ?
                           AND situacao IN ('AUTORIZADO', 'CONTINGENCIA')
                         ORDER BY criado_em DESC
                         LIMIT 1
                        """)
                .param(idVenda)
                .query((rs, n) -> {
                    String xml = rs.getString("xml_assinado");
                    // ⚠️ CPF/CNPJ do CONSUMIDOR — nunca extrairTag(xml, "CNPJ") direto: o <emit>
                    // (a empresa) também tem CNPJ, e apareceria ANTES de um <dest> eventual, dando
                    // o CNPJ errado quando incluirCpf=false (achado testando ao vivo, 2026-08-19).
                    // O grupo <dest> inteiro está ausente quando a nota não identifica o consumidor.
                    String dest = extrairTag(xml, "dest");
                    String cpf = extrairTag(dest, "CPF");
                    String documentoConsumidor = cpf != null ? cpf : extrairTag(dest, "CNPJ");
                    return new DadosFiscaisComprovante(
                            rs.getString("chave_acesso"), rs.getString("protocolo"),
                            rs.getObject("data_autorizacao", OffsetDateTime.class),
                            "HOMOLOGACAO".equals(rs.getString("ambiente")), rs.getInt("tipo_emissao") == 9,
                            extrairTagCdata(xml, "qrCode"), extrairTag(xml, "urlChave"),
                            rs.getBigDecimal("valor_total_tributos"), rs.getInt("numero"), rs.getInt("serie"),
                            documentoConsumidor, rs.getBigDecimal("base_ibs_cbs"),
                            rs.getBigDecimal("valor_ibs_uf"), rs.getBigDecimal("valor_ibs_mun"),
                            rs.getBigDecimal("valor_cbs"), rs.getBigDecimal("valor_trib_federal"),
                            rs.getBigDecimal("valor_trib_estadual"), rs.getBigDecimal("valor_trib_municipal"));
                })
                .optional()
                .orElse(null);
    }

    private static String extrairTag(String xml, String tag) {
        if (xml == null) return null;
        String abre = "<" + tag + ">";
        String fecha = "</" + tag + ">";
        int inicio = xml.indexOf(abre);
        if (inicio < 0) return null;
        inicio += abre.length();
        int fim = xml.indexOf(fecha, inicio);
        return fim < 0 ? null : xml.substring(inicio, fim);
    }

    private static String extrairTagCdata(String xml, String tag) {
        String bruto = extrairTag(xml, tag);
        if (bruto == null) return null;
        return bruto.replace("<![CDATA[", "").replace("]]>", "");
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

    /** AVISTA/CARTAO_DEBITO/VALE_MERCADORIA só aceitam pagamento em parcela única — não tem
     *  relação com a parcela já estar recebida ou não em {@code contas_receber} (ver {@link
     *  #gerarEInserirParcelas}). */
    private static boolean aceitaApenasUmaParcela(CategoriaCarteira categoria) {
        return categoria == CategoriaCarteira.AVISTA || categoria == CategoriaCarteira.CARTAO_DEBITO
                || categoria == CategoriaCarteira.VALE_MERCADORIA;
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
     * cadastro). {@code idDevolucao} (2026-08-03) só é não-nulo em linhas VALE_MERCADORIA —
     * carregado até o loop final de {@link #efetivarVenda} pra marcar o vale como usado.
     */
    private record LinhaPagamentoResolvida(CarteiraInfo carteira, BigDecimal valorPago, int numeroParcelas,
                                            BigDecimal descontoLinha, BigDecimal acrescimoLinha,
                                            BigDecimal cobertura, Long idDevolucao) {
    }

    private record ValeInfo(BigDecimal valor, boolean usado, boolean cancelado) {
    }

    /** Resolve o valor de um vale-mercadoria (soma dos itens do movimento DEVOLUCAO vinculado —
     *  mesma conta de {@code DevolucaoProdutoService.buscarVale}, mas sem depender daquele
     *  service: cada serviço consulta o schema direto, mesmo padrão do resto do projeto). 404 se
     *  o vale não existir (ou for de outro tenant, RLS). {@code cancelado} (2026-08-10, ver
     *  {@code CancelamentoDevolucaoService}) bloqueia o resgate igual a um vale já usado — um
     *  vale cancelado não tem mais lastro em estoque. */
    private ValeInfo resolverVale(long idDevolucao) {
        record Cabecalho(boolean valeUsado, boolean cancelada) {
        }
        Cabecalho c = jdbc.sql("""
                        SELECT vale_usado, cancelada FROM venda_devolucao
                        WHERE id_tenant = plataforma.tenant_atual() AND id_devolucao = ?
                        """)
                .param(idDevolucao)
                .query((rs, n) -> new Cabecalho(rs.getBoolean("vale_usado"), rs.getBoolean("cancelada")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Vale-mercadoria não encontrado."));

        BigDecimal valor = jdbc.sql("""
                        SELECT COALESCE(SUM(pmd.qtd_produto * pmd.preco_venda), 0)
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_devolucao = ?
                              AND pmm.tipo_movimento = 'DEVOLUCAO'
                        """)
                .param(idDevolucao).query(BigDecimal.class).single();

        return new ValeInfo(valor, c.valeUsado(), c.cancelada());
    }

    /**
     * Processa as linhas na ordem em que chegam (mesma ordem em que a tela vai lançando),
     * mantendo o saldo restante corrente — necessário porque o limite de {@code valorPago}
     * de uma linha com desconto próprio depende do saldo que ainda falta cobrir antes dela.
     * VALE_MERCADORIA (2026-08-03) é resolvida à parte: {@code valorPago} do request é ignorado
     * — o valor de fato vem do vale (nunca confia em valor vindo do cliente, mesmo princípio de
     * qualquer preço nesta classe); sem desconto/acréscimo (a carteira nasce com os dois zerados,
     * ver seed). Um vale já usado é 409; um vale maior que o saldo que ainda falta pagar é 400
     * (RN: "não é possível usar um vale maior que a venda", decisão do dono do produto).
     */
    private List<LinhaPagamentoResolvida> resolverPagamentos(List<PagamentoRequest> pagamentos, BigDecimal valorLiquido) {
        List<LinhaPagamentoResolvida> resolvidas = new ArrayList<>();
        BigDecimal saldoRestante = valorLiquido;
        for (PagamentoRequest pgto : pagamentos) {
            CarteiraInfo carteira = buscarCarteira(pgto.idCarteira());
            validarParcelas(carteira, aceitaApenasUmaParcela(carteira.categoria()), pgto.numeroParcelas());

            if (carteira.categoria() == CategoriaCarteira.VALE_MERCADORIA) {
                if (pgto.idDevolucao() == null) {
                    throw new IllegalArgumentException("Informe o número do vale-mercadoria.");
                }
                ValeInfo vale = resolverVale(pgto.idDevolucao());
                if (vale.usado()) {
                    throw new ConflitoDadosException("O vale-mercadoria nº " + pgto.idDevolucao() + " já foi usado.");
                }
                if (vale.cancelado()) {
                    throw new ConflitoDadosException("O vale-mercadoria nº " + pgto.idDevolucao() + " foi cancelado.");
                }
                if (vale.valor().compareTo(saldoRestante.max(BigDecimal.ZERO).add(TOLERANCIA_SALDO)) > 0) {
                    throw new IllegalArgumentException(
                            "O vale-mercadoria nº " + pgto.idDevolucao() + " vale R$ " + vale.valor()
                                    + ", maior que o saldo a pagar de R$ " + saldoRestante
                                    + " — não é possível usar um vale maior que a venda.");
                }
                resolvidas.add(new LinhaPagamentoResolvida(
                        carteira, vale.valor(), pgto.numeroParcelas(), BigDecimal.ZERO, BigDecimal.ZERO, vale.valor(),
                        pgto.idDevolucao()));
                saldoRestante = saldoRestante.subtract(vale.valor());
                continue;
            }

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
                    carteira, pgto.valorPago(), pgto.numeroParcelas(), descontoLinha, acrescimoLinha, cobertura, null));
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

    /**
     * Limite de crédito ({@code cliente.limite_credito}, RN nova) — só se aplica quando a venda
     * tem alguma linha CREDIARIO e o cliente tem limite definido ({@code &gt; 0}; {@code &lt;= 0}
     * significa sem limite, não checa nada). {@code valorEmAberto} soma as parcelas de CREDIARIO
     * já em aberto do cliente em QUALQUER venda anterior ({@code data_recebimento IS NULL} —
     * mesmo filtro de {@code RecebimentoCrediarioService}/{@code ClienteHistoricoService.
     * buscarResumoCrediario}); somado ao CREDIARIO desta venda (ainda não gravado), não pode
     * passar do limite.
     */
    private void validarLimiteCredito(long idCliente, List<LinhaPagamentoResolvida> linhas) {
        BigDecimal valorCrediarioNestaVenda = linhas.stream()
                .filter(l -> l.carteira().categoria() == CategoriaCarteira.CREDIARIO)
                .map(LinhaPagamentoResolvida::valorPago)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (valorCrediarioNestaVenda.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        BigDecimal limiteCredito = jdbc.sql("""
                        SELECT limite_credito FROM cliente
                        WHERE id_tenant = plataforma.tenant_atual() AND id_cliente = ?
                        """)
                .param(idCliente).query(BigDecimal.class).single();
        if (limiteCredito.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal valorEmAberto = jdbc.sql("""
                        SELECT COALESCE(SUM(cr.valor_receber), 0)
                        FROM contas_receber cr
                        JOIN venda v ON v.id_tenant = cr.id_tenant AND v.id_venda = cr.id_venda
                        JOIN tipo_carteira tc ON tc.id_tenant = cr.id_tenant AND tc.id_carteira = cr.id_carteira
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND v.id_cliente = ?
                              AND tc.categoria_carteira = 'CREDIARIO' AND cr.data_recebimento IS NULL
                        """)
                .param(idCliente).query(BigDecimal.class).single();

        BigDecimal totalComprometido = valorEmAberto.add(valorCrediarioNestaVenda);
        if (totalComprometido.compareTo(limiteCredito.add(TOLERANCIA_SALDO)) > 0) {
            throw new IllegalArgumentException(
                    "Limite de crédito excedido — o cliente já tem R$ " + valorEmAberto
                            + " em parcelas de crediário em aberto; somado aos R$ " + valorCrediarioNestaVenda
                            + " desta venda, o total de R$ " + totalComprometido
                            + " ultrapassa o limite de crédito de R$ " + limiteCredito + ".");
        }
    }

    private long validarFuncionario(long idFuncionario) {
        return jdbc.sql("""
                        SELECT id_funcionario FROM funcionario
                        WHERE id_tenant = plataforma.tenant_atual() AND id_funcionario = ? AND ativo = true
                        """)
                .param(idFuncionario).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Vendedor informado não existe ou está inativo."));
    }

    private record ItemResolvido(long idVariacao, BigDecimal qtd, BigDecimal precoVenda, BigDecimal precoCusto) {
        BigDecimal valorItem() {
            return precoVenda.multiply(qtd);
        }
    }

    private record LinhaItem(String descricaoProduto, String variacaoCor, String variacaoTamanho, BigDecimal precoVenda, BigDecimal precoCusto) {
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
                                   co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho,
                                   p.preco_venda, p.preco_custo
                            FROM produto_barra pb
                            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                            LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                            LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                            WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ? AND p.ativo = true
                            """)
                    .params(item.idVariacao())
                    .query((rs, n) -> new LinhaItem(
                            rs.getString("descricao_produto"), rs.getString("variacao_cor"), rs.getString("variacao_tamanho"),
                            rs.getBigDecimal("preco_venda"), rs.getBigDecimal("preco_custo")))
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("Produto informado não existe ou está inativo."));

            resolvidos.add(new ItemResolvido(item.idVariacao(), item.qtd(), linha.precoVenda(), linha.precoCusto()));
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
        // AVISTA (dinheiro/Pix) e VALE_MERCADORIA (2026-08-03: resgate é liquidação instantânea —
        // o vale já era um crédito líquido) nascem quitadas. CARTAO_DEBITO/CARTAO_CREDITO já
        // entraram no caixa (ver efetivarVenda), mas o dinheiro em si ainda não liquidou na conta
        // do lojista — o prazo de cada bandeira é `tipo_carteira.prazo_pagamento` (débito costuma
        // ser D+1, já usado abaixo no vencimento) — a parcela fica em aberto até a conciliação de
        // cartões (ainda não construída) baixá-la. CREDIARIO segue em aberto até o Recebimento de
        // Crediário baixar. (2026-07-30, revisão — antes CARTAO_DEBITO nascia quitado junto com
        // AVISTA, mas entrar no caixa não é o mesmo que já estar recebido.)
        boolean jaRecebido = linha.carteira().categoria() == CategoriaCarteira.AVISTA
                || linha.carteira().categoria() == CategoriaCarteira.VALE_MERCADORIA;

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
