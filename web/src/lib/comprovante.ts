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
export function montarLinhasComprovante(c: ComprovanteRecebimento, reimpressao: boolean = false): string[] {
  const linhas: string[] = []
  linhas.push(linha())
  linhas.push(centralizar(c.nomeEmpresa))
  linhas.push(linha())
  if (reimpressao) {
    linhas.push(centralizar('REIMPRESSÃO DE PAPELETA DE'))
    linhas.push(centralizar('RECEBIMENTO DE CREDIARIO'))
  } else {
    linhas.push(centralizar('COMPROVANTE DE PAGAMENTO'))
    linhas.push(centralizar('DE CREDIARIO'))
  }
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
  if (reimpressao) {
    linhas.push(`Impresso em: ${formatarDataHora(new Date().toISOString())}`)
    linhas.push(linha())
  }

  return linhas
}

/**
 * Largura fixa (mm) de toda bobina térmica gerada por este arquivo — usada também como piso da
 * altura calculada (ver {@link LARGURA_MM} nos comentários de cada `montarDocumento*`: o jsPDF, em
 * orientação retrato, troca largura por altura sempre que `largura > altura`; sem esse piso, um
 * comprovante curto — poucos itens/parcelas — vira uma página mais estreita que 80mm e corta as
 * colunas da direita. Bug real encontrado 2026-08-11 comparando o vale-mercadoria — que costuma
 * ter só 1 item, caindo abaixo do piso — com a papeleta de venda, que raramente é curta o
 * suficiente pra disparar a troca).
 */
const LARGURA_MM = 80

/**
 * Monta o documento jsPDF do comprovante de crediário (80mm de largura, altura dinâmica conforme
 * o número de linhas, fonte courier monoespaçada pra alinhar exatamente igual à pré-visualização)
 * — fonte única de verdade reusada tanto pra baixar o arquivo ({@link gerarPdfComprovante}) quanto
 * pra gerar o Blob que sobe pro compartilhamento por link ({@link gerarBlobComprovante}).
 */
function montarDocumentoComprovante(linhas: string[]): jsPDF {
  const margem = 4
  const tamanhoFonte = 8
  const alturaLinha = 3.6
  const altura = Math.max(LARGURA_MM, margem * 2 + linhas.length * alturaLinha)

  const doc = new jsPDF({ unit: 'mm', format: [LARGURA_MM, altura] })
  doc.setFont('courier', 'normal')
  doc.setFontSize(tamanhoFonte)
  linhas.forEach((texto, indice) => {
    doc.text(texto, margem, margem + (indice + 1) * alturaLinha)
  })
  return doc
}

/**
 * Gera o PDF direto (sem passar pelo diálogo de impressão do navegador — pedido explícito,
 * 2026-07-30).
 */
export function gerarPdfComprovante(linhas: string[], idLoteRecebimento: number): void {
  montarDocumentoComprovante(linhas).save(`comprovante-crediario-${idLoteRecebimento}.pdf`)
}

/**
 * Mesmo documento de {@link gerarPdfComprovante}, mas devolve o Blob em vez de baixar — usado
 * pra subir o PDF pro compartilhamento por link (envio por WhatsApp, `lib/compartilhamento.ts`).
 */
export function gerarBlobComprovante(linhas: string[]): Blob {
  return montarDocumentoComprovante(linhas).output('blob')
}

function formatarQuantidadeSimples(qtd: number): string {
  return Number.isInteger(qtd) ? String(qtd) : qtd.toString().replace('.', ',')
}

/**
 * Papeleta de venda do PDV — **42 colunas, item em 2 linhas** (revisão de 2026-08-14).
 *
 * <p>A versão original tinha 64 colunas numa única linha por item. Impressa numa bobina real o
 * resultado era ilegível, e a conta explica: o papel tem 80mm mas a **área imprimível é 75mm**
 * (a estimativa inicial era 72mm; 75mm é o valor calibrado contra a impressora e a convenção do
 * projeto); descontando a margem sobra ~1,1mm por caractere — fonte de ~6px. Pior, o CSS
 * declarava `width: 80mm`, então o driver ainda encolhia tudo ~10% pra caber na área imprimível.
 *
 * <p>Solução pedida pelo dono do produto: **quebrar o item em duas linhas** em vez de espremer a
 * largura. A primeira traz código de barras + o que couber do nome; a segunda, quantidade × preço
 * unitário e o total. Com 42 colunas (o mesmo padrão das outras bobinas deste arquivo, e o padrão
 * clássico de térmica 80mm), cada caractere ganha ~1,6mm e a fonte sobe pra ~10px — legível.
 *
 * <p>O PDF acompanha a mesma tabela (é a mesma função de montagem) e pôde subir de ~5pt pra 8pt.
 */
