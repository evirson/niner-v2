import { api } from './api'
import { somenteAlfanumerico } from './masks'
import { maiusculas } from './texto'

/**
 * Painel do assinante (ADR-015, docs/telas/painel-assinatura.md) — o que a loja paga à Vetor.
 * Não confundir com o financeiro do lojista (caixa/crediário): são planos diferentes (P9).
 */

export type SituacaoUso = 'NORMAL' | 'ATENCAO' | 'TOLERANCIA' | 'BLOQUEADO'

export interface PlanoAtual {
  idPlano: number
  nome: string
  gratuito: boolean
  precoMensal: number
  precoAnual: number
  ciclo: string
  limiteVendasMes: number | null
}

export interface UsoAtual {
  competencia: string
  qtdVendas: number
  limite: number | null
  restantes: number | null
  tolerancia: number
  toleranciaRestante: number | null
  situacao: SituacaoUso
  zeraEm: string
}

export interface UsoMes {
  competencia: string
  qtdVendas: number
}

export interface FaixaSugerida {
  nome: string
  limiteVendasMes: number | null
  precoMensal: number
  precoAnual: number
}

export interface EmpresaDoTenant {
  idEmpresa: number
  codigoEmpresa: number
  razaoSocial: string
  nomeFantasia: string | null
  cnpj: string | null
  cidade: string | null
  estado: string | null
  matriz: boolean
  ativo: boolean
}

export interface MinhaConta {
  plano: PlanoAtual
  uso: UsoAtual
  historico: UsoMes[]
  faixaRecomendada: FaixaSugerida | null
  empresas: EmpresaDoTenant[]
}

export function buscarMinhaConta(): Promise<MinhaConta> {
  return api<MinhaConta>('/api/v1/minha-conta')
}

export interface NovaEmpresaForm {
  razaoSocial: string
  nomeFantasia: string
  cnpj: string
}

/** CNPJ é alfanumérico desde a IN RFB 2.229/2024 — nunca limpar com filtro só-dígitos. */
export function criarEmpresa(f: NovaEmpresaForm): Promise<{ idEmpresa: number; codigoEmpresa: number }> {
  return api('/api/v1/empresas', {
    method: 'POST',
    body: JSON.stringify({
      razaoSocial: maiusculas(f.razaoSocial.trim()),
      nomeFantasia: f.nomeFantasia.trim() ? maiusculas(f.nomeFantasia.trim()) : null,
      cnpj: f.cnpj.trim() ? somenteAlfanumerico(f.cnpj) : null,
    }),
  })
}

/** "2026-08" a partir de "2026-08-01" — rótulo curto do gráfico. */
export function rotuloCompetencia(iso: string): string {
  const [ano, mes] = iso.split('-')
  const nomes = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez']
  return `${nomes[Number(mes) - 1]}/${ano.slice(2)}`
}

/* ---------------------------------------------------------------------------------------------
 * Assinatura paga (ADR-015/016). O plano só troca quando o pagamento é confirmado pelo gateway —
 * a tela pede o PIX e fica consultando a fatura; quem promove a assinatura é o worker no backend.
 * ------------------------------------------------------------------------------------------- */

export type Ciclo = 'MENSAL' | 'ANUAL'

export interface FaixaPublica {
  idPlano: number
  nome: string
  gratuito: boolean
  faixaOrdem: number | null
  limiteVendasMes: number | null
  precoMensal: number
  precoAnual: number
}

/** Catálogo é público (a landing usa o mesmo endpoint) — não exige token. */
export async function listarFaixas(): Promise<FaixaPublica[]> {
  const base = (window as unknown as { NINER_API_BASE?: string }).NINER_API_BASE ?? ''
  const res = await fetch(`${base}/api/publico/planos`)
  if (!res.ok) throw new Error('Não foi possível carregar as faixas de plano.')
  const faixas = (await res.json()) as FaixaPublica[]
  return faixas.filter((f) => !f.gratuito)
}

export interface PagamentoPix {
  idFatura: number
  plano: string
  ciclo: Ciclo
  valor: number
  competencia: string
  copiaECola: string
  qrCodeBase64: string | null
  linkPagamento: string | null
  expiraEm: string
  situacao: string
}

export function iniciarPagamento(idPlano: number, ciclo: Ciclo): Promise<PagamentoPix> {
  return api<PagamentoPix>('/api/v1/assinatura/pagamento', {
    method: 'POST',
    body: JSON.stringify({ idPlano, ciclo }),
  })
}

export interface SituacaoFatura {
  idFatura: number
  situacao: string
  pagoEm: string | null
  planoAtual: string
}

export function consultarFatura(idFatura: number): Promise<SituacaoFatura> {
  return api<SituacaoFatura>(`/api/v1/assinatura/faturas/${idFatura}`)
}
