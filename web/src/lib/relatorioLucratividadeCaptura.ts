import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { aguardarGraficosEstaveis, forcarPaletaDeImpressaoNoClone } from './paletaDeImpressaoParaCaptura'
import { dividirEmPaginas, medirFaixaDoCabecalho, recortarFaixa, type FaixaCabecalho } from './cabecalhoRepetidoPdf'

/** Ver `relatorioDreCaptura.ts` — mesmo mecanismo: o `.lista-corpo` tem altura travada pelo layout
 *  flex da tela, então sem liberar altura/overflow de cada ancestral (só no clone isolado, nunca na
 *  página real) o html2canvas cortaria o PDF na altura da tela. */
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

const ESCALA_CAPTURA = 2

const OPCOES_CAPTURA = {
  scale: ESCALA_CAPTURA,
  useCORS: true,
  ignoreElements: (el: Element) => el.closest('.app-header, .app-nav, .modal-overlay') !== null,
  onclone: (doc: Document) => {
    // Tema claro só no clone: PDF em dark gasta muito mais tinta, e a tela não pisca.
    forcarPaletaDeImpressaoNoClone(doc)
    liberarAlturaDosAncestrais(doc, '.relatorio-conteudo')
  },
} as const

const ALTURA_CABECALHO_MM = 16
const ALTURA_RODAPE_MM = 10
const MARGEM_LATERAL_MM = 3
const CINZA_NEUTRO: [number, number, number] = [130, 130, 130]
const COR_FUNDO_PDF = '#ffffff'
const COR_TEXTO_PDF = '#20262a'

function hexParaRgb(hex: string): [number, number, number] {
  const limpo = hex.replace('#', '').trim()
  const normalizado = limpo.length === 3 ? limpo.split('').map((c) => c + c).join('') : limpo
  const inteiro = parseInt(normalizado, 16)
  return [(inteiro >> 16) & 255, (inteiro >> 8) & 255, inteiro & 255]
}

async function desenharElementoPaginado(doc: jsPDF, elemento: HTMLElement): Promise<void> {
  // Medido no `onclone` e guardado aqui, não num `let` de módulo: duas exportações ao mesmo
  // tempo compartilhariam a variável e uma sobrescreveria a medida da outra.
  const medicao: { faixa: FaixaCabecalho | null } = { faixa: null }
  const canvas = await html2canvas(elemento, {
    ...OPCOES_CAPTURA,
    backgroundColor: COR_FUNDO_PDF,
    onclone: (documento: Document) => {
      OPCOES_CAPTURA.onclone(documento)
      // Depois de liberar a altura dos ancestrais: antes disso o clone tem o layout da tela.
      medicao.faixa = medirFaixaDoCabecalho(documento, '.relatorio-conteudo', ESCALA_CAPTURA)
    },
  })

  const larguraPagina = doc.internal.pageSize.getWidth()
  const alturaPagina = doc.internal.pageSize.getHeight()
  const alturaUtil = alturaPagina - ALTURA_CABECALHO_MM - ALTURA_RODAPE_MM
  const larguraImagem = larguraPagina - 2 * MARGEM_LATERAL_MM
  const alturaImagem = (canvas.height * larguraImagem) / canvas.width
  const imagem = canvas.toDataURL('image/jpeg', 0.92)
  const [r, g, b] = hexParaRgb(COR_FUNDO_PDF)

  // Cabeçalho das colunas repetido a partir da 2ª página. Sem tabela (ou com mais de uma, caso
  // dos relatórios em seções), `imagemCabecalho` fica nulo e a paginação é a de antes.
  const faixa = medicao.faixa
  const imagemCabecalho = faixa ? recortarFaixa(canvas, faixa) : null
  const alturaCabecalhoRepetido = faixa && imagemCabecalho ? (faixa.alturaPx * larguraImagem) / canvas.width : 0

  const escalaMm = larguraImagem / canvas.width
  const limitesDaTabela = faixa && imagemCabecalho
    ? { topoMm: faixa.topoPx * escalaMm, fimMm: faixa.fimCorpoPx * escalaMm }
    : null
  const paginas = dividirEmPaginas(alturaImagem, alturaUtil, alturaCabecalhoRepetido, limitesDaTabela)
  for (const [indice, pagina] of paginas.entries()) {
    if (indice > 0) doc.addPage()
    const topoConteudo = ALTURA_CABECALHO_MM + (pagina.repeteCabecalho ? alturaCabecalhoRepetido : 0)

    doc.setFillColor(r, g, b)
    doc.rect(0, 0, larguraPagina, alturaPagina, 'F')
    doc.addImage(imagem, 'JPEG', MARGEM_LATERAL_MM, topoConteudo - pagina.deslocamentoMm, larguraImagem, alturaImagem)

    // Tampa o que a imagem invadiu fora da área útil — inclusive a faixa reservada ao
    // cabeçalho, que é pintada logo depois, por cima.
    doc.setFillColor(r, g, b)
    doc.rect(0, 0, larguraPagina, topoConteudo, 'F')
    doc.rect(0, alturaPagina - ALTURA_RODAPE_MM, larguraPagina, ALTURA_RODAPE_MM, 'F')

    if (pagina.repeteCabecalho && imagemCabecalho) {
      doc.addImage(imagemCabecalho, 'JPEG', MARGEM_LATERAL_MM, ALTURA_CABECALHO_MM, larguraImagem, alturaCabecalhoRepetido)
    }
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

    // O período vai no cabeçalho de toda página: num relatório de resultado impresso, "de quando
    // é este lucro" é a primeira pergunta de quem recebe a folha solta.
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
 * PDF da Lucratividade como captura visual, mesmo padrão dos demais relatórios.
 *
 * **Retrato**, como a DRE: poucas colunas e muitas linhas de conta, e é o formato que o lojista
 * leva para conversar com o contador.
 */
export async function gerarPdfCapturaLucratividade(
  elemento: HTMLElement, subtitulo: string, rodapeEsquerda: string): Promise<void> {
  // ⚠️ Sem isto, exportar logo depois de gerar fotografa o gráfico no meio da animação
  // do recharts e as barras saem VAZIAS no PDF (achado de 2026-08-26).
  await aguardarGraficosEstaveis(elemento)
  const doc = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'portrait' })

  await desenharElementoPaginado(doc, elemento)

  desenharCabecalhoERodape(
    doc, 'Relatório de Lucratividade', subtitulo,
    new Date().toLocaleString('pt-BR'), rodapeEsquerda, 'Nainer ERP')

  doc.save('relatorio-de-lucratividade.pdf')
}
