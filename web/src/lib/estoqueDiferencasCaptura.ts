import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'

/** Mesmo mecanismo de `relatorioContasReceberCaptura.ts`/`relatorioComissoesCaptura.ts` — ver
 *  comentário lá pro raciocínio completo (sobe a árvore a partir do alvo desclipando altura/
 *  overflow/flex de cada ancestral, só no clone isolado do html2canvas). */
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
    doc.setFillColor(r, g, b)
    doc.rect(0, 0, larguraPagina, ALTURA_CABECALHO_MM, 'F')
    doc.rect(0, alturaPagina - ALTURA_RODAPE_MM, larguraPagina, ALTURA_RODAPE_MM, 'F')
  }
}

function desenharCabecalhoERodape(doc: jsPDF, titulo: string, dataHoraGeracao: string, rodapeEsquerda: string, rodapeDireita: string): void {
  const totalPaginas = doc.getNumberOfPages()
  const largura = doc.internal.pageSize.getWidth()
  const altura = doc.internal.pageSize.getHeight()
  const [r, g, b] = hexParaRgb(COR_TEXTO_PDF)

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

/** Gera o PDF do relatório de Diferenças de Estoque como captura visual (mesmo padrão dos
 *  outros relatórios) — um único elemento (grid + total), sem seção de filtros aplicados (não
 *  existe filtro nesta tela — sempre a empresa logada, sem período). */
export async function gerarPdfCapturaDiferencasEstoque(elemento: HTMLElement, rodapeEsquerda: string): Promise<void> {
  const doc = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'landscape' })

  await desenharElementoPaginado(doc, elemento)

  desenharCabecalhoERodape(doc, 'Diferenças de Estoque', new Date().toLocaleString('pt-BR'), rodapeEsquerda, 'Nainer ERP')

  doc.save('diferencas-de-estoque.pdf')
}
