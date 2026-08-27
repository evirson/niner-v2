package com.vetor.niner.identidade.permissao;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Liga um controller (ou um método) à tela do ERP que ele serve, para o RBAC saber o que exigir.
 *
 * <p><b>Uma linha por controller.</b> O {@link PermissaoInterceptor} traduz o método HTTP em ação:
 * GET → acessar, POST → incluir, PUT/PATCH → alterar, DELETE → excluir. Quando o verbo não traduz
 * a intenção — e são vários casos aqui —, o método declara a sua com {@link Acao}.
 *
 * <p><b>⚠️ Controller SEM esta anotação não é bloqueado</b>, e isso é deliberado (decisão do dono
 * do produto, 2026-08-27). São as consultas auxiliares que uma tela faz sobre o domínio de outra:
 * o PDV busca cliente pela API de Clientes, a Entrada busca produto pela API de Produtos. Bloquear
 * por tela ali faria o caixa não conseguir <b>vender</b> por não ter a tela de Clientes — um
 * bloqueio que ninguém entenderia e que ninguém pediu.
 *
 * <p>O que essa escolha custa, dito claramente: quem tem acesso ao sistema consegue <b>ler</b>
 * dados auxiliares da própria conta mesmo sem a tela correspondente. O que ele não consegue é
 * <b>abrir a tela</b> nem <b>criar, alterar ou excluir</b> nada por ela.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Tela {

    /** Chave em {@code cfg_tela} (ex.: {@code "clientes"}, {@code "relatorio-dre"}). */
    String value();
}
