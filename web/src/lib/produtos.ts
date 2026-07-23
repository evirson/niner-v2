import { api } from './api'
import type { ImagemProduto } from './produtoImagens'
import {
  dataParaIso,
  desmascararMoeda,
  desmascararPercentual,
  desmascararPeso,
  formatarMoeda,
  formatarPercentual,
  formatarPeso,
  isoParaData,
  mascararNcm,
  somenteDigitos,
} from './masks'
import { maiusculas } from './texto'

export type StatusProduto = 'ATIVOS' | 'INATIVOS' | 'TODOS'

export interface CategoriaSelecionada {
  idCategoria: number
  nomeCategoria: string
  indice: number
}

export interface Produto {
  idProduto: number
  descricao: string
  marca: string | null
  referencia: string | null
  precoCusto: number
  percentualVenda: number
  precoVenda: number
  dataInicioOferta: string | null
  dataFinalOferta: string | null
  precoOferta: number | null
  codigoNcm: string | null
  pesoBruto: number
  pesoLiquido: number
  nomeVarianteLinha: string | null
  nomeVarianteColuna: string | null
  ativo: boolean
  categorias: CategoriaSelecionada[]
  imagens: ImagemProduto[]
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — strings para casar com inputs controlados (máscaras de moeda/%). */
export interface ProdutoFormState {
  descricao: string
  marca: string
  referencia: string
  precoCusto: string
  percentualVenda: string
  precoVenda: string
  dataInicioOferta: string
  dataFinalOferta: string
  precoOferta: string
  codigoNcm: string
  pesoBruto: string
  pesoLiquido: string
  nomeVarianteLinha: string
  nomeVarianteColuna: string
  ativo: boolean
  /** Categorias escolhidas, na ordem de exibição — o índice na lista é o `indice` enviado à API. */
  categorias: CategoriaSelecionada[]
}

export const PRODUTO_VAZIO: ProdutoFormState = {
  descricao: '',
  marca: '',
  referencia: '',
  precoCusto: '',
  percentualVenda: '',
  precoVenda: '',
  dataInicioOferta: '',
  dataFinalOferta: '',
  precoOferta: '',
  codigoNcm: '',
  pesoBruto: '',
  pesoLiquido: '',
  nomeVarianteLinha: '',
  nomeVarianteColuna: '',
  ativo: true,
  categorias: [],
}

/** "dd/mm/aaaa" (campo de texto, ver masks.ts#mascararData) -> ISO com hora, para a API. */
function paraIsoOuNulo(dataBr: string): string | null {
  const iso = dataParaIso(dataBr)
  return iso ? `${iso}T00:00:00Z` : null
}

export function paraFormulario(p: Produto): ProdutoFormState {
  return {
    descricao: p.descricao,
    marca: p.marca ?? '',
    referencia: p.referencia ?? '',
    precoCusto: formatarMoeda(p.precoCusto),
    percentualVenda: formatarPercentual(p.percentualVenda),
    precoVenda: formatarMoeda(p.precoVenda),
    dataInicioOferta: isoParaData(p.dataInicioOferta),
    dataFinalOferta: isoParaData(p.dataFinalOferta),
    precoOferta: p.precoOferta == null ? '' : formatarMoeda(p.precoOferta),
    codigoNcm: p.codigoNcm ? mascararNcm(p.codigoNcm) : '',
    pesoBruto: formatarPeso(p.pesoBruto ?? 0),
    pesoLiquido: formatarPeso(p.pesoLiquido ?? 0),
    nomeVarianteLinha: p.nomeVarianteLinha ?? '',
    nomeVarianteColuna: p.nomeVarianteColuna ?? '',
    ativo: p.ativo,
    categorias: [...p.categorias].sort((a, b) => a.indice - b.indice),
  }
}

/** Monta o corpo da requisição: máscaras removidas, vazio vira null, texto em MAIÚSCULAS. */
export function paraRequisicao(f: ProdutoFormState) {
  const maiusculoOuNulo = (v: string) => (v.trim() ? maiusculas(v.trim()) : null)
  return {
    descricao: maiusculas(f.descricao.trim()),
    marca: maiusculoOuNulo(f.marca),
    referencia: maiusculoOuNulo(f.referencia),
    precoCusto: desmascararMoeda(f.precoCusto),
    percentualVenda: desmascararPercentual(f.percentualVenda),
    precoVenda: desmascararMoeda(f.precoVenda),
    dataInicioOferta: paraIsoOuNulo(f.dataInicioOferta),
    dataFinalOferta: paraIsoOuNulo(f.dataFinalOferta),
    precoOferta: f.precoOferta ? desmascararMoeda(f.precoOferta) : null,
    codigoNcm: f.codigoNcm ? somenteDigitos(f.codigoNcm) : null,
    pesoBruto: desmascararPeso(f.pesoBruto),
    pesoLiquido: desmascararPeso(f.pesoLiquido),
    nomeVarianteLinha: maiusculoOuNulo(f.nomeVarianteLinha),
    nomeVarianteColuna: maiusculoOuNulo(f.nomeVarianteColuna),
    ativo: f.ativo,
    categorias: f.categorias.map((c) => c.idCategoria),
  }
}

export interface PaginaProdutos {
  itens: Produto[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoProduto {
  acao: 'excluido' | 'inativado'
  motivo: string | null
}

export type ColunaOrdenacaoProduto = 'descricao' | 'marca' | 'referencia' | 'precoCusto' | 'precoVenda' | 'status'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosProdutos {
  descricao?: string
  marca?: string
  idCategoria?: number
  status?: StatusProduto
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoProduto
  direcao?: DirecaoOrdenacao
}

export function listarProdutos(filtros: FiltrosProdutos): Promise<PaginaProdutos> {
  const params = new URLSearchParams()
  if (filtros.descricao) params.set('descricao', filtros.descricao)
  if (filtros.marca) params.set('marca', filtros.marca)
  if (filtros.idCategoria) params.set('idCategoria', String(filtros.idCategoria))
  if (filtros.status) params.set('status', filtros.status)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaProdutos>(`/api/v1/produtos${query ? `?${query}` : ''}`)
}

export function buscarProduto(id: number): Promise<Produto> {
  return api<Produto>(`/api/v1/produtos/${id}`)
}

export function criarProduto(payload: ReturnType<typeof paraRequisicao>): Promise<Produto> {
  return api<Produto>('/api/v1/produtos', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarProduto(id: number, payload: ReturnType<typeof paraRequisicao>): Promise<Produto> {
  return api<Produto>(`/api/v1/produtos/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function excluirProduto(id: number): Promise<ExclusaoProduto> {
  return api<ExclusaoProduto>(`/api/v1/produtos/${id}`, { method: 'DELETE' })
}
