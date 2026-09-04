import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { aguardarGraficosEstaveis, forcarPaletaDeImpressaoNoClone } from './paletaDeImpressaoParaCaptura'
import { dividirEmPaginas, medirFaixaDoCabecalho, recortarFaixa, type FaixaCabecalho } from './cabecalhoRepetidoPdf'

/** A grid (segunda captura, `gridEl`) usa `.grid-altura-fixa` (cabeçalho/rodapé fixos, só as
 *  linhas rolam — ver styles.css) — sem isto, o html2canvas capturaria só a altura MÁXIMA
 *  configurada (60vh) em vez de todas as linhas, mesmo com o `.table-wrap` internamente
 *  "correto". html2canvas clona o documento inteiro pra computar layout, então qualquer
 *  ancestral entre o alvo e a `<html>` que ainda limite altura/overflow corta o desenho — por
 *  isso aqui a gente sobe a árvore a partir do `.relatorio-grid-conteudo` (só no clone isolado,
 *  nunca na página real) zerando altura/overflow/flex de cada ancestral, até a `<html>` (mesmo
 *  mecanismo de `relatorioContasReceberCaptura.ts`). Rodar isto também na captura de `topoEl`
 *  (KPIs/gráficos) é inofensivo — ele não tem essa classe, então o seletor não encontra nada. */
function liberarAlturaDosAncestrais(doc: Document, seletorAlvo: string): void {
  let el: HTMLElement | null = doc.querySelector(seletorAlvo)
  while (el) {
    el.style.overflow = 'visible'
    el.style.height = 'auto'
    el.style.maxHeight = 'none'
    el.style.flex = 'none'
    el = el.parentElement
  }
  // A própria grid tem altura máxima própria (`.grid-altura-fixa`, ver styles.css) — diferente
  // do Contas a Receber/Comissões (onde quem corta é um ANCESTRAL do alvo), aqui quem corta é
  // um DESCENDENTE do alvo (`.table-wrap` dentro de `.relatorio-grid-conteudo`); subir a árvore
  // a partir do alvo nunca alcança isso, por isso o passo extra abaixo.
  doc.querySelectorAll<HTMLElement>('.grid-altura-fixa').forEach((elemento) => {
    elemento.style.maxHeight = 'none'
    elemento.style.overflow = 'visible'
  })
}

const ESCALA_CAPTURA = 2

const OPCOES_CAPTURA = {
  scale: ESCALA_CAPTURA,
  useCORS: true,
  // html2canvas sempre clona o <html> inteiro pra manter contexto (não só o elemento
  // passado) — ignora o resto do app (menu lateral, cabeçalho, popups) tanto por
  // performance quanto porque só o conteúdo do relatório interessa na captura.
  ignoreElements: (el: Element) => el.closest('.app-header, .app-nav, .modal-overlay') !== null,
  // PDF sai sempre em tema claro, mesmo com o app em dark (pedido explícito: economizar tinta
  // na impressão) — mas SEM mudar o tema da página real visível (isso causava um "flash" pro
  // usuário: o app inteiro piscava pra claro e voltava). `onclone` roda só no clone fora de
  // tela que o html2canvas já monta internamente pra rasterizar; `styles.css` já tem
  // `:root[data-theme='light']` pronto (especificidade maior que a media query
  // `prefers-color-scheme`), só nunca era setado por ninguém.
  onclone: (doc: Document) => {
    forcarPaletaDeImpressaoNoClone(doc)
    liberarAlturaDosAncestrais(doc, '.relatorio-grid-conteudo')
  },
} as const

