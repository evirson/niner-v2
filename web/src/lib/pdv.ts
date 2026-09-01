import { api } from './api'
import type { TipoItem } from './produtos'

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
  /** `'MERCADORIA'` ou `'SERVICO'` (S1). Serviço não tem saldo — `estoqueTotal` vem 0 e não
   *  significa "acabou": a tela não pode tratar os dois do mesmo jeito. */
  tipoItem: TipoItem
}

export interface ItemVendaRequest {
  idVariacao: number
  qtd: number
  /** `true` = esta linha é coberta pelo orçamento e sai com o preço CONGELADO dele; `false`/ausente
   *  = venda comum, preço do cadastro. Ver {@link ItemLedger.qtdOrcada} e `dividirParaEnvio`. */
  doOrcamento?: boolean
  /**
   * `true` = esta linha veio da **Ordem de Serviço** puxada no F5 (S4).
   *
   * <p>⛔ É uma marca **separada** de `doOrcamento`, não um sinônimo: orçamento e OS são
   * documentos diferentes, e a mesma venda nunca carrega os dois (o servidor recusa). O que a
   * marca faz aqui é o mesmo que lá — dizer ao servidor quais linhas conferir contra o documento
   * de origem, com o preço que aquele documento fechou.
   */
  daOrdemServico?: boolean
}

/**
 * Divide as linhas do ledger nos itens que o servidor recebe (2026-08-21).
 *
 * <p>Uma linha da tela pode valer DOIS itens no envio: a parte que o orçamento cobre, com o preço
 * congelado que a loja se comprometeu a honrar, e o que passar disso, com o preço de hoje. É o que
 * permite ao operador ver "3 × camiseta" numa linha só quando o preço não mudou, sem que o servidor
 * recuse a venda por "não pode levar mais do que foi orçado" — a regra do orçamento se aplica só à
 * parte orçada.
 *
 * <p>Quando o preço MUDOU, a tela já mantém duas linhas separadas (`lancarProduto` só junta com
 * preço igual) e cada uma vira um item, com a sua própria marca.
 */
export function dividirParaEnvio(itens: ItemLedger[]): ItemVendaRequest[] {
  const envio: ItemVendaRequest[] = []
  for (const item of itens) {
    // ⚠️ `qtdOrcada` serve aos DOIS documentos porque eles nunca convivem na mesma venda (o
    // servidor recusa orçamento + OS juntos). Quem diz QUAL documento é `origemDocumento` — e a
    // marca enviada muda com ele, senão o servidor procuraria a linha no documento errado e
    // recusaria a venda falando de um documento que o operador nem abriu.
    const doDocumento = Math.min(item.qtd, item.qtdOrcada)
    if (doDocumento > 0) {
      envio.push(
        item.origemDocumento === 'OS'
          ? { idVariacao: item.idVariacao, qtd: doDocumento, daOrdemServico: true }
          : { idVariacao: item.idVariacao, qtd: doDocumento, doOrcamento: true },
      )
    }
    const excedente = item.qtd - doDocumento
    if (excedente > 0) {
      envio.push({ idVariacao: item.idVariacao, qtd: excedente, doOrcamento: false })
    }
  }
  return envio
}

/** Resultado da busca de cliente (F6, 2026-07-28) — nome, CPF/CNPJ ou celular. */
export interface PdvCliente {
  idCliente: number
  nome: string
  cpfCnpj: string | null
  telefone: string | null
}

