package com.vetor.niner.configuracao.etiqueta;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTOs de Configuração de Etiqueta de Produtos (docs/telas/configuracao-etiqueta.md,
 * db/migration/V029__cfg_etiqueta.sql). {@code colunas}/{@code campos} são coleções filhas
 * completas — diferente de {@code ProdutoRequest.categorias} (que referencia uma linha já
 * existente por id), aqui cada item da lista É o dado inteiro da coluna/campo, sem catálogo
 * separado por trás.
 */
public final class EtiquetaConfigDtos {

    private EtiquetaConfigDtos() {
    }

    /** Os 4 campos possíveis numa etiqueta — cada um mapeia pra uma coluna já existente no
     * schema (ver comentário do tipo `campo_etiqueta` em V029). Marca/Referência/Cor/Tamanho
     * não são mais campos próprios (2026-08-05, pedido do dono do produto; cor/tamanho
     * substituíram variação de linha/coluna em 2026-08-08): o front concatena esses 4 dentro de
     * {@code DESCRICAO_PRODUTO} (pulando o que já
     * aparecer na descrição), então o back só precisa continuar expondo os dados brutos em
     * {@link ProdutoExemploResponse} — a composição do texto final é responsabilidade da tela
     * (hoje o editor, futuramente a tela de Emissão), não deste enum. */
    public enum CampoEtiqueta {
        NOME_EMPRESA, DESCRICAO_PRODUTO, PRECO_VENDA, SKU_BARRAS
    }

    /** Provisório — depende da tecnologia de impressão ainda não decidida (impressora térmica
     * dedicada vs. impressão via navegador), ver package-info. */
    public enum FonteEtiqueta {
        ARIAL, COURIER, TIMES_NEW_ROMAN
    }

    public enum AlinhamentoEtiquetaCampo {
        ESQUERDA, CENTRO, DIREITA
    }

    /** Posição (mm) de uma coluna de etiqueta no rolo físico — valor explícito, não calculado
     * por fórmula (rolos com espaçamento irregular existem na prática). */
    public record ColunaRequest(
            @NotNull Integer numeroColuna,
            @NotNull @DecimalMin("0") BigDecimal posicaoInicialMm) {
    }

    public record ColunaResponse(int numeroColuna, BigDecimal posicaoInicialMm) {
    }

    /**
     * Um campo posicionado na etiqueta. {@code posicaoXMm}/{@code posicaoYMm} são relativos ao
     * canto superior-esquerdo da PRÓPRIA etiqueta (não do rolo). {@code tamanhoFontePt}/
     * {@code negrito} só fazem sentido pros 3 campos de texto (mais o texto legível do código de
     * barras, se {@code exibirTextoLegivel}); {@code exibirTextoLegivel} só faz sentido pra
     * {@code SKU_BARRAS}. Não há `CHECK` cruzado no banco pra essas
     * combinações — é regra de formulário, validada de novo no servidor.
     */
    public record CampoRequest(
            @NotNull CampoEtiqueta campo,
            @NotNull @DecimalMin("0") BigDecimal posicaoXMm,
            @NotNull @DecimalMin("0") BigDecimal posicaoYMm,
            @DecimalMin("0") BigDecimal larguraMm,
            @DecimalMin("0") BigDecimal alturaMm,
            @NotNull FonteEtiqueta fonte,
            BigDecimal tamanhoFontePt,
            @NotNull Boolean negrito,
            @NotNull Boolean fundoPreto,
            @NotNull AlinhamentoEtiquetaCampo alinhamento,
            Boolean exibirTextoLegivel) {
    }

    public record CampoResponse(
            CampoEtiqueta campo,
            BigDecimal posicaoXMm,
            BigDecimal posicaoYMm,
            BigDecimal larguraMm,
            BigDecimal alturaMm,
            FonteEtiqueta fonte,
            BigDecimal tamanhoFontePt,
            boolean negrito,
            boolean fundoPreto,
            AlinhamentoEtiquetaCampo alinhamento,
            Boolean exibirTextoLegivel) {
    }

    /**
     * Corpo de criação/atualização. As 4 bordas aceitam {@code null} (equivalem a 0, mesmo
     * `DEFAULT 0` do schema). {@code colunas} deve ter exatamente {@code numeroColunas} itens,
     * com {@code numeroColuna} 1..N sem repetir — validado no serviço, não só no `CHECK` do
     * banco (mensagem amigável em vez de 500).
     */
    public record EtiquetaConfigRequest(
            @NotBlank @Size(max = 80) String nome,
            @NotNull @DecimalMin("0.01") BigDecimal larguraRoloMm,
            @NotNull Integer numeroColunas,
            @NotNull @DecimalMin("0.01") BigDecimal larguraEtiquetaMm,
            @NotNull @DecimalMin("0.01") BigDecimal alturaEtiquetaMm,
            /** Espaco em branco ENTRE fileiras do rolo (V056). Nulo = 0 = fileiras coladas. */
            @DecimalMin("0") @DecimalMax("100") BigDecimal espacamentoVerticalMm,
            BigDecimal bordaSuperiorMm,
            BigDecimal bordaInferiorMm,
            BigDecimal bordaEsquerdaMm,
            BigDecimal bordaDireitaMm,
            Boolean ativo,
            @NotNull @Valid List<ColunaRequest> colunas,
            @NotNull @Valid List<CampoRequest> campos) {
    }

    public record EtiquetaConfigResponse(
            long idConfigEtiqueta,
            String nome,
            BigDecimal larguraRoloMm,
            int numeroColunas,
            BigDecimal larguraEtiquetaMm,
            BigDecimal alturaEtiquetaMm,
            BigDecimal espacamentoVerticalMm,
            BigDecimal bordaSuperiorMm,
            BigDecimal bordaInferiorMm,
            BigDecimal bordaEsquerdaMm,
            BigDecimal bordaDireitaMm,
            boolean ativo,
            List<ColunaResponse> colunas,
            List<CampoResponse> campos,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página, mesmo padrão do resto do domínio. */
    public record PaginaEtiquetasConfig(
            List<EtiquetaConfigResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Resultado do DELETE: {@code acao} é {@code "excluido"} ou {@code "inativado"}. */
    public record ExclusaoEtiquetaConfigResponse(String acao, String motivo) {
    }

    /**
     * Produto de exemplo pra pré-visualizar a etiqueta com dado real (2026-08-04, pedido do dono
     * do produto). Endpoint/DTO próprios — não reaproveita {@code VariacaoEncontrada} do Kardex
     * porque falta referência/preço, que a prévia da etiqueta precisa.
     */
    public record ProdutoExemploResponse(
            long idVariacao,
            String sku,
            String descricao,
            String marca,
            String referencia,
            BigDecimal precoVenda,
            String variacaoCor,
            String variacaoTamanho) {
    }
}
