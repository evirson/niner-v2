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

/** Uma linha de totais do Fechamento de Caixa, por tipo de carteira (2026-07-30). `valorEsperado`
 *  é sempre recalculado no servidor a partir de `caixa_detalhe` — nunca vem de um campo gravado. */
export interface LinhaTotalCarteira {
  idCarteira: number
  nomeCarteira: string
  saldoInicial: number
  totalCredito: number
  totalDebito: number
  valorEsperado: number
}

export interface FechamentoCaixa {
  idCaixa: number
  idUsuario: number
  nomeUsuario: string
  nomeEmpresa: string
  dataAbertura: string
  dataFechamento: string | null
  fechado: boolean
  linhas: LinhaTotalCarteira[]
  valorContadoDinheiro: number | null
}

export interface FecharCaixaRequest {
  idCaixa: number
  valorContadoDinheiro: number
}

/** `idUsuario` omitido busca o próprio caixa do usuário logado — só ADMIN pode informar outro
 *  (checado no servidor, ver `CaixaService.buscarParaFechamento`). */
export function buscarFechamentoCaixa(dataIso: string, idUsuario?: number): Promise<FechamentoCaixa> {
  const params = new URLSearchParams()
  params.set('data', dataIso)
  if (idUsuario) params.set('idUsuario', String(idUsuario))
  return api<FechamentoCaixa>(`/api/v1/caixa/fechamento?${params.toString()}`)
}

export function fecharCaixa(payload: FecharCaixaRequest): Promise<FechamentoCaixa> {
  return api<FechamentoCaixa>('/api/v1/caixa/fechamento', { method: 'POST', body: JSON.stringify(payload) })
}