/** Um item lançado no ledger da venda em andamento (estado local da tela, antes do F6). */
export interface ItemLedger {
  /**
   * Chave da LINHA, não do produto (2026-08-21).
   *
   * <p>⚠️ Antes o `codigo` (SKU) fazia esse papel, e isso deixou de funcionar quando o mesmo
   * produto passou a poder aparecer duas vezes na venda com preços diferentes — o orçado, que a
   * loja honra, e o de hoje, para as unidades a mais. Com o SKU como chave, alterar a quantidade
   * de uma dessas linhas alterava as DUAS, e remover uma removia as duas.
   */
  idLinha: number
  idVariacao: number
  codigo: string
  descricao: string
  variacao: string | null
  qtd: number
  precoUnit: number
  urlImagem: string | null
  /**
   * Quanto desta linha está coberto pelo orçamento (0 = nada; a linha é venda comum).
   *
   * <p>É o que permite juntar numa linha só o que foi orçado e o que o cliente resolveu levar a
   * mais quando o preço não mudou: a tela mostra uma linha, e o envio a divide em duas — a parte
   * orçada com o preço congelado, o excedente com o preço de hoje. Sem isso, o servidor recusaria
   * a venda por "não pode levar mais do que foi orçado".
   *
   * <p>⚠️ Desde a Ordem de Serviço (S4) este número também conta as linhas vindas de uma **OS** —
   * o teto e o preço fechado funcionam igual. Quem diz de qual documento a linha veio é
   * {@link ItemLedger.origemDocumento}; o nome do campo ficou por ser o caso original.
   */
  qtdOrcada: number
  /**
   * De qual documento esta linha veio: `'ORCAMENTO'`, `'OS'` ou ausente (venda comum).
   *
   * <p>⛔ Não dá para inferir pelo estado da tela: os dois documentos preenchem `qtdOrcada` do
   * mesmo jeito, e mandar a marca errada faz o servidor procurar a linha no documento errado.
   */
  origemDocumento?: 'ORCAMENTO' | 'OS'
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
  /** Orcamento que originou a venda (V058). O servidor usa o preco CONGELADO dele e recusa
   *  quantidade maior que a orcada. */
  idOrcamento?: number | null
  /**
   * Ordem de Serviço que originou a venda (V087, S4) — mesmo efeito do orçamento: preço fechado
   * na OS e teto de quantidade. Ao efetivar, a OS passa a FATURADA e a reserva de estoque das
   * peças é liberada (o estoque sai de verdade agora, pela venda).
   *
   * <p>⛔ Nunca junto com `idOrcamento`: o servidor recusa os dois na mesma venda, porque cada
   * um traria o próprio preço fechado para a mesma linha e não há como desempatar.
   */
  idOrdemServico?: number | null
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
  unidadeComercial: string | null
  valorUnitario: number
  valorTotal: number
  /** `'SERVICO'` faz a papeleta abrir um bloco separado, com subtotal próprio (S4). */
  tipoItem: TipoItem
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
  /** Documento que este comprovante representa — abre o DANFE A4 na reimpressão de venda PJ. */
  idDocumentoFiscal: number
  /**
   * ⚠️ `65` = NFC-e (cupom térmico), `55` = NF-e (DANFE A4). Sem este campo a tela só sabia que
   * "tem documento fiscal" e imprimia o DANFCE para os dois — o cupom de uma venda a pessoa
   * jurídica saía dizendo "NFC-e … Consumidor Final" sobre uma NF-e 55 (2026-08-29).
   */
  modelo: number
  chaveAcesso: string
  protocolo: string | null
  dataAutorizacao: string | null
  homologacao: boolean
  contingencia: boolean
  /**
   * URL completa do QR Code (já com `?p=...`) — extraída do XML assinado, nunca remontada aqui.
   *
   * ⚠️ **`null` quando a nota é NF-e modelo 55** (2026-08-29): QR Code e URL de consulta são do
   * DANFE **NFC-e**; a NF-e 55 não tem nem um nem outro. O tipo dizia `string` — e essa mentira
   * foi exatamente o que impediu o `tsc -b` de apontar o defeito que apagava a tela ao reimprimir
   * a papeleta de uma venda a pessoa jurídica. Campo que o servidor pode devolver nulo é
   * `| null` **no tipo**, senão o type-check vira carimbo.
   */
  qrCodeUrl: string | null
  /** `null` na NF-e 55, pelo mesmo motivo de `qrCodeUrl`. */
  urlConsultaChave: string | null
  valorTotalTributos: number
  numero: number
  serie: number
  /** CPF/CNPJ (só dígitos) que foi para o `<dest>` do XML — `null` = consumidor não identificado. */
  documentoConsumidor: string | null
  /** Reforma tributária (LC 214/2025), somados da nota inteira (2026-08-19, DANFE). */
  baseIbsCbs: number
  valorIbsUf: number
  valorIbsMun: number
  valorCbs: number
  /** Detalhamento da Lei 12.741 (2026-08-19) — os 3 somam `valorTotalTributos`. */
  valorTribFederal: number
  valorTribEstadual: number
  valorTribMunicipal: number
}

/** Cabeçalho fiscal completo da empresa (2026-08-19, DANFE) — só presente quando `dadosFiscais`
 *  também está presente; `enderecoCompleto` já vem formatado pronto pra imprimir. */
