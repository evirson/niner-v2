package com.vetor.niner.identidade.permissao;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara a ação de um método quando o verbo HTTP <b>não</b> traduz a intenção.
 *
 * <p><b>Por que isto existe.</b> A tradução padrão (POST → incluir) está errada em várias rotinas
 * deste ERP, e o erro seria grave:
 *
 * <table>
 *   <tr><th>Rotina</th><th>Verbo</th><th>Se ficasse "incluir"…</th></tr>
 *   <tr><td>Cancelar venda</td><td>POST</td><td>quem pode vender poderia cancelar</td></tr>
 *   <tr><td>Estornar recebimento</td><td>POST</td><td>quem recebe poderia estornar</td></tr>
 *   <tr><td>Reabrir caixa</td><td>POST</td><td>o operador reabriria o próprio fechamento</td></tr>
 *   <tr><td>Cancelar entrada</td><td>POST</td><td>o estoquista desfaria a própria compra</td></tr>
 * </table>
 *
 * <p>Regra combinada com o dono do produto (2026-08-27): <b>desfazer é excluir</b>. Assim dá para
 * conceder "incluir" ao caixa (vender) sem conceder "excluir" (cancelar) — que é exatamente a
 * separação que interessa a quem configura permissão.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Acao {

    PermissaoService.Acao value();
}
