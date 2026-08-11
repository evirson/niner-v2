import { api, apiUpload, ApiError, getToken } from './api'
import { API_BASE } from './config'

/** Um item lançado no ledger da entrada (mesmo shape do detalhe gravado em `produto_movimento_detalhe`). */
export interface ItemEntradaRequest {
  idVariacao: number
  qtd: number
  precoCusto: number
}

/** Uma duplicata/parcela opcional a gerar em `contas_pagar` — sempre opcional no fluxo Manual. */
export interface ContaPagarEntradaRequest {
  numeroDuplicata: string | null
  dataVencimento: string
  valor: number
}

export interface EfetivarEntradaRequest {
  idFornecedor: number
  /** Empresa que recebe a mercadoria — opcional; ausente cai na empresa ativa da sessão. */
  idEmpresa?: number | null
  notaFiscal: number | null
  /** Data em que a mercadoria foi de fato recebida ("aaaa-mm-dd") — opcional; ausente grava a
   *  data/hora de agora (comportamento de sempre). */
  dataMovimento?: string | null
  chaveNfe?: string | null
  serieNota?: number | null
  xmlBruto?: string | null
  valorRateio: number | null
  itens: ItemEntradaRequest[]
  contasPagar: ContaPagarEntradaRequest[] | null
}

export interface ItemEntradaResponse {
  idVariacao: number
  sku: string
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtd: number
  precoCusto: number
  valorTotal: number
}

export interface EntradaEfetivadaResponse {
  idMovimento: number
  idEmpresa: number
  idFornecedor: number
  nomeFornecedor: string
  notaFiscal: number | null
  dataMovimento: string
  valorTotal: number
  itens: ItemEntradaResponse[]
}

export interface EntradaResumoResponse {
  idMovimento: number
  dataMovimento: string
  idFornecedor: number | null
  nomeFornecedor: string | null
  notaFiscal: number | null
  qtdItens: number
  valorTotal: number
  origem: string
}

export interface PaginaEntradas {
  itens: EntradaResumoResponse[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ItemEntradaDetalheResponse {
  idMovimentoDetalhe: number
  idVariacao: number
  sku: string
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtd: number
  precoCusto: number
  valorAcrescimo: number
  valorTotal: number
}

export interface EntradaDetalheResponse {
  idMovimento: number
  idEmpresa: number
  idFornecedor: number | null
  nomeFornecedor: string | null
  notaFiscal: number | null
  chaveNfe: string | null
  dataMovimento: string
  valorTotal: number
  itens: ItemEntradaDetalheResponse[]
}

export type ColunaOrdenacaoEntrada = 'dataMovimento' | 'fornecedor' | 'notaFiscal'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosEntradas {
  idFornecedor?: number
  notaFiscal?: number
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoEntrada
  direcao?: DirecaoOrdenacao
}

export function listarEntradas(filtros: FiltrosEntradas): Promise<PaginaEntradas> {
  const params = new URLSearchParams()
  if (filtros.idFornecedor) params.set('idFornecedor', String(filtros.idFornecedor))
  if (filtros.notaFiscal) params.set('notaFiscal', String(filtros.notaFiscal))
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaEntradas>(`/api/v1/estoque/entradas${query ? `?${query}` : ''}`)
}

export function buscarEntrada(idMovimento: number): Promise<EntradaDetalheResponse> {
  return api<EntradaDetalheResponse>(`/api/v1/estoque/entradas/${idMovimento}`)
}

export function efetivarEntrada(payload: EfetivarEntradaRequest): Promise<EntradaEfetivadaResponse> {
  return api<EntradaEfetivadaResponse>('/api/v1/estoque/entradas', { method: 'POST', body: JSON.stringify(payload) })
}

/** Uma linha da planilha já processada (fluxo Planilha) — preview, nada persistido no ledger.
 *  `resolvido=true` já tem `idVariacao` pronto pra virar um `ItemEntradaRequest`. `resolvido=false`
 *  com `idProdutoEncontrado` preenchido: produto achado, falta cor/tamanho (a tela pode oferecer
 *  os selects direto na linha). Nenhum dos dois preenchido: produto não encontrado (pesquisar/
 *  cadastrar). */
export interface ItemPlanilhaPreviewResponse {
  numeroLinha: number
  nomeProduto: string | null
  marca: string | null
  referencia: string | null
  cor: string | null
  tamanho: string | null
  codigoBarrasFabricante: string | null
  qtd: number | null
  custoUnitario: number | null
  resolvido: boolean
  idVariacao: number | null
  sku: string | null
  descricaoProduto: string | null
  variacaoCor: string | null
  variacaoTamanho: string | null
  idProdutoEncontrado: number | null
  idGradeEncontrada: number | null
  motivoPendencia: string | null
}

export function previewPlanilhaEntrada(arquivo: File): Promise<ItemPlanilhaPreviewResponse[]> {
  const fd = new FormData()
  fd.append('arquivo', arquivo)
  return apiUpload('/api/v1/estoque/entradas/planilha/preview', fd)
}

/** Baixa o modelo `.xlsx` da planilha de entrada e dispara o download no navegador (a rota
 *  exige o Bearer token, então não dá pra ser um `<a href>` simples). */
export async function baixarModeloPlanilhaEntrada(): Promise<void> {
  const token = getToken()
  const res = await fetch(`${API_BASE}/api/v1/estoque/entradas/planilha/modelo`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!res.ok) {
    throw new ApiError(res.status, 'Não foi possível baixar o modelo.')
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'entrada_produtos_modelo.xlsx'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export function atualizarItemEntrada(
  idMovimento: number,
  idMovimentoDetalhe: number,
  payload: { qtd: number; precoCusto: number },
): Promise<void> {
  return api<void>(`/api/v1/estoque/entradas/${idMovimento}/itens/${idMovimentoDetalhe}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}
