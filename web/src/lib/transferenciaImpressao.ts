import { jsPDF } from 'jspdf'
import type { Transferencia } from './transferencias'
import { formatarQuantidade } from './masks'

/**
 * Guia de Transferência de Produtos (2026-08-06) — folha A4, mesmo padrão do Fechamento de
 * Caixa (`fechamentoCaixaImpressao.ts`): texto monoespaçado (96 colunas) reusado idêntico pela
 * pré-visualização, pela impressão (`window.print`) e pelo PDF (jsPDF fonte courier). Documento
 * pensado pra viajar com a mercadoria — por isso termina com linhas de assinatura (conferido na
 * origem / recebido no destino), sem equivalente em nenhum outro comprovante deste projeto.
 */
const LARGURA = 96
const COL_CODIGO = 15
const COL_DESCRICAO = 46
const COL_VARIACAO = 16
const COL_QTD = 10

function linha(caractere: string = '—'): string {
  return caractere.repeat(LARGURA)
}

function centralizar(texto: string): string {
  const t = texto.length > LARGURA ? texto.slice(0, LARGURA) : texto
  const espacos = LARGURA - t.length
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

function campo(rotulo: string, valor: string): string {
  return `${rotulo} ${valor}`
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

export function montarLinhasGuiaTransferencia(t: Transferencia, permiteQtdDecimal: boolean): string[] {
  const linhas: string[] = []
  linhas.push(linha())
  linhas.push(centralizar(t.empresaOrigem.nomeEmpresa))
  linhas.push(linha())
  linhas.push(centralizar(`GUIA DE TRANSFERÊNCIA Nº ${t.idTransferencia}`))
  linhas.push(linha())
  linhas.push(campo('Origem.....:', t.empresaOrigem.nomeEmpresa))
  linhas.push(campo('Destino....:', t.empresaDestino.nomeEmpresa))
  linhas.push(campo('Data.......:', formatarDataHora(t.dataTransferencia)))
  linhas.push(campo('Usuário....:', t.nomeUsuario))
  if (t.observacoes) linhas.push(campo('Observações:', t.observacoes))
  linhas.push(linha())
  linhas.push(
    [
      colEsq('CÓDIGO', COL_CODIGO),
      colEsq('DESCRIÇÃO', COL_DESCRICAO),
      colEsq('VARIAÇÃO', COL_VARIACAO),
      colDir('QTD', COL_QTD),
    ].join(' '),
  )
  linhas.push(linha())
  t.itens.forEach((item) => {
    const variacao = [item.variacaoCor, item.variacaoTamanho].filter(Boolean).join(' · ')
    linhas.push(
      [
        colEsq(item.sku, COL_CODIGO),
        colEsq(item.descricaoProduto, COL_DESCRICAO),
        colEsq(variacao, COL_VARIACAO),
        colDir(formatarQuantidade(item.qtd, permiteQtdDecimal), COL_QTD),
      ].join(' '),
    )
  })
  linhas.push(linha())
  linhas.push('')
  linhas.push('')
  linhas.push(`${'_'.repeat(34)}          ${'_'.repeat(34)}`)
  linhas.push(`${colEsq('Conferido por (Origem)', 34)}          ${colEsq('Recebido por (Destino)', 34)}`)

  return linhas
}

/**
 * Gera o PDF direto (sem diálogo de impressão), folha A4, fonte courier monoespaçada pra
 * alinhar exatamente igual à pré-visualização — mesmo padrão de `gerarPdfFechamento`.
 */
export function gerarPdfGuiaTransferencia(linhas: string[], idTransferencia: number): void {
  const margem = 15
  const tamanhoFonte = 9
  const alturaLinha = 4.6

  const doc = new jsPDF({ unit: 'mm', format: 'a4' })
  doc.setFont('courier', 'normal')
  doc.setFontSize(tamanhoFonte)
  // ⛔ PAGINA (achado de auditoria, 2026-08-29). Sem isto o `forEach` desenhava em
  // `y = 15 + (i+1) * 4.6` sem limite: a partir de ~44 itens o texto ia para fora da A4 e o jsPDF
  // simplesmente NÃO o renderizava — sem erro nenhum. Uma transferência de 60 SKUs gerava um PDF
  // que terminava no meio da lista, **sem as linhas de "Conferido por / Recebido por"**, que é
  // justamente o que a guia existe para colher. O botão "Imprimir" ao lado sempre saiu certo (o
  // `.documento-a4-imprimir` pagina no navegador), então os dois caminhos discordavam.
  // ⚠️ É o irmão exato do defeito do `position: absolute` de 2026-08-22, no caminho do jsPDF.
  const alturaPagina = doc.internal.pageSize.getHeight()
  const ultimaLinhaUtil = alturaPagina - margem
  let y = margem + alturaLinha
  for (const texto of linhas) {
    if (y > ultimaLinhaUtil) {
      doc.addPage()
      // `addPage` reseta fonte e corpo — redeclarar é obrigatório, senão a página 2 sai
      // proporcional e desalinha as colunas que a monoespaçada garante.
      doc.setFont('courier', 'normal')
      doc.setFontSize(tamanhoFonte)
      y = margem + alturaLinha
    }
    doc.text(texto, margem, y)
    y += alturaLinha
  }
  doc.save(`guia-transferencia-${idTransferencia}.pdf`)
}