const LARGURA_VENDA = 42
const COL_CODIGO = 13
/** Nome do produto na 1ª linha do item: o que sobra depois do código e do espaço separador. */
const COL_DESCRICAO = LARGURA_VENDA - COL_CODIGO - 1
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

/** Linha de cabeçalho tipo "Nº Venda...: valor" — rótulo já vem com os pontos, só concatena.
 *  Todos os rótulos têm 12 caracteres de propósito: é o que mantém os valores alinhados. */
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

/** Concatena descrição + cor (se tiver) + tamanho (se tiver) e quebra em blocos de
 *  `COL_DESCRICAO` caracteres — no máximo 3 linhas. Diferente da versão de 64 colunas, **não
 *  devolve mais linhas em branco de enchimento**: com o item já ocupando 2 linhas, cada linha
 *  vazia extra viraria papel desperdiçado em toda venda. */
function montarDescricaoEmLinhas(descricaoProduto: string, variacaoCor: string | null, variacaoTamanho: string | null): string[] {
  const texto = [descricaoProduto, variacaoCor, variacaoTamanho].filter(Boolean).join(' ')
  const linhas: string[] = []
  for (let i = 0; i < texto.length && linhas.length < 3; i += COL_DESCRICAO) {
    linhas.push(texto.slice(i, i + COL_DESCRICAO))
  }
  return linhas.length > 0 ? linhas : ['']
}

/** 2ª linha do item: `qtd x unitário` recuado sob o nome, e o total alinhado à direita da bobina.
 *  O recuo é o mesmo do nome (largura do código + separador), pra amarrar visualmente as duas
 *  linhas do mesmo item. */
function linhaValoresItem(qtd: string, unitario: string, total: string): string {
  const esquerda = ' '.repeat(COL_CODIGO + 1) + colDir(qtd, COL_QTD) + ' x ' + colDir(unitario, COL_UNITARIO)
  return colEsq(esquerda, LARGURA_VENDA - COL_TOTAL) + colDir(total, COL_TOTAL)
}

