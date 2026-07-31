import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'

const OPCOES_CAPTURA = {
  scale: 2,
  useCORS: true,
  // html2canvas sempre clona o <html> inteiro pra manter contexto (não só o elemento
  // passado) — ignora o resto do app (menu lateral, cabeçalho, popups) tanto por
  // performance quanto porque só o conteúdo do relatório interessa na captura.
  ignoreElements: (el: Element) => el.closest('.app-header, .app-nav, .modal-overlay') !== null,
} as const

/** Captura um elemento e desenha suas páginas no `doc` — sempre começando numa página nova
 *  (chamador decide quando chamar `doc.addPage()` antes). Recorta a imagem inteira em fatias do
 *  tamanho da página deslocando o Y a cada nova página, mesmo truque de qualquer exportação
 *  "print a screenshot to multi-page PDF". Devolve quantas páginas usou. */
async function desenharElementoPaginado(doc: jsPDF, elemento: HTMLElement, corFundo: string): Promise<number> {
  const canvas = await html2canvas(elemento, { ...OPCOES_CAPTURA, backgroundColor: corFundo })

  const larguraPagina = doc.internal.pageSize.getWidth()
  const alturaPagina = doc.internal.pageSize.getHeight()
  const larguraImagem = larguraPagina
  const alturaImagem = (canvas.height * larguraImagem) / canvas.width
  // JPEG, não PNG — o conteúdo é UI plana (sem foto), mas em `scale: 2` um PNG sem perdas de
  // uma tela inteira passa fácil de 30-40MB; JPEG a 92% cai pra poucos MB sem serrilhado
  // perceptível em texto/gráficos nesse tamanho.
  const imagem = canvas.toDataURL('image/jpeg', 0.92)

  const totalPaginas = Math.max(1, Math.ceil(alturaImagem / alturaPagina))
  for (let pagina = 0; pagina < totalPaginas; pagina++) {
    if (pagina > 0) doc.addPage()
    doc.addImage(imagem, 'JPEG', 0, -pagina * alturaPagina, larguraImagem, alturaImagem)
  }
  return totalPaginas
}

/**
 * Gera o PDF do Relatório de Vendas como uma **captura visual da própria tela** (KPIs,
 * composição, os 7 gráficos e a grid) — pedido explícito do dono do produto: "gere como está na
 * tela, com os gráficos". Diferente do padrão textual monoespaçado já usado no Fechamento de
 * Caixa/Comprovante de Crediário: aqui o conteúdo mais importante são os gráficos (SVG do
 * Recharts), que não dá pra reconstruir como tabela de texto sem perder a visualização.
 *
 * `topoEl` (KPIs + composição + gráficos) e `gridEl` (a grid de vendas/totalizador, com o
 * `<tfoot>` de totais já embutido no DOM) são capturados **separadamente** e a grid **sempre
 * começa numa página nova** (pedido explícito) — mesmo que o topo termine no meio de uma página.
 */
export async function gerarPdfCapturaRelatorioVendas(topoEl: HTMLElement, gridEl: HTMLElement, corFundo: string): Promise<void> {
  const doc = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'landscape' })

  await desenharElementoPaginado(doc, topoEl, corFundo)
  doc.addPage()
  await desenharElementoPaginado(doc, gridEl, corFundo)

  const totalPaginas = doc.getNumberOfPages()
  const larguraPagina = doc.internal.pageSize.getWidth()
  const alturaPagina = doc.internal.pageSize.getHeight()
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(9)
  for (let pagina = 1; pagina <= totalPaginas; pagina++) {
    doc.setPage(pagina)
    doc.setTextColor(120, 120, 120)
    doc.text(`Página ${pagina} de ${totalPaginas}`, larguraPagina - 10, alturaPagina - 6, { align: 'right' })
  }

  doc.save('relatorio-de-vendas.pdf')
}
