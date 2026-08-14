/** Tema claro/escuro do ERP (2026-08-23).
 *
 *  A paleta dos dois temas já existia em `styles.css` desde sempre — `:root` é a clara, a media
 *  query `prefers-color-scheme: dark` é a escura, e os seletores `:root[data-theme='light']` /
 *  `[data-theme='dark']` permitem forçar um dos dois. O que faltava era **alguém escrever o
 *  atributo**: como ninguém escrevia, o ERP seguia o tema do sistema operacional e, com o Windows
 *  em escuro, todo mundo via só o escuro. O tema claro já rodava em produção mesmo assim, na
 *  geração de PDF dos relatórios (`lib/*Captura.ts` força `data-theme='light'` no clone antes do
 *  html2canvas) — ou seja, a paleta clara é conhecida e aprovada, não é código novo não testado.
 *
 *  `'auto'` **não** escreve o atributo, de propósito: é a media query decidindo, que é o
 *  comportamento histórico. Por isso é o padrão de quem nunca mexeu no seletor. */
export type Tema = 'claro' | 'escuro' | 'auto'

export const CHAVE_TEMA = 'niner_tema'

export function temaSalvo(): Tema {
  const valor = localStorage.getItem(CHAVE_TEMA)
  return valor === 'claro' || valor === 'escuro' ? valor : 'auto'
}

/** Escreve (ou remove) `data-theme` no `<html>`. Mesma função usada pelo boot em `index.html` —
 *  ver o comentário lá sobre por que aquele trecho é duplicado em JS puro. */
export function aplicarTema(tema: Tema) {
  const raiz = document.documentElement
  if (tema === 'auto') raiz.removeAttribute('data-theme')
  else raiz.setAttribute('data-theme', tema === 'claro' ? 'light' : 'dark')
}

export function salvarTema(tema: Tema) {
  if (tema === 'auto') localStorage.removeItem(CHAVE_TEMA)
  else localStorage.setItem(CHAVE_TEMA, tema)
  aplicarTema(tema)
}

/** Qual tema está de fato na tela agora — resolve o `'auto'` contra a media query. Usado só para
 *  desenhar o ícone certo (sol/lua) quando a preferência é automática. */
export function temaEfetivo(tema: Tema): 'claro' | 'escuro' {
  if (tema !== 'auto') return tema
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'escuro' : 'claro'
}
