import { api } from './api'

/** Uma linha por produto contado — `qtdContada` é a soma de todas as leituras ativas dele. */
export interface LinhaContagem {
  idVariacao: number
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  sku: string
  qtdContada: number
}

export interface LinhaDiferenca {
  idVariacao: number
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  sku: string
  qtdEstoque: number
  qtdContada: number
  diferenca: number
}

/** `existeContagemAtiva = false` quando não há nenhuma leitura ativa nesta empresa (ninguém
 *  começou a contar de novo, ou a contagem nunca começou) — `linhas` vem sempre vazia nesse
 *  caso, mas por um motivo diferente de "contagem bate com o estoque". */
export interface DiferencasEstoque {
  existeContagemAtiva: boolean
  linhas: LinhaDiferenca[]
}

export interface EfetivacaoBalanco {
  idMovimento: number
  totalProdutosAjustados: number
  dataEfetivacao: string
}

export interface UltimaEfetivacao {
  existe: boolean
  idMovimento: number | null
  dataEfetivacao: string | null
  totalProdutos: number | null
}

export function listarContagemAtiva(): Promise<LinhaContagem[]> {
  return api<LinhaContagem[]>('/api/v1/estoque/balanco/contagem')
}

export function registrarContagem(idVariacao: number, qtd: number): Promise<void> {
  return api<void>('/api/v1/estoque/balanco/contagem', { method: 'POST', body: JSON.stringify({ idVariacao, qtd }) })
}

export function ajustarContagem(idVariacao: number, qtdContada: number): Promise<void> {
  return api<void>(`/api/v1/estoque/balanco/contagem/${idVariacao}`, { method: 'PUT', body: JSON.stringify({ qtdContada }) })
}

export function removerContagem(idVariacao: number): Promise<void> {
  return api<void>(`/api/v1/estoque/balanco/contagem/${idVariacao}`, { method: 'DELETE' })
}

export function zerarContagem(): Promise<void> {
  return api<void>('/api/v1/estoque/balanco/contagem', { method: 'DELETE' })
}

export function obterDiferencas(): Promise<DiferencasEstoque> {
  return api<DiferencasEstoque>('/api/v1/estoque/balanco/diferencas')
}

export function efetivarBalanco(): Promise<EfetivacaoBalanco> {
  return api<EfetivacaoBalanco>('/api/v1/estoque/balanco/efetivar', { method: 'POST' })
}

export function obterUltimaEfetivacao(): Promise<UltimaEfetivacao> {
  return api<UltimaEfetivacao>('/api/v1/estoque/balanco/ultima-efetivacao')
}

export function desfazerUltimaEfetivacao(): Promise<void> {
  return api<void>('/api/v1/estoque/balanco/desfazer', { method: 'POST' })
}
