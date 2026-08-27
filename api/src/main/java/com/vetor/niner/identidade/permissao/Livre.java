package com.vetor.niner.identidade.permissao;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um método como <b>fora do RBAC</b>, mesmo dentro de um controller que tem {@link Tela}.
 *
 * <p><b>Por que precisou existir.</b> A trava por controller quebrou endpoints que estavam
 * deliberadamente abertos a qualquer papel — e o javadoc deles já dizia isso, em português, antes
 * de o RBAC existir:
 * <ul>
 *   <li>{@code /config-geral/emite-fiscal-apos-venda} e {@code /permite-qtd-decimal} — o PDV
 *       precisa saber disso para montar a venda, e Parâmetros do Sistema é tela de ADMIN. Sem esta
 *       marca, <b>o caixa não conseguiria vender</b>;</li>
 *   <li>{@code /fiscal/perfis/opcoes} — combo usado por outras telas;</li>
 *   <li>listagem de empresas — usada por várias telas para escolher a empresa da operação.</li>
 * </ul>
 *
 * <p>⚠️ É o mesmo princípio dos controllers sem {@code @Tela} (consulta auxiliar entre domínios),
 * só que no nível do método, porque aqui o resto do controller <b>é</b> restrito.
 *
 * <p>⛔ Use somente em <b>leitura</b>. Marcar uma operação de escrita como livre esvazia a
 * permissão da tela sem que nada na grade indique isso.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Livre {
}
