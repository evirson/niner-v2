package com.vetor.niner.canais;

import java.time.Instant;

/**
 * O que o adapter precisa para falar com o canal em nome de um lojista. Sai <b>decifrado</b> de
 * {@link CanalService}; a forma cifrada mora em {@code canal.credenciais} (ADR-005, AES-256-GCM
 * com a chave fora do banco — o mesmo {@code SegredoCifrador} que protege certificado e CSRT).
 *
 * <p>⚠️ <b>Nunca serializar isto.</b> Não vai para DTO, não vai para log, não volta pela API — a
 * mesma regra do CSRT. O que a tela mostra é o booleano "conectado" e o nome da conta no canal,
 * nunca o token. Por isso o {@code toString} é sobrescrito: um {@code log.debug(credenciais)}
 * distraído vazaria o token de acesso de um lojista para o arquivo de log.
 *
 * @param idCanal        canal do ERP a que estas credenciais pertencem
 * @param contaExterna   id da conta no canal (no ML, o {@code user_id} do vendedor) — é o que
 *                       amarra uma notificação recebida ao canal certo
 * @param accessToken    token de acesso corrente
 * @param refreshToken   token de renovação; {@code null} se o canal não usa refresh
 * @param expiraEm       quando {@code accessToken} deixa de valer. ⚠️ No Mercado Livre são
 *                       <b>6 horas</b> ({@code expires_in: 21600}), curto o bastante para que a
 *                       renovação automática seja obrigatória, não conveniência
 */
public record CredenciaisCanal(long idCanal, String contaExterna, String accessToken,
                               String refreshToken, Instant expiraEm) {

    /**
     * Já venceu (ou vence dentro da folga)? A folga evita o caso em que o token passa na
     * verificação e expira no meio da chamada seguinte.
     */
    public boolean expiradoEm(Instant agora, java.time.Duration folga) {
        return expiraEm == null || !agora.plus(folga).isBefore(expiraEm);
    }

    /** ⚠️ Nunca imprime o token. Ver a nota da classe. */
    @Override
    public String toString() {
        return "CredenciaisCanal[idCanal=%d, contaExterna=%s, accessToken=***, refreshToken=%s, expiraEm=%s]"
                .formatted(idCanal, contaExterna, refreshToken == null ? "ausente" : "***", expiraEm);
    }
}
