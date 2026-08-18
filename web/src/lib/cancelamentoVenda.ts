import { api } from './api'

// A busca/listagem para cancelamento (`listarVendasParaCancelamento`) saiu daqui em 2026-08-18 —
// a tela de Cancelamento de Venda foi migrada pro popup de detalhe da Pesquisa de Vendas, que já
// tem sua própria busca (`lib/pesquisaVendas.ts`). Só sobra o que o modal de confirmação usa.

export interface ItemVendaDetalhe {
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtd: number
  precoVenda: number
  valorItem: number
}

export interface PagamentoVendaDetalhe {
  idCarteira: number
  nomeCarteira: string
  categoriaCarteira: string
  valorPago: number
  quitado: boolean
}

export interface ParcelaRecebidaDetalhe {
  numeroParcela: number
  dataVencimento: string
  dataRecebimento: string
  valorRecebido: number
}

export interface VendaDetalheCancelamento {
  idVenda: number
  idEmpresa: number
  nomeEmpresa: string
  dataVenda: string
  idCliente: number | null
  nomeCliente: string | null
  idFuncionario: number | null
  nomeFuncionario: string | null
  valorVenda: number
  cancelada: boolean
  dataCancelamento: string | null
  nomeUsuarioCancelamento: string | null
  motivoCancelamento: string | null
  itens: ItemVendaDetalhe[]
  pagamentos: PagamentoVendaDetalhe[]
  bloqueadaCredario: boolean
  parcelasRecebidas: ParcelaRecebidaDetalhe[]
}

export function buscarDetalheParaCancelamento(idVenda: number): Promise<VendaDetalheCancelamento> {
  return api<VendaDetalheCancelamento>(`/api/v1/vendas/cancelamento/${idVenda}`)
}

export interface CancelamentoEfetivado {
  idVenda: number
  dataCancelamento: string
}

export function cancelarVenda(idVenda: number, motivo: string): Promise<CancelamentoEfetivado> {
  return api<CancelamentoEfetivado>(`/api/v1/vendas/cancelamento/${idVenda}`, {
    method: 'POST',
    body: JSON.stringify({ motivo }),
  })
}
