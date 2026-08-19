/**
 * Configuração da plataforma editável pelo backoffice (2026-08-19): SMTP, agenda de backup e
 * credencial do gateway de cobrança.
 *
 * <p><b>Três camadas, e a fronteira entre elas é de segurança, não de gosto:</b>
 * <ul>
 *   <li><b>nunca aqui</b> — senha do Postgres, segredo do JWT e a <i>chave mestra</i>
 *       ({@code niner.seguranca.chave-segredos}): é ela que cifra as colunas {@code *_cifrado},
 *       então guardá-la no mesmo banco faria um dump roubado valer tudo;</li>
 *   <li><b>aqui, cifrado</b> — segredo de terceiro que o admin precisa trocar sem deploy;</li>
 *   <li><b>aqui, em claro</b> — o que é operacional e não abre porta nenhuma sozinho.</li>
 * </ul>
 *
 * <p>Segredo gravado <b>nunca volta</b> pela API, nem para {@code SUPER_ADMIN}: a leitura devolve
 * apenas se está definido. Campo em branco na gravação significa "mantenha o atual" — sem isso,
 * salvar o formulário sem redigitar a senha a apagaria.
 */
package com.vetor.niner.plataforma.configuracao;
