import { api } from './api'
import { desmascararEtiquetaMm, formatarEtiquetaMm } from './masks'
import { maiusculas } from './texto'

/** Os 10 campos possíveis numa etiqueta — cada um mapeia pra uma coluna já existente no schema
 * (ver docs/telas/configuracao-etiqueta.md). */
export type CampoEtiqueta =
  | 'NOME_EMPRESA'
  | 'DESCRICAO_PRODUTO'
  | 'MARCA'
  | 'REFERENCIA'
  | 'PRECO_VENDA'
  | 'PRECO_OFERTA'
  | 'SKU_BARRAS'
  | 'EAN_BARRAS'
  | 'VARIANTE_LINHA'
  | 'VARIANTE_COLUNA'

export const ROTULO_CAMPO_ETIQUETA: Record<CampoEtiqueta, string> = {
  NOME_EMPRESA: 'Nome da Empresa',
  DESCRICAO_PRODUTO: 'Descrição do Produto',
  MARCA: 'Marca',
  REFERENCIA: 'Referência',
  PRECO_VENDA: 'Preço de Venda',
  PRECO_OFERTA: 'Preço de Oferta',
  SKU_BARRAS: 'Código de Barras (SKU)',
  EAN_BARRAS: 'Código de Barras (EAN)',
  VARIANTE_LINHA: 'Variação de Linha (ex.: cor)',
  VARIANTE_COLUNA: 'Variação de Coluna (ex.: tamanho)',
}

/** Ordem fixa de exibição na paleta — mesma ordem em que o dono do produto listou os campos. */
export const TODOS_OS_CAMPOS: CampoEtiqueta[] = [
  'NOME_EMPRESA', 'DESCRICAO_PRODUTO', 'MARCA', 'REFERENCIA', 'PRECO_VENDA', 'PRECO_OFERTA',
  'SKU_BARRAS', 'EAN_BARRAS', 'VARIANTE_LINHA', 'VARIANTE_COLUNA',
]

/** Campos de código de barras — únicos onde `exibirTextoLegivel` faz sentido e onde o canvas
 * desenha um código de barras de verdade em vez de texto. */
export const CAMPOS_DE_BARRAS: CampoEtiqueta[] = ['SKU_BARRAS', 'EAN_BARRAS']

/** Provisório (docs/telas/configuracao-etiqueta.md) — depende da tecnologia de impressão ainda
 * não decidida. */
export type FonteEtiqueta = 'ARIAL' | 'COURIER' | 'TIMES_NEW_ROMAN'

export const ROTULO_FONTE_ETIQUETA: Record<FonteEtiqueta, string> = {
  ARIAL: 'Arial',
  COURIER: 'Courier New',
  TIMES_NEW_ROMAN: 'Times New Roman',
}

/** CSS `font-family` correspondente — todas web-safe, sem precisar carregar fonte nenhuma. */
export const CSS_FONTE_ETIQUETA: Record<FonteEtiqueta, string> = {
  ARIAL: 'Arial, Helvetica, sans-serif',
  COURIER: "'Courier New', Courier, monospace",
  TIMES_NEW_ROMAN: "'Times New Roman', Times, serif",
}

export type AlinhamentoEtiquetaCampo = 'ESQUERDA' | 'CENTRO' | 'DIREITA'

export const CSS_ALINHAMENTO_ETIQUETA: Record<AlinhamentoEtiquetaCampo, 'left' | 'center' | 'right'> = {
  ESQUERDA: 'left',
  CENTRO: 'center',
  DIREITA: 'right',
}

export interface ColunaEtiqueta {
  numeroColuna: number
  posicaoInicialMm: number
}

/** Um campo posicionado na etiqueta — `posicaoXMm`/`posicaoYMm` relativos ao canto
 * superior-esquerdo da PRÓPRIA etiqueta (não do rolo). */
export interface CampoEtiquetaPosicionado {
  campo: CampoEtiqueta
  posicaoXMm: number
  posicaoYMm: number
  larguraMm: number | null
  alturaMm: number | null
  fonte: FonteEtiqueta
  tamanhoFontePt: number | null
  negrito: boolean
  fundoPreto: boolean
  alinhamento: AlinhamentoEtiquetaCampo
  exibirTextoLegivel: boolean | null
}

