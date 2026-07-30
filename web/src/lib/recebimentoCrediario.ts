import { api } from './api'

export interface ClienteCrediario {
  idCliente: number
  nome: string
  cpfCnpj: string | null
  telefone: string | null
}

/**
 * `multa`/`juros`/`totalAPagar` já vêm calculados do servidor (nunca recalcular no front).
 * `totalParcelas` é o total de parcelas do mesmo plano (mesma venda + mesma carteira, não a
 * venda inteira) — usado pra exibir "Nº PC" como "02/05".
 */
export interface ParcelaCrediario {
  idContaReceber: number
  idVenda: number
  codigoEmpresa: number
  dataVenda: string
  numeroParcela: number
  totalParcelas: number
  dataVencimento: string
  valorOriginal: number
  multa: number
  juros: number
  totalAPagar: number
}

export interface CarteiraDisponivel {
  idCarteira: number
  nomeCarteira: string
  categoriaCarteira: 'AVISTA' | 'CARTAO_DEBITO' | 'CARTAO_CREDITO'
}

export interface PagamentoRecebimentoRequest {
  idCarteira: number
  valorPago: number
}

export interface EfetivarRecebimentoRequest {
  idCliente: number
  idsContaReceber: number[]
  pagamentos: PagamentoRecebimentoRequest[]
}

export interface RecebimentoEfetivado {
  idLoteRecebimento: number
  qtdParcelas: number
  valorTotal: number
}

/**
 * Um lote de recebimento já efetivado, candidato a estorno (2026-07-29). `formasPagamento` é só
 * pra contexto na listagem (nomes de carteira usadas naquele lote, separados por vírgula).
 */
export interface LoteRecebimento {
  idLoteRecebimento: number
  nomeCliente: string
  dataRecebimento: string
  valorTotal: number
  qtdParcelas: number
  formasPagamento: string | null
}

export interface EstornoEfetivado {
  idLoteRecebimento: number
  qtdParcelas: number
  valorTotal: number
}

/** Uma parcela dentro de um lote, só para visualização antes de decidir estornar. */
export interface ParcelaDoLote {
  idContaReceber: number
  idVenda: number
  numeroParcela: number
  totalParcelas: number
  dataVencimento: string
  valorRecebido: number
}

export function buscarClientesCrediario(filtros: { nome?: string; cpf?: string; celular?: string }): Promise<ClienteCrediario[]> {
  const params = new URLSearchParams()
  if (filtros.nome) params.set('nome', filtros.nome)
  if (filtros.cpf) params.set('cpf', filtros.cpf)
  if (filtros.celular) params.set('celular', filtros.celular)
  return api<ClienteCrediario[]>(`/api/v1/recebimento-crediario/clientes?${params.toString()}`)
}

export function listarParcelasCrediario(idCliente: number): Promise<ParcelaCrediario[]> {
  return api<ParcelaCrediario[]>(`/api/v1/recebimento-crediario/parcelas?idCliente=${idCliente}`)
}

export function listarCarteirasCrediario(): Promise<CarteiraDisponivel[]> {
  return api<CarteiraDisponivel[]>('/api/v1/recebimento-crediario/carteiras')
}

export function efetivarRecebimentoCrediario(payload: EfetivarRecebimentoRequest): Promise<RecebimentoEfetivado> {
  return api<RecebimentoEfetivado>('/api/v1/recebimento-crediario', { method: 'POST', body: JSON.stringify(payload) })
}

export function listarLotesRecebimento(filtros: {
  nomeCliente: string
  dataInicial?: string
  dataFinal?: string
}): Promise<LoteRecebimento[]> {
  const params = new URLSearchParams()
  params.set('nomeCliente', filtros.nomeCliente)
  if (filtros.dataInicial) params.set('dataInicial', filtros.dataInicial)
  if (filtros.dataFinal) params.set('dataFinal', filtros.dataFinal)
  return api<LoteRecebimento[]>(`/api/v1/recebimento-crediario/estornos?${params.toString()}`)
}

export function estornarLoteRecebimento(idLoteRecebimento: number): Promise<EstornoEfetivado> {
  return api<EstornoEfetivado>(`/api/v1/recebimento-crediario/estornos/${idLoteRecebimento}`, { method: 'POST' })
}

export function listarParcelasDoLote(idLoteRecebimento: number): Promise<ParcelaDoLote[]> {
  return api<ParcelaDoLote[]>(`/api/v1/recebimento-crediario/estornos/${idLoteRecebimento}/parcelas`)
}

/**
 * Comprovante de pagamento pra impressão térmica 80mm (2026-07-30). `multaJuros` já vem
 * congelado no valor cobrado na hora do recebimento — nunca recalcular no front.
 */
export interface ParcelaComprovante {
  idVenda: number
  numeroParcela: number
  totalParcelas: number
  dataVencimento: string
  valorParcela: number
  multaJuros: number
  valorAPagar: number
}

export interface PagamentoComprovante {
  nomeCarteira: string
  valorPago: number
}

export interface ComprovanteRecebimento {
  idLoteRecebimento: number
  idCaixa: number
  nomeEmpresa: string
  nomeCliente: string
  dataPagamento: string
  parcelas: ParcelaComprovante[]
  valorTotalAPagar: number
  pagamentos: PagamentoComprovante[]
}

export function buscarComprovanteRecebimento(idLoteRecebimento: number): Promise<ComprovanteRecebimento> {
  return api<ComprovanteRecebimento>(`/api/v1/recebimento-crediario/${idLoteRecebimento}/comprovante`)
}
