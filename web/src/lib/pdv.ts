import { api } from './api'

/** Estoque de uma variação numa empresa específica — `0` quando não há linha em `produto_estoque`. */
export interface EstoqueEmpresa {
  codigoEmpresa: number
  nomeEmpresa: string
  qtd: number
}

/** Uma variação (`produto_barra`) — `variacaoCor`/`variacaoTamanho` são `null` sem variação. */
export interface PdvProduto {
  idVariacao: number
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  sku: string
  precoVenda: number
  estoquePorEmpresa: EstoqueEmpresa[]
  estoqueTotal: number
  /** URL pública da primeira foto da galeria do produto (indice 0), `null` se não tiver foto. */
  urlImagem: string | null
  marca: string | null
  referencia: string | null
}

export interface ItemVendaRequest {
  idVariacao: number
  qtd: number
}

/** Resultado da busca de cliente (F6, 2026-07-28) — nome, CPF/CNPJ ou celular. */
export interface PdvCliente {
  idCliente: number
  nome: string
  cpfCnpj: string | null
  telefone: string | null
}

/** Um item lançado no ledger da venda em andamento (estado local da tela, antes do F5). */
export interface ItemLedger {
  idVariacao: number
  codigo: string
  descricao: string
  variacao: string | null
  qtd: number
  precoUnit: number
  urlImagem: string | null
}

/**
 * Uma linha de pagamento (split-tender, 2026-07-28) — `valorPago` é o valor tendido nessa
 * forma de pagamento; o quanto isso abate do saldo a pagar (desconto/acréscimo do tipo de
 * carteira) é calculado pelo servidor, nunca enviado daqui.
 */
export interface PagamentoRequest {
  idCarteira: number
  valorPago: number
  numeroParcelas: number
  /** Obrigatório só quando a carteira escolhida é VALE_MERCADORIA — número do vale
   *  (`venda_devolucao.id_devolucao`) sendo resgatado (2026-08-03). */
  idDevolucao?: number
}

export interface EfetivarVendaRequest {
  itens: ItemVendaRequest[]
  /** Desconto em R$ que o operador decidiu dar nesta venda (0 se nenhum) — nunca pode passar do
   *  máximo de `cfg_geral.percentual_desconto_venda`, validado também no servidor. */
  descontoVenda: number
  pagamentos: PagamentoRequest[]
  /** Cliente e vendedor são obrigatórios em toda venda do PDV (2026-07-28). */
  idCliente: number
  idFuncionario: number
}

export interface ParcelaGerada {
  numeroParcela: number
  dataVencimento: string
  valorParcela: number
  paga: boolean
}

export interface PagamentoGerado {
  idCarteira: number
  nomeCarteira: string
  valorPago: number
  parcelas: ParcelaGerada[]
}

export interface VendaEfetivada {
  idVenda: number
  valorTotalProdutos: number
  descontoVenda: number
  valorLiquido: number
  pagamentos: PagamentoGerado[]
}

export interface FiltrosBuscaProdutoPdv {
  busca?: string
  marca?: string
  referencia?: string
}

export function buscarProdutosPdv(filtros: FiltrosBuscaProdutoPdv): Promise<PdvProduto[]> {
  const params = new URLSearchParams()
  if (filtros.busca) params.set('busca', filtros.busca)
  if (filtros.marca) params.set('marca', filtros.marca)
  if (filtros.referencia) params.set('referencia', filtros.referencia)
  const query = params.toString()
  return api<PdvProduto[]>(`/api/v1/pdv/produtos${query ? `?${query}` : ''}`)
}

export function buscarProdutoPorCodigo(codigo: string): Promise<PdvProduto> {
  return api<PdvProduto>(`/api/v1/pdv/produtos/codigo/${encodeURIComponent(codigo)}`)
}

/**
 * Aceita "quantidade*código" no campo de código de barras (ex.: "5*9001000000138" — quantidade
 * 5, código 9001000000138), pra já lançar o item com a quantidade digitada em vez de sempre 1 —
 * pedido do dono do produto (2026-07-29) pra não precisar ler o mesmo código várias vezes
 * seguidas quando o cliente leva 5, 10 ou mais unidades do mesmo produto. Sem "*", o valor
 * inteiro é o código de barras e a quantidade é 1 (comportamento de sempre). Usado tanto no PDV
 * quanto na Transferência de Produtos — mesmo campo, mesma convenção.
 */
export function interpretarCodigoBarras(valor: string): { qtd: number; codigo: string } {
  const m = /^(\d+)\*(.+)$/.exec(valor.trim())
  if (!m) return { qtd: 1, codigo: valor.trim() }
  const qtd = Number(m[1])
  return { qtd: Number.isFinite(qtd) && qtd > 0 ? qtd : 1, codigo: m[2].trim() }
}