export function montarLinhasComprovanteVenda(c: ComprovanteVenda, reimpressao: boolean = false): string[] {
  const linhas: string[] = []
  linhas.push(linhaVenda())
  linhas.push(centralizarVenda('***  DOCUMENTO SEM VALOR FISCAL  ***'))
  linhas.push(linhaVenda())
  linhas.push(centralizarVenda(`${c.nomeEmpresa} (Loja ${c.codigoEmpresa})`))
  linhas.push(linhaVenda())
  if (reimpressao) {
    linhas.push(centralizarVenda('REIMPRESSÃO DE PAPELETA DE VENDA'))
    linhas.push(linhaVenda())
  }
  linhas.push(campoVenda('Nº Venda...:', String(c.idVenda)))
  linhas.push(campoVenda('Cliente....:', c.nomeCliente ?? '(não informado)'))
  linhas.push(campoVenda('Vendedor...:', c.nomeVendedor ?? '(não informado)'))
  linhas.push(campoVenda('Operador...:', c.nomeOperador ?? '(não informado)'))
  linhas.push(campoVenda('Data.......:', formatarDataHora(c.dataVenda)))
  linhas.push(linhaVenda())
  // Cabeçalho da tabela em 2 linhas, espelhando o formato do item (código+nome / valores).
  linhas.push(`${colEsq('CODIGO', COL_CODIGO)} ${colEsq('DESCRICAO DO PRODUTO', COL_DESCRICAO)}`)
  linhas.push(colEsq(' '.repeat(COL_CODIGO + 1) + 'QTD x UNITARIO', LARGURA_VENDA - COL_TOTAL) + colDir('TOTAL', COL_TOTAL))
  linhas.push(linhaVenda())

  c.itens.forEach((item) => {
    const [desc1, ...descRestante] = montarDescricaoEmLinhas(item.descricaoProduto, item.variacaoCor, item.variacaoTamanho)
    linhas.push(`${colEsq(item.sku, COL_CODIGO)} ${colEsq(desc1, COL_DESCRICAO)}`)
    descRestante.forEach((linhaDesc) => {
      linhas.push(' '.repeat(COL_CODIGO + 1) + colEsq(linhaDesc, COL_DESCRICAO))
    })
    linhas.push(linhaValoresItem(
      formatarQuantidadeSimples(item.qtd),
      formatarMoeda(item.valorUnitario),
      formatarMoeda(item.valorTotal),
    ))
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

  if (reimpressao) {
    linhas.push(campoVenda('Impresso em:', formatarDataHora(new Date().toISOString())))
    linhas.push(linhaVenda())
  }

  return linhas
}

/**
 * Monta o documento jsPDF da papeleta de venda — courier 8pt, a mesma dos outros comprovantes
 * desta bobina desde que a papeleta passou a 42 colunas (2026-08-14; antes era ~5pt pra espremer
 * 64 colunas em 80mm). Fonte única de verdade reusada tanto pra
 * baixar ({@link gerarPdfComprovanteVenda}) quanto pro Blob do compartilhamento por WhatsApp
 * ({@link gerarBlobComprovanteVenda}).
 */
function montarDocumentoComprovanteVenda(linhas: string[]): jsPDF {
  const margem = 4
  // 8pt (era 5pt): com a papeleta em 42 colunas (2026-08-14) cabe a mesma fonte dos outros
  // comprovantes desta bobina — 42 × ~1,7mm ≈ 71mm dentro dos 80mm menos as margens.
  const tamanhoFonte = 8
  const alturaLinha = 3.6
  const altura = Math.max(LARGURA_MM, margem * 2 + linhas.length * alturaLinha)

  const doc = new jsPDF({ unit: 'mm', format: [LARGURA_MM, altura] })
  doc.setFont('courier', 'normal')
  doc.setFontSize(tamanhoFonte)
  linhas.forEach((texto, indice) => {
    doc.text(texto, margem, margem + (indice + 1) * alturaLinha)
  })
  return doc
}

export function gerarPdfComprovanteVenda(linhas: string[], idVenda: number): void {
  montarDocumentoComprovanteVenda(linhas).save(`papeleta-venda-${idVenda}.pdf`)
}

/** Mesmo documento de {@link gerarPdfComprovanteVenda}, mas devolve o Blob em vez de baixar —
 *  usado pra subir a papeleta pro compartilhamento por link (envio por WhatsApp). */
export function gerarBlobComprovanteVenda(linhas: string[]): Blob {
  return montarDocumentoComprovanteVenda(linhas).output('blob')
}

/**
 * Vale-mercadoria emitido por uma devolução (2026-08-03) — desde 2026-08-07 usa a MESMA tabela
 * de itens (42 colunas, item em 2 linhas desde 2026-08-14) e as mesmas funções de
 * layout da papeleta de venda (`linhaVenda`/`centralizarVenda`/`colEsq`/`colDir`/`campoVenda`/
 * `linhaResumoVenda`/`montarDescricaoEmLinhas`) — pedido explícito do dono do produto pra
 * padronizar a impressão dos itens entre os dois comprovantes que saem na mesma bobina física
 * (antes o vale usava um layout de 42 colunas próprio, mais simples). O número do vale
 * (`idDevolucao`) é o dado mais importante da bobina — é o que o cliente apresenta depois pra
 * resgatar o crédito numa venda futura.
 */
export function montarLinhasComprovanteVale(d: DevolucaoEfetivada, nomeEmpresa: string): string[] {
  const linhas: string[] = []
  linhas.push(linhaVenda())
  linhas.push(centralizarVenda('VALE-MERCADORIA'))
  linhas.push(centralizarVenda('DEVOLUCAO DE PRODUTOS'))
  linhas.push(linhaVenda())
  linhas.push(centralizarVenda(nomeEmpresa))
  linhas.push(linhaVenda())
  linhas.push(campoVenda('Vale n°....:', String(d.idDevolucao)))
  if (d.nomeFuncionario) linhas.push(campoVenda('Vendedor...:', d.nomeFuncionario))
  linhas.push(campoVenda('Data.......:', formatarDataHora(d.dataMovimento)))
  linhas.push(linhaVenda())
  // Mesma tabela de 2 linhas por item da papeleta (2026-08-14) — os dois comprovantes saem na
  // mesma bobina física, então continuam compartilhando o layout.
  linhas.push(`${colEsq('CODIGO', COL_CODIGO)} ${colEsq('DESCRICAO DO PRODUTO', COL_DESCRICAO)}`)
  linhas.push(colEsq(' '.repeat(COL_CODIGO + 1) + 'QTD x UNITARIO', LARGURA_VENDA - COL_TOTAL) + colDir('TOTAL', COL_TOTAL))
  linhas.push(linhaVenda())

  d.itens.forEach((item) => {
    const [desc1, ...descRestante] = montarDescricaoEmLinhas(item.descricaoProduto, item.variacaoCor, item.variacaoTamanho)
    linhas.push(`${colEsq(item.sku, COL_CODIGO)} ${colEsq(desc1, COL_DESCRICAO)}`)
    descRestante.forEach((linhaDesc) => {
      linhas.push(' '.repeat(COL_CODIGO + 1) + colEsq(linhaDesc, COL_DESCRICAO))
    })
    linhas.push(linhaValoresItem(
      formatarQuantidadeSimples(item.qtd),
      formatarMoeda(item.precoVenda),
      formatarMoeda(item.valorTotal),
    ))
  })

  linhas.push(linhaVenda())
  linhas.push(linhaResumoVenda('VALOR DO VALE..:', formatarMoeda(d.valorVale)))
  linhas.push(linhaVenda())
  linhas.push(centralizarVenda('Apresente este vale para'))
  linhas.push(centralizarVenda('usar o credito numa compra futura.'))
  linhas.push(linhaVenda())

  return linhas
}

/** Mesmo documento de {@link montarDocumentoComprovanteVenda} — mesma largura, mesma fonte 8pt,
 *  mesma altura de linha. Fonte única de verdade reusada tanto pra baixar o arquivo
 *  ({@link gerarPdfComprovanteVale}) quanto pra gerar o Blob do compartilhamento por WhatsApp
 *  ({@link gerarBlobComprovanteVale}).
 *
 *  O texto do vale acompanhou a mudança pra 42 colunas em 2026-08-14, mas **este PDF ficou pra
 *  trás em 5pt/2,6mm** — o docstring afirmava "mesma largura/fonte" enquanto os números diziam
 *  outra coisa. Corrigido em 2026-08-14: 8pt/3,6mm, idêntico à papeleta e ao comprovante de
 *  crediário. */
function montarDocumentoComprovanteVale(linhas: string[]): jsPDF {
  const margem = 4
  const tamanhoFonte = 8
  const alturaLinha = 3.6
  const altura = Math.max(LARGURA_MM, margem * 2 + linhas.length * alturaLinha)

  const doc = new jsPDF({ unit: 'mm', format: [LARGURA_MM, altura] })
  doc.setFont('courier', 'normal')
  doc.setFontSize(tamanhoFonte)
  linhas.forEach((texto, indice) => {
    doc.text(texto, margem, margem + (indice + 1) * alturaLinha)
  })
  return doc
}

/** Mesmo mecanismo de {@link gerarPdfComprovanteVenda}, nome de arquivo próprio do vale. */
export function gerarPdfComprovanteVale(linhas: string[], idDevolucao: number): void {
  montarDocumentoComprovanteVale(linhas).save(`vale-mercadoria-${idDevolucao}.pdf`)
}

/** Mesmo documento de {@link gerarPdfComprovanteVale}, mas devolve o Blob em vez de baixar. */
export function gerarBlobComprovanteVale(linhas: string[]): Blob {
  return montarDocumentoComprovanteVale(linhas).output('blob')
}
