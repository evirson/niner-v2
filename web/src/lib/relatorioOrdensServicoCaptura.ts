import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { aguardarGraficosEstaveis, forcarPaletaDeImpressaoNoClone } from './paletaDeImpressaoParaCaptura'
import { dividirEmPaginas, medirFaixaDoCabecalho, recortarFaixa, type FaixaCabecalho } from './cabecalhoRepetidoPdf'

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
const ESCALA_CAPTURA = 2

const OPCOES_CAPTURA = {
  scale: ESCALA_CAPTURA,
  useCORS: true,
  ignoreElements: (el: Element) => el.closest('.app-header, .app-nav, .modal-overlay') !== null,
  onclone: (doc: Document) => {
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

/** Gera o PDF do Relatório de Ordens de Serviço como captura visual (mesmo padrão do Relatório de
 *  Vendas) — um único elemento (filtros aplicados + grid banda, com os subtotais/total já no
 *  DOM), sem separação em duas páginas forçadas por não ter KPIs/gráficos pesados. */
export async function gerarPdfCapturaRelatorioOrdensServico(elemento: HTMLElement, rodapeEsquerda: string): Promise<void> {
  // ⚠️ Esta tela não tem gráfico, mas a espera fica: `aguardarGraficosEstaveis` custa 0 ms
  // quando não há animação em curso, e o dia em que um gráfico entrar aqui a captura já estará
  // protegida — o defeito de fotografar a barra vazia (2026-08-26) não avisa quando aparece.
  await aguardarGraficosEstaveis(elemento)
  const doc = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'landscape' })

  await desenharElementoPaginado(doc, elemento)

  desenharCabecalhoERodape(doc, 'Relatório de Ordens de Serviço', new Date().toLocaleString('pt-BR'), rodapeEsquerda, 'Nainer ERP')

  doc.save('relatorio-de-ordens-de-servico.pdf')
}