export function buscarClientesPdv(busca: string): Promise<PdvCliente[]> {
  const params = new URLSearchParams()
  if (busca) params.set('busca', busca)
  const query = params.toString()
  return api<PdvCliente[]>(`/api/v1/pdv/clientes${query ? `?${query}` : ''}`)
}

export function efetivarVenda(payload: EfetivarVendaRequest): Promise<VendaEfetivada> {
  return api<VendaEfetivada>('/api/v1/pdv/vendas', { method: 'POST', body: JSON.stringify(payload) })
}

/** Uma linha de item da papeleta de venda (2026-08-06) — `valorTotal` é bruto (unitário × qtd);
 *  desconto/acréscimo só aparecem somados no rodapé da papeleta, nunca por item. */
export interface ItemComprovanteVenda {
  sku: string
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtd: number
  valorUnitario: number
  valorTotal: number
}

/** `crediario` diferencia o rótulo na papeleta: "VALOR PAGO EM" (já circulou) vs "VALOR A PAGAR
 *  EM" (crediário, ainda em aberto — ver `parcelasCrediario`). */
export interface PagamentoComprovanteVenda {
  nomeCarteira: string
  crediario: boolean
  valorPago: number
}

/** Uma parcela de CREDIARIO em aberto (2026-08-06) — só existe na papeleta quando a venda teve
 *  pagamento nessa categoria (`parcelasCrediario` vem `[]` senão). */
export interface ParcelaComprovanteVenda {
  numeroParcela: number
  totalParcelas: number
  dataVencimento: string
  valorParcela: number
}

/**
 * Dados fiscais pra a papeleta virar DANFCE (§9.6, bloco B7) — `null` quando o fiscal está
 * desligado (F12) ou a nota não terminou autorizada/em contingência: nesses casos a papeleta sai
 * exatamente como sempre foi, sem nenhuma menção fiscal.
 */
export interface DadosFiscaisComprovante {
  chaveAcesso: string
  protocolo: string | null
  dataAutorizacao: string | null
  homologacao: boolean
  contingencia: boolean
  /** URL completa do QR Code (já com `?p=...`) — extraída do XML assinado, nunca remontada aqui. */
  qrCodeUrl: string
  urlConsultaChave: string
  valorTotalTributos: number
}

/** Papeleta de venda pra impressão térmica 80mm, buscada logo após o F5 efetivar a venda.
 *  `nomeVendedor`/`nomeOperador` podem vir `null` (venda gravada antes de existir vínculo). */
export interface ComprovanteVenda {
  idVenda: number
  nomeEmpresa: string
  codigoEmpresa: number
  dataVenda: string
  nomeCliente: string | null
  telefoneCliente: string | null
  nomeVendedor: string | null
  nomeOperador: string | null
  itens: ItemComprovanteVenda[]
  subtotal: number
  descontos: number
  acrescimos: number
  totalAPagar: number
  pagamentos: PagamentoComprovanteVenda[]
  parcelasCrediario: ParcelaComprovanteVenda[]
  dadosFiscais: DadosFiscaisComprovante | null
}

export function buscarComprovanteVenda(idVenda: number): Promise<ComprovanteVenda> {
  return api<ComprovanteVenda>(`/api/v1/pdv/vendas/${idVenda}/comprovante`)
}

/** Situações possíveis da emissão (§9.1/B7) — espelha `EmissaoNfceService.ResultadoEmissao`. */
export type SituacaoEmissaoNfce =
  | 'AUTORIZADO'
  | 'REJEITADO'
  | 'DENEGADO'
  | 'EM_PROCESSAMENTO'
  | 'FALHA_COMUNICACAO'
  | 'CONTINGENCIA'
  | 'NAO_EMITIDO'

export interface ResultadoEmissaoNfce {
  situacao: SituacaoEmissaoNfce
  idDocumentoFiscal: number
  chaveAcesso: string | null
  protocolo: string | null
  cStat: string | null
  mensagem: string
}

/**
 * Dispara a emissão da NFC-e depois que o F5 já efetivou a venda (F3: a venda nunca depende
 * disto). `null` quando o fiscal está desligado para a empresa (204, F12) — a tela não mostra
 * nada, como se o módulo fiscal não existisse.
 */
export async function emitirNfce(idVenda: number): Promise<ResultadoEmissaoNfce | null> {
  const resposta = await api<ResultadoEmissaoNfce | undefined>(`/api/v1/pdv/vendas/${idVenda}/nfce`, {
    method: 'POST',
  })
  return resposta ?? null
}
