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

export type TipoItem = 'MERCADORIA' | 'SERVICO'

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
  idGrade: number | null
  descricaoGrade: string | null
  ativo: boolean
  categorias: CategoriaSelecionada[]
  imagens: ImagemProduto[]
  idPerfilFiscal: number | null
  /** MERCADORIA (padrão) ou SERVICO — V085. */
  tipoItem: TipoItem
  duracaoMinutos: number | null
  percComissaoServico: number | null
  nomePerfilFiscal: string | null
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
  idGrade: number | null
  descricaoGrade: string | null
  ativo: boolean
  /** Categorias escolhidas, na ordem de exibição — o índice na lista é o `indice` enviado à API. */
  categorias: CategoriaSelecionada[]
  /** Fiscal (2026-08-18, `docs/MODULOFISCAL.md` §6.2/DF3) — opcional aqui de propósito: quem
   * cobra o preenchimento antes de emitir é a tela de Conformidade Fiscal, não este cadastro. */
  idPerfilFiscal: number | null
  /** ⚠️ Só escolhido na CRIAÇÃO: o tipo é imutável (trigger da V085) e a tela desabilita o
   *  seletor ao editar — trocar deixaria estoque, relatórios e notas já emitidas
   *  descrevendo o item como ele era. */
  tipoItem: TipoItem
  duracaoMinutos: string
  percComissaoServico: string
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
  idGrade: null,
  descricaoGrade: null,
  ativo: true,
  categorias: [],
  idPerfilFiscal: null,
  tipoItem: 'MERCADORIA',
  duracaoMinutos: '',
  percComissaoServico: '',
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
    idGrade: p.idGrade,
    descricaoGrade: p.descricaoGrade,
    ativo: p.ativo,
    categorias: [...p.categorias].sort((a, b) => a.indice - b.indice),
    idPerfilFiscal: p.idPerfilFiscal,
    tipoItem: p.tipoItem ?? 'MERCADORIA',
    duracaoMinutos: p.duracaoMinutos == null ? '' : String(p.duracaoMinutos),
    percComissaoServico: p.percComissaoServico == null ? '' : formatarPercentual(p.percComissaoServico),
  }
}

/**
 * Monta o corpo da requisição: máscaras removidas, vazio vira null, texto em MAIÚSCULAS.
 *
 * ⭐ **E é aqui que a regra "serviço não tem esses campos" fica travada de verdade** (pedido do
 * dono do produto, 2026-08-31: serviço não tem Marca, Referência, NCM, os três campos de oferta,
 * nem Categorias). Esconder na tela não basta: os valores continuam no estado do formulário, e
 * quem preenchesse a descrição, a marca e o NCM e **só então** marcasse "Serviço" mandaria tudo
 * assim mesmo — com os campos invisíveis e ninguém vendo. É a mesma armadilha das fotos, que
 * seriam enviadas depois do POST.
 *
 * ⚠️ O `ProdutoForm` **também** limpa esses campos ao trocar o tipo, mas por outro motivo: para a
 * tela não guardar dado fantasma que reaparece se o operador voltar para "Mercadoria". Uma coisa é
 * a interface, outra é o que sai no corpo — e a segunda é a que não pode falhar.
 */
export function paraRequisicao(f: ProdutoFormState) {
  const maiusculoOuNulo = (v: string) => (v.trim() ? maiusculas(v.trim()) : null)
  const ehServico = f.tipoItem === 'SERVICO'
  const soMercadoria = <T,>(valor: T): T | null => (ehServico ? null : valor)
  return {
    descricao: maiusculas(f.descricao.trim()),
    marca: soMercadoria(maiusculoOuNulo(f.marca)),
    referencia: soMercadoria(maiusculoOuNulo(f.referencia)),
    precoCusto: desmascararMoeda(f.precoCusto),
    percentualVenda: desmascararPercentual(f.percentualVenda),
    precoVenda: desmascararMoeda(f.precoVenda),
    dataInicioOferta: soMercadoria(paraIsoOuNulo(f.dataInicioOferta)),
    dataFinalOferta: soMercadoria(paraIsoOuNulo(f.dataFinalOferta)),
    precoOferta: soMercadoria(f.precoOferta ? desmascararMoeda(f.precoOferta) : null),
    codigoNcm: soMercadoria(f.codigoNcm ? somenteDigitos(f.codigoNcm) : null),
    pesoBruto: desmascararPeso(f.pesoBruto),
    pesoLiquido: desmascararPeso(f.pesoLiquido),
    idGrade: f.idGrade,
    ativo: f.ativo,
    categorias: ehServico ? [] : f.categorias.map((c) => c.idCategoria),
    idPerfilFiscal: f.idPerfilFiscal,
    tipoItem: f.tipoItem,
    // Só fazem sentido em serviço; vazio vira null (zero é valor legítimo em percentual).
    duracaoMinutos: f.tipoItem === 'SERVICO' && f.duracaoMinutos.trim() ? Number(f.duracaoMinutos) : null,
    percComissaoServico: f.tipoItem === 'SERVICO' && f.percComissaoServico.trim()
      ? desmascararPercentual(f.percComissaoServico)
      : null,
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

export function listarMarcas(): Promise<string[]> {
  return api<string[]>('/api/v1/produtos/marcas')
}

/** Uma variação (`produto_barra`) já com produto/cor/tamanho resolvidos. `ean` (2026-08-11,
 *  Entrada de Produtos por Compra) é o código de barras do fabricante, distinto do `sku` interno. */
export interface VariacaoProduto {
  idVariacao: number
  sku: string
  ean: string | null
  descricao: string
  marca: string | null
  referencia: string | null
  precoVenda: number
  variacaoCor: string | null
  variacaoTamanho: string | null
}

/**
 * Cria produto + primeira variação numa TRANSAÇÃO SÓ — caminho do cadastro rápido
 * (auditoria 2026-08-21, item 28; endpoint criado em 2026-08-22).
 *
 * ⚠️ Substitui a sequência `criarProduto` → `criarVariacao`, que não era atômica: falhando a
 * variação (EAN repetido vindo de planilha/XML de terceiro), sobrava um produto sem SKU e sem
 * código de barras, invisível no PDV, com a tela dizendo que a criação falhou — e clicar de novo
 * criava um segundo órfão.
 *
 * Devolve a VARIAÇÃO, que já traz descrição, marca, referência e preço do produto — é com ela que
 * o chamador segue (lançar o item no PDV, na entrada).
 */
export function criarProdutoComVariacao(
  produto: ReturnType<typeof paraRequisicao>,
  variacao: { idCor: number | null; idTamanho: number | null; ean?: string | null },
): Promise<VariacaoProduto> {
  return api<VariacaoProduto>('/api/v1/produtos/com-variacao', {
    method: 'POST',
    body: JSON.stringify({ produto, variacao }),
  })
}

/** Acha a variação já cadastrada pra essa combinação produto/cor/tamanho, ou cria na hora
 *  (gera o SKU). `ean` só é gravado se esta chamada de fato criar a variação. */
export function criarVariacao(
  idProduto: number,
  variacao: { idCor: number | null; idTamanho: number | null; ean?: string | null },
): Promise<VariacaoProduto> {
  return api<VariacaoProduto>(`/api/v1/produtos/${idProduto}/variacoes`, {
    method: 'POST',
    body: JSON.stringify(variacao),
  })
}
