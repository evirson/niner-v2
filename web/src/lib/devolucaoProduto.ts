import { api } from './api'

export interface ItemDevolucaoRequest {
  idVariacao: number
  qtd: number
}

/** `numeroVenda` é opcional — só usado para resolver o vendedor (comissão futura). */
export interface EfetivarDevolucaoRequest {
  numeroVenda: number | null
  itens: ItemDevolucaoRequest[]
}

export interface VendedorDaVenda {
  numeroVenda: number
  idFuncionario: number | null
  nomeFuncionario: string | null
}

export interface ItemDevolucaoResponse {
  idVariacao: number
  descricaoProduto: string
  variacaoLinha: string | null
  variacaoColuna: string | null
  qtd: number
  precoVenda: number
}

/** `idDevolucao` é o número do vale-mercadoria gerado (toda devolução gera um, 2026-08-03);
 *  `valorVale` é a soma dos itens devolvidos. */
export interface DevolucaoEfetivada {
  idMovimento: number
  idDevolucao: number
  valorVale: number
  dataMovimento: string
  idFuncionario: number | null
  nomeFuncionario: string | null
  itens: ItemDevolucaoResponse[]
}

export interface ValeMercadoria {
  idDevolucao: number
  valorVale: number
  valeUsado: boolean
  dataDevolucao: string
  idVendaCredito: number | null
  idVendaDebito: number | null
}

export function buscarVendedorDaVenda(numeroVenda: number): Promise<VendedorDaVenda> {
  return api<VendedorDaVenda>(`/api/v1/vendas/devolucao/vendedor?numeroVenda=${numeroVenda}`)
}

export function efetivarDevolucao(payload: EfetivarDevolucaoRequest): Promise<DevolucaoEfetivada> {
  return api<DevolucaoEfetivada>('/api/v1/vendas/devolucao', { method: 'POST', body: JSON.stringify(payload) })
}

/** Consulta um vale-mercadoria pelo número — usada tanto pra reimprimir quanto pelo PDV na hora
 *  de resgatar (FormaPagamentoModal, categoria VALE_MERCADORIA). */
export function buscarVale(idDevolucao: number): Promise<ValeMercadoria> {
  return api<ValeMercadoria>(`/api/v1/vendas/devolucao/vale/${idDevolucao}`)
}
