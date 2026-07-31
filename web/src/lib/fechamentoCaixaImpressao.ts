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
const LARGURAS_COLUNA_CONFERENCIA = [30, 20, 20, 20]

function linha(caractere: string = '—'): string {
  return caractere.repeat(LARGURA)
}

function centralizar(texto: string): string {
  const t = texto.length > LARGURA ? texto.slice(0, LARGURA) : texto
  const espacos = LARGURA - t.length
  return ' '.repeat(Math.floor(espacos / 2)) + t
}

/** Primeira coluna alinhada à esquerda, as demais à direita — usada pelo cabeçalho e pelas
 *  linhas das tabelas de totais/conferência por carteira. */
function linhaTabela(valores: string[], larguras: number[]): string {
  return valores
    .map((valor, indice) => {
      const largura = larguras[indice]
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
 * `montarLinhasComprovante`. A tabela de conferência (esperado/contado/diferença por carteira,
 * "às cegas" — 2026-07-30) só existe depois que o caixa fecha de verdade (todas as carteiras
 * bateram); a impressão só fica disponível nesse ponto, então `f.conferencia` sempre vem
 * preenchida aqui.
 */
export function montarLinhasFechamento(f: FechamentoCaixa): string[] {
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
  linhas.push(linhaTabela(['CARTEIRA', 'SALDO INIC.', 'CRÉDITO', 'DÉBITO', 'ESPERADO'], LARGURAS_COLUNA_TABELA))
  linhas.push(linha('•'))
  f.linhas.forEach((l) => {
    linhas.push(
      linhaTabela(
        [l.nomeCarteira, moeda(l.saldoInicial), moeda(l.totalCredito), moeda(l.totalDebito), moeda(l.valorEsperado)],
        LARGURAS_COLUNA_TABELA,
      ),
    )
  })
  linhas.push(linha())

  if (f.conferencia.length > 0) {
    linhas.push(centralizar('CONFERÊNCIA (CONTAGEM ÀS CEGAS)'))
    linhas.push(linha())
    linhas.push(linhaTabela(['CARTEIRA', 'ESPERADO', 'CONTADO', 'DIFERENÇA'], LARGURAS_COLUNA_CONFERENCIA))
    linhas.push(linha('•'))
    f.conferencia.forEach((c) => {
      linhas.push(
        linhaTabela([c.nomeCarteira, moeda(c.valorEsperado), moeda(c.valorContado), moeda(c.diferenca)], LARGURAS_COLUNA_CONFERENCIA),
      )
    })
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