export interface EtiquetaConfig {
  idConfigEtiqueta: number
  nome: string
  larguraRoloMm: number
  numeroColunas: number
  larguraEtiquetaMm: number
  alturaEtiquetaMm: number
  bordaSuperiorMm: number
  bordaInferiorMm: number
  bordaEsquerdaMm: number
  bordaDireitaMm: number
  ativo: boolean
  colunas: ColunaEtiqueta[]
  campos: CampoEtiquetaPosicionado[]
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — strings pros campos de mm digitáveis do cabeçalho casarem com inputs
 * controlados (mesmo padrão de `TipoCarteiraFormState`); colunas/campos ficam com número puro
 * (editados por arraste/nudge/painel de propriedades, não digitação livre). */
export interface EtiquetaConfigFormState {
  nome: string
  larguraRoloMm: string
  numeroColunas: number
  larguraEtiquetaMm: string
  alturaEtiquetaMm: string
  bordaSuperiorMm: string
  bordaInferiorMm: string
  bordaEsquerdaMm: string
  bordaDireitaMm: string
  ativo: boolean
  colunas: ColunaEtiqueta[]
  campos: CampoEtiquetaPosicionado[]
}

export const ETIQUETA_CONFIG_VAZIA: EtiquetaConfigFormState = {
  nome: '',
  larguraRoloMm: '',
  numeroColunas: 1,
  larguraEtiquetaMm: '',
  alturaEtiquetaMm: '',
  bordaSuperiorMm: '',
  bordaInferiorMm: '',
  bordaEsquerdaMm: '',
  bordaDireitaMm: '',
  ativo: true,
  colunas: [{ numeroColuna: 1, posicaoInicialMm: 0 }],
  campos: [],
}

export function paraFormulario(ec: EtiquetaConfig): EtiquetaConfigFormState {
  return {
    nome: ec.nome,
    larguraRoloMm: formatarEtiquetaMm(ec.larguraRoloMm),
    numeroColunas: ec.numeroColunas,
    larguraEtiquetaMm: formatarEtiquetaMm(ec.larguraEtiquetaMm),
    alturaEtiquetaMm: formatarEtiquetaMm(ec.alturaEtiquetaMm),
    bordaSuperiorMm: formatarEtiquetaMm(ec.bordaSuperiorMm),
    bordaInferiorMm: formatarEtiquetaMm(ec.bordaInferiorMm),
    bordaEsquerdaMm: formatarEtiquetaMm(ec.bordaEsquerdaMm),
    bordaDireitaMm: formatarEtiquetaMm(ec.bordaDireitaMm),
    ativo: ec.ativo,
    colunas: ec.colunas,
    campos: ec.campos,
  }
}

function desmascararMmOuZero(valor: string): number {
  return valor.trim() ? desmascararEtiquetaMm(valor) : 0
}

export function paraRequisicao(f: EtiquetaConfigFormState) {
  return {
    nome: maiusculas(f.nome.trim()),
    larguraRoloMm: desmascararMmOuZero(f.larguraRoloMm),
    numeroColunas: f.numeroColunas,
    larguraEtiquetaMm: desmascararMmOuZero(f.larguraEtiquetaMm),
    alturaEtiquetaMm: desmascararMmOuZero(f.alturaEtiquetaMm),
    bordaSuperiorMm: desmascararMmOuZero(f.bordaSuperiorMm),
    bordaInferiorMm: desmascararMmOuZero(f.bordaInferiorMm),
    bordaEsquerdaMm: desmascararMmOuZero(f.bordaEsquerdaMm),
    bordaDireitaMm: desmascararMmOuZero(f.bordaDireitaMm),
    ativo: f.ativo,
    colunas: f.colunas,
    campos: f.campos,
  }
}

export interface PaginaEtiquetasConfig {
  itens: EtiquetaConfig[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoEtiquetaConfig {
  acao: 'excluido' | 'inativado'
  motivo: string | null
}

export type ColunaOrdenacaoEtiquetaConfig = 'nome' | 'larguraRoloMm' | 'numeroColunas' | 'ativo'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosEtiquetasConfig {
  busca?: string
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoEtiquetaConfig
  direcao?: DirecaoOrdenacao
}

export function listarEtiquetasConfig(filtros: FiltrosEtiquetasConfig): Promise<PaginaEtiquetasConfig> {
  const params = new URLSearchParams()
  if (filtros.busca) params.set('busca', filtros.busca)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaEtiquetasConfig>(`/api/v1/etiquetas-config${query ? `?${query}` : ''}`)
}

export function buscarEtiquetaConfig(id: number): Promise<EtiquetaConfig> {
  return api<EtiquetaConfig>(`/api/v1/etiquetas-config/${id}`)
}

export function criarEtiquetaConfig(payload: ReturnType<typeof paraRequisicao>): Promise<EtiquetaConfig> {
  return api<EtiquetaConfig>('/api/v1/etiquetas-config', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarEtiquetaConfig(
  id: number,
  payload: ReturnType<typeof paraRequisicao>,
): Promise<EtiquetaConfig> {
  return api<EtiquetaConfig>(`/api/v1/etiquetas-config/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function excluirEtiquetaConfig(id: number): Promise<ExclusaoEtiquetaConfig> {
  return api<ExclusaoEtiquetaConfig>(`/api/v1/etiquetas-config/${id}`, { method: 'DELETE' })
}

/** Produto de exemplo pra pré-visualizar a etiqueta com dado real (2026-08-04). DTO próprio —
 * não reaproveita `VariacaoEncontrada` do Kardex, que não tem referência/preço/EAN. */
export interface ProdutoExemplo {
  idVariacao: number
  sku: string
  ean: string | null
  descricao: string
  marca: string | null
  referencia: string | null
  precoVenda: number
  precoOferta: number | null
  dataInicioOferta: string | null
  dataFinalOferta: string | null
  variacaoLinha: string | null
  variacaoColuna: string | null
}

export function buscarProdutosExemplo(busca: string): Promise<ProdutoExemplo[]> {
  const params = new URLSearchParams()
  if (busca) params.set('busca', busca)
  return api<ProdutoExemplo[]>(`/api/v1/etiquetas-config/produtos-exemplo?${params.toString()}`)
}
