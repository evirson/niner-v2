import { jsPDF } from 'jspdf'
import type { ComprovanteRecebimento } from './recebimentoCrediario'
import type { DevolucaoEfetivada } from './devolucaoProduto'
import type { ComprovanteVenda } from './pdv'
import { formatarMoeda } from './masks'

/**
 * Largura em colunas do comprovante — 42 caracteres é o padrão seguro pra bobina térmica de
 * 80mm em fonte monoespaçada legível (Font A, ~12 caracteres/25,4mm); a tabela larga do mockup
 * original (dono do produto, 2026-07-30) não caberia fisicamente numa impressora térmica real,
 * então o layout foi reorganizado em blocos por parcela mantendo as mesmas informações.
 */
const LARGURA = 42

/**
 * '—' (travessão) e '•' (marcador) em vez de '-'/'.': ficam graficamente mais parecidos com uma
 * linha/separador impresso de verdade. Restritos ao WinAnsiEncoding (CP1252) de propósito — são
 * os únicos "caracteres gráficos" que a fonte padrão do jsPDF (`gerarPdfComprovante`) consegue
 * desenhar sem precisar embutir uma fonte TTF nova; caracteres de desenho de caixa (─/═/Unicode
 * U+2500+) apareceriam certos na tela mas quebrados no PDF.
 */
function linha(caractere: string = '—'): string {
  return caractere.repeat(LARGURA)
}

function centralizar(texto: string): string {
  const t = texto.length > LARGURA ? texto.slice(0, LARGURA) : texto
  const espacos = LARGURA - t.length
  return ' '.repeat(Math.floor(espacos / 2)) + t
}

/** Texto à esquerda, valor à direita, preenchido de espaços — trunca a esquerda se não couber. */
function duasColunas(esquerda: string, direita: string): string {
  const maxEsquerda = Math.max(0, LARGURA - direita.length - 1)
  const e = esquerda.length > maxEsquerda ? esquerda.slice(0, maxEsquerda) : esquerda
  const espacos = Math.max(1, LARGURA - e.length - direita.length)
  return e + ' '.repeat(espacos) + direita
}

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * Monta o comprovante como um array de linhas de texto monoespaçado (42 colunas) — fonte única
 * de verdade reusada pela pré-visualização na tela, pela impressão (window.print) e pelo PDF
 * (jsPDF com fonte courier), garantindo que os três saem idênticos.
 */
export function montarLinhasComprovante(c: ComprovanteRecebimento): string[] {
  const linhas: string[] = []
  linhas.push(linha())
  linhas.push(centralizar(c.nomeEmpresa))
  linhas.push(linha())
  linhas.push(centralizar('COMPROVANTE DE PAGAMENTO'))
  linhas.push(centralizar('DE CREDIARIO'))
  linhas.push(linha())
  linhas.push(`Cliente: ${c.nomeCliente}`)
  linhas.push(linha())

  c.parcelas.forEach((p, indice) => {
    const pc = `${String(p.numeroParcela).padStart(2, '0')}/${String(p.totalParcelas).padStart(2, '0')}`
    linhas.push(`Venda ${p.idVenda}  PC ${pc}  Venc. ${formatarData(p.dataVencimento)}`)
    linhas.push(duasColunas('  Vlr. Parcela:', moeda(p.valorParcela)))
    linhas.push(duasColunas('  Multa/Juros:', moeda(p.multaJuros)))
    linhas.push(duasColunas('  Vlr. a Pagar:', moeda(p.valorAPagar)))
    if (indice < c.parcelas.length - 1) linhas.push(linha('•'))
  })

  linhas.push(linha())
  linhas.push(duasColunas('Total a Pagar:', moeda(c.valorTotalAPagar)))
  linhas.push(linha())
  linhas.push(duasColunas('FORMA DE PAGAMENTO', 'VALOR PAGO'))
  linhas.push(linha('•'))
  c.pagamentos.forEach((p) => linhas.push(duasColunas(p.nomeCarteira, moeda(p.valorPago))))
  const totalPago = c.pagamentos.reduce((soma, p) => soma + p.valorPago, 0)
  linhas.push(linha())
  linhas.push(duasColunas('Total Pago:', moeda(totalPago)))
  linhas.push(linha())
  linhas.push(`Data Pagamento: ${formatarDataHora(c.dataPagamento)}`)
  linhas.push(`Identificacao: ${c.idCaixa}-${c.idLoteRecebimento}`)
  linhas.push(linha())

  return linhas
}

