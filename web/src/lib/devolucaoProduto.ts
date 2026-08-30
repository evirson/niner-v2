import { api } from './api'

export interface ItemDevolucaoRequest {
  idVariacao: number
  qtd: number
  /**
   * Preço da LINHA da venda que está sendo devolvida (2026-08-22, auditoria item 2).
   *
   * ⚠️ O mesmo produto pode aparecer duas vezes na mesma venda com preços diferentes — o congelado
   * do orçamento, que a loja honrou, e o do dia, das unidades levadas na hora. Sem mandar o preço,
   * o servidor usava a MÉDIA das duas, e o vale saía por um valor que a venda nunca praticou (1 un
   * de uma venda 1×80 + 1×120 gerava vale de 100). O mesmo valor médio ia para a NF-e 55.
   *
   * Opcional: devolução sem venda de origem não tem linha para apontar, e omitir mantém o
   * comportamento antigo.
   */
  precoUnitario?: number
}

/** `numeroVenda` é opcional — só usado para resolver o vendedor (comissão futura). */
export interface EfetivarDevolucaoRequest {
  numeroVenda: number | null
  itens: ItemDevolucaoRequest[]
}

/** Item vendido na venda de origem, com quanto ainda pode ser devolvido dele (`qtdVendida` menos
 *  o que já foi devolvido em devoluções não canceladas da mesma venda) — quando a venda é
 *  informada, a tela só aceita produtos presentes nesta lista, até `qtdDisponivelDevolucao`. */
export interface ItemVendaOrigem {
  idVariacao: number
  sku: string
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtdVendida: number
  qtdDisponivelDevolucao: number
  /** ⚠️ Preço BRUTO praticado na venda (não o do cadastro atual). É a chave de linha que casa a
   *  devolução com a venda — o que o cliente pagou é `precoUnitario - descontoUnitario`. */
  precoUnitario: number
  /** Desconto por unidade desta linha da venda (2026-08-29). */
  descontoUnitario: number
  /** ⚠️ LÍQUIDO: `(precoUnitario - descontoUnitario) × qtdVendida` — o que o vale vai valer. */
  valorTotal: number
}

export interface VendedorDaVenda {
  numeroVenda: number
  idFuncionario: number | null
  nomeFuncionario: string | null
  itens: ItemVendaOrigem[]
}

export interface ItemDevolucaoResponse {
  idVariacao: number
  sku: string
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtd: number
  /** ⚠️ BRUTO — é a chave de linha que casa com a linha da venda; nunca o líquido. */
  precoVenda: number
  /** Desconto rateado desta linha; é ele que explica `qtd × precoVenda ≠ valorTotal`. */
  valorDesconto: number
  /** ⚠️ LÍQUIDO desde 2026-08-29 (`qtd × precoVenda − valorDesconto`) — o que o cliente PAGOU. */
  valorTotal: number
}

/**
 * Desfecho da NF-e de devolução (modelo 55, entrada) emitida junto — §10.2, B9.
 *
 * ⚠️ Situação diferente de `AUTORIZADO` **não desfaz a devolução**: a mercadoria já voltou ao
 * estoque e o vale já é do cliente (F3 — fiscal nunca bloqueia a operação de balcão). A nota fica
 * em Documentos Fiscais para ser reprocessada.
 */
export interface NotaFiscalDevolucao {
  /**
   * ⚠️ `FALHA_NA_EMISSAO` NÃO vem do enum `situacao_documento_fiscal` do banco — é um valor que só
   * o controller produz (2026-08-30), quando a emissão estourou **antes** de existir linha em
   * `documento_fiscal`. A diferença importa na tela: os outros valores são reprocessáveis em
   * Documentos Fiscais, este não é (não há o que reprocessar) — mandar o operador procurar lá faz
   * com que ele não ache nada e conclua que o sistema perdeu a nota.
   */
  situacao:
    | 'AUTORIZADO'
    | 'REJEITADO'
    | 'DENEGADO'
    | 'EM_PROCESSAMENTO'
    | 'FALHA_COMUNICACAO'
    | 'FALHA_NA_EMISSAO'
  idDocumentoFiscal: number
  chaveAcesso: string | null
  protocolo: string | null
  cStat: string | null
  mensagem: string
}

/** `idDevolucao` é o número do vale-mercadoria gerado (toda devolução gera um, 2026-08-03);
 *  `valorVale` é a soma dos itens devolvidos. `notaFiscal` é `null` quando não havia nota a
 *  emitir (fiscal desligado, devolução sem venda de origem, ou venda sem NFC-e autorizada). */
export interface DevolucaoEfetivada {
  idMovimento: number
  idDevolucao: number
  valorVale: number
  dataMovimento: string
  idFuncionario: number | null
  nomeFuncionario: string | null
  itens: ItemDevolucaoResponse[]
  notaFiscal: NotaFiscalDevolucao | null
}

export interface ValeMercadoria {
  idDevolucao: number
  valorVale: number
  valeUsado: boolean
  cancelada: boolean
  dataDevolucao: string
  idVendaCredito: number | null
  idVendaDebito: number | null
}

export function buscarVendedorDaVenda(numeroVenda: number): Promise<VendedorDaVenda> {
  return api<VendedorDaVenda>(`/api/v1/vendas/devolucao/vendedor?numeroVenda=${numeroVenda}`)
}

export function efetivarDevolucao(payload: EfetivarDevolucaoRequest): Promise<DevolucaoEfetivada> {
  return api<DevolucaoEfetivada>('/api/v1/vendas/devolucao', { method: 'POST', body: JSON.stringify(payload) })
}

/** Consulta um vale-mercadoria pelo número — usada tanto pra reimprimir quanto pelo PDV na hora
 *  de resgatar (FormaPagamentoModal, categoria VALE_MERCADORIA). */
export function buscarVale(idDevolucao: number): Promise<ValeMercadoria> {
  return api<ValeMercadoria>(`/api/v1/vendas/devolucao/vale/${idDevolucao}`)
}