// Faixas reservadas em toda página pro cabeçalho/rodapé nativos do jsPDF (título/paginação e
// empresa/sistema) — desenhadas por cima da imagem capturada, nunca fazem parte do html2canvas.
const ALTURA_CABECALHO_MM = 16
const ALTURA_RODAPE_MM = 10
// Respiro lateral pedido explicitamente (2026-08-02): a imagem capturada não encosta mais nas
// bordas esquerda/direita da página — só a imagem, os preenchimentos de fundo (cabeçalho/rodapé/
// página inteira) continuam full-width, é o que sobra visível como essa margem.
const MARGEM_LATERAL_MM = 3
const CINZA_NEUTRO: [number, number, number] = [130, 130, 130]
// PDF é sempre claro por construção (ver onclone acima) — cores fixas, mesmos valores de
// `:root[data-theme='light']` em styles.css. Não depende mais do tema da página real.
const COR_FUNDO_PDF = '#ffffff'
const COR_TEXTO_PDF = '#20262a'

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
 *  pintada por baixo, então a faixa de cabeçalho/rodapé é sempre recoberta com `COR_FUNDO_PDF`
 *  depois — tanto pra abrir espaço pro texto nativo quanto pra tapar qualquer sobra da fatia
 *  anterior/seguinte que vaze pra dentro dessas faixas. Devolve quantas páginas usou. */
async function desenharElementoPaginado(doc: jsPDF, elemento: HTMLElement): Promise<number> {
  console.log('[DIAG] 3 html2canvas inicio'); // Medido no `onclone` e guardado aqui, não num `let` de módulo: duas exportações ao mesmo
  // tempo compartilhariam a variável e uma sobrescreveria a medida da outra.
  const medicao: { faixa: FaixaCabecalho | null } = { faixa: null }
  const canvas = await html2canvas(elemento, {
    ...OPCOES_CAPTURA,
    backgroundColor: COR_FUNDO_PDF,
    onclone: (documento: Document) => {
      OPCOES_CAPTURA.onclone(documento)
      // Depois de liberar a altura dos ancestrais: antes disso o clone tem o layout da tela.
      medicao.faixa = medirFaixaDoCabecalho(documento, '.relatorio-grid-conteudo', ESCALA_CAPTURA)
    },
  })

  const larguraPagina = doc.internal.pageSize.getWidth()
  const alturaPagina = doc.internal.pageSize.getHeight()
  const alturaUtil = alturaPagina - ALTURA_CABECALHO_MM - ALTURA_RODAPE_MM
  // Imagem recuada 3mm de cada lado (MARGEM_LATERAL_MM) — o preenchimento de fundo abaixo
  // continua full-width, então essa faixa fica visível como margem sem precisar de mais nada.
  const larguraImagem = larguraPagina - 2 * MARGEM_LATERAL_MM
  const alturaImagem = (canvas.height * larguraImagem) / canvas.width
  // JPEG, não PNG — o conteúdo é UI plana (sem foto), mas em `scale: 2` um PNG sem perdas de
  // uma tela inteira passa fácil de 30-40MB; JPEG a 92% cai pra poucos MB sem serrilhado
  // perceptível em texto/gráficos nesse tamanho.
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
  return paginas.length
}

/** Cabeçalho (título + "Página X de Y" + data/hora de geração) e rodapé (empresa(s) filtrada(s)
 *  + "Nainer ERP") repetidos em **todas** as páginas do PDF — modelo de referência
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
  rodapeEsquerda: string,
): Promise<void> {
  // ⚠️ Sem isto, exportar logo depois de gerar fotografa o gráfico no meio da animação
  // do recharts e as barras saem VAZIAS no PDF (achado de 2026-08-26).
  console.log('[DIAG] 1 pre-graficos'); await aguardarGraficosEstaveis(topoEl); console.log('[DIAG] 2 graficos ok')
  const doc = new jsPDF({ unit: 'mm', format: 'a4', orientation: 'landscape' })

  await desenharElementoPaginado(doc, topoEl); console.log('[DIAG] 4 topo ok')
  doc.addPage()
  await desenharElementoPaginado(doc, gridEl); console.log('[DIAG] 5 grid ok')

  desenharCabecalhoERodape(doc, COR_TEXTO_PDF, 'Relatório de Vendas', new Date().toLocaleString('pt-BR'), rodapeEsquerda, 'Nainer ERP')

  doc.save('relatorio-de-vendas.pdf')
}
