import { jsPDF } from 'jspdf'
import type { FechamentoCaixa } from './caixa'
import { formatarMoeda } from './masks'

/**
 * Largura em colunas do relatório de Fechamento de Caixa — folha A4 (diferente do Comprovante
 * de Pagamento de Crediário, 80mm, ver `comprovante.ts`), fonte courier monoespaçada pra manter
 * a pré-visualização, a impressão e o PDF idênticos (mesmo padrão).
 */
const LARGURA = 96

const LARGURAS_COLUNA_TABELA = [30, 16, 16, 16, 18]

function linha(caractere: string = '—'): string {
  return caractere.repeat(LARGURA)
}

function centralizar(texto: string): string {
  const t = texto.length > LARGURA ? texto.slice(0, LARGURA) : texto
  const espacos = LARGURA - t.length
  return ' '.repeat(Math.floor(espacos / 2)) + t
}

function duasColunas(esquerda: string, direita: string): string {
  const maxEsquerda = Math.max(0, LARGURA - direita.length - 1)
  const e = esquerda.length > maxEsquerda ? esquerda.slice(0, maxEsquerda) : esquerda
  const espacos = Math.max(1, LARGURA - e.length - direita.length)
  return e + ' '.repeat(espacos) + direita
}

/** Primeira coluna alinhada à esquerda, as demais à direita — usada pelo cabeçalho e pelas
 *  linhas da tabela de totais por carteira. */
function linhaTabela(valores: string[]): string {
  return valores
    .map((valor, indice) => {
      const largura = LARGURAS_COLUNA_TABELA[indice]
      const v = valor.length > largura ? valor.slice(0, largura) : valor
      return indice === 0 ? v.padEnd(largura) : v.padStart(largura)
    })
    .join(' ')
}

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarDataHora(iso: string | null): string {
  if (!iso) return '(em aberto)'
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * Monta o relatório de fechamento como linhas de texto monoespaçado (96 colunas, A4) — fonte
 * única de verdade reusada pela pré-visualização, pela impressão e pelo PDF, mesmo padrão de
 * `montarLinhasComprovante`. `valorContadoDinheiro`/`diferenca` só aparecem quando informados
 * (tela ainda não fechou o caixa) ou quando o caixa já está fechado (valor gravado).
 */
export function montarLinhasFechamento(
  f: FechamentoCaixa,
  valorContadoDinheiro: number | null,
  linhaDinheiro: { nomeCarteira: string; valorEsperado: number } | null,
): string[] {
  const linhas: string[] = []
  linhas.push(linha())
  linhas.push(centralizar(f.nomeEmpresa))
  linhas.push(linha())
  linhas.push(centralizar('FECHAMENTO DE CAIXA'))
  linhas.push(linha())
  linhas.push(`Usuário: ${f.nomeUsuario}`)
  linhas.push(`Empresa: ${f.nomeEmpresa}`)
  linhas.push(`Abertura:   ${formatarDataHora(f.dataAbertura)}`)
  linhas.push(`Fechamento: ${formatarDataHora(f.dataFechamento)}`)
  linhas.push(linha())
  linhas.push(linhaTabela(['CARTEIRA', 'SALDO INIC.', 'CRÉDITO', 'DÉBITO', 'ESPERADO']))
  linhas.push(linha('•'))
  f.linhas.forEach((l) => {
    linhas.push(
      linhaTabela([l.nomeCarteira, moeda(l.saldoInicial), moeda(l.totalCredito), moeda(l.totalDebito), moeda(l.valorEsperado)]),
    )
  })
  linhas.push(linha())

  if (valorContadoDinheiro !== null && linhaDinheiro) {
    const diferenca = valorContadoDinheiro - linhaDinheiro.valorEsperado
    linhas.push(duasColunas(`Contado em Dinheiro (${linhaDinheiro.nomeCarteira}):`, moeda(valorContadoDinheiro)))
    linhas.push(duasColunas('Diferença:', moeda(diferenca)))
    linhas.push(linha())
  }

  return linhas
}

/**
 * Gera o PDF direto (sem diálogo de impressão), folha A4, fonte courier monoespaçada pra
 * alinhar exatamente igual à pré-visualização — mesmo padrão de `gerarPdfComprovante`.
 */
export function gerarPdfFechamento(linhas: string[], idCaixa: number): void {
  const margem = 15
  const tamanhoFonte = 9
  const alturaLinha = 4.6

  const doc = new jsPDF({ unit: 'mm', format: 'a4' })
  doc.setFont('courier', 'normal')
  doc.setFontSize(tamanhoFonte)
  linhas.forEach((texto, indice) => {
    doc.text(texto, margem, margem + (indice + 1) * alturaLinha)
  })
  doc.save(`fechamento-caixa-${idCaixa}.pdf`)
}
