import { api, apiUpload, ApiError, getToken } from './api'
import { API_BASE } from './config'

/** Um item lançado no ledger da entrada (mesmo shape do detalhe gravado em `produto_movimento_detalhe`).
 *  `precoVenda` (2026-08-12, fluxo Individual com grade) só vem preenchido quando o item passou
 *  pelo grid de tamanhos (custo + %venda do produto sugerem o valor, editável antes de lançar) —
 *  quando presente, vira o novo `preco_venda` do produto na confirmação. */
export interface ItemEntradaRequest {
  idVariacao: number
  qtd: number
  precoCusto: number
  precoVenda?: number | null
  /** Código do produto no fornecedor (`cProd` do XML, 2026-08-12) — quando presente, a
   *  confirmação aprende/atualiza `produto_fornecedor`: a próxima nota do mesmo fornecedor com
   *  este código já resolve sozinha. Só o fluxo XML preenche. */
  codigoFornecedor?: string | null
  /** NCM do XML (2026-08-13) — quando presente e diferente do NCM já cadastrado no produto, a
   *  confirmação SUBSTITUI (o NCM do XML sempre vale). Só o fluxo XML preenche. */
  ncm?: string | null
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
  /** Cancelamento de Entrada (2026-08-12) — grid principal mostra a linha marcada, sem esconder. */
  cancelada: boolean
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
  cancelada: boolean
  dataCancelamento: string | null
  motivoCancelamento: string | null
}

export interface CancelamentoEntradaEfetivadoResponse {
  idMovimento: number
  dataCancelamento: string
}

/** Cancelamento de Entrada (2026-08-12) — ADMIN-only, ver docs do backend
 *  (`EntradaMercadoriaService#cancelar`) pra regras de bloqueio. */
export function cancelarEntrada(idMovimento: number, motivo: string): Promise<CancelamentoEntradaEfetivadoResponse> {
  return api<CancelamentoEntradaEfetivadoResponse>(`/api/v1/estoque/entradas/${idMovimento}/cancelar`, {
    method: 'POST',
    body: JSON.stringify({ motivo }),
  })
}

export type ColunaOrdenacaoEntrada = 'dataMovimento' | 'fornecedor' | 'notaFiscal'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosEntradas {
  idFornecedor?: number
  idEmpresa?: number
  notaFiscal?: number
  /** "aaaa-mm-dd" */
  dataInicial?: string
  /** "aaaa-mm-dd" */
  dataFinal?: string
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoEntrada
  direcao?: DirecaoOrdenacao
}

export function listarEntradas(filtros: FiltrosEntradas): Promise<PaginaEntradas> {
  const params = new URLSearchParams()
  if (filtros.idFornecedor) params.set('idFornecedor', String(filtros.idFornecedor))
  if (filtros.idEmpresa) params.set('idEmpresa', String(filtros.idEmpresa))
  if (filtros.notaFiscal) params.set('notaFiscal', String(filtros.notaFiscal))
  if (filtros.dataInicial) params.set('dataInicial', filtros.dataInicial)
  if (filtros.dataFinal) params.set('dataFinal', filtros.dataFinal)
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
  /** `cProd` do XML (2026-08-12) — `null` no fluxo Planilha. `cor`/`tamanho` acima são só
   *  PALPITE nesse caso (nunca resolvem/cadastram sozinhos — o XML sempre exige confirmação
   *  manual do operador, diferente da Planilha). */
  codigoFornecedor: string | null
  /** NCM do XML, já validado contra o cadastro de NCM (2026-08-13) — `null` no fluxo Planilha
   *  (sem coluna de NCM). Em linha resolvida, segue até a confirmação e substitui o NCM do
   *  produto se vier diferente. Em pendência, pré-preenche o NCM do cadastro rápido. */
  ncm: string | null
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

/** Resultado da pesquisa de produto do fluxo Individual (2026-08-12) — nível produto (não
 *  variação): sem cor/tamanho/estoque, só o necessário pra escolher o produto e montar a grade
 *  de tamanhos + custo/%venda/preço de venda em seguida. `idGrade` nulo ⇒ produto sem grade. */
export interface ProdutoOpcaoEntrada {
  idProduto: number
  descricao: string
  marca: string | null
  referencia: string | null
  idGrade: number | null
  precoCusto: number
  percentualVenda: number
}

export function buscarProdutosEntrada(busca: string, marca: string, referencia: string): Promise<ProdutoOpcaoEntrada[]> {
  const params = new URLSearchParams()
  if (busca) params.set('busca', busca)
  if (marca) params.set('marca', marca)
  if (referencia) params.set('referencia', referencia)
  const query = params.toString()
  return api<ProdutoOpcaoEntrada[]>(`/api/v1/estoque/entradas/produtos${query ? `?${query}` : ''}`)
}

/** Fornecedor casado pelo CNPJ do emitente do XML (2026-08-12, Fase 3) — `idFornecedor` nulo
 *  ⇒ não achou no cadastro; os demais campos vêm do próprio XML, prontos pra pré-preencher o
 *  cadastro rápido de fornecedor se o operador optar por criar um novo. */
export interface FornecedorXmlPreview {
  idFornecedor: number | null
  cnpj: string | null
  razaoSocial: string | null
  nomeFantasia: string | null
  inscricaoEstadual: string | null
  endereco: string | null
  numero: string | null
  bairro: string | null
  cidade: string | null
  estado: string | null
  cep: string | null
  telefone: string | null
}

export interface DuplicataXmlPreview {
  numeroDuplicata: string | null
  dataVencimento: string
  valor: number
}

/** Pré-entrada do fluxo XML (2026-08-12, Fase 3) — parse do arquivo + tentativa de casar cada
 *  item (EAN → `produto_fornecedor` aprendido → pendência), sem gravar nada. `xmlBruto` deve
 *  ser reenviado, sem alteração nenhuma, no `POST /api/v1/estoque/entradas` de confirmação. */
export interface EntradaXmlPreview {
  chaveNfe: string
  serieNota: number | null
  notaFiscal: number | null
  dataEmissao: string | null
  chaveJaImportada: boolean
  fornecedor: FornecedorXmlPreview
  /** Empresa do tenant casada pelo CNPJ do destinatário (`dest/CNPJ`) do XML (2026-08-12) —
   *  `null` sem match (mantém o padrão de sempre: empresa da sessão, trocável no select). */
  idEmpresaEncontrada: number | null
  valorTotalNota: number
  duplicatas: DuplicataXmlPreview[]
  itens: ItemPlanilhaPreviewResponse[]
  xmlBruto: string
}

export function previewXmlEntrada(arquivo: File): Promise<EntradaXmlPreview> {
  const fd = new FormData()
  fd.append('arquivo', arquivo)
  return apiUpload('/api/v1/estoque/entradas/xml/preview', fd)
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
