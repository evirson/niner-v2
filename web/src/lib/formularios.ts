import type { KeyboardEvent } from 'react'

/**
 * Intercepta Enter num campo de texto do formulário para pedir confirmação em vez de submeter
 * direto (ex.: Enter no CEP salvava a tela sem querer). Enter em `<select>`/botão/checkbox
 * continua com o comportamento nativo do navegador — só campo de texto passa pela confirmação.
 */
export function aoTeclarEnterNoFormulario(e: KeyboardEvent<HTMLFormElement>, aoConfirmar: () => void): void {
  if (e.key !== 'Enter') return
  const alvo = e.target as HTMLElement
  if (alvo.tagName !== 'INPUT') return
  const tipo = (alvo as HTMLInputElement).type
  if (tipo === 'checkbox' || tipo === 'radio' || tipo === 'button' || tipo === 'submit') return
  e.preventDefault()
  aoConfirmar()
}
