package com.vetor.niner.comum.armazenamento;

import java.util.List;

/**
 * Contrato do object storage <b>privado</b> (ADR-014) — XML fiscal e dado pessoal. Nada aqui é
 * público: não existe {@code urlPublica()}, e não é esquecimento. Quem precisar entregar um
 * arquivo destes ao navegador tem que fazê-lo <b>pela API</b>, autenticado, e a API é quem lê os
 * bytes daqui — nunca o navegador falando com o bucket.
 *
 * <p>É uma interface <b>separada</b> de {@link ArmazenamentoDeArquivos} (fotos de produto, GCS,
 * leitura pública) porque os dois contratos são incompatíveis: um exige URL estável e pública, o
 * outro exige o oposto, mais WORM.
 *
 * <p><b>Isolamento de tenant (P8).</b> O caminho completo é sempre
 * {@code tenants/{id_tenant}/{área}/{caminho relativo}}, e o prefixo do tenant é montado <b>aqui
 * dentro</b>, a partir do {@code TenantContext} — nunca vem por parâmetro. Na leitura e na
 * exclusão a chave é conferida contra esse mesmo prefixo <b>antes</b> de ir ao bucket: chave
 * adulterada, ou chave correta de outro tenant vinda do banco por um bug de query, é recusada em
 * vez de servida.
 */
public interface ArmazenamentoPrivado {

    /**
     * Grava e devolve a <b>chave completa</b> — é ela que vai para a coluna do banco (ex.:
     * {@code documento_fiscal.xml_objeto_bucket}), nunca uma URL.
     *
     * <p>Em área imutável ({@link AreaPrivada#imutavel()}), gravar por cima de uma chave que já
     * existe é <b>recusado</b> (F6: XML autorizado não se reescreve). O bucket recusaria de
     * qualquer forma; a checagem aqui é para o erro sair claro, e não como falha de S3.
     *
     * @param caminhoRelativo caminho <b>dentro</b> da área, sem barra inicial e sem {@code ..}
     */
    String gravar(AreaPrivada area, String caminhoRelativo, byte[] conteudo, String contentType);

    /** Lê pela chave completa devolvida por {@link #gravar}. Recusa chave de outro tenant (P8). */
    byte[] ler(AreaPrivada area, String chave);

    /** {@code true} se o objeto existe. Recusa chave de outro tenant (P8). */
    boolean existe(AreaPrivada area, String chave);

    /**
     * Apaga pela chave completa. <b>Recusado em área imutável</b> — e a credencial da API também
     * não tem permissão de apagar no bucket fiscal, então a proteção existe nas duas pontas.
     * Idempotente: objeto já ausente não é erro.
     */
    void apagar(AreaPrivada area, String chave);

    /**
     * Lista as chaves completas sob um prefixo <b>do tenant vigente</b> (ex.: {@code "2026/08/"}
     * na área fiscal para fechar o ZIP do contador). Prefixo vazio lista tudo o que é do tenant.
     */
    List<String> listar(AreaPrivada area, String prefixoRelativo);
}
