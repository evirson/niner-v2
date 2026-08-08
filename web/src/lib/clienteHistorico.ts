import { api } from './api'
import type { CategoriaCarteira } from './tiposCarteira'

export interface VendaHistorico {
  idVenda: number
  codigoEmpresa: number
  dataVenda: string
  valor: number
  qtdProdutos: number
}

/** `variacaoCor`/`variacaoTamanho` são `null` quando o produto não usa variação. */
export interface ItemVendaHistorico {
  idVenda: number
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtdVendida: number
  precoVenda: number
}

/** `codigoEmpresaPagamento`/`diasAtraso` só existem quando aplicável (ver ClienteHistoricoDtos). */
export interface ParcelaHistorico {
  idContaReceber: number
  idVenda: number
  codigoEmpresaVenda: number
  dataVenda: string
  numeroParcela: number
  dataVencimento: string
  dataPagamento: string | null
  valorAPagar: number
  valorPago: number
  codigoEmpresaPagamento: number | null
  diasAtraso: number | null
  nomeCarteira: string
  categoriaCarteira: CategoriaCarteira
}

export interface ResumoParcelasComJuros {
  valorTotal: number
  valorJurosMulta: number
  numeroParcelas: number
}

export interface ResumoParcelasSemJuros {
  valorTotal: number
  numeroParcelas: number
}

export interface ResumoCrediario {
  vencidas: ResumoParcelasComJuros
  aVencer: ResumoParcelasSemJuros
  total: ResumoParcelasComJuros
}

export interface ClienteHistorico {
  compras: VendaHistorico[]
  produtos: ItemVendaHistorico[]
  parcelas: ParcelaHistorico[]
  resumoCrediario: ResumoCrediario
}

export function buscarHistoricoCliente(idCliente: number): Promise<ClienteHistorico> {
  return api<ClienteHistorico>(`/api/v1/clientes/${idCliente}/historico`)
}
