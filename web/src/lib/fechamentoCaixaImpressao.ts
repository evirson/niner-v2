import { rotuloCarteira, type FechamentoCaixa } from './caixa'
import { formatarMoeda } from './masks'

/**
 * Impressão do Fechamento de Caixa (2026-08-19) — convertida de folha A4/96 colunas pra bobina
 * térmica 80mm/42 colunas, "mesmo formato e dimensões da venda" (pedido explícito do dono do
 * produto): mesma largura, mesmos separadores e o mesmo mecanismo de duas colunas (rótulo à
 * esquerda, valor à direita) da papeleta de venda (`comprovante.ts`).
 */
const LARGURA = 42

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
 * Monta o relatório de fechamento como linhas de texto monoespaçado (42 colunas, 80mm) — fonte
 * única de verdade reusada pela pré-visualização e pela impressão, mesmo padrão de
 * `montarLinhasComprovante`. Cada carteira sai em um bloco de 3 linhas (saldo inicial/crédito/
 * débito) seguido do esperado, igual ao bloco de parcela do comprovante de crediário — uma tabela
 * larga não caberia nas 42 colunas. A conferência (esperado/contado/diferença) só existe depois
 * que o caixa fecha de verdade, então `f.conferencia` sempre vem preenchida aqui.
 */
export function montarLinhasFechamento(f: FechamentoCaixa): string[] {
  const linhas: string[] = []
  linhas.push(linha())
  linhas.push(centralizar(f.nomeEmpresa))
  linhas.push(linha())
  linhas.push(centralizar('FECHAMENTO DE CAIXA'))
  linhas.push(linha())
  linhas.push(`Usuario: ${f.nomeUsuario}`)
  linhas.push(`Abertura:   ${formatarDataHora(f.dataAbertura)}`)
  linhas.push(`Fechamento: ${formatarDataHora(f.dataFechamento)}`)
  linhas.push(linha())

  f.linhas.forEach((l, indice) => {
    linhas.push(rotuloCarteira(l))
    linhas.push(duasColunas('  Saldo Inicial:', moeda(l.saldoInicial)))
    linhas.push(duasColunas('  Credito:', moeda(l.totalCredito)))
    linhas.push(duasColunas('  Debito:', moeda(l.totalDebito)))
    linhas.push(duasColunas('  Esperado:', moeda(l.valorEsperado)))
    if (indice < f.linhas.length - 1) linhas.push(linha('•'))
  })
  linhas.push(linha())

  if (f.conferencia.length > 0) {
    linhas.push(centralizar('CONFERENCIA'))
    linhas.push(linha())
    f.conferencia.forEach((c, indice) => {
      linhas.push(rotuloCarteira(c))
      linhas.push(duasColunas('  Esperado:', moeda(c.valorEsperado)))
      linhas.push(duasColunas('  Contado:', moeda(c.valorContado)))
      linhas.push(duasColunas('  Diferenca:', moeda(c.diferenca)))
      if (indice < f.conferencia.length - 1) linhas.push(linha('•'))
    })
    linhas.push(linha())
  }

  linhas.push(`Impresso em: ${formatarDataHora(new Date().toISOString())}`)
  linhas.push(linha())

  return linhas
}
