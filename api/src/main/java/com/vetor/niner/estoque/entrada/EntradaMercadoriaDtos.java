package com.vetor.niner.estoque.entrada;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs de Entrada de Produtos por Compra (docs/telas/entrada-mercadoria.md). */
public final class EntradaMercadoriaDtos {

    private EntradaMercadoriaDtos() {
    }

    public record ItemEntradaRequest(
            @NotNull Long idVariacao,
            @NotNull @DecimalMin(value = "0.001") BigDecimal qtd,
            @NotNull @DecimalMin(value = "0") BigDecimal precoCusto) {
    }

    /** Uma parcela/duplicata a gerar em `contas_pagar` — sempre opcional (Manual/Planilha só
     *  gera se o operador preencher; XML preenche a partir de `cobr/dup`, ver Fase 3). */
    public record ContaPagarEntradaRequest(
            String numeroDuplicata,
            @NotNull LocalDate dataVencimento,
            @NotNull @DecimalMin(value = "0.01") BigDecimal valor) {
    }

    /**
     * Corpo de confirmação — comum aos 3 fluxos (Manual/XML/Planilha). {@code chaveNfe}/
     * {@code serieNota}/{@code xmlBruto} só vêm preenchidos no fluxo XML (idempotência P2 e
     * auditoria P3, ver {@code entrada_xml}). {@code valorRateio} é o total de frete/IPI/
     * ICMS-ST da nota a distribuir entre os itens — só tem efeito se
     * {@code cfg_geral.cfg_rateia_frete_entrada} estiver ligada; ignorado quando ausente/zero
     * ou quando a flag está desligada.
     */
    public record EfetivarEntradaRequest(
            @NotNull Long idFornecedor,
            /** Empresa que recebe a mercadoria (2026-08-12) — opcional; ausente cai no `eid`
             *  da sessão (comportamento de sempre). Quando informado, o serviço valida que o
             *  usuário pode operar essa empresa (ADMIN: qualquer uma do tenant; OPERADOR: só
             *  as ligadas a ele em `usuario_empresa`). */
            Long idEmpresa,
            Integer notaFiscal,
            /** Data em que a mercadoria foi de fato recebida (2026-08-12, aba "Dados Gerais" do
             *  fluxo Planilha) — opcional; ausente grava `now()` (comportamento de sempre). */
            LocalDate dataMovimento,
            String chaveNfe,
            Integer serieNota,
            String xmlBruto,
            BigDecimal valorRateio,
            @NotEmpty List<@Valid ItemEntradaRequest> itens,
            List<@Valid ContaPagarEntradaRequest> contasPagar) {
    }

    public record ItemEntradaResponse(
            long idVariacao,
            String sku,
            String descricaoProduto,
            String variacaoCor,
            String variacaoTamanho,
            BigDecimal qtd,
            BigDecimal precoCusto,
            BigDecimal valorTotal) {
    }

    public record EntradaEfetivadaResponse(
            long idMovimento,
            long idEmpresa,
            long idFornecedor,
            String nomeFornecedor,
            Integer notaFiscal,
            OffsetDateTime dataMovimento,
            BigDecimal valorTotal,
            List<ItemEntradaResponse> itens) {
    }

    /** Linha da listagem (`GET /api/v1/estoque/entradas`) — "Origem" reflete a coluna
     *  {@code produto_movimento_detalhe.origem} (uma entrada inteira sempre usa o mesmo valor
     *  em todas as suas linhas, gravado uma vez em {@link EntradaMercadoriaService#efetivar}). */
    public record EntradaResumoResponse(
            long idMovimento,
            OffsetDateTime dataMovimento,
            Long idFornecedor,
            String nomeFornecedor,
            Integer notaFiscal,
            int qtdItens,
            BigDecimal valorTotal,
            String origem) {
    }

    public record PaginaEntradas(
            List<EntradaResumoResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    public record EntradaDetalheResponse(
            long idMovimento,
            long idEmpresa,
            Long idFornecedor,
            String nomeFornecedor,
            Integer notaFiscal,
            String chaveNfe,
            OffsetDateTime dataMovimento,
            BigDecimal valorTotal,
            List<ItemEntradaDetalheResponse> itens) {
    }

    /** Item do detalhe — {@code idMovimentoDetalhe} identifica a linha pra edição
     *  (`PUT .../itens/{idMovimentoDetalhe}`). */
    public record ItemEntradaDetalheResponse(
            long idMovimentoDetalhe,
            long idVariacao,
            String sku,
            String descricaoProduto,
            String variacaoCor,
            String variacaoTamanho,
            BigDecimal qtd,
            BigDecimal precoCusto,
            BigDecimal valorAcrescimo,
            BigDecimal valorTotal) {
    }

    /** Correção pós-confirmação (2026-08-11, decisão do dono do produto: edição direta, sem
     *  tabela de histórico por ora — ver pendência registrada na spec). A trigger de estoque já
     *  trata UPDATE corretamente (desfaz o delta antigo, aplica o novo). */
    public record AtualizarItemEntradaRequest(
            @NotNull @DecimalMin(value = "0.001") BigDecimal qtd,
            @NotNull @DecimalMin(value = "0") BigDecimal precoCusto) {
    }

    /**
     * Uma linha da planilha já processada (fluxo Planilha, 2026-08-12) — preview, nada
     * persistido no ledger. {@code resolvido=true} já tem {@code idVariacao} pronto pra entrar
     * em {@link ItemEntradaRequest} (a variação pode ter sido CRIADA agora, se produto+cor+
     * tamanho bateram com confiança — decisão registrada na spec, mesmo princípio já usado em
     * Emissão de Etiqueta/fluxo Individual). {@code resolvido=false} traz {@code
     * idProdutoEncontrado}/{@code idGradeEncontrada} quando o produto foi achado mas faltou
     * cor/tamanho (a tela pode oferecer os selects direto na linha), ou nenhum dos dois quando
     * nem o produto foi achado (a tela oferece pesquisar/cadastrar).
     */
    public record ItemPlanilhaPreviewResponse(
            int numeroLinha,
            String nomeProduto,
            String marca,
            String referencia,
            String cor,
            String tamanho,
            String codigoBarrasFabricante,
            BigDecimal qtd,
            BigDecimal custoUnitario,
            boolean resolvido,
            Long idVariacao,
            String sku,
            String descricaoProduto,
            String variacaoCor,
            String variacaoTamanho,
            Long idProdutoEncontrado,
            Long idGradeEncontrada,
            String motivoPendencia) {
    }
}
