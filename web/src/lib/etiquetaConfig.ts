import { api } from './api'
import { desmascararEtiquetaMm, formatarEtiquetaMm } from './masks'
import { maiusculas } from './texto'

/** Os 4 campos possíveis numa etiqueta — cada um mapeia pra uma coluna já existente no schema
 * (ver docs/telas/configuracao-etiqueta.md). Marca/Referência/Cor/Tamanho não são mais campos
 * próprios (2026-08-05, pedido do dono do produto): entram
 * automaticamente dentro de `DESCRICAO_PRODUTO` via {@link montarDescricaoImpressa}, pra não
 * precisar posicionar 5 campos separados só pra imprimir a descrição completa. */
export type CampoEtiqueta =
  | 'NOME_EMPRESA'
  | 'DESCRICAO_PRODUTO'
  | 'PRECO_VENDA'
  | 'SKU_BARRAS'

export const ROTULO_CAMPO_ETIQUETA: Record<CampoEtiqueta, string> = {
  NOME_EMPRESA: 'Nome da Empresa',
  DESCRICAO_PRODUTO: 'Descrição do Produto',
  PRECO_VENDA: 'Preço de Venda',
  SKU_BARRAS: 'Código de Barras (SKU)',
}

/** Ordem fixa de exibição na paleta — mesma ordem em que o dono do produto listou os campos. */
export const TODOS_OS_CAMPOS: CampoEtiqueta[] = [
  'NOME_EMPRESA', 'DESCRICAO_PRODUTO', 'PRECO_VENDA', 'SKU_BARRAS',
]

/** Campos de código de barras — únicos onde `exibirTextoLegivel` faz sentido e onde o canvas
 * desenha um código de barras de verdade em vez de texto. */
export const CAMPOS_DE_BARRAS: CampoEtiqueta[] = ['SKU_BARRAS']

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
  /** Espaço em branco ENTRE fileiras do rolo (V056). O passo vertical da impressão é
   *  `alturaEtiquetaMm + espacamentoVerticalMm`. 0 = fileiras coladas. */
  espacamentoVerticalMm: number
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
  espacamentoVerticalMm: string
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
  espacamentoVerticalMm: '0,00',
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
    espacamentoVerticalMm: formatarEtiquetaMm(ec.espacamentoVerticalMm ?? 0),
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
    espacamentoVerticalMm: desmascararMmOuZero(f.espacamentoVerticalMm),
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
 * não reaproveita `VariacaoEncontrada` do Kardex, que não tem referência/preço. */
export interface ProdutoExemplo {
  idVariacao: number
  sku: string
  descricao: string
  marca: string | null
  referencia: string | null
  precoVenda: number
  variacaoCor: string | null
  variacaoTamanho: string | null
}

/**
 * Descrição "completa" impressa no campo `DESCRICAO_PRODUTO` (2026-08-05, pedido do dono do
 * produto): concatena descrição + marca + referência + cor + tamanho (2026-08-08, substituiu
 * variação de linha/coluna), nessa ordem, pulando qualquer pedaço vazio OU que já apareça dentro
 * da descrição (comparação sem diferenciar maiúsculas/minúsculas, mas os 5 campos já nascem em maiúsculas — convenção do
 * projeto — então normalmente é comparação direta) — evita repetir "ADIDAS ADIDAS" quando a
 * marca já está escrita na descrição, por exemplo. Sem produto de exemplo, cai no texto
 * genérico de layout (sem simular marca/referência/variação, que não existem ainda).
 */
export function montarDescricaoImpressa(produto: ProdutoExemplo | null): string {
  if (!produto) return 'Descrição do Produto'
  const descricao = produto.descricao.trim()
  const complementos = [produto.marca, produto.referencia, produto.variacaoCor, produto.variacaoTamanho]
  const partes = [descricao]
  for (const complemento of complementos) {
    const valor = complemento?.trim()
    if (valor && !descricao.toUpperCase().includes(valor.toUpperCase())) {
      partes.push(valor)
    }
  }
  return partes.join(' ')
}

export function buscarProdutosExemplo(busca: string): Promise<ProdutoExemplo[]> {
  const params = new URLSearchParams()
  if (busca) params.set('busca', busca)
  return api<ProdutoExemplo[]>(`/api/v1/etiquetas-config/produtos-exemplo?${params.toString()}`)
}

/** Conversão mm→px pro Teste de Impressão (2026-08-05): 96 px CSS = 1 polegada = 25,4mm, a
 * mesma equivalência que o navegador usa ao mandar a página pra impressora — diferente do
 * `PX_POR_MM_BASE` do editor (zoom de tela arbitrário), aqui o valor precisa corresponder ao
 * tamanho físico real impresso (a página em si é dimensionada em mm via `@page`). */
export const MM_PARA_PX_IMPRESSAO = 96 / 25.4

/** Distribui `quantidade` etiquetas pelas colunas configuradas, uma linha por vez — mesmo jeito
 * que o rolo físico avança sob a impressora. A última linha pode ficar parcial (ex.: 3 colunas,
 * 8 etiquetas → 3+3+2). */
export function linhasParaImprimir(quantidade: number, colunas: ColunaEtiqueta[]): ColunaEtiqueta[][] {
  if (colunas.length === 0 || quantidade <= 0) return []
  const ordenadas = [...colunas].sort((a, b) => a.numeroColuna - b.numeroColuna)
  const linhas: ColunaEtiqueta[][] = []
  let restante = quantidade
  while (restante > 0) {
    const tamanho = Math.min(ordenadas.length, restante)
    linhas.push(ordenadas.slice(0, tamanho))
    restante -= tamanho
  }
  return linhas
}
