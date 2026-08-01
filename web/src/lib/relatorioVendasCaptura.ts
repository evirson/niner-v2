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

// Faixas reservadas em toda página pro cabeçalho/rodapé nativos do jsPDF (título/paginação e
// empresa/sistema) — desenhadas por cima da imagem capturada, nunca fazem parte do html2canvas.
const ALTURA_CABECALHO_MM = 16
const ALTURA_RODAPE_MM = 10
const CINZA_NEUTRO: [number, number, number] = [130, 130, 130]

function hexParaRgb(hex: string): [number, number, number] {
  const limpo = hex.replace('#', '').trim()
  const normalizado = limpo.length === 3 ? limpo.split('').map((c) => c + c).join('') : limpo
  const inteiro = parseInt(normalizado, 16)
  return [(inteiro >> 16) & 255, (inteiro >> 8) & 255, inteiro & 255]
}

/** Captura um elemento e desenha suas páginas no `doc` — sempre começando numa página nova
 *  (chamador decide quando chamar `doc.addPage()` antes). Recorta a imagem inteira em fatias do
 *  tamanho útil da página (descontando as faixas de cabeçalho/rodapé) deslocando o Y a cada nova
 *  página, mesmo truque de qualquer exportação "print a screenshot to multi-page PDF". A imagem é
 *  pintada por baixo, então a faixa de cabeçalho/rodapé é sempre recoberta com `corFundo` depois
 *  — tanto pra abrir espaço pro texto nativo quanto pra tapar qualquer sobra da fatia anterior/
 *  seguinte que vaze pra dentro dessas faixas. Devolve quantas páginas usou. */
async function desenharElementoPaginado(doc: jsPDF, elemento: HTMLElement, corFundo: string): Promise<number> {
  const canvas = await html2canvas(elemento, { ...OPCOES_CAPTURA, backgroundColor: corFundo })

  const larguraPagina = doc.internal.pageSize.getWidth()
  const alturaPagina = doc.internal.pageSize.getHeight()
  const alturaUtil = alturaPagina - ALTURA_CABECALHO_MM - ALTURA_RODAPE_MM
  const larguraImagem = larguraPagina
  const alturaImagem = (canvas.height * larguraImagem) / canvas.width
  // JPEG, não PNG — o conteúdo é UI plana (sem foto), mas em `scale: 2` um PNG sem perdas de
  // uma tela inteira passa fácil de 30-40MB; JPEG a 92% cai pra poucos MB sem serrilhado
  // perceptível em texto/gráficos nesse tamanho.
  const imagem = canvas.toDataURL('image/jpeg', 0.92)
  const [r, g, b] = hexParaRgb(corFundo)

  const totalPaginas = Math.max(1, Math.ceil(alturaImagem / alturaUtil))
  for (let pagina = 0; pagina < totalPaginas; pagina++) {
    if (pagina > 0) doc.addPage()
    doc.setFillColor(r, g, b)
    doc.rect(0, 0, larguraPagina, alturaPagina, 'F')
    doc.addImage(imagem, 'JPEG', 0, ALTURA_CABECALHO_MM - pagina * alturaUtil, larguraImagem, alturaImagem)
    doc.setFillColor(r, g, b)
    doc.rect(0, 0, larguraPagina, ALTURA_CABECALHO_MM, 'F')
    doc.rect(0, alturaPagina - ALTURA_RODAPE_MM, larguraPagina, ALTURA_RODAPE_MM, 'F')
  }
  return totalPaginas
}

/** Cabeçalho (título + "Página X de Y" + data/hora de geração) e rodapé (empresa(s) filtrada(s)
 *  + "Niner ERP") repetidos em **todas** as páginas do PDF — modelo de referência
 *  `RELATORIO_COMISSOES.PDF` (ERP legado): lá o título/paginação ficam no topo de cada página, e
 *  o rodapé mostra loja + sistema. Roda por último, depois de todas as páginas já existirem, pra
 *  saber o total. */
function desenharCabecalhoERodape(
  doc: jsPDF,
  corTexto: string,
  titulo: string,
  dataHoraGeracao: string,
  rodapeEsquerda: string,
  rodapeDireita: string,
): void {
  const totalPaginas = doc.getNumberOfPages()
  const largura = doc.internal.pageSize.getWidth()
  const altura = doc.internal.pageSize.getHeight()
  const [r, g, b] = hexParaRgb(corTexto)

  for (let pagina = 1; pagina <= totalPaginas; pagina++) {
    doc.setPage(pagina)

    doc.setFont('helvetica', 'bold')
    doc.setFontSize(15)
    doc.setTextColor(r, g, b)
    doc.text(titulo, 10, 11)

    doc.setFont('helvetica', 'normal')
    doc.setFontSize(9)
    doc.setTextColor(...CINZA_NEUTRO)
    doc.text(`Página ${pagina} de ${totalPaginas}`, largura - 10, 7, { align: 'right' })
    doc.text(dataHoraGeracao, largura - 10, 12, { align: 'right' })

    doc.setDrawColor(...CINZA_NEUTRO)
    doc.setLineWidth(0.2)
    doc.line(10, 14.5, largura - 10, 14.5)

    doc.line(10, altura - 7.5, largura - 10, altura - 7.5)
    doc.setFont('helvetica', 'normal')
    doc.setFontSize(9)
    doc.setTextColor(r, g, b)
    doc.text(rodapeEsquerda, 10, altura - 3.5)
    doc.text(rodapeDireita, largura - 10, altura - 3.5, { align: 'right' })
  }
}

/**
 * Gera o PDF do Relatório de Vendas como uma **captura visual da própria tela** (filtros
 * aplicados, KPIs, composição, os 7 gráficos e a grid) — pedido explícito do dono do produto:
 * "gere como está na tela, com os gráficos". Diferente do padrão textual monoespaçado já usado
 * no Fechamento de Caixa/Comprovante de Crediário: aqui o conteúdo mais importante são os
 * gráficos (SVG do Recharts), que não dá pra reconstruir como tabela de texto sem perder a
 * visualização.
 *
 * A descrição dos filtros aplicados é a 1ª coisa dentro de `topoEl` (ver `RelatorioVendas.tsx`)
 * — vem no mesmo tamanho de fonte do resto da tela porque é a mesma captura. `topoEl` e `gridEl`
 * (a grid de vendas/totalizador, com o `<tfoot>` de totais já embutido no DOM) são capturados
 * **separadamente** e a grid **sempre começa numa página nova** (pedido explícito) — mesmo que o
 * topo termine no meio de uma página. Cabeçalho/rodapé (texto nativo) entram em todas as páginas
 * por cima das imagens capturadas — modelo de referência `RELATORIO_COMISSOES.PDF` (2026-08-01).
 */
export async function gerarPdfCapturaRelatorioVendas(
  topoEl: HTMLElement,
  gridEl: HTMLElement,
  corFundo: string,
  corTexto: string,
  rodapeEsquerda: string,
): Promise<void> {
  const doc = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'landscape' })

  await desenharElementoPaginado(doc, topoEl, corFundo)
  doc.addPage()
  await desenharElementoPaginado(doc, gridEl, corFundo)

  desenharCabecalhoERodape(doc, corTexto, 'Relatório de Vendas', new Date().toLocaleString('pt-BR'), rodapeEsquerda, 'Niner ERP')

  doc.save('relatorio-de-vendas.pdf')
}
