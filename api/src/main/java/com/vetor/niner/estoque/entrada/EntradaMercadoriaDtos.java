package com.vetor.niner.estoque.entrada;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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
            @NotNull @DecimalMin(value = "0") BigDecimal precoCusto,
            /** Preço de venda informado pelo operador nesta entrada (2026-08-15, fluxo
             *  Individual com grade — custo + % de venda do produto sugerem o valor, editável
             *  antes de lançar). Quando presente, vira o novo {@code produto.preco_venda} na
             *  confirmação, **independente** de {@code cfg_reajusta_preco_entrada} (o operador
             *  já decidiu o preço na hora de lançar). Ausente preserva o comportamento de
             *  sempre: snapshot do detalhe = preço atual do produto, só reajusta automático se
             *  a flag estiver ligada. */
            BigDecimal precoVenda,
            /** Código do produto no fornecedor (`cProd` do XML, 2026-08-18) — quando presente,
             *  a confirmação grava/atualiza {@code produto_fornecedor} (aprendizado: a próxima
             *  nota do mesmo fornecedor com este código já resolve sozinha, sem depender de
             *  EAN nem de heurística de texto). Só o fluxo XML preenche; Manual/Planilha
             *  deixam ausente. */
            String codigoFornecedor,
            /** NCM do item no XML (2026-08-20) — quando presente e diferente do
             *  {@code produto.codigo_ncm} já cadastrado, a confirmação SUBSTITUI (o NCM do XML
             *  sempre vale, pedido do dono do produto). Só o fluxo XML preenche; já validado
             *  contra {@code cfg_produto_ncm} no preview (nunca chega aqui um código que não
             *  exista na referência). */
            String ncm) {
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
            String origem,
            /** Cancelamento de Entrada (2026-08-19) — grid principal mostra a linha marcada,
             *  sem esconder (mesmo princípio de `venda.cancelada`); não pode cancelar de novo. */
            boolean cancelada) {
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
            List<ItemEntradaDetalheResponse> itens,
            boolean cancelada,
            OffsetDateTime dataCancelamento,
            String motivoCancelamento) {
    }

    /** Corpo do `POST /{id}/cancelar` — mesmo contrato de
     *  {@code CancelamentoVendaDtos.CancelarVendaRequest}/{@code CancelarDevolucaoRequest}. */
    public record CancelarEntradaRequest(@NotBlank String motivo) {
    }

    public record CancelamentoEntradaEfetivadoResponse(long idMovimento, OffsetDateTime dataCancelamento) {
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
            String motivoPendencia,
            /** `cProd` do XML (2026-08-18, fluxo XML — ausente/`null` no fluxo Planilha) —
             *  segue junto até a confirmação pra alimentar o aprendizado de
             *  {@code produto_fornecedor} (ver {@link ItemEntradaRequest#codigoFornecedor}),
             *  mesmo quando a linha já resolveu sozinha por EAN. Cor/tamanho aqui são só
             *  PALPITE pra ajudar o operador a escolher mais rápido — o XML nunca cadastra cor
             *  ou tamanho novo sozinho, sempre exige confirmação manual (diferente da
             *  Planilha, que o dono do produto pediu pra cadastrar automático). */
            String codigoFornecedor,
            /** NCM do item (2026-08-20, fluxo XML — ausente/`null` no fluxo Planilha, que não
             *  tem coluna de NCM). Em linha resolvida, JÁ VALIDADO contra {@code
             *  cfg_produto_ncm} (vai virar um UPDATE em {@code produto.codigo_ncm} na
             *  confirmação se vier diferente do já cadastrado — ver {@link ItemEntradaRequest#ncm}
             *  — um código desconhecido quebraria a confirmação com erro de FK, por isso
             *  valida antes). Em pendência (produto não encontrado), vem CRU/sem validar — só
             *  pré-preenche um campo de formulário, não grava nada; validar aqui faria o campo
             *  vir vazio sempre que a base local de NCM estiver incompleta (o comum — a base
             *  oficial da Receita tem milhares de códigos). */
            String ncm) {
    }

    /**
     * Resultado da pesquisa de produto do fluxo Individual (2026-08-15) — busca por
     * nome/marca/referência, nível produto (não variação): não traz cor/tamanho nem estoque,
     * só o necessário pra escolher o produto e, na sequência, montar a grade de tamanhos +
     * custo/% de venda/preço de venda. {@code idGrade} nulo ⇒ produto sem grade, a tela pede só
     * uma quantidade em vez do grid de tamanhos.
     */
    public record ProdutoOpcaoEntradaResponse(
            long idProduto, String descricao, String marca, String referencia,
            Long idGrade, BigDecimal precoCusto, BigDecimal percentualVenda) {
    }

    /** Fornecedor casado pelo CNPJ do emitente do XML (2026-08-18, Fase 3) —
     *  {@code idFornecedor} nulo ⇒ não achou no cadastro; os demais campos vêm do próprio XML
     *  (`emit`), prontos pra pré-preencher o cadastro rápido de fornecedor se o operador optar
     *  por criar um novo em vez de escolher um existente. */
    public record FornecedorXmlPreviewResponse(
            Long idFornecedor,
            String cnpj,
            String razaoSocial,
            String nomeFantasia,
            String inscricaoEstadual,
            String endereco,
            String numero,
            String bairro,
            String cidade,
            String estado,
            String cep,
            String telefone) {
    }

    /** Uma duplicata (`cobr/dup` do XML) — mesmo shape de {@link ContaPagarEntradaRequest}, só
     *  que ainda em modo de conferência (a tela pode deixar o operador editar antes de
     *  confirmar). */
    public record DuplicataXmlPreviewResponse(String numeroDuplicata, LocalDate dataVencimento, BigDecimal valor) {
    }

    /**
     * Pré-entrada do fluxo XML (2026-08-18, Fase 3, docs/telas/entrada-mercadoria.md) — parse
     * do arquivo + tentativa de casar cada item, sem gravar nada (mesmo espírito do preview da
     * Planilha). {@code xmlBruto} volta pro cliente pra ser reenviado, sem alteração, no
     * {@code POST /api/v1/estoque/entradas} de confirmação (auditoria P3, `entrada_xml`).
     * {@code chaveJaImportada} avisa a idempotência (P2) antes mesmo de tentar confirmar — a
     * confirmação valida de novo (defesa em profundidade), mas a tela pode bloquear cedo.
     */
    public record EntradaXmlPreviewResponse(
            String chaveNfe,
            Integer serieNota,
            Integer notaFiscal,
            OffsetDateTime dataEmissao,
            boolean chaveJaImportada,
            FornecedorXmlPreviewResponse fornecedor,
            /** Empresa do tenant casada pelo CNPJ do destinatário (`dest/CNPJ`) do XML
             *  (2026-08-19) — `null` sem match (nenhuma empresa do tenant tem esse CNPJ
             *  cadastrado, ou o XML não trouxe `dest`); a tela cai no padrão de sempre (empresa
             *  da sessão) e o operador pode trocar manualmente no select, que continua ali. */
            Long idEmpresaEncontrada,
            BigDecimal valorTotalNota,
            List<DuplicataXmlPreviewResponse> duplicatas,
            List<ItemPlanilhaPreviewResponse> itens,
            String xmlBruto) {
    }
}
