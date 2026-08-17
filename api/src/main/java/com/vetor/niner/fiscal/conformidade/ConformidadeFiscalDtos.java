package com.vetor.niner.fiscal.conformidade;

import java.util.List;

/** DTOs da Conformidade Fiscal (docs/telas/fiscal-conformidade.md, bloco B3) — painel de
 * diagnóstico somente-leitura, por empresa. */
public final class ConformidadeFiscalDtos {

    private ConformidadeFiscalDtos() {
    }

    /** As 4 categorias do painel, na ordem em que aparecem. */
    public enum CategoriaConformidade {
        EMPRESA, PRODUTOS, PAGAMENTOS, CLIENTES
    }

    public record CategoriaConformidadeResponse(
            CategoriaConformidade categoria, String rotulo, boolean bloqueia, long quantidadePendencias) {
    }

    /** {@code pronto} é falso quando qualquer categoria {@code bloqueia} tem pendência > 0 —
     *  categorias que só avisam (Clientes) nunca derrubam o veredito. */
    public record PainelConformidadeResponse(boolean pronto, String veredito, List<CategoriaConformidadeResponse> categorias) {
    }

    /** Uma linha do drill-down — o registro com problema, o que falta, e pra onde corrigir.
     *  {@code telaCorrecao} é {@code null} quando a tela de destino ainda não existe no produto
     *  (ex.: cadastro de Empresa, DF23-adjacente — a correção hoje é fora do sistema). */
    public record ItemPendenciaResponse(long idRegistro, String identificador, String problema, String telaCorrecao) {
    }

    public record PaginaPendencias(
            List<ItemPendenciaResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }
}
