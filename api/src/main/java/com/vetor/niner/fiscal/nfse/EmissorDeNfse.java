package com.vetor.niner.fiscal.nfse;

/**
 * O contrato com quem emite a NFS-e — hoje o Emissor Nacional, e é a única implementação real.
 *
 * <h2>Por que existe uma interface se só há um destino</h2>
 *
 * <p>É a DS7 do {@code docs/MODULOSERVICOS.md} §5.7, e o argumento não é "flexibilidade" genérica:
 * nada do que sustenta a decisão de emitir direto é certeza permanente. Se o Emissor Nacional se
 * mostrar imaturo, ou se a lacuna "aderiu mas não opera" doer em campo, <b>plugar um provedor é
 * uma classe</b>, não um projeto. E há precedente vivo do risco oposto: a Nuvem Fiscal anunciou em
 * 22/04/2026 a desativação do serviço em 31/07/2026 — quem tivesse apostado nela teria 90 dias
 * para reescrever a emissão fiscal de toda a base.
 *
 * <h2>⭐ E o motivo prático, que vale mais que o estratégico</h2>
 *
 * <p>{@link EmissorFalso} é o <b>padrão</b>, e o real só entra com
 * {@code niner.nfse.emissor=nacional}. Isso faz a suíte <b>exercitar</b> o caminho da NFS-e —
 * exatamente o contrário do que acontece hoje com o fiscal de mercadoria, que roda com
 * {@code emite_nfe = false} e por isso deixou dois defeitos reais passarem por 911 testes verdes
 * na devolução ao fornecedor. Caminho fiscal sem teste não é caminho testado; é caminho ausente.
 */
public interface EmissorDeNfse {

    /**
     * Transmite a DPS já assinada e empacotada.
     *
     * @param dps o que enviar e com qual certificado
     * @return o desfecho — autorizada, rejeitada, ou <b>indisponível</b>, que é diferente de
     *         rejeitada e não consome número
     */
    RespostaSefin emitir(EnvioDps dps);

    /**
     * Registra um evento (no v1, só o 101101 de cancelamento).
     *
     * <p>⚠️ A resposta traz o XML num campo de <b>nome diferente</b> do da emissão
     * ({@code eventoXmlGZipB64}) e a recusa vem num contêiner {@code "erro"} singular — o
     * {@link RespostaSefin} cobre os dois.
     */
    RespostaSefin registrarEvento(EnvioEvento evento);

    /**
     * Recupera a chave de uma DPS que pode ter virado nota do outro lado.
     *
     * <p>⛔ É o <b>único</b> caminho de recuperação: a chave de acesso <b>não</b> é derivável do
     * {@code Id} (leva o {@code nNFSe} do SEFIN e 9 dígitos aleatórios). Depois de um timeout,
     * reenviar cego devolve {@code E0014}; consultar aqui devolve a nota que já existe.
     *
     * @return a chave, ou {@code null} se a DPS ainda não gerou NFS-e
     */
    String consultarChavePorDps(String idDps, Credencial credencial);

    /**
     * Confere que certificado, mTLS e ambiente estão de pé.
     *
     * <p>⭐ A resposta <b>esperada</b> é 404 com {@code E2401} (chave inexistente): é justamente
     * ela que prova que a conexão autenticou e chegou à aplicação. Um teste que espera 200 nunca
     * passaria, e um que aceita qualquer resposta não prova nada.
     */
    RespostaSefin testarConexao(Credencial credencial);

    /** O certificado A1 do lojista, decifrado. Vive o mínimo possível e nunca vai para log (F7). */
    record Credencial(byte[] pkcs12, String senha, String impressaoDigital,
                      boolean ambienteProducao) {
    }

    record EnvioDps(String idDps, String xmlAssinado, Credencial credencial) {
    }

    record EnvioEvento(String chaveAcesso, String xmlAssinado, Credencial credencial) {
    }
}
