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

    /**
     * Preço nunca vem do cliente — só {@code idVariacao} + {@code qtd}; o servidor resolve o preço.
     *
     * <p>{@code doOrcamento} (2026-08-21) diz se ESTA linha é uma das que o orçamento cobre. Existe
     * porque o mesmo produto pode aparecer duas vezes na mesma venda com preços diferentes: o
     * cliente leva o que orçou (preço congelado, que a loja tem de honrar) e mais algumas unidades
     * do mesmo item (preço de hoje, que ele não orçou). Sem a marca por linha, o servidor mapeava o
     * preço congelado por variação e o aplicava aos dois — e a validação "não pode levar mais do
     * que foi orçado" somava as duas linhas e recusava a venda inteira.
     *
     * <p>Nulo = {@code false}: item de venda comum, preço do cadastro. Só o PDV vindo de orçamento
     * marca {@code true}, e ainda assim apenas até a quantidade orçada.
     */
    public record ItemVendaRequest(
            @NotNull Long idVariacao,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qtd,
            Boolean doOrcamento) {

        /** Sem a marca, a linha é venda comum — nunca herda o preço congelado do orçamento. */
        public boolean ehDoOrcamento() {
            return Boolean.TRUE.equals(doOrcamento);
        }
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
            @NotNull Long idFuncionario,
            /**
             * Orçamento que originou esta venda (V058), ou nulo numa venda normal.
             *
             * <p>⚠️ Quando presente, o PDV usa o preço <b>congelado do orçamento</b> em vez do
             * preço do cadastro — lido do banco, nunca do que a tela mandar. E as quantidades só
             * podem ser <b>menores ou iguais</b> às orçadas (R2): o cliente pode levar menos, nunca
             * mais nem por outro preço.
             */
            Long idOrcamento) {
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
            List<PagamentoGerado> pagamentos,
            /** Orçamento que virou esta venda, e se foi parcial (V058). Nulo em venda normal. */
            Long idOrcamento,
            Boolean orcamentoParcial) {
    }

    /** Uma linha de item da papeleta de venda (2026-08-06) — {@code valorTotal} é bruto
     *  ({@code valorUnitario × qtd}); desconto/acréscimo só aparecem somados no rodapé da
     *  papeleta, nunca por item (mesmo formato do mockup pedido pelo dono do produto). */
    public record ItemComprovanteVenda(
            String sku, String descricaoProduto, String variacaoCor, String variacaoTamanho,
            BigDecimal qtd, String unidadeComercial, BigDecimal valorUnitario, BigDecimal valorTotal) {
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
            /** {@code funcionario.id_funcionario} do vendedor (2026-08-19, DANFE) — o "VEN.: nn"
             *  do rodapé fiscal. {@code null} nos mesmos casos em que {@code nomeVendedor} é nulo. */
            Integer codigoVendedor,
            /** {@code venda.id_caixa} (2026-08-19, DANFE) — identifica qual caixa processou a
             *  venda, informação de auditoria comum em DANFE. {@code null} numa venda sem caixa
             *  vinculado (não deveria acontecer no fluxo normal do PDV, mas o campo é nullable). */
            Integer numeroCaixa,
            String nomeOperador,
            List<ItemComprovanteVenda> itens,
            BigDecimal subtotal,
            BigDecimal descontos,
            BigDecimal acrescimos,
            BigDecimal totalAPagar,
            List<PagamentoComprovanteVenda> pagamentos,
            List<ParcelaComprovanteVenda> parcelasCrediario,
            DadosFiscaisComprovante dadosFiscais,
            /** Cabeçalho fiscal completo da empresa (2026-08-19, DANFE) — {@code null} quando a
             *  venda não é fiscal ({@code dadosFiscais == null}); a papeleta comum não precisa
             *  disso, só o DANFCE. */
            EmpresaComprovante empresaFiscal,
            /** {@code venda.cancelada} (2026-08-19) — a reimpressão precisa avisar "VENDA
             *  CANCELADA" logo no início do cupom; sem isso, uma papeleta reimpressa de uma venda
             *  já cancelada saía idêntica à de uma venda válida. Note que uma venda cancelada com
             *  NFC-e nunca chega como DANFCE aqui: {@link #buscarDadosFiscais} só considera
             *  documento {@code AUTORIZADO}/{@code CONTINGENCIA}, e o cancelamento marca o
             *  documento como {@code CANCELADO} — a papeleta comum é sempre o formato certo pra
             *  esse aviso. */
            boolean cancelada) {
    }

    /**
     * Cabeçalho de empresa que o DANFE exige (razão social/CNPJ/IE/endereço/telefone) — dado
     * diferente do {@code nomeEmpresa}/{@code codigoEmpresa} que a papeleta comum já usa (esses
     * dois continuam existindo pra não quebrar o cabeçalho não-fiscal). {@code enderecoCompleto}
     * já vem formatado pronto pra imprimir: {@code logradouro, numero - bairro - cidade - UF}.
     */
    public record EmpresaComprovante(
            String razaoSocial, String cnpj, String inscricaoEstadual, String enderecoCompleto, String telefone) {
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
            BigDecimal valorTotalTributos, int numero, int serie,
            /** CPF/CNPJ (só dígitos) que de fato foi para o {@code <dest>} do XML — extraído do
             *  XML assinado, nunca do cadastro do cliente (podem divergir: o operador respondeu
             *  "não" à pergunta de incluir CPF). {@code null} quando a NFC-e saiu para consumidor
             *  não identificado — DANFE §4.3 pede "CONSUMIDOR NÃO IDENTIFICADO" nesse caso. */
            String documentoConsumidor,
            /** Reforma tributária (LC 214/2025), somados de {@code documento_fiscal} — a mesma
             *  base/valor que o motor calculou e o XML declara por item, aqui já agregado pela
             *  nota inteira (2026-08-19, DANFE). */
            BigDecimal baseIbsCbs, BigDecimal valorIbsUf, BigDecimal valorIbsMun, BigDecimal valorCbs,
            /** Detalhamento federal/estadual/municipal da Lei 12.741 (2026-08-19) — os 3 somam
             *  {@code valorTotalTributos}; o DANFE mostra cada parcela, não só o total. */
            BigDecimal valorTribFederal, BigDecimal valorTribEstadual, BigDecimal valorTribMunicipal) {
    }
}