/**
 * Gera o PDF direto (sem passar pelo diálogo de impressão do navegador — pedido explícito,
 * 2026-07-30), página no tamanho exato da bobina (80mm de largura, altura dinâmica conforme o
 * número de linhas), fonte courier monoespaçada pra alinhar exatamente igual à pré-visualização.
 */
export function gerarPdfComprovante(linhas: string[], idLoteRecebimento: number): void {
  const margem = 4
  const tamanhoFonte = 8
  const alturaLinha = 3.6
  const altura = margem * 2 + linhas.length * alturaLinha

  const doc = new jsPDF({ unit: 'mm', format: [80, altura] })
  doc.setFont('courier', 'normal')
  doc.setFontSize(tamanhoFonte)
  linhas.forEach((texto, indice) => {
    doc.text(texto, margem, margem + (indice + 1) * alturaLinha)
  })
  doc.save(`comprovante-crediario-${idLoteRecebimento}.pdf`)
}

/** Comprovante do vale-mercadoria emitido por uma devolução (2026-08-03) — mesmo padrão visual
 *  de `montarLinhasComprovante`, mas mais simples (sem parcelas/formas de pagamento). O número
 *  do vale (`idDevolucao`) é o dado mais importante da bobina — é o que o cliente apresenta
 *  depois pra resgatar o crédito numa venda futura. */
export function montarLinhasComprovanteVale(d: DevolucaoEfetivada, nomeEmpresa: string): string[] {
  const linhas: string[] = []
  linhas.push(linha())
  linhas.push(centralizar(nomeEmpresa))
  linhas.push(linha())
  linhas.push(centralizar('VALE-MERCADORIA'))
  linhas.push(centralizar('DEVOLUCAO DE PRODUTOS'))
  linhas.push(linha())
  linhas.push(duasColunas('Vale nº:', String(d.idDevolucao)))
  linhas.push(duasColunas('Valor do Vale:', moeda(d.valorVale)))
  linhas.push(linha())
  linhas.push('Itens devolvidos:')
  d.itens.forEach((item) => {
    const descricao = item.variacaoLinha || item.variacaoColuna
      ? `${item.descricaoProduto} (${[item.variacaoLinha, item.variacaoColuna].filter(Boolean).join(' · ')})`
      : item.descricaoProduto
    linhas.push(`  ${formatarQuantidadeSimples(item.qtd)}x ${descricao}`)
  })
  linhas.push(linha())
  if (d.nomeFuncionario) linhas.push(`Vendedor original: ${d.nomeFuncionario}`)
  linhas.push(`Data: ${formatarDataHora(d.dataMovimento)}`)
  linhas.push(linha())
  linhas.push(centralizar('Apresente este vale para'))
  linhas.push(centralizar('usar o credito numa compra futura.'))
  linhas.push(linha())

  return linhas
}

function formatarQuantidadeSimples(qtd: number): string {
  return Number.isInteger(qtd) ? String(qtd) : qtd.toString().replace('.', ',')
}

/** Mesmo mecanismo de {@link gerarPdfComprovante}, nome de arquivo próprio do vale. */
export function gerarPdfComprovanteVale(linhas: string[], idDevolucao: number): void {
  const margem = 4
  const tamanhoFonte = 8
  const alturaLinha = 3.6
  const altura = margem * 2 + linhas.length * alturaLinha

  const doc = new jsPDF({ unit: 'mm', format: [80, altura] })
  doc.setFont('courier', 'normal')
  doc.setFontSize(tamanhoFonte)
  linhas.forEach((texto, indice) => {
    doc.text(texto, margem, margem + (indice + 1) * alturaLinha)
  })
  doc.save(`vale-mercadoria-${idDevolucao}.pdf`)
}

/**
 * Papeleta de venda do PDV (2026-08-06) — layout largo (64 colunas) pedido pelo dono do produto,
 * diferente das outras duas bobinas deste arquivo (42 colunas). A largura maior só cabe numa
 * bobina de 80mm com uma fonte mais condensada que a courier usada acima — a pré-visualização e
 * a impressão (`window.print`) usam `font-family: 'Lucida Console'` (`.papeleta-imprimir`,
 * `styles.css`), decisão do dono do produto pra resolver isso. O PDF (`gerarPdfComprovanteVenda`)
 * não consegue seguir a mesma fonte: 'Lucida Console' é proprietária da Microsoft e o jsPDF só
 * embute fontes TTF que a gente empacota — não há uma embutível aqui, então o PDF cai pra
 * 'courier' (nativa do jsPDF) num tamanho bem menor (~5pt) só pra caber as 64 colunas fisicamente
 * nos 80mm; a impressão direta (Lucida Console, tela/bobina real) é o caminho recomendado.
 */
