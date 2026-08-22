/**
 * Impressão de documento A4 que **pagina** (auditoria 2026-08-21, item 4).
 *
 * ⚠️ Chamar `window.print()` direto **não paginava**: os três documentos A4 do produto — orçamento,
 * DANFE de devolução e guia de transferência — imprimiam a primeira página e descartavam o resto
 * **sem aviso nenhum**. Um orçamento de ~30 itens saía sem total e sem assinatura, e nada na tela
 * nem em teste automatizado mostrava isso — só o papel.
 *
 * Duas regras do projeto escondem esse trabalho em silêncio, e as duas precisam ser vencidas
 * durante a impressão (ver o bloco `.documento-a4-imprimir` em `styles.css`):
 *
 *   1. `html, body, #root { height:100%; overflow:hidden }` — o shell de altura travada do ERP.
 *      Com `overflow: hidden` o navegador **corta** o que passa da primeira página em vez de
 *      paginar;
 *   2. `body * { visibility: hidden }` — o isolamento global de impressão. Esconde o pixel mas
 *      **mantém o espaço** ocupado.
 *
 * ⚠️ A classe entra no `<html>` **e** no `<body>`. Prendê-la a um só dos dois apaga o `#root` de
 * toda impressão do produto — foi exatamente o que aconteceu ao consertar a etiqueta.
 *
 * O `afterprint` é o que devolve a tela ao normal. Há um `setTimeout` de segurança porque alguns
 * navegadores não disparam `afterprint` quando o usuário cancela o diálogo — sem ele, a tela
 * ficaria com o resto do app escondido até recarregar.
 */
export function imprimirDocumentoA4(): void {
  const html = document.documentElement
  const body = document.body

  const limpar = () => {
    html.classList.remove('imprimindo-documento')
    body.classList.remove('imprimindo-documento')
    window.removeEventListener('afterprint', limpar)
  }

  html.classList.add('imprimindo-documento')
  body.classList.add('imprimindo-documento')
  window.addEventListener('afterprint', limpar)

  // Rede de segurança: `afterprint` não é garantido em todo navegador/cancelamento.
  window.setTimeout(limpar, 60_000)

  // Um tick antes de imprimir: dá ao navegador a chance de refazer o layout com as classes já
  // aplicadas. Sem isso, o Chrome pode montar as páginas com a altura travada ainda valendo.
  window.setTimeout(() => window.print(), 60)
}
