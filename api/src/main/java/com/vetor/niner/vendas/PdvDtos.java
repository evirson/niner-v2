package com.vetor.niner.vendas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do PDV (docs/telas/pdv.md) — busca/leitura de produto e efetivação de venda. */
public final class PdvDtos {

    private PdvDtos() {
    }

    /** Estoque de uma variação numa empresa específica — `0` quando não há linha em `produto_estoque`. */
    public record EstoqueEmpresa(int codigoEmpresa, String nomeEmpresa, BigDecimal qtd) {
    }

    /**
     * Uma variação (`produto_barra`) — cada linha do resultado de busca/leitura é uma
     * variação, nunca um produto agrupado. `variacaoCor`/`variacaoTamanho` são `null` quando
     * o produto não usa grade.
     */
    public record PdvProdutoResponse(
            long idVariacao,
            String descricaoProduto,
            String variacaoCor,
            String variacaoTamanho,
            String sku,
            BigDecimal precoVenda,
            List<EstoqueEmpresa> estoquePorEmpresa,
            BigDecimal estoqueTotal,
            /** URL pública da primeira foto da galeria do produto (indice 0), {@code null} se não tiver foto. */
            String urlImagem,
            /** Marca/referência do produto (2026-08-12) — usados como filtro de busca na
             *  Entrada de Produtos por Compra, exibidos no resultado para desambiguar. */
            String marca,
            String referencia) {
    }

    /** Preço nunca vem do cliente — só `idVariacao` + `qtd`; o servidor resolve o preço. */
    public record ItemVendaRequest(
            @NotNull Long idVariacao,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qtd) {
    }

    /** Resultado da busca de cliente (F6, 2026-07-28) — nome, CPF/CNPJ ou celular (`cliente.telefone`). */
    public record PdvClienteResponse(long idCliente, String nome, String cpfCnpj, String telefone) {
    }

    /**
     * Uma linha de pagamento (split-tender, 2026-07-28) — {@code valorPago} é o valor tendido
     * nessa forma de pagamento (o que efetivamente circula: dinheiro entregue, valor cobrado no
     * cartão). O quanto essa linha abate do saldo a pagar (a "cobertura") é calculado no
     * servidor a partir do desconto/acréscimo do {@code tipo_carteira} — nunca enviado pelo
     * cliente. {@code idDevolucao} (2026-08-03) é obrigatório só quando o {@code tipo_carteira}
     * escolhido é da categoria {@code VALE_MERCADORIA} — identifica qual vale
     * (`venda_devolucao.id_devolucao`) está sendo resgatado; o servidor ignora {@code
     * valorPago} nesse caso e usa o valor de fato do vale (mesmo princípio de nunca confiar em
     * valor vindo do cliente).
     */
    public record PagamentoRequest(
            @NotNull Long idCarteira,
            @NotNull @DecimalMin(value = "0.01") BigDecimal valorPago,
            @NotNull @Min(1) Integer numeroParcelas,
            Long idDevolucao) {
    }

    /**
     * Split-tender (2026-07-28): uma venda pode ter várias linhas de pagamento, cada uma com
     * seu próprio {@code tipo_carteira} e número de parcelas. A soma das "coberturas" das
     * linhas tem que fechar exatamente o líquido a pagar (produtos − desconto da venda) — ver
     * {@code PdvVendaService.efetivarVenda}. {@code descontoVenda} é o desconto em R$ que o
     * operador decidiu dar nesta venda (campo de digitação livre na tela — % ou R$, o front
     * converte para R$ antes de enviar); nunca pode passar do máximo configurado em {@code
     * cfg_geral.percentual_desconto_venda} — o servidor valida e rejeita se passar.
     * {@code idCliente}/{@code idFuncionario} (2026-07-28) são obrigatórios — toda venda do PDV
     * passou a exigir cliente e vendedor identificados, não é mais venda de balcão anônima.
     */
    public record EfetivarVendaRequest(
            @NotEmpty List<@Valid ItemVendaRequest> itens,
            @NotNull @DecimalMin(value = "0") BigDecimal descontoVenda,
            @NotEmpty List<@Valid PagamentoRequest> pagamentos,
            @NotNull Long idCliente,
            @NotNull Long idFuncionario) {
    }

    /** `paga` = true quando a parcela já nasce recebida (categoria AVISTA/CARTAO_DEBITO). */
    public record ParcelaGerada(int numeroParcela, OffsetDateTime dataVencimento, BigDecimal valorParcela, boolean paga) {
    }

    /** Uma linha de pagamento já efetivada, com as parcelas geradas em `contas_receber`. */
    public record PagamentoGerado(
            long idCarteira,
            String nomeCarteira,
            BigDecimal valorPago,
            List<ParcelaGerada> parcelas) {
    }

