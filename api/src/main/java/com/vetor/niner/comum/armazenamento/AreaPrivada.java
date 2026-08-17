package com.vetor.niner.comum.armazenamento;

/**
 * Que tipo de arquivo privado está sendo guardado (ADR-014). A área decide <b>três</b> coisas de
 * uma vez — bucket, prefixo do caminho e mutabilidade —, e é por isso que ela é um enum e não
 * três parâmetros soltos: quem chama não tem como escolher a combinação errada.
 *
 * <p><b>Para acrescentar uma área nova</b> (ex.: anexo de contrato), acrescente um valor aqui e
 * decida conscientemente o {@code imutavel}: {@code true} manda o objeto para o bucket com WORM,
 * onde <b>nem apagar nem sobrescrever é possível</b>, nem pela API nem pelo console.
 */
public enum AreaPrivada {

    /**
     * XML autorizado (`nfeProc`), evento e inutilização — bucket fiscal, WORM, guarda de 5 anos
     * (F6/DF21). Caminho relativo esperado: {@code {ano}/{mes}/{modelo}/{chave}.xml}.
     */
    FISCAL_XML("fiscal", true),

    /**
     * Foto do cliente — bucket privado comum. <b>Apagável de propósito:</b> é dado pessoal, e a
     * LGPD dá ao titular o direito de exclusão; guardar sob WORM criaria um passivo, não uma
     * proteção.
     */
    CLIENTE_FOTO("clientes", false);

    private final String prefixo;
    private final boolean imutavel;

    AreaPrivada(String prefixo, boolean imutavel) {
        this.prefixo = prefixo;
        this.imutavel = imutavel;
    }

    /** Segmento que vai depois do tenant no caminho do objeto. */
    public String prefixo() {
        return prefixo;
    }

    /** {@code true} = área WORM: gravar por cima e apagar são recusados. */
    public boolean imutavel() {
        return imutavel;
    }
}
