import { api } from './api'
import { dataParaIso, desmascararMoeda, formatarMoeda, isoParaData } from './masks'
import { maiusculas } from './texto'

export interface ContaPagar {
  idContaPagar: number
  idFornecedor: number
  nomeFornecedor: string
  idEmpresa: number
  nomeEmpresa: string
  idPlanoContas: string
  descricaoPlanoContas: string
  notaFiscal: number | null
  numeroDuplicata: string | null
  dataLancamento: string
  dataVencimento: string
  dataPagamento: string | null
  valorPagar: number
  valorPago: number
  documentoPago: boolean
  observacoes: string | null
  idMovimento: number | null
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — strings para casar com inputs controlados (padrão do projeto). */
export interface ContaPagarFormState {
  idFornecedor: number | ''
  nomeFornecedorEscolhido: string
  idEmpresa: number | ''
  idPlanoContas: string
  notaFiscalTexto: string
  numeroDuplicata: string
  dataLancamentoTexto: string
  dataVencimentoTexto: string
  dataPagamentoTexto: string
  valorPagarTexto: string
  valorPagoTexto: string
  documentoPago: boolean
  observacoes: string
}

export function contaPagarVazia(hojeTexto: string): ContaPagarFormState {
  return {
    idFornecedor: '',
    nomeFornecedorEscolhido: '',
    idEmpresa: '',
    idPlanoContas: '',
    notaFiscalTexto: '',
    numeroDuplicata: '',
    dataLancamentoTexto: hojeTexto,
    dataVencimentoTexto: '',
    dataPagamentoTexto: '',
    valorPagarTexto: '0,00',
    valorPagoTexto: '0,00',
    documentoPago: false,
    observacoes: '',
  }
}

export function paraFormulario(c: ContaPagar): ContaPagarFormState {
  return {
    idFornecedor: c.idFornecedor,
    nomeFornecedorEscolhido: c.nomeFornecedor,
    idEmpresa: c.idEmpresa,
    idPlanoContas: c.idPlanoContas,
    notaFiscalTexto: c.notaFiscal != null ? String(c.notaFiscal) : '',
    numeroDuplicata: c.numeroDuplicata ?? '',
    dataLancamentoTexto: isoParaData(c.dataLancamento),
    dataVencimentoTexto: isoParaData(c.dataVencimento),
    dataPagamentoTexto: isoParaData(c.dataPagamento),
    valorPagarTexto: formatarMoeda(c.valorPagar),
    valorPagoTexto: formatarMoeda(c.valorPago),
    documentoPago: c.documentoPago,
    observacoes: c.observacoes ?? '',
  }
}

/** Datas viram meio-dia UTC (não meia-noite) antes de ir pro servidor — evita o fuso virar o
 *  dia ao converter de volta pra exibição (mesmo princípio já usado em
 *  `contaCorrenteMovimento.ts` e corrigido em `EntradaMercadoriaService`, 2026-08-19). Campos já
 *  validados (dd/mm/aaaa) antes de chamar esta função. */
export function paraRequisicao(f: ContaPagarFormState) {
  return {
    idFornecedor: f.idFornecedor === '' ? null : f.idFornecedor,
    idEmpresa: f.idEmpresa === '' ? null : f.idEmpresa,
    idPlanoContas: f.idPlanoContas,
    notaFiscal: f.notaFiscalTexto.trim() ? Number(f.notaFiscalTexto.trim()) : null,
    numeroDuplicata: f.numeroDuplicata.trim() ? maiusculas(f.numeroDuplicata.trim()) : null,
    dataLancamento: `${dataParaIso(f.dataLancamentoTexto)}T12:00:00Z`,
    dataVencimento: `${dataParaIso(f.dataVencimentoTexto)}T12:00:00Z`,
    dataPagamento: f.dataPagamentoTexto.trim() ? `${dataParaIso(f.dataPagamentoTexto)}T12:00:00Z` : null,
    valorPagar: desmascararMoeda(f.valorPagarTexto),
    valorPago: desmascararMoeda(f.valorPagoTexto),
    documentoPago: f.documentoPago,
    observacoes: f.observacoes.trim() ? maiusculas(f.observacoes.trim()) : null,
  }
}

export interface PaginaContasPagar {
  itens: ContaPagar[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoContaPagar {
  acao: 'excluido'
}

export type ColunaOrdenacaoContaPagar =
  | 'dataVencimento'
  | 'dataPagamento'
  | 'fornecedor'
  | 'empresa'
  | 'notaFiscal'
  | 'valorPagar'
  | 'valorPago'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosContasPagar {
  idFornecedor?: number
  idEmpresa?: number
  notaFiscal?: number
  numeroDuplicata?: string
  /** ISO (aaaa-mm-dd) — converter com {@link dataParaIso} antes de passar aqui. */
  dataVencimentoInicial?: string
  dataVencimentoFinal?: string
  dataPagamentoInicial?: string
  dataPagamentoFinal?: string
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoContaPagar
  direcao?: DirecaoOrdenacao
}

export function listarContasPagar(filtros: FiltrosContasPagar): Promise<PaginaContasPagar> {
  const params = new URLSearchParams()
  if (filtros.idFornecedor) params.set('idFornecedor', String(filtros.idFornecedor))
  if (filtros.idEmpresa) params.set('idEmpresa', String(filtros.idEmpresa))
  if (filtros.notaFiscal) params.set('notaFiscal', String(filtros.notaFiscal))
  if (filtros.numeroDuplicata) params.set('numeroDuplicata', filtros.numeroDuplicata)
  if (filtros.dataVencimentoInicial) params.set('dataVencimentoInicial', filtros.dataVencimentoInicial)
  if (filtros.dataVencimentoFinal) params.set('dataVencimentoFinal', filtros.dataVencimentoFinal)
  if (filtros.dataPagamentoInicial) params.set('dataPagamentoInicial', filtros.dataPagamentoInicial)
  if (filtros.dataPagamentoFinal) params.set('dataPagamentoFinal', filtros.dataPagamentoFinal)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaContasPagar>(`/api/v1/contas-pagar${query ? `?${query}` : ''}`)
}

export function buscarContaPagar(idContaPagar: number): Promise<ContaPagar> {
  return api<ContaPagar>(`/api/v1/contas-pagar/${idContaPagar}`)
}

export function criarContaPagar(payload: ReturnType<typeof paraRequisicao>): Promise<ContaPagar> {
  return api<ContaPagar>('/api/v1/contas-pagar', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarContaPagar(idContaPagar: number, payload: ReturnType<typeof paraRequisicao>): Promise<ContaPagar> {
  return api<ContaPagar>(`/api/v1/contas-pagar/${idContaPagar}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function excluirContaPagar(idContaPagar: number): Promise<ExclusaoContaPagar> {
  return api<ExclusaoContaPagar>(`/api/v1/contas-pagar/${idContaPagar}`, { method: 'DELETE' })
}
