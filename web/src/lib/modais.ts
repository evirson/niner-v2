import type { MouseEvent } from 'react'

/**
 * Handler do `onClick` de um `.modal-overlay`: fecha **só** quando o clique foi no fundo, e
 * **não deixa o clique subir** para um overlay de fora.
 *
 * ⛔ **Por que existe** (2026-08-30): modal aberto de dentro de outro modal é renderizado
 * **dentro** do `.modal-overlay` do pai (nenhum deles usa portal). Cada overlay trazia
 * `onClick={aoFechar}` cru e `stopPropagation` só no `.modal` interno — então um clique no fundo
 * do filho disparava o `aoFechar` do filho **e continuava subindo** até o overlay do pai,
 * fechando os dois de uma vez.
 *
 * O caso caro é o PDV: numa venda de R$ 1.200 o operador aperta F5, digita o desconto gerencial,
 * lança R$ 400 em dinheiro e R$ 800 em crédito 6×, abre a pesquisa de cliente e desiste clicando
 * fora da caixinha — o jeito habitual de dispensar um popup. **Os dois fechavam**: o desconto, as
 * duas linhas de pagamento e o vendedor escolhido sumiam, e tudo era redigitado com o cliente
 * esperando. Na Entrada por Compra o mesmo clique descartava o cadastro rápido inteiro de um
 * produto (descrição, NCM, categorias, custo, pesos) por dispensar o popup de grade.
 *
 * ⭐ Fecha também um vazamento que ninguém tinha visto: `onClick={aoFechar}` no overlay dispara
 * para **qualquer** clique que borbulhe até ele, inclusive um clique dentro do `.modal` que
 * esqueça o `stopPropagation`. Com `e.target !== e.currentTarget` o overlay só reage ao que é
 * clique no fundo de verdade — a proteção deixa de depender de cada filho lembrar de se defender.
 *
 * Aceita `undefined` para o caso de overlay que não fecha por clique fora (popup de entrada
 * obrigatória): `onClick={fecharAoClicarNoFundo(primeiraVez ? undefined : aoFechar)}`.
 */
export function fecharAoClicarNoFundo(aoFechar?: (() => void) | undefined) {
  return (e: MouseEvent) => {
    if (e.target !== e.currentTarget) return
    e.stopPropagation()
    aoFechar?.()
  }
}