    /**
     * {@code valorTotalProdutos} é a soma bruta dos itens; {@code descontoVenda} é o desconto
     * que o operador aplicou (0 se nenhum, sempre ≤ o máximo de {@code
     * cfg_geral.percentual_desconto_venda}); {@code valorLiquido} é o que as linhas de
     * pagamento tiveram que cobrir juntas.
     */
    public record VendaEfetivadaResponse(
            long idVenda,
            BigDecimal valorTotalProdutos,
            BigDecimal descontoVenda,
            BigDecimal valorLiquido,
            List<PagamentoGerado> pagamentos) {
    }

    /** Uma linha de item da papeleta de venda (2026-08-06) — {@code valorTotal} é bruto
     *  ({@code valorUnitario × qtd}); desconto/acréscimo só aparecem somados no rodapé da
     *  papeleta, nunca por item (mesmo formato do mockup pedido pelo dono do produto). */
    public record ItemComprovanteVenda(
            String sku, String descricaoProduto, String variacaoCor, String variacaoTamanho,
            BigDecimal qtd, BigDecimal valorUnitario, BigDecimal valorTotal) {
    }

    /** {@code crediario} (2026-08-06) diferencia o rótulo na papeleta: "VALOR PAGO EM" (dinheiro/
     *  cartão, já circulou) vs "VALOR A PAGAR EM" (crediário, ainda em aberto — ver parcelas). */
    public record PagamentoComprovanteVenda(String nomeCarteira, boolean crediario, BigDecimal valorPago) {
    }

    /** Uma parcela de CREDIARIO em aberto (2026-08-06) — a papeleta lista todas pro consumidor
     *  conferir vencimento/valor; só aparece na papeleta quando a venda teve pagamento nessa
     *  categoria (lista vazia = nenhuma linha de crediário nessa venda). */
    public record ParcelaComprovanteVenda(
            int numeroParcela, int totalParcelas, OffsetDateTime dataVencimento, BigDecimal valorParcela) {
    }

    /**
     * Papeleta de venda pra impressão térmica 80mm (2026-08-06, docs/telas/pdv.md) — aberta
     * automaticamente após F5 efetivar a venda. {@code subtotal}/{@code descontos}/{@code
     * acrescimos} vêm somados direto de {@code produto_movimento_detalhe} (não recalculados a
     * partir da regra de negócio do split-tender) — é o valor de fato gravado, garantindo que
     * bate com {@code totalAPagar = subtotal − descontos + acrescimos}, que por sua vez bate com
     * a soma de {@code pagamentos} (ver PdvVendaService.buscarComprovante). {@code nomeOperador}
     * vem de {@code venda.id_caixa → caixa_mestre.id_usuario} — {@code null} em venda gravada
     * antes dessa coluna existir.
     */
    public record ComprovanteVendaResponse(
            long idVenda,
            String nomeEmpresa,
            int codigoEmpresa,
            OffsetDateTime dataVenda,
            String nomeCliente,
            String telefoneCliente,
            /** CPF/CNPJ do cliente da venda (2026-08-19) — só pra tela de emissão fiscal decidir
             *  se oferece a pergunta "incluir CPF na nota?"; {@code null} quando não há cliente ou
             *  ele não tem documento cadastrado (nesse caso a nota só pode sair pra consumidor). */
            String documentoCliente,
            String nomeVendedor,
            String nomeOperador,
            List<ItemComprovanteVenda> itens,
            BigDecimal subtotal,
            BigDecimal descontos,
            BigDecimal acrescimos,
            BigDecimal totalAPagar,
            List<PagamentoComprovanteVenda> pagamentos,
            List<ParcelaComprovanteVenda> parcelasCrediario,
            DadosFiscaisComprovante dadosFiscais) {
    }

    /**
     * Dados fiscais pra a papeleta virar DANFCE (§9.6, bloco B7) — {@code null} quando o fiscal
     * está desligado (F12) ou a nota não terminou autorizada/em contingência (rejeitada, denegada,
     * falha de comunicação, não emitida por DF13): nesses casos a papeleta sai <b>exatamente como
     * hoje</b>, sem menção fiscal — imprimir um DANFCE de uma nota que não existe seria pior que
     * não imprimir nada.
     *
     * <p>{@code qrCodeUrl}/{@code urlConsultaChave} vêm extraídos do <b>XML já assinado</b>
     * (nunca reconstruídos aqui) — é a única forma de garantir que o QR impresso é
     * <b>exatamente</b> o que está no documento, inclusive na contingência offline, cujo QR carrega
     * uma assinatura própria que não dá para recalcular fora do momento da emissão (B7).
     */
    public record DadosFiscaisComprovante(
            String chaveAcesso, String protocolo, OffsetDateTime dataAutorizacao,
            boolean homologacao, boolean contingencia, String qrCodeUrl, String urlConsultaChave,
            BigDecimal valorTotalTributos) {
    }
}