const LARGURA_VENDA = 64
const COL_CODIGO = 13
const COL_DESCRICAO = 25
const COL_QTD = 3
const COL_UNITARIO = 9
const COL_TOTAL = 10
const COL_PARC = 5
const COL_VENCIMENTO = 10
const COL_VALOR_PARC = 13

function linhaVenda(caractere: string = '—'): string {
  return caractere.repeat(LARGURA_VENDA)
}

function centralizarVenda(texto: string): string {
  const t = texto.length > LARGURA_VENDA ? texto.slice(0, LARGURA_VENDA) : texto
  const espacos = LARGURA_VENDA - t.length
  return ' '.repeat(Math.floor(espacos / 2)) + t
}

function colEsq(texto: string, largura: number): string {
  const t = texto.length > largura ? texto.slice(0, largura) : texto
  return t.padEnd(largura)
}

function colDir(texto: string, largura: number): string {
  const t = texto.length > largura ? texto.slice(0, largura) : texto
  return t.padStart(largura)
}

/** Linha de cabeçalho tipo "Id. Venda..: valor" — rótulo já vem com os pontos, só concatena. */
function campoVenda(rotulo: string, valor: string): string {
  return `${rotulo} ${valor}`
}

/** Linha de resumo (SUB-TOTAL/DESCONTOS/ACRESCIMOS/TOTAL A PAGAR) — rótulo à direita, valor nos
 *  últimos `COL_TOTAL` caracteres. */
function linhaResumoVenda(rotulo: string, valor: string): string {
  return rotulo.padStart(LARGURA_VENDA - COL_TOTAL) + valor.padStart(COL_TOTAL)
}

/** Linha "VALOR PAGO EM: NOME DA CARTEIRA ... valor" (ou "VALOR A PAGAR EM" quando `crediario` —
 *  esse dinheiro ainda não circulou, ver `parcelasCrediario` — 2026-08-06) — rótulo à ESQUERDA
 *  (diferente do bloco de resumo acima), valor nos últimos `COL_TOTAL` caracteres, uma linha por
 *  forma de pagamento usada na venda (split-tender: mostra todas). */
function linhaPagamentoVenda(nomeCarteira: string, crediario: boolean, valor: string): string {
  const rotulo = `${crediario ? 'VALOR A PAGAR EM' : 'VALOR PAGO EM'}: ${nomeCarteira}`
  return colEsq(rotulo, LARGURA_VENDA - COL_TOTAL) + valor.padStart(COL_TOTAL)
}

/** Linha da tabela "PARCELAS A VENCER DE CREDIARIO" (2026-08-06) — largura própria (37, menor
 *  que as 64 do resto da papeleta), reaproveitada tanto pro cabeçalho quanto pras linhas de
 *  dado (os rótulos "PARC."/"VENCIMENTO"/"VALOR A PAGAR" já cabem exatamente nas larguras das
 *  colunas). Só aparece quando a venda teve pagamento em CREDIARIO. */
function linhaParcelaCrediario(parc: string, vencimento: string, valor: string): string {
  return colEsq(parc, COL_PARC) + ' '.repeat(6) + colEsq(vencimento, COL_VENCIMENTO) + ' '.repeat(3) + colDir(valor, COL_VALOR_PARC)
}

/** Concatena descrição + variante de linha (se tiver) + variante de coluna (se tiver) e quebra
 *  em blocos fixos de `COL_DESCRICAO` caracteres — sempre 3 linhas (as 2 últimas em branco
 *  quando o texto não preenche), mesmo layout fixo do mockup pedido pelo dono do produto. */
function montarDescricaoEmLinhas(descricaoProduto: string, variacaoLinha: string | null, variacaoColuna: string | null): string[] {
  const texto = [descricaoProduto, variacaoLinha, variacaoColuna].filter(Boolean).join(' ')
  const linhas: string[] = []
  for (let i = 0; i < texto.length && linhas.length < 3; i += COL_DESCRICAO) {
    linhas.push(texto.slice(i, i + COL_DESCRICAO))
  }
  while (linhas.length < 3) linhas.push('')
  return linhas
}

