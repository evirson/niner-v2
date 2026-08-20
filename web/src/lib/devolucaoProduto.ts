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

/** Item vendido na venda de origem, com quanto ainda pode ser devolvido dele (`qtdVendida` menos
 *  o que já foi devolvido em devoluções não canceladas da mesma venda) — quando a venda é
 *  informada, a tela só aceita produtos presentes nesta lista, até `qtdDisponivelDevolucao`. */
export interface ItemVendaOrigem {
  idVariacao: number
  sku: string
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtdVendida: number
  qtdDisponivelDevolucao: number
  /** Preço que o cliente PAGOU nesta venda (não o do cadastro atual, que pode ter mudado desde
   *  então) — é ele que a grid de seleção mostra e que o vale-mercadoria usa. */
  precoUnitario: number
  /** `qtdVendida × precoUnitario` — o total daquele item na venda original. */
  valorTotal: number
}

export interface VendedorDaVenda {
  numeroVenda: number
  idFuncionario: number | null
  nomeFuncionario: string | null
  itens: ItemVendaOrigem[]
}

export interface ItemDevolucaoResponse {
  idVariacao: number
  sku: string
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtd: number
  precoVenda: number
  valorTotal: number
}

/**
 * Desfecho da NF-e de devolução (modelo 55, entrada) emitida junto — §10.2, B9.
 *
 * ⚠️ Situação diferente de `AUTORIZADO` **não desfaz a devolução**: a mercadoria já voltou ao
 * estoque e o vale já é do cliente (F3 — fiscal nunca bloqueia a operação de balcão). A nota fica
 * em Documentos Fiscais para ser reprocessada.
 */
export interface NotaFiscalDevolucao {
  situacao: 'AUTORIZADO' | 'REJEITADO' | 'DENEGADO' | 'EM_PROCESSAMENTO' | 'FALHA_COMUNICACAO'
  idDocumentoFiscal: number
  chaveAcesso: string | null
  protocolo: string | null
  cStat: string | null
  mensagem: string
}

/** `idDevolucao` é o número do vale-mercadoria gerado (toda devolução gera um, 2026-08-03);
 *  `valorVale` é a soma dos itens devolvidos. `notaFiscal` é `null` quando não havia nota a
 *  emitir (fiscal desligado, devolução sem venda de origem, ou venda sem NFC-e autorizada). */
export interface DevolucaoEfetivada {
  idMovimento: number
  idDevolucao: number
  valorVale: number
  dataMovimento: string
  idFuncionario: number | null
  nomeFuncionario: string | null
  itens: ItemDevolucaoResponse[]
  notaFiscal: NotaFiscalDevolucao | null
}

export interface ValeMercadoria {
  idDevolucao: number
  valorVale: number
  valeUsado: boolean
  cancelada: boolean
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