export interface EmpresaComprovante {
  razaoSocial: string
  cnpj: string | null
  inscricaoEstadual: string | null
  enderecoCompleto: string | null
  telefone: string | null
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
  /** CPF/CNPJ do cliente da venda (2026-08-19) — `null` sem cliente ou sem documento cadastrado;
   *  é o que decide se a pergunta "incluir CPF na nota?" oferece a opção "sim". */
  documentoCliente: string | null
  nomeVendedor: string | null
  /** Código (`id_funcionario`) do vendedor (2026-08-19, DANFE) — o "VEN.: nn" do rodapé fiscal. */
  codigoVendedor: number | null
  /** `venda.id_caixa` (2026-08-19, DANFE). */
  numeroCaixa: number | null
  nomeOperador: string | null
  itens: ItemComprovanteVenda[]
  subtotal: number
  descontos: number
  acrescimos: number
  totalAPagar: number
  pagamentos: PagamentoComprovanteVenda[]
  parcelasCrediario: ParcelaComprovanteVenda[]
  dadosFiscais: DadosFiscaisComprovante | null
  empresaFiscal: EmpresaComprovante | null
  cancelada: boolean
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
  /** ⭐ A venda não tem MERCADORIA — logo não existe NFC-e/NF-e a emitir (2026-09-01). É o caso
   *  normal de petshop e de consultório, e NÃO é falha: o documento dela é a NFS-e, que vem em
   *  `nfse` na mesma resposta. Por isso não reaproveita `NAO_EMITIDO`, que a tela pinta de
   *  vermelho — dizer "não emitida" de um documento que não deveria existir manda o operador
   *  procurar um defeito que não há. */
  | 'SEM_MERCADORIA'

/** Uma NFS-e da venda — espelha `NfseEmissaoService.Resultado`. */
export interface ResultadoNfseDaVenda {
  idNfse: number
  situacao: string
  chaveAcesso: string | null
  numeroNfse: number | null
  codigo: string | null
  mensagem: string
}

export interface ResultadoEmissaoNfce {
  situacao: SituacaoEmissaoNfce
  idDocumentoFiscal: number
  /** 65 (NFC-e) ou 55 (NF-e). Decide qual documento o PDV imprime: DANFCE térmico ou DANFE A4
   *  (2026-08-25). Quem escolhe o modelo é o servidor, a partir do cliente da venda. */
  modelo: number
  chaveAcesso: string | null
  protocolo: string | null
  cStat: string | null
  mensagem: string
  /** ⭐ O que ficou SEM documento. Desde 2026-09-01 o PDV emite a NFS-e, então este aviso só
   *  aparece quando a NFS-e está DESLIGADA para a empresa — com ela ligada, uma nota que falha
   *  aparece em `nfse` com o motivo, e repetir o aviso ali diria que falta configurar algo que já
   *  está configurado. `null` na esmagadora maioria das vendas. */
  avisoServicos: string | null
  /** ⭐ As NFS-e da mesma venda (2026-09-01, pendência #78). É uma LISTA porque a DPS carrega UM
   *  código de serviço: banho/tosa + consulta veterinária na mesma venda rendem DUAS notas, cada
   *  uma com sua alíquota e local de incidência. Vazia quando a venda não tem serviço ou a NFS-e
   *  está desligada. Cada nota traz o próprio desfecho — uma falhar não invalida a NFC-e, que é
   *  documento de outro imposto. */
  nfse: ResultadoNfseDaVenda[]
}

/**
 * Dispara a emissão da NFC-e depois que o F5 já efetivou a venda (F3: a venda nunca depende
 * disto). `null` quando o fiscal está desligado para a empresa (204, F12) — a tela não mostra
 * nada, como se o módulo fiscal não existisse.
 *
 * `incluirCpf` (2026-08-19) — resposta da pergunta feita ao operador antes de emitir: `true`
 * inclui o CPF/CNPJ do cliente da venda na nota; `false` emite pra consumidor não identificado,
 * mesmo que a venda tenha cliente vinculado. Nunca mais decidido sozinho a partir do cliente da
 * venda — ver `ComprovantePapeletaModal.tsx`.
 */
export async function emitirNfce(idVenda: number, incluirCpf: boolean, observacao?: string): Promise<ResultadoEmissaoNfce | null> {
  const resposta = await api<ResultadoEmissaoNfce | undefined>(`/api/v1/pdv/vendas/${idVenda}/nfce`, {
    method: 'POST',
    body: JSON.stringify({ incluirCpf, observacao: observacao?.trim() || null }),
  })
  return resposta ?? null
}
