import { api } from './api'

/** Os cinco estados. ⚠️ Só `ABERTO` não é final — ver docs/telas/orcamento.md, R5. */
export type SituacaoOrcamento = 'ABERTO' | 'VENDIDO' | 'VENDIDO_PARCIAL' | 'CANCELADO' | 'VENCIDO'

export const ROTULO_SITUACAO: Record<SituacaoOrcamento, string> = {
  ABERTO: 'Aberto',
  VENDIDO: 'Vendido',
  VENDIDO_PARCIAL: 'Vendido em parte',
  CANCELADO: 'Cancelado',
  VENCIDO: 'Vencido',
}

/** Classe do selo na grade — só `ABERTO` é "ativo"; o resto é estado final. */
export const CLASSE_SITUACAO: Record<SituacaoOrcamento, string> = {
  ABERTO: 'badge',
  VENDIDO: 'badge',
  VENDIDO_PARCIAL: 'badge',
  CANCELADO: 'badge badge-inativo',
  VENCIDO: 'badge badge-inativo',
}

export interface ItemOrcamento {
  idOrcamentoItem: number
  idVariacao: number
  sku: string
  descricao: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtd: number
  /** Preço CONGELADO na emissão — é ele que a venda usa. */
  precoVenda: number
  valorTotal: number
  /** Preço do cadastro hoje. Diferente de `precoVenda` = o produto mudou de preço depois. */
  precoAtual: number
  /** ⚠️ Produto inativado depois da emissão: não pode ser vendido (R7). */
  produtoInativo: boolean
  qtdEstoque: number
}

export interface Orcamento {
  idOrcamento: number
  dataOrcamento: string
  dataValidade: string
  situacao: SituacaoOrcamento
  idEmpresa: number
  nomeEmpresa: string
  idCliente: number
  nomeCliente: string
  documentoCliente: string | null
  telefoneCliente: string | null
  idFuncionario: number
  nomeFuncionario: string
  nomeUsuario: string
  observacao: string | null
  subtotal: number
  valorDesconto: number
  valorTotal: number
  idVenda: number | null
  dataEfetivacao: string | null
  dataCancelamento: string | null
  nomeUsuarioCancelamento: string | null
  motivoCancelamento: string | null
  itens: ItemOrcamento[]
}

export interface OrcamentoResumo {
  idOrcamento: number
  dataOrcamento: string
  dataValidade: string
  situacao: SituacaoOrcamento
  nomeCliente: string
  nomeFuncionario: string
  valorTotal: number
  idVenda: number | null
  qtdItens: number
}

export interface PaginaOrcamentos {
  itens: OrcamentoResumo[]
  pagina: number
  limite: number
  total: number
}

export interface FiltrosOrcamento {
  dataInicial?: string
  dataFinal?: string
  idCliente?: number
  idFuncionario?: number
  situacao?: SituacaoOrcamento | ''
  pagina?: number
  limite?: number
}

export function listarOrcamentos(f: FiltrosOrcamento): Promise<PaginaOrcamentos> {
  const params = new URLSearchParams()
  if (f.dataInicial) params.set('dataInicial', f.dataInicial)
  if (f.dataFinal) params.set('dataFinal', f.dataFinal)
  if (f.idCliente) params.set('idCliente', String(f.idCliente))
  if (f.idFuncionario) params.set('idFuncionario', String(f.idFuncionario))
  if (f.situacao) params.set('situacao', f.situacao)
  if (f.pagina) params.set('pagina', String(f.pagina))
  if (f.limite) params.set('limite', String(f.limite))
  const query = params.toString()
  return api<PaginaOrcamentos>(`/api/v1/orcamentos${query ? `?${query}` : ''}`)
}

/** ⚠️ Consultar um orçamento vencido o marca como VENCIDO na hora (R6) — não é um GET puro. */
export function buscarOrcamento(idOrcamento: number): Promise<Orcamento> {
  return api<Orcamento>(`/api/v1/orcamentos/${idOrcamento}`)
}

export function emitirOrcamento(payload: {
  idCliente: number
  idFuncionario: number
  dataValidade?: string | null
  valorDesconto?: number
  observacao?: string | null
  /** ⚠️ Sem preço: o servidor resolve pelo `idVariacao` e congela. A tela nunca manda preço. */
  itens: Array<{ idVariacao: number; qtd: number }>
}): Promise<Orcamento> {
  return api<Orcamento>('/api/v1/orcamentos', { method: 'POST', body: JSON.stringify(payload) })
}

export function cancelarOrcamento(idOrcamento: number, motivo: string): Promise<unknown> {
  return api(`/api/v1/orcamentos/${idOrcamento}/cancelar`, {
    method: 'POST',
    body: JSON.stringify({ motivo }),
  })
}

/** Dias de validade sugeridos (Parâmetros do Sistema). Endpoint leve — aberto a OPERADOR. */
export function buscarDiasValidadeOrcamento(): Promise<{ cfgDiasValidadeOrcamento: number }> {
  return api<{ cfgDiasValidadeOrcamento: number }>('/api/v1/config-geral/dias-validade-orcamento')
}
