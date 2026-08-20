import { api } from './api'

/** Entrada que pode gerar devolução ao fornecedor — a listagem já vem filtrada pelo backend
 *  (só COMPRA não cancelada, com XML arquivado e tributação por item gravada). */
export interface EntradaElegivel {
  idMovimento: number
  dataMovimento: string
  idEmpresa: number
  nomeEmpresa: string | null
  idFornecedor: number | null
  nomeFornecedor: string | null
  cnpjFornecedor: string | null
  notaFiscal: number | null
  serieNota: number | null
  chaveNfe: string | null
  valorTotal: number
  qtdItens: number
  temDevolucao: boolean
}

export interface PaginaEntradasElegiveis {
  itens: EntradaElegivel[]
  pagina: number
  limite: number
  total: number
}

/**
 * Item devolvível. `qtdMaxima` é o menor entre `qtdSaldo` (o que a nota trouxe menos o que já
 * voltou) e `qtdEstoque` (o que existe hoje) — é ele que a tela usa como teto, e os outros dois
 * aparecem na grid para o operador entender QUAL dos limites está pesando.
 */
export interface ItemDevolvivel {
  idVariacao: number
  sku: string
  descricao: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  codigoFornecedor: string | null
  cfopEntrada: string | null
  qtdComprada: number
  qtdDevolvida: number
  qtdSaldo: number
  qtdEstoque: number
  qtdMaxima: number
  valorUnitario: number | null
}

export interface NotaFiscalDevolucaoCompra {
  situacao: string
  idDocumentoFiscal: number
  chaveAcesso: string | null
  protocolo: string | null
  cStat: string | null
  mensagem: string
}

export interface DevolucaoCompraEfetivada {
  idMovimento: number
  idMovimentoOrigem: number
  dataMovimento: string
  idEmpresa: number
  idFornecedor: number | null
  nomeFornecedor: string | null
  notaFiscalOrigem: number | null
  valorTotal: number
  itens: Array<{
    idVariacao: number
    sku: string
    descricao: string
    qtd: number
    valorUnitario: number | null
    valorTotal: number
  }>
  /** Nula quando o fiscal está desligado para a empresa — a devolução vale do mesmo jeito. */
  nota: NotaFiscalDevolucaoCompra | null
}

export interface FiltrosEntradasElegiveis {
  idFornecedor?: number
  idEmpresa?: number
  notaFiscal?: number
  dataInicial?: string
  dataFinal?: string
  pagina?: number
  limite?: number
}

export function listarEntradasElegiveis(f: FiltrosEntradasElegiveis): Promise<PaginaEntradasElegiveis> {
  const params = new URLSearchParams()
  if (f.idFornecedor) params.set('idFornecedor', String(f.idFornecedor))
  if (f.idEmpresa) params.set('idEmpresa', String(f.idEmpresa))
  if (f.notaFiscal) params.set('notaFiscal', String(f.notaFiscal))
  if (f.dataInicial) params.set('dataInicial', f.dataInicial)
  if (f.dataFinal) params.set('dataFinal', f.dataFinal)
  if (f.pagina) params.set('pagina', String(f.pagina))
  if (f.limite) params.set('limite', String(f.limite))
  const query = params.toString()
  return api<PaginaEntradasElegiveis>(`/api/v1/estoque/devolucao-compra/entradas${query ? `?${query}` : ''}`)
}

export function listarItensDevolviveis(idMovimento: number): Promise<ItemDevolvivel[]> {
  return api<ItemDevolvivel[]>(`/api/v1/estoque/devolucao-compra/entradas/${idMovimento}/itens`)
}

export function efetivarDevolucaoCompra(payload: {
  idMovimentoOrigem: number
  itens: Array<{ idVariacao: number; qtd: number }>
  observacao?: string | null
}): Promise<DevolucaoCompraEfetivada> {
  return api<DevolucaoCompraEfetivada>('/api/v1/estoque/devolucao-compra', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function cancelarDevolucaoCompra(
  idMovimento: number,
  motivo: string,
): Promise<{ idMovimento: number; dataCancelamento: string; motivo: string; protocoloCancelamentoNota: string | null }> {
  return api(`/api/v1/estoque/devolucao-compra/${idMovimento}/cancelar`, {
    method: 'POST',
    body: JSON.stringify({ motivo }),
  })
}
