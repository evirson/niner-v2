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

/**
 * Geometria do rolo (V057) — o mínimo que descreve TODA a folha de etiquetas.
 *
 * <p>A posição de cada etiqueta é **derivada**, não guardada. O modelo anterior tinha uma tabela
 * `cfg_etiqueta_coluna` com a posição de cada coluna digitada à mão, e era exatamente ali que o
 * erro entrava: 3 colunas de 34 mm gravadas em 3/41/79 (passo 38) num rolo de passo 40 faziam o
 * texto sair progressivamente cortado, e a tela mostrava fielmente o número errado. Derivando de
 * medidas físicas — as que se tiram com a régua — esse erro deixa de ser representável.
 */
export interface GeometriaRolo {
  larguraRoloMm: number
  numeroColunas: number
  larguraEtiquetaMm: number
  alturaEtiquetaMm: number
  margemEsquerdaMm: number
  espacamentoHorizontalMm: number
  espacamentoVerticalMm: number
}

/** x (mm) do canto esquerdo da coluna `indice` (base 0) dentro do rolo. */
export function xDaColuna(g: Pick<GeometriaRolo, 'margemEsquerdaMm' | 'larguraEtiquetaMm' | 'espacamentoHorizontalMm'>, indice: number): number {
  return g.margemEsquerdaMm + indice * (g.larguraEtiquetaMm + g.espacamentoHorizontalMm)
}

/**
 * Passo vertical (mm) de uma fileira para a seguinte — altura do adesivo + o branco entre elas.
 *
 * ⚠️ **NÃO é a altura da página de impressão** (2026-08-24). Foi por acreditar que era que a
 * etiqueta alta saía partida em duas. Ver {@link alturaPaginaImpressaoMm}.
 */
export function passoVertical(g: Pick<GeometriaRolo, 'alturaEtiquetaMm' | 'espacamentoVerticalMm'>): number {
  return g.alturaEtiquetaMm + g.espacamentoVerticalMm
}

/**
 * Altura (mm) da página de impressão de UMA fileira — a altura do **adesivo**, sem o branco entre
 * fileiras (2026-08-24).
 *
 * <p><b>Por que não é o passo.</b> A impressora térmica faz duas coisas em sequência: imprime o
 * bitmap da página e **depois avança o papel até o próximo gap**. Quem produz o espaço entre
 * fileiras é esse avanço, não o nosso desenho. Somando o gap à página, o bitmap cobria o adesivo
 * <b>e</b> o vão — e o avanço seguinte ainda somava o vão de novo, desencontrando tudo.
 *
 * <p><b>A evidência.</b> Os dois relatórios do sistema Delphi que imprimem certo nesta mesma
 * impressora declaram o papel com a altura do adesivo, nunca com o passo:
 * <ul>
 *   <li>modelo 30 × 34: {@code mmPaperHeight = 30000} (30 mm)</li>
 *   <li>modelo 60 × 35: {@code mmPaperHeight = 60000} (60 mm)</li>
 * </ul>
 *
 * <p>⚠️ <b>Por que o modelo de 31,7 mm funcionava mesmo com a conta errada:</b> o papel do driver
 * estava em 31,0 mm e <b>cortava</b> a nossa página de 33,9 — sobrando por acaso ≈ a altura do
 * adesivo. O erro estava lá o tempo todo, escondido por esse corte. Foi por isso que aumentar o
 * papel do driver para 62 mm não consertou a etiqueta alta: sem o corte, a página de 61,2 mm
 * passou inteira e o desencontro apareceu por completo.
 *
 * <p>Continua valendo <b>uma página por fileira</b> — a parte certa da decisão de 2026-08-21. O
 * que muda é só o valor.
 */
export function alturaPaginaImpressaoMm(g: Pick<GeometriaRolo, 'alturaEtiquetaMm'>): number {
  return g.alturaEtiquetaMm
}

/**
 * Folga (mm) entre o bloco de uma fileira e a página que o contém.
 *
 * <p>Enquanto a página valia o passo do rolo, a folga vinha de graça: o bloco tinha a altura do
 * adesivo e a página tinha o adesivo + o vão. Agora que as duas valem a mesma coisa
 * ({@link alturaPaginaImpressaoMm}), o bloco encosta na página — e bloco tão alto quanto a página
 * é o caso limite da paginação: um arredondamento de sub-pixel o empurra para a página seguinte e
 * nasce **uma página em branco entre cada etiqueta**, gastando o rolo inteiro pela metade.
 *
 * <p>Dois décimos de milímetro resolvem e não deslocam nada: os campos são posicionados em
 * absoluto a partir do topo do bloco, então encurtar a base não move nenhum deles.
 */
export const FOLGA_PAGINACAO_MM = 0.2

/** Altura (mm) do bloco de uma fileira — a página menos {@link FOLGA_PAGINACAO_MM}. */
export function alturaBlocoFileiraMm(g: Pick<GeometriaRolo, 'alturaEtiquetaMm'>): number {
  return Math.max(alturaPaginaImpressaoMm(g) - FOLGA_PAGINACAO_MM, 1)
}

