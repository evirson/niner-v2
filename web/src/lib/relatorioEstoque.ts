import { api } from './api'

export type ModeloRelatorioEstoque = 'INVENTARIO' | 'SINTETICO' | 'ANALITICO'
export type TipoQuantidade = 'TODOS' | 'DIFERENTE_DE_ZERO' | 'ZERADA'
export type SituacaoProduto = 'ATIVOS' | 'INATIVOS' | 'TODOS'

export interface ColunaEmpresaEstoque {
  idEmpresa: number
  nomeEmpresa: string
}

export interface LinhaInventario {
  descricaoProduto: string
  marca: string | null
  referencia: string | null
  qtdTotal: number
  custoUnitario: number
  custoTotal: number
}

/** `qtdPorEmpresa` é posicional — mesma ordem de `colunasEmpresa` da resposta, não um mapa por id. */
export interface LinhaSintetica {
  descricaoProduto: string
  marca: string | null
  referencia: string | null
  qtdPorEmpresa: number[]
  qtdTotal: number
}

export interface LinhaAnalitica {
  descricaoProduto: string
  marca: string | null
  referencia: string | null
  variacaoLinha: string | null
  variacaoColuna: string | null
  qtdPorEmpresa: number[]
  qtdTotal: number
}

export interface TotalInventario {
  qtdTotal: number
  custoTotal: number
}

export interface TotalSintetico {
  qtdPorEmpresa: number[]
  qtdTotal: number
}

/** Só os campos do `modelo` pedido vêm preenchidos — mesmo padrão do Totalizador do Relatório de
 *  Vendas (campo discriminador + listas/totais alternativos). */
export interface RelatorioEstoqueResponse {
  modelo: ModeloRelatorioEstoque
  colunasEmpresa: ColunaEmpresaEstoque[]
  linhasInventario: LinhaInventario[]
  linhasSintetico: LinhaSintetica[]
  linhasAnalitico: LinhaAnalitica[]
  totalInventario: TotalInventario | null
  totalSintetico: TotalSintetico | null
}

export interface FiltrosRelatorioEstoque {
  modelo: ModeloRelatorioEstoque
  idsEmpresa?: number[]
  marcas?: string[]
  idsCategoria?: number[]
  tipoQuantidade?: TipoQuantidade
  situacao?: SituacaoProduto
}

export function gerarRelatorioEstoque(filtros: FiltrosRelatorioEstoque): Promise<RelatorioEstoqueResponse> {
  const params = new URLSearchParams()
  params.set('modelo', filtros.modelo)
  for (const id of filtros.idsEmpresa ?? []) params.append('idsEmpresa', String(id))
  for (const marca of filtros.marcas ?? []) params.append('marcas', marca)
  for (const id of filtros.idsCategoria ?? []) params.append('idsCategoria', String(id))
  if (filtros.tipoQuantidade) params.set('tipoQuantidade', filtros.tipoQuantidade)
  if (filtros.situacao) params.set('situacao', filtros.situacao)
  return api<RelatorioEstoqueResponse>(`/api/v1/relatorios/estoque?${params.toString()}`)
}