export function montarLinhasComprovanteVenda(c: ComprovanteVenda): string[] {
  const linhas: string[] = []
  linhas.push(linhaVenda())
  linhas.push(centralizarVenda('***  DOCUMENTO SEM VALOR FISCAL  ***'))
  linhas.push(linhaVenda())
  linhas.push(centralizarVenda(`${c.nomeEmpresa} (Loja ${c.codigoEmpresa})`))
  linhas.push(linhaVenda())
  linhas.push(campoVenda('Id. Venda..:', String(c.idVenda)))
  linhas.push(campoVenda('Cliente....:', c.nomeCliente ?? '(não informado)'))
  linhas.push(campoVenda('Vendedor...:', c.nomeVendedor ?? '(não informado)'))
  linhas.push(campoVenda('Operador...:', c.nomeOperador ?? '(não informado)'))
  linhas.push(campoVenda('Data.......:', formatarDataHora(c.dataVenda)))
  linhas.push(linhaVenda())
  linhas.push(
    [
      colEsq('CODIGO', COL_CODIGO),
      colEsq('DESCRICAO DOS PRODUTOS', COL_DESCRICAO),
      colDir('QTD', COL_QTD),
      colDir('UNITARIO', COL_UNITARIO),
      colDir('TOTAL', COL_TOTAL),
    ].join(' '),
  )
  linhas.push(linhaVenda())

  c.itens.forEach((item) => {
    const [desc1, desc2, desc3] = montarDescricaoEmLinhas(item.descricaoProduto, item.variacaoLinha, item.variacaoColuna)
    linhas.push(
      [
        colEsq(item.sku, COL_CODIGO),
        colEsq(desc1, COL_DESCRICAO),
        colDir(formatarQuantidadeSimples(item.qtd), COL_QTD),
        colDir(formatarMoeda(item.valorUnitario), COL_UNITARIO),
        colDir(formatarMoeda(item.valorTotal), COL_TOTAL),
      ].join(' '),
    )
    if (desc2) linhas.push(' '.repeat(COL_CODIGO + 1) + colEsq(desc2, COL_DESCRICAO))
    if (desc3) linhas.push(' '.repeat(COL_CODIGO + 1) + colEsq(desc3, COL_DESCRICAO))
  })

  linhas.push(linhaVenda())
  linhas.push(linhaResumoVenda('SUB-TOTAL....:', formatarMoeda(c.subtotal)))
  linhas.push(linhaResumoVenda('DESCONTOS....:', formatarMoeda(c.descontos)))
  linhas.push(linhaResumoVenda('ACRESCIMOS...:', formatarMoeda(c.acrescimos)))
  linhas.push(linhaResumoVenda('TOTAL A PAGAR..:', formatarMoeda(c.totalAPagar)))
  linhas.push(linhaVenda())
  c.pagamentos.forEach((p) => {
    linhas.push(linhaPagamentoVenda(p.nomeCarteira, p.crediario, formatarMoeda(p.valorPago)))
  })
  linhas.push(linhaVenda())

  if (c.parcelasCrediario.length > 0) {
    linhas.push(centralizarVenda('PARCELAS A VENCER DE CREDIARIO'))
    linhas.push(linhaVenda())
    linhas.push(linhaParcelaCrediario('PARC.', 'VENCIMENTO', 'VALOR A PAGAR'))
    linhas.push(linhaVenda())
    c.parcelasCrediario.forEach((p) => {
      const parc = `${String(p.numeroParcela).padStart(2, '0')}/${String(p.totalParcelas).padStart(2, '0')}`
      linhas.push(linhaParcelaCrediario(parc, formatarData(p.dataVencimento), formatarMoeda(p.valorParcela)))
    })
    linhas.push(linhaVenda())
  }

  return linhas
}

/**
 * PDF da papeleta — mesmo mecanismo de {@link gerarPdfComprovante}, mas fonte courier ~5pt (em
 * vez de 8pt/42 colunas): é o tamanho que cabe fisicamente 64 colunas em 80mm de largura (ver
 * comentário no topo desta seção) — bem menor que o ideal, o caminho recomendado pra imprimir de
 * verdade é o botão "Imprimir" (Lucida Console, via CSS), não este PDF.
 */
export function gerarPdfComprovanteVenda(linhas: string[], idVenda: number): void {
  const margem = 4
  const tamanhoFonte = 5
  const alturaLinha = 2.6
  const altura = margem * 2 + linhas.length * alturaLinha

  const doc = new jsPDF({ unit: 'mm', format: [80, altura] })
  doc.setFont('courier', 'normal')
  doc.setFontSize(tamanhoFonte)
  linhas.forEach((texto, indice) => {
    doc.text(texto, margem, margem + (indice + 1) * alturaLinha)
  })
  doc.save(`papeleta-venda-${idVenda}.pdf`)
}
