package com.vetor.niner.plataforma.acesso;

/**
 * Como terminou a tentativa de entrar no ERP.
 *
 * <p>⚠️ <b>{@link #CREDENCIAL_INVALIDA} não distingue</b> e-mail inexistente, senha errada e conta
 * inativa — porque <b>o login não distingue</b>, de propósito: qualquer diferença ali vira oráculo
 * para quem está adivinhando e-mails. O log <b>não pode ser mais específico que a autenticação</b>,
 * senão recria o mesmo oráculo pela porta dos fundos, para quem tiver acesso ao backoffice.
 *
 * <p>⭐ Este enum existe para que o motivo <b>não seja deduzido da mensagem de erro</b>. Classificar
 * por texto é a família da constante literal que já custou um {@code cStat 253} neste projeto: no
 * dia em que alguém melhorar a frase, o log passaria a classificar errado — e em silêncio.
 */
public enum ResultadoAcesso {

    SUCESSO,

    /** Senha errada, e-mail inexistente ou conta inativa — indistinguíveis, ver o javadoc. */
    CREDENCIAL_INVALIDA,

    /** Fora da janela de horário de acesso do usuário (V052). */
    FORA_DO_HORARIO,

    /** Autenticou, mas não há empresa vinculada — o administrador precisa vincular. */
    SEM_EMPRESA,

    /** Escolheu uma empresa que não está na lista de acesso dele. */
    EMPRESA_INVALIDA,

    /** Segunda etapa (V079): código de 4 dígitos errado, expirado ou já usado. */
    CODIGO_2FA_INVALIDO
}
