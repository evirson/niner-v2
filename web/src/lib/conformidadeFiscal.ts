import { api } from './api'

/** Conformidade Fiscal (docs/telas/fiscal-conformidade.md) — painel de diagnóstico
 *  somente-leitura, por empresa. Nada aqui edita dado; só aponta e navega. */

export type CategoriaConformidade = 'EMPRESA' | 'PRODUTOS' | 'PAGAMENTOS' | 'CLIENTES'

export interface CategoriaConformidadeResponse {
  categoria: CategoriaConformidade
  rotulo: string
  bloqueia: boolean
  quantidadePendencias: number
}

export interface PainelConformidade {
  pronto: boolean
  veredito: string
  categorias: CategoriaConformidadeResponse[]
}

export interface ItemPendencia {
  idRegistro: number
  identificador: string
  problema: string
  telaCorrecao: string | null
}

export interface PaginaPendencias {
  itens: ItemPendencia[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

/** Mapeia `telaCorrecao` (chave lógica devolvida pelo backend) pra rota real do front — só as
 *  telas que já existem viram link; o resto some do "abrir cadastro" sem quebrar nada. */
export const ROTA_POR_TELA: Partial<Record<string, string>> = {
  'fiscal.configuracao': '/fiscal/configuracao',
  'fiscal.certificado': '/fiscal/certificados',
  'catalogo.produto': '/produtos',
  'financeiro.tipocarteira': '/tipos-carteira',
  'cadastros.cliente': '/clientes',
  'identidade.empresa': '/empresas',
}

export function rotaDeCorrecao(item: ItemPendencia): string | null {
  const base = item.telaCorrecao ? ROTA_POR_TELA[item.telaCorrecao] : null
  if (!base) return null
  // As 3 telas de cadastro têm rota de edição /rota/:id; as telas singleton (fiscal.*) não.
  if (base.startsWith('/fiscal/')) return base
  return `${base}/${item.idRegistro}`
}

export function buscarPainelConformidade(idEmpresa: number): Promise<PainelConformidade> {
  return api<PainelConformidade>(`/api/v1/fiscal/conformidade/${idEmpresa}`)
}

export function buscarDrillDown(
  idEmpresa: number,
  categoria: CategoriaConformidade,
  pagina: number,
): Promise<PaginaPendencias> {
  return api<PaginaPendencias>(
    `/api/v1/fiscal/conformidade/${idEmpresa}/${categoria.toLowerCase()}?pagina=${pagina}`,
  )
}
