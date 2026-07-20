/**
 * Convenção do projeto (2026-07-20): todo campo de texto livre é armazenado em
 * MAIÚSCULAS, independente do estado do teclado do usuário (Caps Lock etc.). Aplicar em
 * todo novo campo de texto do frontend — via {@link maiusculas} no `onChange` (feedback
 * imediato) e reforçado no backend como defesa em profundidade. Exceção: e-mail (caixa
 * preservada, convenção usual de endereço de e-mail).
 */
export function maiusculas(valor: string): string {
  return valor.toUpperCase()
}
