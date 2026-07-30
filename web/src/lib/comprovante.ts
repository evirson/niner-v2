import { jsPDF } from 'jspdf'
import type { ComprovanteRecebimento } from './recebimentoCrediario'
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