/*
 * ⚠️ Aqui existiam `yDaFileira` e `alturaFolhaMm` (2026-08-21, manhã), que montavam TODAS as
 * fileiras numa folha só e posicionavam cada uma pelo índice. Foram removidas na tarde do mesmo
 * dia, quando a impressora explicou por que a ideia não podia dar certo.
 *
 * <p>Uma impressora térmica de etiqueta **não imprime folha**. A Argox OS-2140 do dono do produto
 * estava com mídia "etiquetas cortadas com molde" (die-cut) e papel de 101,6 × 152,4 mm no driver.
 * Mandar uma página de 102 × 634 mm (40 etiquetas em 20 fileiras) fez o driver fatiar o trabalho
 * em pedaços de 152,4 mm — e como 152,4 não é múltiplo do passo de 31,7, cada corte caía no meio
 * de um adesivo: saíam ~5 fileiras em branco antes da impressão começar, e a primeira etiqueta já
 * nascia 3 mm fora. Nenhum ajuste de milímetro na geometria alcança esse erro, porque quem decide
 * a origem vertical nesse arranjo é o driver, não o CSS.
 *
 * <p>O modelo certo é o inverso: **uma página por fileira**, com a página valendo exatamente o
 * passo do rolo ({@link passoVertical}). Aí o sensor de gap da impressora encaixa cada página no
 * começo de um adesivo, que é o trabalho para o qual ele existe — e o erro de passo, que era o
 * pesadelo da folha longa, deixa de existir por construção: ele não tem onde acumular, porque
 * cada fileira recomeça do zero físico. Continua valendo o alerta que a versão anterior trouxe
 * (não empilhar blocos de altura fracionária); a quebra de página resolve isso de graça.
 */

/** Onde cada coluna começa, em ordem — usado pela tela como conferência e pelas prévias. */
export function posicoesDasColunas(g: Pick<GeometriaRolo, 'numeroColunas' | 'margemEsquerdaMm' | 'larguraEtiquetaMm' | 'espacamentoHorizontalMm'>): number[] {
  return Array.from({ length: Math.max(g.numeroColunas, 0) }, (_, i) => Number(xDaColuna(g, i).toFixed(2)))
}

/** mm que as colunas ocupam no rolo, da borda até o fim da última — o que passar disso é cortado. */
export function larguraOcupadaPelasColunas(g: Pick<GeometriaRolo, 'numeroColunas' | 'margemEsquerdaMm' | 'larguraEtiquetaMm' | 'espacamentoHorizontalMm'>): number {
  if (g.numeroColunas <= 0) return 0
  return Number((xDaColuna(g, g.numeroColunas - 1) + g.larguraEtiquetaMm).toFixed(2))
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
  margemEsquerdaMm: number
  espacamentoHorizontalMm: number
  espacamentoVerticalMm: number
  ativo: boolean
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
  margemEsquerdaMm: string
  espacamentoHorizontalMm: string
  espacamentoVerticalMm: string
  ativo: boolean
  campos: CampoEtiquetaPosicionado[]
}

export const ETIQUETA_CONFIG_VAZIA: EtiquetaConfigFormState = {
  nome: '',
  larguraRoloMm: '',
  numeroColunas: 1,
  larguraEtiquetaMm: '',
  alturaEtiquetaMm: '',
  margemEsquerdaMm: '0,00',
  espacamentoHorizontalMm: '0,00',
  espacamentoVerticalMm: '0,00',
  ativo: true,
  campos: [],
}

export function paraFormulario(ec: EtiquetaConfig): EtiquetaConfigFormState {
  return {
    nome: ec.nome,
    larguraRoloMm: formatarEtiquetaMm(ec.larguraRoloMm),
    numeroColunas: ec.numeroColunas,
    larguraEtiquetaMm: formatarEtiquetaMm(ec.larguraEtiquetaMm),
    alturaEtiquetaMm: formatarEtiquetaMm(ec.alturaEtiquetaMm),
    margemEsquerdaMm: formatarEtiquetaMm(ec.margemEsquerdaMm ?? 0),
    espacamentoHorizontalMm: formatarEtiquetaMm(ec.espacamentoHorizontalMm ?? 0),
    espacamentoVerticalMm: formatarEtiquetaMm(ec.espacamentoVerticalMm ?? 0),
    ativo: ec.ativo,
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
    margemEsquerdaMm: desmascararMmOuZero(f.margemEsquerdaMm),
    espacamentoHorizontalMm: desmascararMmOuZero(f.espacamentoHorizontalMm),
    espacamentoVerticalMm: desmascararMmOuZero(f.espacamentoVerticalMm),
    ativo: f.ativo,
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

/** Distribui `quantidade` etiquetas pelas colunas, uma fileira por vez — mesmo jeito que o rolo
 * físico avança sob a impressora. Devolve ÍNDICES de coluna (base 0); a posição de cada uma sai de
 * {@link xDaColuna}. A última fileira pode ficar parcial (ex.: 3 colunas, 8 etiquetas → 3+3+2). */
export function linhasParaImprimir(quantidade: number, numeroColunas: number): number[][] {
  if (numeroColunas <= 0 || quantidade <= 0) return []
  const linhas: number[][] = []
  let restante = quantidade
  while (restante > 0) {
    const tamanho = Math.min(numeroColunas, restante)
    linhas.push(Array.from({ length: tamanho }, (_, i) => i))
    restante -= tamanho
  }
  return linhas
}
