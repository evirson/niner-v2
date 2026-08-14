import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'

/** Ver `relatorioComissoesCaptura.ts` — mesmo mecanismo: o `.lista-corpo` tem altura travada pelo
 *  layout flex da tela, então sem liberar altura/overflow de cada ancestral (só no clone isolado,
 *  nunca na página real) o html2canvas cortaria o PDF na altura da tela. */
function liberarAlturaDosAncestrais(doc: Document, seletorAlvo: string): void {
  let el: HTMLElement | null = doc.querySelector(seletorAlvo)
  while (el) {
    el.style.overflow = 'visible'
    el.style.height = 'auto'
    el.style.maxHeight = 'none'
    el.style.flex = 'none'
    el = el.parentElement
  }
}

const OPCOES_CAPTURA = {
  scale: 2,
  useCORS: true,
  ignoreElements: (el: Element) => el.closest('.app-header, .app-nav, .modal-overlay') !== null,
  onclone: (doc: Document) => {
    // Tema claro só no clone: PDF em dark gasta muito mais tinta, e a tela não pisca.
    doc.documentElement.setAttribute('data-theme', 'light')
    liberarAlturaDosAncestrais(doc, '.relatorio-conteudo')
  },
} as const

const ALTURA_CABECALHO_MM = 16
const ALTURA_RODAPE_MM = 10
const MARGEM_LATERAL_MM = 3
const CINZA_NEUTRO: [number, number, number] = [130, 130, 130]
const COR_FUNDO_PDF = '#f5f4f0'
const COR_TEXTO_PDF = '#20262a'

function hexParaRgb(hex: string): [number, number, number] {
  const limpo = hex.replace('#', '').trim()
  const normalizado = limpo.length === 3 ? limpo.split('').map((c) => c + c).join('') : limpo
  const inteiro = parseInt(normalizado, 16)
  return [(inteiro >> 16) & 255, (inteiro >> 8) & 255, inteiro & 255]
}

async function desenharElementoPaginado(doc: jsPDF, elemento: HTMLElement): Promise<void> {
  const canvas = await html2canvas(elemento, { ...OPCOES_CAPTURA, backgroundColor: COR_FUNDO_PDF })

  const larguraPagina = doc.internal.pageSize.getWidth()
  const alturaPagina = doc.internal.pageSize.getHeight()
  const alturaUtil = alturaPagina - ALTURA_CABECALHO_MM - ALTURA_RODAPE_MM
  const larguraImagem = larguraPagina - 2 * MARGEM_LATERAL_MM
  const alturaImagem = (canvas.height * larguraImagem) / canvas.width
  const imagem = canvas.toDataURL('image/jpeg', 0.92)
  const [r, g, b] = hexParaRgb(COR_FUNDO_PDF)

  const totalPaginas = Math.max(1, Math.ceil(alturaImagem / alturaUtil))
  for (let pagina = 0; pagina < totalPaginas; pagina++) {
    if (pagina > 0) doc.addPage()
    doc.setFillColor(r, g, b)
    doc.rect(0, 0, larguraPagina, alturaPagina, 'F')
    doc.addImage(imagem, 'JPEG', MARGEM_LATERAL_MM, ALTURA_CABECALHO_MM - pagina * alturaUtil, larguraImagem, alturaImagem)
    // Tampa o que vazou pra faixa de cabeçalho/rodapé antes de escrever por cima.
    doc.setFillColor(r, g, b)
    doc.rect(0, 0, larguraPagina, ALTURA_CABECALHO_MM, 'F')
    doc.rect(0, alturaPagina - ALTURA_RODAPE_MM, larguraPagina, ALTURA_RODAPE_MM, 'F')
  }
}

function desenharCabecalhoERodape(
  doc: jsPDF, titulo: string, subtitulo: string, dataHoraGeracao: string,
  rodapeEsquerda: string, rodapeDireita: string): void {
  const totalPaginas = doc.getNumberOfPages()
  const largura = doc.internal.pageSize.getWidth()
  const altura = doc.internal.pageSize.getHeight()
  const [r, g, b] = hexParaRgb(COR_TEXTO_PDF)

  for (let pagina = 1; pagina <= totalPaginas; pagina++) {
    doc.setPage(pagina)

    doc.setFont('helvetica', 'bold')
    doc.setFontSize(15)
    doc.setTextColor(r, g, b)
    doc.text(titulo, 10, 9)

    // Regime + período no cabeçalho: numa DRE impressa, ler "competência" ou "caixa" é a
    // diferença entre entender e não entender o número — não pode ficar só na tela.
    doc.setFont('helvetica', 'normal')
    doc.setFontSize(9)
    doc.setTextColor(...CINZA_NEUTRO)
    doc.text(subtitulo, 10, 13)

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
 * PDF do Fluxo de Caixa como captura visual, mesmo mecanismo do PDF da DRE (tema claro no clone,
 * cabeçalho/rodapé nativos por página) — **retrato** pelo mesmo motivo: poucas colunas e muitas
 * linhas. O subtítulo carrega a visão (Realizado/Projeção) e o período, porque um fluxo impresso
 * sem essa informação não diz se olha para trás ou para a frente.
 */
export async function gerarPdfCapturaFluxoCaixa(
  elemento: HTMLElement, subtitulo: string, rodapeEsquerda: string): Promise<void> {
  const doc = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'portrait' })

  await desenharElementoPaginado(doc, elemento)

  desenharCabecalhoERodape(
    doc, 'Fluxo de Caixa', subtitulo,
    new Date().toLocaleString('pt-BR'), rodapeEsquerda, 'Niner ERP')

  doc.save('fluxo-de-caixa.pdf')
}
