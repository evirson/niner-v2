import { api } from './api'

/** `aberto = false` deixa os demais campos `null` — não há caixa hoje para o usuário/empresa. */
export interface CaixaStatus {
  aberto: boolean
  idCaixa: number | null
  dataAbertura: string | null
  idCarteira: number | null
  nomeCarteira: string | null
  saldoInicial: number | null
}

export interface CarteiraParaAbertura {
  idCarteira: number
  nomeCarteira: string
}

export interface AbrirCaixaRequest {
  idCarteira: number
  saldoInicial: number
}

export function buscarStatusCaixa(): Promise<CaixaStatus> {
  return api<CaixaStatus>('/api/v1/caixa/status')
}

export function listarCarteirasParaAbertura(): Promise<CarteiraParaAbertura[]> {
  return api<CarteiraParaAbertura[]>('/api/v1/caixa/carteiras')
}

export function abrirCaixa(payload: AbrirCaixaRequest): Promise<CaixaStatus> {
  return api<CaixaStatus>('/api/v1/caixa/abrir', { method: 'POST', body: JSON.stringify(payload) })
}
