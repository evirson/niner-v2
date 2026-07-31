import { api } from './api'
import { ROTULO_CATEGORIA_CARTEIRA, type CategoriaCarteira } from './tiposCarteira'

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
 *  é sempre recalculado no servidor a partir de `caixa_detalhe` — nunca vem de um campo gravado.
 *  `categoriaCarteira` (2026-07-31) distingue carteiras com o mesmo nome em categorias
 *  diferentes (ex.: "HIPER" cadastrada em débito e em crédito) — ver `rotuloCarteira`. */
export interface LinhaTotalCarteira {
  idCarteira: number
  nomeCarteira: string
  categoriaCarteira: CategoriaCarteira
  saldoInicial: number
  totalCredito: number
  totalDebito: number
  valorEsperado: number
}

/** Uma linha de conferência: `diferenca = valorContado - valorEsperado`. Só existe (fora da
 *  resposta imediata de `fecharCaixa`) quando o caixa já está fechado de verdade — gravada em
 *  `caixa_fechamento_conferencia` só no fechamento com sucesso (2026-07-30, fechamento "às cegas"). */
export interface LinhaConferencia {
  idCarteira: number
  nomeCarteira: string
  categoriaCarteira: CategoriaCarteira
  valorEsperado: number
  valorContado: number
  diferenca: number
}

/** "HIPER — Cartão Débito" — rótulo pra distinguir carteiras com o mesmo nome em categorias
 *  diferentes (2026-07-31), mesmo padrão já usado em `FormaPagamentoModal.tsx`. */
export function rotuloCarteira(l: { nomeCarteira: string; categoriaCarteira: CategoriaCarteira }): string {
  return `${l.nomeCarteira} — ${ROTULO_CATEGORIA_CARTEIRA[l.categoriaCarteira]}`
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
  conferencia: LinhaConferencia[]
}

export interface ValorContado {
  idCarteira: number
  valorContado: number
}

export interface FecharCaixaRequest {
  idCaixa: number
  valoresContados: ValorContado[]
}

/** `fechado = false` quando alguma carteira não bateu — o caixa continua aberto e `linhas` traz
 *  a divergência de cada carteira (esperado/contado/diferença), sem nada gravado. */
export interface ResultadoFechamento {
  idCaixa: number
  fechado: boolean
  linhas: LinhaConferencia[]
}

export interface LancamentoCarteira {
  dataHora: string
  tipoOperacao: string
  creditoDebito: 'C' | 'D'
  valor: number
  origem: string
}

/** `idUsuario` omitido busca o próprio caixa do usuário logado — só ADMIN pode informar outro
 *  (checado no servidor, ver `CaixaService.buscarParaFechamento`). */
export function buscarFechamentoCaixa(dataIso: string, idUsuario?: number): Promise<FechamentoCaixa> {
  const params = new URLSearchParams()
  params.set('data', dataIso)
  if (idUsuario) params.set('idUsuario', String(idUsuario))
  return api<FechamentoCaixa>(`/api/v1/caixa/fechamento?${params.toString()}`)
}

/** "Às cegas" (2026-07-30): manda o valor contado de cada carteira com movimento no dia; só
 *  fecha de fato quando todas batem — senão devolve a divergência sem fechar nada. */
export function fecharCaixa(payload: FecharCaixaRequest): Promise<ResultadoFechamento> {
  return api<ResultadoFechamento>('/api/v1/caixa/fechamento', { method: 'POST', body: JSON.stringify(payload) })
}

/** Drill-down analítico de uma carteira dentro do caixa — pra conferir lançamento a lançamento
 *  quando a conferência não bate. */
export function listarLancamentosDaCarteira(idCaixa: number, idCarteira: number): Promise<LancamentoCarteira[]> {
  return api<LancamentoCarteira[]>(`/api/v1/caixa/fechamento/${idCaixa}/carteiras/${idCarteira}/lancamentos`)
}
