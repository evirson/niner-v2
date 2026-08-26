import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { aguardarGraficosEstaveis, forcarTemaClaroNoClone } from './temaClaroParaCaptura'

/** A tela usa `.lista-corpo` normal (a página inteira rola) e a grid (`.grid-altura-fixa`,
 *  2026-08-04) tem altura própria limitada a 60vh com rolagem interna — sem isto, o html2canvas
 *  capturaria só essa altura máxima em vez de todas as linhas. html2canvas clona o documento
 *  inteiro pra computar layout, então qualquer ancestral entre o alvo e a `<html>` que ainda
 *  limite altura/overflow corta o desenho — por isso aqui a gente sobe a árvore a partir do alvo
 *  (só no clone isolado, nunca na página real) zerando altura/overflow/flex de cada ancestral,
 *  até a `<html>` (mesmo mecanismo de `relatorioVendasCaptura.ts`). A própria grid tem altura
 *  máxima PRÓPRIA (`.grid-altura-fixa`) — diferente do que os ancestrais cortam, aqui quem corta
 *  é um DESCENDENTE do alvo, então a subida pela árvore nunca alcança isso; por isso o passo
 *  extra abaixo. */
function liberarAlturaDosAncestrais(doc: Document, seletorAlvo: string): void {
  let el: HTMLElement | null = doc.querySelector(seletorAlvo)
  while (el) {
    el.style.overflow = 'visible'
    el.style.height = 'auto'
    el.style.maxHeight = 'none'
    el.style.flex = 'none'
    el = el.parentElement
  }
  doc.querySelectorAll<HTMLElement>('.grid-altura-fixa').forEach((elemento) => {
    elemento.style.maxHeight = 'none'
    elemento.style.overflow = 'visible'
  })
}

// Mesmo mecanismo de `relatorioComissoesCaptura.ts`/`relatorioVendasCaptura.ts` (tema claro via
// onclone, sem flash na tela; cabeçalho/rodapé nativos por página) — duplicado de propósito,
// mesmo padrão de independência entre módulos de relatório já adotado no projeto.
const OPCOES_CAPTURA = {
  scale: 2,
  useCORS: true,
  ignoreElements: (el: Element) => el.closest('.app-header, .app-nav, .modal-overlay') !== null,
  onclone: (doc: Document) => {
    forcarTemaClaroNoClone(doc)
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

/** Gera o PDF do Relatório de Contas a Pagar / Pagas como captura visual — mesmo padrão
 *  do Relatório de Comissões (um único elemento: filtros aplicados + grid banda). */
export async function gerarPdfCapturaRelatorioContasPagar(elemento: HTMLElement, rodapeEsquerda: string): Promise<void> {
  // ⚠️ Sem isto, exportar logo depois de gerar fotografa o gráfico no meio da animação
  // do recharts e as barras saem VAZIAS no PDF (achado de 2026-08-26).
  await aguardarGraficosEstaveis(elemento)
  const doc = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'landscape' })

  await desenharElementoPaginado(doc, elemento)

  desenharCabecalhoERodape(doc, 'Relatório de Contas a Pagar / Pagas', new Date().toLocaleString('pt-BR'), rodapeEsquerda, 'Nainer ERP')

  doc.save('relatorio-de-contas-a-pagar.pdf')
}
