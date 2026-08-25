package com.vetor.niner.canais;

/**
 * Espelha o enum {@code tipo_canal} do banco (V013). Valor novo aqui exige {@code ALTER TYPE} lá —
 * e vice-versa; {@code CanalTipoEmSincroniaTest} reprova o build se as duas listas divergirem.
 */
public enum TipoCanal {

    MERCADO_LIVRE("Mercado Livre"),
    SHOPEE("Shopee"),
    AMAZON("Amazon"),
    ECOMMERCE("E-commerce próprio");

    private final String rotulo;

    TipoCanal(String rotulo) {
        this.rotulo = rotulo;
    }

    /** Nome como o lojista o vê na tela. */
    public String rotulo() {
        return rotulo;
    }
}
