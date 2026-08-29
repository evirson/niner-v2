import { api } from './api'
import type { TipoItem } from './produtos'

/**
 * Ordem de Serviço (V087) — o trabalho que leva tempo: oficina, banho e tosa.
 *
 * ⛔ **OS não é orçamento.** São entidades separadas (decisão do dono do produto, 2026-08-28): o
 * orçamento é imutável e comercial; a OS é mutável e de execução. O que as duas compartilham é o
 * caminho de virar venda — o F5 do PDV.
 */
export type SituacaoOs =
  | 'ABERTA'
  | 'APROVADA'
  | 'EM_EXECUCAO'
  | 'CONCLUIDA'
  | 'FATURADA'
  | 'CANCELADA'

/** Rótulo e cor de cada estado, para a grade e o cabeçalho não inventarem cada um o seu. */
export const SITUACAO_OS: Record<SituacaoOs, { rotulo: string; cor: string }> = {
  ABERTA: { rotulo: 'Aberta', cor: 'var(--ink-muted)' },
  APROVADA: { rotulo: 'Aprovada', cor: 'var(--accent)' },
  EM_EXECUCAO: { rotulo: 'Em execução', cor: 'var(--aviso)' },
  CONCLUIDA: { rotulo: 'Concluída', cor: 'var(--sucesso)' },
  FATURADA: { rotulo: 'Faturada', cor: 'var(--accent)' },
  CANCELADA: { rotulo: 'Cancelada', cor: 'var(--danger)' },
}

/** Os estados que ainda aceitam edição — depois deles a OS é história. */
export const ESTADOS_EDITAVEIS: SituacaoOs[] = ['ABERTA', 'APROVADA', 'EM_EXECUCAO', 'CONCLUIDA']

/** Para onde cada estado pode avançar. Só para frente, um passo por vez. */
export const PROXIMO_ESTADO: Partial<Record<SituacaoOs, SituacaoOs>> = {
  ABERTA: 'APROVADA',
  APROVADA: 'EM_EXECUCAO',
  EM_EXECUCAO: 'CONCLUIDA',
}

export interface ItemOs {
  idOrdemServicoItem: number
  idVariacao: number
  sku: string
  descricaoProduto: string
  /** Nulos quando o produto não tem grade — é o que distingue duas linhas do mesmo produto. */
  variacaoCor: string | null
  variacaoTamanho: string | null
  tipoItem: TipoItem
  qtdProduto: number
  precoVenda: number
  total: number
  idFuncionario: number | null
  nomeFuncionario: string | null
  /** Quanto esta linha segura em estoque. Serviço é sempre 0 — não tem saldo. */
  qtdReservada: number
  /** Minutos cadastrados do serviço; nulo em peça e em serviço sem duração. */
  duracaoMinutos: number | null
}

/**
 * Soma a duração dos serviços de uma OS, em minutos. `null` quando nenhum serviço tem duração
 * cadastrada — o que é diferente de "zero minutos" e faz a tela não mostrar previsão nenhuma.
 *
 * ⛔ **Isto não é agenda.** Ninguém reserva horário, ninguém checa conflito, e a soma ignora que
 * dois mecânicos podem trabalhar em paralelo. É a estimativa que responde "fica pronto quando?"
 * no balcão, e a tela a rotula como estimativa.
 */
export function duracaoTotalMinutos(itens: ItemOs[]): number | null {
  const comDuracao = itens.filter((i) => i.tipoItem === 'SERVICO' && i.duracaoMinutos != null)
  if (comDuracao.length === 0) return null
  return comDuracao.reduce((s, i) => s + (i.duracaoMinutos ?? 0) * i.qtdProduto, 0)
}

/** "1h30" / "45min" / "2h" — o formato que o balcão fala, não "90 minutos". */
export function formatarDuracao(minutos: number): string {
  const h = Math.floor(minutos / 60)
  const m = Math.round(minutos % 60)
  if (h === 0) return `${m}min`
  return m === 0 ? `${h}h` : `${h}h${String(m).padStart(2, '0')}`
}

