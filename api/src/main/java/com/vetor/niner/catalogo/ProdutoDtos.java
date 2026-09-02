package com.vetor.niner.catalogo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do cadastro de produto (docs/telas/produto.md). */
public final class ProdutoDtos {

    private ProdutoDtos() {
    }

    /**
     * Corpo de criação/atualização. {@code categorias} é a lista de {@code idCategoria} na
     * ordem escolhida pelo usuário — o servidor deriva o {@code indice} (0, 1, 2…) da posição
     * na lista, então o cliente não precisa (nem deve) enviar índices manualmente.
     * {@code idGrade} é obrigatório (400 se ausente) quando o tenant usa cor/grade em
     * {@code cfg_geral.cfg_usa_cor_grade} (verificado no serviço); ignorado (gravado como
     * {@code null}) quando o tenant não usa — mesmo princípio dos antigos
     * {@code nomeVarianteLinha}/{@code nomeVarianteColuna}.
     *
     * <p>{@code idPerfilFiscal} (2026-08-18, `docs/MODULOFISCAL.md` §6.2/DF3) — opcional aqui de
     * propósito (coluna sem {@code NOT NULL}, {@code produto.id_perfil_fiscal} em V035): quem
     * cobra o preenchimento antes de emitir é a Conformidade Fiscal, não este CRUD.
     */
    public record ProdutoRequest(
            @NotBlank @Size(max = 200) String descricao,
            @Size(max = 60) String marca,
            @Size(max = 60) String referencia,
            @NotNull @DecimalMin("0") BigDecimal precoCusto,
            @NotNull @DecimalMin("0") BigDecimal percentualVenda,
            @NotNull @DecimalMin("0") BigDecimal precoVenda,
            OffsetDateTime dataInicioOferta,
            OffsetDateTime dataFinalOferta,
            BigDecimal precoOferta,
            @Size(max = 20) String codigoNcm,
            BigDecimal pesoBruto,
            BigDecimal pesoLiquido,
            Long idGrade,
            Boolean ativo,
            List<Long> categorias,
            Long idPerfilFiscal,
            /**
             * ST <b>ja retido</b> por unidade -- {@code vBCSTRet} e {@code vICMSSTRet} do
             * {@code ICMSSN500} (pendencia 23, 2026-09-02).
             *
             * <p>* <b>Reserva</b>, nao fonte principal: quando o produto tem entrada por XML de
             * compra, o valor vem de la -- e o que o fornecedor de fato reteve. Estes campos valem
             * para a mercadoria que nunca entrou por XML, caso em que so o contador sabe o numero.
             *
             * <p>Sem eles (e sem entrada), a <b>NF-e 55</b> de um produto com CSOSN 500 e recusada
             * antes de reservar numero: a SEFAZ rejeita com {@code cStat 938}. A NFC-e 65 nao e
             * afetada -- a SEFAZ nao exige o bloco nela.
             *
             * <p>Nulo != zero: nulo e "ninguem informou" (a nota nao sai), zero e "o contador
             * disse que nao ha retencao neste produto" (a nota sai com 0,00).
             */
            BigDecimal stRetidoBaseUnitario,
            BigDecimal stRetidoValorUnitario,
            /** {@code pST}. Nulo faz o sistema derivar (valor / base x 100), que e a aliquota
             *  coerente com o que foi de fato retido. */
            BigDecimal stRetidoAliquota,
            /* --- serviço (V085, bloco S1 de docs/MODULOSERVICOS.md) --- */
            /**
             * {@code MERCADORIA} (padrão) ou {@code SERVICO}. Nulo = MERCADORIA, para o cliente
             * antigo que não manda o campo continuar funcionando igual (F12).
             * <p>⚠️ É <b>imutável</b> depois de criado — a trigger {@code tg_produto_tipo_item_imutavel}
             * (V085) recusa a troca, porque histórico de estoque, relatórios e notas já emitidas
             * descrevem o item como ele era.
             */
            String tipoItem,
            /** Só faz sentido em serviço; duração típica, para dimensionar o dia. */
            @Positive Integer duracaoMinutos,
            /**
             * Comissão <b>deste serviço</b>, que sobrepõe a do funcionário quando preenchida (DS5).
             * Nulo = usa a do funcionário, como hoje.
             */
            @DecimalMin("0") @DecimalMax("100") BigDecimal percComissaoServico,
            /** cTribNac — o código deste serviço na Lista Nacional (V099). */
            @Size(max = 6) String codigoTributacaoNacional,
            /** cTribMun — 3 dígitos, só quando a prefeitura exige. Quase sempre nulo. */
            @Size(max = 3) String codigoTributacaoMunicipal,
            /** ⚠️ Teto de 5% é a LC 116 art. 8º-A, não palpite; piso 0 cobre imunidade e ISS fixo. */
            @DecimalMin("0") @DecimalMax("5") BigDecimal aliquotaIss,
            Boolean issRetidoPadrao) {
    }

    public record CategoriaSelecionada(long idCategoria, String nomeCategoria, int indice) {
    }

    public record ProdutoResponse(
            long idProduto,
            String descricao,
            String marca,
            String referencia,
            BigDecimal precoCusto,
            BigDecimal percentualVenda,
            BigDecimal precoVenda,
            OffsetDateTime dataInicioOferta,
            OffsetDateTime dataFinalOferta,
            BigDecimal precoOferta,
            String codigoNcm,
            BigDecimal pesoBruto,
            BigDecimal pesoLiquido,
            Long idGrade,
            String descricaoGrade,
            String tipoItem,
            Integer duracaoMinutos,
            BigDecimal percComissaoServico,
            String codigoTributacaoNacional,
            String codigoTributacaoMunicipal,
            BigDecimal aliquotaIss,
            Boolean issRetidoPadrao,
            /** Vem por JOIN de cfg_servico_lc116 — a tela mostra, o lojista não escolhe. */
            String descricaoServicoLc116,
            String localIncidencia,
            boolean ativo,
            List<CategoriaSelecionada> categorias,
            List<ProdutoImagemDtos.ImagemResponse> imagens,
            Long idPerfilFiscal,
            String nomePerfilFiscal,
            /** ST ja retido por unidade (CSOSN 500) -- ver o request. */
            BigDecimal stRetidoBaseUnitario,
            BigDecimal stRetidoValorUnitario,
            BigDecimal stRetidoAliquota,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm,
            OffsetDateTime reajustadoEm) {
    }

    /** Listagem paginada por número de página, mesmo padrão de `cadastros.*` (2026-07-21). */
    public record PaginaProdutos(
            List<ProdutoResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Resultado do DELETE: {@code acao} é {@code "excluido"} ou {@code "inativado"}. */
    public record ExclusaoProdutoResponse(String acao, String motivo) {
    }
}
