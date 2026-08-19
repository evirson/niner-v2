import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'

/** Este relatório usa `.relatorio-corpo-fixo` (cabeçalho/rodapé da grid fixos, só as linhas
 *  rolam — ver styles.css) — sem isto, o html2canvas capturaria só a altura VISÍVEL do
 *  `.lista-corpo` (que tem `overflow-y: hidden` e altura travada pelo layout flex da tela),
 *  cortando o PDF na mesma altura da tela mesmo com o `.table-wrap` já "desclipado" por dentro.
 *  html2canvas clona o documento inteiro pra computar layout, então qualquer ancestral entre o
 *  alvo e a `<html>` que ainda limite altura/overflow corta o desenho — por isso aqui a gente
 *  sobe a árvore a partir do alvo (só no clone isolado, nunca na página real) zerando altura/
 *  overflow/flex de cada ancestral, até a `<html>` (mesmo mecanismo de
 *  `relatorioContasReceberCaptura.ts`). */
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

// Mesmo mecanismo de `relatorioVendasCaptura.ts` (tema claro via onclone, sem flash na tela;
// cabeçalho/rodapé nativos por página) — duplicado aqui de propósito (mesmo padrão de
// independência já usado entre os módulos de relatório/teste do projeto) porque este relatório
// não tem KPIs/gráficos: uma única captura (filtros aplicados + grid), não duas paginadas.
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

/** Gera o PDF do Relatório de Comissões como captura visual (mesmo padrão do Relatório de
 *  Vendas) — um único elemento (filtros aplicados + grid banda, com os subtotais/total já no
 *  DOM), sem separação em duas páginas forçadas por não ter KPIs/gráficos pesados. */
export async function gerarPdfCapturaRelatorioComissoes(elemento: HTMLElement, rodapeEsquerda: string): Promise<void> {
  const doc = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'landscape' })

  await desenharElementoPaginado(doc, elemento)

  desenharCabecalhoERodape(doc, 'Relatório de Comissões', new Date().toLocaleString('pt-BR'), rodapeEsquerda, 'Nainer ERP')

  doc.save('relatorio-de-comissoes.pdf')
}