export interface OrdemServico {
  idOrdemServico: number
  idEmpresa: number
  idCliente: number
  nomeCliente: string
  /** Para a VIA IMPRESSA e o envio por WhatsApp — sem o telefone aqui, o popup abriria vazio. */
  documentoCliente: string | null
  telefoneCliente: string | null
  nomeEmpresa: string
  idFuncionario: number
  nomeFuncionario: string
  objetoServico: string
  observacao: string | null
  situacao: SituacaoOs
  dataAbertura: string
  dataConclusao: string | null
  valorDesconto: number
  totalServicos: number
  totalPecas: number
  total: number
  itens: ItemOs[]
  idVenda: number | null
  dataFaturamento: string | null
  dataCancelamento: string | null
  motivoCancelamento: string | null
  criadoEm: string
  atualizadoEm: string
}

export interface LinhaOs {
  idOrdemServico: number
  nomeCliente: string
  objetoServico: string
  situacao: SituacaoOs
  dataAbertura: string
  total: number
  idVenda: number | null
}

export interface PaginaOs {
  itens: LinhaOs[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ItemOsRequest {
  idVariacao: number
  qtdProduto: number
  /** Em branco, o servidor resolve pelo cadastro — a tela nunca decide preço. */
  precoVenda?: number | null
  idFuncionario?: number | null
}

export interface OrdemServicoRequest {
  idCliente: number
  idFuncionario: number
  objetoServico: string
  observacao?: string | null
  valorDesconto?: number
  itens: ItemOsRequest[]
}

export interface FiltrosOs {
  busca?: string
  situacao?: string
  dataInicial?: string
  dataFinal?: string
  pagina?: number
  limite?: number
}

export function listarOrdensServico(f: FiltrosOs = {}): Promise<PaginaOs> {
  const q = new URLSearchParams()
  if (f.busca) q.set('busca', f.busca)
  if (f.situacao) q.set('situacao', f.situacao)
  if (f.dataInicial) q.set('dataInicial', f.dataInicial)
  if (f.dataFinal) q.set('dataFinal', f.dataFinal)
  q.set('pagina', String(f.pagina ?? 1))
  q.set('limite', String(f.limite ?? 50))
  return api<PaginaOs>(`/api/v1/ordens-servico?${q}`)
}

export function buscarOrdemServico(id: number): Promise<OrdemServico> {
  return api<OrdemServico>(`/api/v1/ordens-servico/${id}`)
}

export function criarOrdemServico(req: OrdemServicoRequest): Promise<OrdemServico> {
  return api<OrdemServico>('/api/v1/ordens-servico', { method: 'POST', body: JSON.stringify(req) })
}

export function atualizarOrdemServico(id: number, req: OrdemServicoRequest): Promise<OrdemServico> {
  return api<OrdemServico>(`/api/v1/ordens-servico/${id}`, { method: 'PUT', body: JSON.stringify(req) })
}

export function mudarSituacaoOs(id: number, para: SituacaoOs): Promise<OrdemServico> {
  return api<OrdemServico>(`/api/v1/ordens-servico/${id}/situacao?para=${para}`, { method: 'PUT' })
}

/** ⚠️ Cancelar devolve a reserva de estoque das peças — é o caminho da OS parada (DS17). */
export function cancelarOrdemServico(id: number, motivo: string): Promise<OrdemServico> {
  return api<OrdemServico>(`/api/v1/ordens-servico/${id}/cancelar`, {
    method: 'POST',
    body: JSON.stringify({ motivo }),
  })
}

/** As OS que o PDV pode puxar para um cliente — só as concluídas (DS18). */
export function listarOsFaturaveis(idCliente: number): Promise<LinhaOs[]> {
  return api<LinhaOs[]>(`/api/v1/ordens-servico/faturaveis?idCliente=${idCliente}`)
}
