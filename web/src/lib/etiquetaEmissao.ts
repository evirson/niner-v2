import { api } from './api'
import type { ProdutoExemplo } from './etiquetaConfig'
import { dataParaIso } from './masks'

/** `nomeVarianteLinha`/`nomeVarianteColuna` (2026-08-05, revisão) — não nulos quando ESTE produto
 * usa aquela dimensão (configurado no cadastro dele, não uma flag global de tenant); dizem à tela
 * de seleção Individual se o respectivo seletor de variação deve aparecer, e como obrigatório. */
export interface ProdutoOpcaoEmissao {
  idProduto: number
  descricao: string
  marca: string | null
  referencia: string | null
  nomeVarianteLinha: string | null
  nomeVarianteColuna: string | null
}

export interface FornecedorOpcaoEmissao {
  idFornecedor: number
  razaoSocial: string
}

export interface OpcaoVarianteEmissao {
  id: number
  descricao: string
}

export interface OpcoesVarianteEmissao {
  variantesLinha: OpcaoVarianteEmissao[]
  variantesColuna: OpcaoVarianteEmissao[]
}

/** Uma variação vinda do backend — mesmo shape de `ProdutoExemplo`, + a quantidade sugerida
 * (nula no modo Individual, calculada nos modos Por Entradas/Por Estoques). */
export interface ProdutoEmissao extends ProdutoExemplo {
  quantidadeSugerida: number | null
}

/** Uma linha da grade de emissão (item 4 do pedido) — 100% estado local até "Emitir Etiquetas",
 * sem persistência nenhuma no servidor. `quantidade` é sempre inteira (não dá pra imprimir meia
 * etiqueta, mesmo quando a sugestão veio de um estoque/entrada com quantidade fracionária). */
export interface ItemEmissao {
  idVariacao: number
  sku: string
  descricao: string
  marca: string | null
  referencia: string | null
  precoVenda: number
  variacaoLinha: string | null
  variacaoColuna: string | null
  quantidade: number
}

export function buscarProdutosEmissao(busca: string): Promise<ProdutoOpcaoEmissao[]> {
  const params = new URLSearchParams()
  if (busca) params.set('busca', busca)
  return api<ProdutoOpcaoEmissao[]>(`/api/v1/etiqueta-emissao/produtos?${params.toString()}`)
}

export function buscarOpcoesVarianteEmissao(): Promise<OpcoesVarianteEmissao> {
  return api<OpcoesVarianteEmissao>('/api/v1/etiqueta-emissao/variantes')
}

/** Acha a variação (produto + linha + coluna) já cadastrada, ou CRIA na hora se ainda não existir
 * (item 1 do pedido de revisão, 2026-08-05) — só o modo Individual precisa disso; Por Entradas/
 * Por Estoques só trazem variação que já existe (senão não teria movimento/estoque). */
export function criarOuObterVariacaoEmissao(
  idProduto: number,
  variacao: { idVarianteLinha: number | null; idVarianteColuna: number | null },
): Promise<ProdutoEmissao> {
  return api<ProdutoEmissao>(`/api/v1/etiqueta-emissao/produtos/${idProduto}/variacao`, {
    method: 'POST',
    body: JSON.stringify(variacao),
  })
}

export function buscarFornecedoresEmissao(busca: string): Promise<FornecedorOpcaoEmissao[]> {
  const params = new URLSearchParams()
  if (busca) params.set('busca', busca)
  return api<FornecedorOpcaoEmissao[]>(`/api/v1/etiqueta-emissao/fornecedores?${params.toString()}`)
}

export interface FiltrosEntradasEmissao {
  dataDe: string
  dataAte: string
  idFornecedor: number | null
  notaFiscal: string
}

export const FILTROS_ENTRADAS_EMISSAO_VAZIO: FiltrosEntradasEmissao = {
  dataDe: '',
  dataAte: '',
  idFornecedor: null,
  notaFiscal: '',
}

export function buscarPorEntradas(f: FiltrosEntradasEmissao): Promise<ProdutoEmissao[]> {
  const params = new URLSearchParams()
  if (f.dataDe.trim()) params.set('dataDe', dataParaIso(f.dataDe) ?? '')
  if (f.dataAte.trim()) params.set('dataAte', dataParaIso(f.dataAte) ?? '')
  if (f.idFornecedor) params.set('idFornecedor', String(f.idFornecedor))
  if (f.notaFiscal.trim()) params.set('notaFiscal', f.notaFiscal.trim())
  return api<ProdutoEmissao[]>(`/api/v1/etiqueta-emissao/entradas?${params.toString()}`)
}

export function buscarPorEstoques(idEmpresa: number | null, idCategoria: number | null): Promise<ProdutoEmissao[]> {
  const params = new URLSearchParams()
  if (idEmpresa) params.set('idEmpresa', String(idEmpresa))
  if (idCategoria) params.set('idCategoria', String(idCategoria))
  return api<ProdutoEmissao[]>(`/api/v1/etiqueta-emissao/estoques?${params.toString()}`)
}

/** Arredonda pra inteiro mais próximo, mínimo 1 — quantidade de estoque/entrada pode vir
 * fracionária (produto vendido por peso/medida), mas etiqueta é sempre uma unidade inteira. */
export function paraQuantidadeInteira(valor: number | null): number {
  if (valor == null) return 1
  return Math.max(1, Math.round(valor))
}

export function produtoEmissaoParaItem(p: ProdutoEmissao, quantidade: number): ItemEmissao {
  return {
    idVariacao: p.idVariacao,
    sku: p.sku,
    descricao: p.descricao,
    marca: p.marca,
    referencia: p.referencia,
    precoVenda: p.precoVenda,
    variacaoLinha: p.variacaoLinha,
    variacaoColuna: p.variacaoColuna,
    quantidade,
  }
}

export function paraProdutoExemplo(item: ItemEmissao): ProdutoExemplo {
  return {
    idVariacao: item.idVariacao,
    sku: item.sku,
    descricao: item.descricao,
    marca: item.marca,
    referencia: item.referencia,
    precoVenda: item.precoVenda,
    variacaoLinha: item.variacaoLinha,
    variacaoColuna: item.variacaoColuna,
  }
}

/** Junta itens novos na grade — mesma variação já presente soma a quantidade em vez de duplicar
 * a linha (ex.: usuário busca "Por Estoques" duas vezes com filtros que se sobrepõem). */
export function mesclarItensEmissao(atual: ItemEmissao[], novos: ItemEmissao[]): ItemEmissao[] {
  const porVariacao = new Map(atual.map((item) => [item.idVariacao, item]))
  for (const novo of novos) {
    const existente = porVariacao.get(novo.idVariacao)
    porVariacao.set(novo.idVariacao, existente ? { ...existente, quantidade: existente.quantidade + novo.quantidade } : novo)
  }
  return Array.from(porVariacao.values())
}

/** Achata a grade numa sequência de "1 produto por etiqueta" (cada item repetido pela própria
 * quantidade) — é essa sequência que a impressão em lote distribui pelas colunas/linhas do rolo. */
export function montarSequenciaImpressao(itens: ItemEmissao[]): ProdutoExemplo[] {
  const sequencia: ProdutoExemplo[] = []
  for (const item of itens) {
    const produto = paraProdutoExemplo(item)
    for (let i = 0; i < item.quantidade; i++) sequencia.push(produto)
  }
  return sequencia
}

export function totalDeEtiquetas(itens: ItemEmissao[]): number {
  return itens.reduce((soma, item) => soma + item.quantidade, 0)
}
