import type { KeyboardEvent } from 'react'

/** Mesmo critério de navegação do Tab, mas sem botões — Enter nunca deve "clicar" um botão de
 *  submit sem querer (é assim que o último campo de um formulário consegue cair no fallback de
 *  confirmação em vez de simplesmente focar o botão Salvar, que viria em seguida na ordem do
 *  DOM). */
const SELETOR_CAMPO_NAVEGAVEL =
  'input:not([type=checkbox]):not([type=radio]):not([type=button]):not([type=submit]):not([type=file]):not(:disabled), ' +
  'select:not(:disabled), textarea:not(:disabled)'

/** Acha o próximo campo navegável depois de `atual`, dentro de `escopo`, e foca — devolve
 *  `false` quando `atual` já é o último (nada pra focar). */
function focarProximoCampo(atual: HTMLElement, escopo: ParentNode): boolean {
  const campos = Array.from(escopo.querySelectorAll<HTMLElement>(SELETOR_CAMPO_NAVEGAVEL))
  const indice = campos.indexOf(atual)
  const proximo = indice >= 0 ? campos[indice + 1] : undefined
  if (!proximo) return false
  proximo.focus()
  return true
}

/** `<input>` de texto (exclui checkbox/radio/button/submit/file) e `<select>` disparam a
 *  navegação — `<textarea>` fica de fora de propósito (Enter ali quebra linha, não muda de
 *  campo, 2026-08-04). */
function ehOrigemValida(alvo: HTMLElement): boolean {
  if (alvo.tagName === 'SELECT') return true
  if (alvo.tagName !== 'INPUT') return false
  const tipo = (alvo as HTMLInputElement).type
  return tipo !== 'checkbox' && tipo !== 'radio' && tipo !== 'button' && tipo !== 'submit' && tipo !== 'file'
}

/**
 * Enter num campo do formulário navega pro próximo campo — igual Tab (2026-08-04, pedido do
 * dono do produto: quer poder usar Enter pra mudar de campo, não só Tab; combos/`<select>`
 * entraram no mesmo dia, depois de reportar que Enter não saía deles). Só quando `atual` já é o
 * ÚLTIMO campo (não sobra mais nada pra focar) é que roda `aoConfirmar` — nas telas de cadastro
 * isso abre o popup de confirmação em vez de submeter direto (comportamento original de
 * 2026-07-23: Enter no CEP salvava a tela sem querer). `<textarea>`/botão/checkbox continuam
 * nativos.
 */
export function aoTeclarEnterNoFormulario(e: KeyboardEvent<HTMLFormElement>, aoConfirmar?: () => void): void {
  if (e.key !== 'Enter') return
  const alvo = e.target as HTMLElement
  if (!ehOrigemValida(alvo)) return
  e.preventDefault()
  if (!focarProximoCampo(alvo, e.currentTarget)) aoConfirmar?.()
}

/**
 * Fora de `<form>` (PDV, filtros de lista, telas financeiras sem o padrão de cadastro, popups de
 * busca) não existe um `onKeyDown` por tela pra interceptar Enter — esta função liga UM listener
 * global (chamado uma vez em `main.tsx`) que faz o mesmo "Enter navega pro próximo campo" pra
 * qualquer `<input>`/`<select>` solto pela aplicação.
 *
 * Regras (2026-08-04):
 * - Só age se `!e.defaultPrevented` — todo campo que já tem Enter com propósito próprio (leitor
 *   de código de barras do PDV/Contagem/Transferência/Devolução, busca por nº de venda) já
 *   chama `preventDefault()` no próprio handler, então este listener nunca os atropela.
 * - Ignora campos dentro de `<form>` — esses formulários cuidam do próprio Enter via
 *   `aoTeclarEnterNoFormulario` (ou, no caso do Login, o submit nativo é o comportamento
 *   esperado).
 * - Escopo da busca pelo "próximo campo" é o container mais próximo (`.modal`, senão `.card`,
 *   senão `.lista-tela`) — nunca o documento inteiro, pra não vazar o foco pra trás de um popup
 *   aberto.
 */
export function iniciarNavegacaoGlobalPorEnter(): () => void {
  const aoTeclar = (e: globalThis.KeyboardEvent) => {
    if (e.key !== 'Enter' || e.defaultPrevented) return
    const alvo = e.target as HTMLElement
    if (!ehOrigemValida(alvo)) return
    if (alvo.closest('form')) return
    const escopo = alvo.closest('.modal') ?? alvo.closest('.card') ?? alvo.closest('.lista-tela') ?? document.body
    if (focarProximoCampo(alvo, escopo)) e.preventDefault()
  }
  document.addEventListener('keydown', aoTeclar)
  return () => document.removeEventListener('keydown', aoTeclar)
}
