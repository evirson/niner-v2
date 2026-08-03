import { useMutation, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarDescontoVenda } from '../../lib/configuracaoGeral'
import { buscarVale, type ValeMercadoria } from '../../lib/devolucaoProduto'
import type { Funcionario } from '../../lib/funcionarios'
import {
  completarMoeda,
  completarPercentual,
  desmascararMoeda,
  desmascararPercentual,
  formatarMoeda,
  formatarPercentual,
  mascararMoeda,
  mascararPercentual,
} from '../../lib/masks'
import { efetivarVenda, type ItemLedger, type PagamentoRequest, type PdvCliente, type VendaEfetivada } from '../../lib/pdv'
import {
  ROTULO_CATEGORIA_CARTEIRA,
  listarTiposCarteira,
  type CategoriaCarteira,
  type TipoCarteira,
} from '../../lib/tiposCarteira'
import PesquisaClienteModal from './PesquisaClienteModal'
import PesquisaVendedorModal from './PesquisaVendedorModal'

const CATEGORIAS_ORDEM: CategoriaCarteira[] = ['AVISTA', 'CARTAO_DEBITO', 'CARTAO_CREDITO', 'CREDIARIO', 'VALE_MERCADORIA']

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function arredondar2(v: number): number {
  return Math.round((v + Number.EPSILON) * 100) / 100
}

/** À vista/débito/vale-mercadoria só aceitam 1 parcela (regra também reforçada no backend). */
function pagaNaHora(categoria: CategoriaCarteira): boolean {
  return categoria === 'AVISTA' || categoria === 'CARTAO_DEBITO' || categoria === 'VALE_MERCADORIA'
}

/** Mesma fórmula do backend (`PdvVendaService.resolverPagamentos`) — só pra pré-visualizar.
 *  Desconto/acréscimo incide sobre o valor pago (retificado 2026-07-28): pagar R$78 numa forma
 *  com 10% de desconto dá R$7,80 de desconto. */
function calcularCobertura(valorPago: number, carteira: TipoCarteira): number {
  const desconto = carteira.percDesconto ? (valorPago * carteira.percDesconto) / 100 : 0
  const acrescimo = carteira.percAcrescimo ? (valorPago * carteira.percAcrescimo) / 100 : 0
  return arredondar2(valorPago + desconto - acrescimo)
}

interface ParcelaPrevia {
  numeroParcela: number
  valor: number
  vencimento: Date
}

/** Mesma conta do backend (`PdvVendaService.gerarEInserirParcelas`) — só pra pré-visualizar. */
function calcularPrevia(valorPago: number, numeroParcelas: number, prazoPagamento: number): ParcelaPrevia[] {
  const base = Math.floor((valorPago / numeroParcelas) * 100) / 100
  const ajusteUltima = Math.round((valorPago - base * numeroParcelas) * 100) / 100
  const hoje = new Date()
  const parcelas: ParcelaPrevia[] = []
  for (let n = 1; n <= numeroParcelas; n++) {
    const vencimento = new Date(hoje)
    vencimento.setDate(vencimento.getDate() + prazoPagamento * n)
    parcelas.push({ numeroParcela: n, valor: n === numeroParcelas ? base + ajusteUltima : base, vencimento })
  }
  return parcelas
}

/** Uma linha de pagamento já lançada nesta venda (estado local, antes de confirmar).
 *  `idDevolucao` (2026-08-03) só existe em linhas VALE_MERCADORIA — número do vale resgatado. */
interface LinhaLocal {
  chave: string
  carteira: TipoCarteira
  valorPago: number
  numeroParcelas: number
  cobertura: number
  idDevolucao?: number
}

const TOLERANCIA_SALDO = 0.01

/** Máximo (e valor sugerido) de `valorPago` numa linha: nunca pode passar do que ainda falta
 *  pagar. Com desconto próprio, o teto é o valor que fecha o saldo sozinho, sem sobra —
 *  calculado ao contrário da fórmula de `calcularCobertura` (`saldoAtual ÷ (1 + percDesconto/100)`,
 *  mesma regra do backend). */
function calcularValorPagoMaximo(saldoAtual: number, carteira: TipoCarteira | null): number {
  const saldo = Math.max(saldoAtual, 0)
  if (carteira?.percDesconto) {
    return arredondar2(saldo / (1 + carteira.percDesconto / 100))
  }
  return arredondar2(saldo)
}

/**
 * F5 — Efetiva Venda (docs/telas/pdv.md): split-tender (2026-07-28), revisado 2026-07-29 —
 * `cfg_geral.percentual_desconto_venda` deixou de ser aplicado automaticamente: é só o
 * desconto **máximo** permitido, e o operador digita (% ou R$, campos sincronizados) o
 * desconto que quer dar nesta venda, nunca acima desse máximo. O usuário cobre o saldo
 * resultante com uma ou mais formas de pagamento (categoria → tipo de carteira → valor). Cada
 * linha abate do saldo pelo que "cobre" (valor pago ajustado pelo desconto/acréscimo do tipo
 * de carteira escolhido); "X% de desconto/acréscimo" é sempre sobre o valor **pago** — o valor
 * pago sugerido/máximo de uma linha com desconto próprio é o que fecha o saldo restante
 * sozinha, sem sobra (`saldo ÷ (1 + %desconto)`) — a venda só pode ser confirmada quando o
 * saldo a pagar chega a zero.
 */
export default function FormaPagamentoModal({
  itens,
  valorTotal,
  aoFechar,
  aoEfetivada,
}: {
  itens: ItemLedger[]
  valorTotal: number
  aoFechar: () => void
  aoEfetivada: (resultado: VendaEfetivada) => void
}) {
  const [pagamentos, setPagamentos] = useState<LinhaLocal[]>([])
  const [descontoPercentualTexto, setDescontoPercentualTexto] = useState('')
  const [descontoValorTexto, setDescontoValorTexto] = useState('')
  const [categoriaAberta, setCategoriaAberta] = useState<CategoriaCarteira | null>(null)
  const [idCarteiraEdicao, setIdCarteiraEdicao] = useState<number | null>(null)
  const [valorPagoTexto, setValorPagoTexto] = useState('')
  const [numeroParcelasEdicao, setNumeroParcelasEdicao] = useState(1)
  const [toast, setToast] = useState('')
  /** Resgate de vale-mercadoria (2026-08-03) — número digitado, resultado da busca (ou erro) e
   *  estado de carregamento; separado do restante da edição porque o valor não é digitável, só
   *  vem do vale encontrado. */
  const [numeroValeTexto, setNumeroValeTexto] = useState('')
  const [valeResolvido, setValeResolvido] = useState<ValeMercadoria | null>(null)
  const [buscandoVale, setBuscandoVale] = useState(false)
  const [erroVale, setErroVale] = useState<string | null>(null)
  /** Cliente e vendedor são obrigatórios em toda venda do PDV (2026-07-28). */
  const [clienteSelecionado, setClienteSelecionado] = useState<PdvCliente | null>(null)
  const [vendedorSelecionado, setVendedorSelecionado] = useState<Funcionario | null>(null)
  const [mostrarPesquisaCliente, setMostrarPesquisaCliente] = useState(false)
  const [mostrarPesquisaVendedor, setMostrarPesquisaVendedor] = useState(false)

  const { data: pagina, isLoading } = useQuery({
    queryKey: ['tipos-carteira-pdv'],
    queryFn: () => listarTiposCarteira({ tamanho: 100 }),
  })
  const { data: desconto } = useQuery({
    queryKey: ['pdv-desconto-venda'],
    queryFn: buscarDescontoVenda,
  })

  const carteiras = pagina?.itens ?? []
  const percentualMaximoPermitido = desconto?.percentualDescontoVenda ?? 0
  const temDesconto = percentualMaximoPermitido > 0

  const valorTotalProdutos = valorTotal
  const descontoValorMaximo = arredondar2((valorTotalProdutos * percentualMaximoPermitido) / 100)
  const descontoVenda = temDesconto ? Math.min(desmascararMoeda(descontoValorTexto || '0'), descontoValorMaximo) : 0
  const valorLiquido = arredondar2(valorTotalProdutos - descontoVenda)

  /** Editável só antes do primeiro pagamento lançado — evita recalcular saldo já coberto. */
  const descontoTravado = pagamentos.length > 0

  const aoSairDescontoPercentual = (texto: string) => {
    const completo = completarPercentual(texto)
    const percentual = Math.min(Math.max(desmascararPercentual(completo), 0), percentualMaximoPermitido)
    const valor = arredondar2((valorTotalProdutos * percentual) / 100)
    setDescontoPercentualTexto(formatarPercentual(percentual))
    setDescontoValorTexto(formatarMoeda(valor))
  }

  const aoSairDescontoValor = (texto: string) => {
    const completo = completarMoeda(texto)
    const valor = Math.min(Math.max(desmascararMoeda(completo), 0), descontoValorMaximo)
    const percentual = valorTotalProdutos > 0 ? arredondar2((valor / valorTotalProdutos) * 100) : 0
    setDescontoValorTexto(formatarMoeda(valor))
    setDescontoPercentualTexto(formatarPercentual(percentual))
  }

  const somaCobertura = pagamentos.reduce((soma, p) => soma + p.cobertura, 0)
  const saldoAPagar = arredondar2(valorLiquido - somaCobertura)
  const saldoFechado = pagamentos.length > 0 && Math.abs(saldoAPagar) < TOLERANCIA_SALDO

  /** Soma do desconto/acréscimo de cada linha de pagamento já lançada — "calculado pela moeda
   *  paga" (tipo_carteira escolhido em cada linha), exibido como Desconto Promocional no
   *  resumo. Começa em R$0,00 antes da primeira linha ser adicionada. */
  const descontoPromocionalAcumulado = arredondar2(
    pagamentos.reduce((soma, p) => soma + (p.cobertura - p.valorPago), 0),
  )
  /** Saldo que ainda falta cobrir (2026-07-28, corrigido) — precisa descontar tanto o
   *  desconto acumulado quanto o que já foi pago (`somaCobertura` já soma os dois por linha),
   *  senão a tela mostrava só "Sub-Total − desconto" e não caía conforme as formas de
   *  pagamento eram lançadas. */
  const valorAPagar = saldoAPagar
  const valorPagoAcumulado = arredondar2(pagamentos.reduce((soma, p) => soma + p.valorPago, 0))

  const carteirasDaCategoria = categoriaAberta ? carteiras.filter((c) => c.categoriaCarteira === categoriaAberta) : []
  const carteiraEdicao = carteirasDaCategoria.find((c) => c.idCarteira === idCarteiraEdicao) ?? null
  const soAVistaEdicao = carteiraEdicao ? pagaNaHora(carteiraEdicao.categoriaCarteira) : false
  const opcoesParcelasEdicao = carteiraEdicao
    ? Array.from(
        { length: carteiraEdicao.pcMaxima - carteiraEdicao.pcMinima + 1 },
        (_, i) => carteiraEdicao.pcMinima + i,
      )
    : []

  const valorPagoMaximoEdicao = calcularValorPagoMaximo(saldoAPagar, carteiraEdicao)
  const valorPagoBruto = desmascararMoeda(valorPagoTexto || '0')
  const valorPagoNumero = Math.min(valorPagoBruto, valorPagoMaximoEdicao)
  const coberturaEdicao = carteiraEdicao ? calcularCobertura(valorPagoNumero, carteiraEdicao) : 0

  const previaEdicao = useMemo(
    () =>
      carteiraEdicao && valorPagoNumero > 0
        ? calcularPrevia(valorPagoNumero, numeroParcelasEdicao, carteiraEdicao.prazoPagamento)
        : [],
    [carteiraEdicao, numeroParcelasEdicao, valorPagoNumero],
  )

  const abrirCategoria = (categoria: CategoriaCarteira) => {
    const daCategoria = carteiras.filter((c) => c.categoriaCarteira === categoria)
    if (daCategoria.length === 0) return
    setCategoriaAberta(categoria)
    const unica = daCategoria.length === 1 ? daCategoria[0] : null
    setIdCarteiraEdicao(unica?.idCarteira ?? null)
    setNumeroParcelasEdicao(unica && pagaNaHora(unica.categoriaCarteira) ? 1 : (unica?.pcMinima ?? 1))
    setValorPagoTexto(formatarMoeda(calcularValorPagoMaximo(saldoAPagar, unica)))
    setNumeroValeTexto('')
    setValeResolvido(null)
    setErroVale(null)
  }

  const buscarValeMercadoria = async () => {
    const numero = Number(numeroValeTexto.trim())
    if (!numeroValeTexto.trim() || !Number.isFinite(numero) || numero <= 0) return
    setBuscandoVale(true)
    setErroVale(null)
    setValeResolvido(null)
    try {
      const vale = await buscarVale(numero)
      if (vale.valeUsado) {
        setErroVale(`O vale nº ${numero} já foi usado.`)
        return
      }
      if (vale.valorVale > saldoAPagar + TOLERANCIA_SALDO) {
        setErroVale(
          `O vale nº ${numero} vale ${moeda(vale.valorVale)}, maior que o saldo a pagar de ${moeda(Math.max(saldoAPagar, 0))} — não é possível usar um vale maior que a venda.`,
        )
        return
      }
      setValeResolvido(vale)
    } catch (e) {
      setErroVale(e instanceof ApiError ? e.message : 'Não foi possível localizar o vale-mercadoria.')
    } finally {
      setBuscandoVale(false)
    }
  }

  /** Trocar de tipo de carteira (dropdown, quando a categoria tem mais de uma) já traz o Valor
   *  Pago sugerido pra essa carteira — com desconto embutido quando ela tiver um (2026-07-28). */
  const selecionarCarteiraEdicao = (idCarteira: number) => {
    setIdCarteiraEdicao(idCarteira)
    const carteira = carteirasDaCategoria.find((c) => c.idCarteira === idCarteira) ?? null
    setNumeroParcelasEdicao(carteira && pagaNaHora(carteira.categoriaCarteira) ? 1 : (carteira?.pcMinima ?? 1))
    setValorPagoTexto(formatarMoeda(calcularValorPagoMaximo(saldoAPagar, carteira)))
  }

  const voltarParaCategorias = () => {
    setCategoriaAberta(null)
    setIdCarteiraEdicao(null)
    setValorPagoTexto('')
    setNumeroValeTexto('')
    setValeResolvido(null)
    setErroVale(null)
  }

  const adicionarPagamento = () => {
    if (!carteiraEdicao) return
    if (carteiraEdicao.categoriaCarteira === 'VALE_MERCADORIA') {
      if (!valeResolvido) return
      const linha: LinhaLocal = {
        chave: `${carteiraEdicao.idCarteira}-${Date.now()}`,
        carteira: carteiraEdicao,
        valorPago: valeResolvido.valorVale,
        numeroParcelas: 1,
        cobertura: valeResolvido.valorVale,
        idDevolucao: valeResolvido.idDevolucao,
      }
      setPagamentos((atual) => [...atual, linha])
      voltarParaCategorias()
      return
    }
    if (valorPagoNumero <= 0) return
    const linha: LinhaLocal = {
      chave: `${carteiraEdicao.idCarteira}-${Date.now()}`,
      carteira: carteiraEdicao,
      valorPago: valorPagoNumero,
      numeroParcelas: numeroParcelasEdicao,
      cobertura: coberturaEdicao,
    }
    setPagamentos((atual) => [...atual, linha])
    voltarParaCategorias()
  }

  const removerPagamento = (chave: string) => {
    setPagamentos((atual) => atual.filter((p) => p.chave !== chave))
  }

  const efetivar = useMutation({
    mutationFn: () => {
      if (!clienteSelecionado || !vendedorSelecionado) {
        throw new Error('Selecione o cliente e o vendedor antes de confirmar a venda.')
      }
      const corpoPagamentos: PagamentoRequest[] = pagamentos.map((p) => ({
        idCarteira: p.carteira.idCarteira,
        valorPago: p.valorPago,
        numeroParcelas: p.numeroParcelas,
        idDevolucao: p.idDevolucao,
      }))
      return efetivarVenda({
        itens: itens.map((i) => ({ idVariacao: i.idVariacao, qtd: i.qtd })),
        descontoVenda,
        pagamentos: corpoPagamentos,
        idCliente: clienteSelecionado.idCliente,
        idFuncionario: vendedorSelecionado.idFuncionario,
      })
    },
    onSuccess: (resultado) => aoEfetivada(resultado),
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível efetivar a venda.'),
  })

  /** O botão libera assim que o pagamento fecha o saldo (2026-07-31, pedido do dono do
   *  produto) — cliente/vendedor deixaram de travar o botão em si; a falta deles só é
   *  cobrada ao clicar "Confirmar Venda" (`aoConfirmar`), com aviso específico do que falta,
   *  pra não gravar a venda faltando um dos dois. */
  const podeConfirmar = saldoFechado

  const aoConfirmar = () => {
    if (!clienteSelecionado && !vendedorSelecionado) {
      setToast('Defina o cliente e o vendedor antes de confirmar a venda.')
      return
    }
    if (!clienteSelecionado) {
      setToast('Defina o cliente antes de confirmar a venda.')
      return
    }
    if (!vendedorSelecionado) {
      setToast('Defina o vendedor antes de confirmar a venda.')
      return
    }
    efetivar.mutate()
  }

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div
        className="modal modal-largo pdv-modal-pagamento"
        role="dialog"
        aria-label="Forma de pagamento"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 style={{ marginTop: 0, marginBottom: 6 }}>Forma de Pagamento</h2>

        <div className="pdv-selecao-linha">
          <div className="pdv-selecao-campo">
            <label>Cliente *</label>
            <div className="pdv-selecao-valor">
              <span>{clienteSelecionado ? clienteSelecionado.nome : 'Nenhum cliente selecionado'}</span>
              <button type="button" className="btn ghost" onClick={() => setMostrarPesquisaCliente(true)}>
                Selecionar
              </button>
            </div>
          </div>
          <div className="pdv-selecao-campo">
            <label>Vendedor *</label>
            <div className="pdv-selecao-valor">
              <span>{vendedorSelecionado ? vendedorSelecionado.nome : 'Nenhum vendedor selecionado'}</span>
              <button type="button" className="btn ghost" onClick={() => setMostrarPesquisaVendedor(true)}>
                Selecionar
              </button>
            </div>
          </div>
        </div>

        <div className="pdv-pagamento-topo">
          <div className="pdv-resumo-pagamento">
            <div className="pdv-resumo-linha pdv-resumo-total">
              <span>Valor Total da Venda</span>
              <span className="mono">{moeda(valorTotalProdutos)}</span>
            </div>

            {temDesconto && (
              <>
                <div className="pdv-resumo-linha pdv-resumo-divisor pdv-resumo-desconto-gerencial">
                  <label htmlFor="pdv-desconto-percentual">
                    Desconto Gerencial <span className="muted">até {percentualMaximoPermitido.toString().replace('.', ',')}%</span>
                  </label>
                  <span className="pdv-resumo-desconto-inputs">
                    <input
                      id="pdv-desconto-percentual"
                      className="mono"
                      autoFocus
                      style={{ width: 64, textAlign: 'right' }}
                      value={descontoPercentualTexto}
                      disabled={descontoTravado}
                      title={descontoTravado ? 'Remova os pagamentos lançados para alterar o desconto.' : undefined}
                      onChange={(e) => setDescontoPercentualTexto(mascararPercentual(e.target.value))}
                      onBlur={(e) => aoSairDescontoPercentual(e.target.value)}
                      onFocus={(e) => e.target.select()}
                    />
                    <span className="muted">%</span>
                    <input
                      id="pdv-desconto-valor"
                      className="mono"
                      style={{ width: 100, textAlign: 'right' }}
                      value={descontoValorTexto}
                      disabled={descontoTravado}
                      title={descontoTravado ? 'Remova os pagamentos lançados para alterar o desconto.' : undefined}
                      onChange={(e) => setDescontoValorTexto(mascararMoeda(e.target.value))}
                      onBlur={(e) => aoSairDescontoValor(e.target.value)}
                      onFocus={(e) => e.target.select()}
                    />
                  </span>
                </div>
                <div className="pdv-resumo-linha pdv-resumo-subtotal">
                  <span>Sub-Total</span>
                  <span className="mono">{moeda(valorLiquido)}</span>
                </div>
              </>
            )}

            <div className="pdv-resumo-linha pdv-resumo-divisor">
              <span>Desconto Promocional</span>
              <span className="mono">{moeda(descontoPromocionalAcumulado)}</span>
            </div>
            <div className="pdv-resumo-linha pdv-resumo-subtotal">
              <span>Valor a Pagar</span>
              <span className="mono">{moeda(Math.max(valorAPagar, 0))}</span>
            </div>

            <div className={`pdv-resumo-linha pdv-resumo-divisor pdv-resumo-valor-pago${saldoFechado ? ' pdv-resumo-fechado' : ''}`}>
              <span>Valor Pago</span>
              <span className="mono">{moeda(valorPagoAcumulado)}</span>
            </div>
          </div>

          {!isLoading && carteiras.length > 0 && (
            <div className="pdv-categorias-coluna">
              {CATEGORIAS_ORDEM.map((categoria) => {
                const disponivel = carteiras.some((c) => c.categoriaCarteira === categoria)
                return (
                  <button
                    key={categoria}
                    type="button"
                    className={`btn pdv-botao-categoria-coluna${categoriaAberta === categoria ? ' pdv-botao-categoria-ativa' : ''}`}
                    disabled={!disponivel || saldoFechado}
                    onClick={() => abrirCategoria(categoria)}
                    title={disponivel ? undefined : 'Nenhum tipo de carteira cadastrado nesta categoria'}
                  >
                    {ROTULO_CATEGORIA_CARTEIRA[categoria]}
                  </button>
                )
              })}
            </div>
          )}
        </div>

        <div className="pdv-area-pagamentos">
          {isLoading ? (
            <p className="muted">Carregando formas de pagamento…</p>
          ) : carteiras.length === 0 ? (
            <p className="muted">Nenhum tipo de carteira cadastrado — cadastre um em Tipo de Carteira antes de vender.</p>
          ) : categoriaAberta === null ? (
            pagamentos.length === 0 ? (
              <p className="muted">Nenhuma forma de pagamento lançada ainda — escolha uma categoria ao lado.</p>
            ) : (
              <div className="table-wrap" style={{ maxHeight: 220 }}>
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Forma de Pagamento</th>
                      <th style={{ textAlign: 'right' }}>Valor Pago</th>
                      <th>Parcelas</th>
                      <th aria-label="Remover" />
                    </tr>
                  </thead>
                  <tbody>
                    {pagamentos.map((p) => (
                      <tr key={p.chave}>
                        <td>
                          {p.carteira.nomeCarteira} — {ROTULO_CATEGORIA_CARTEIRA[p.carteira.categoriaCarteira]}
                          {p.idDevolucao ? ` (vale nº ${p.idDevolucao})` : ''}
                        </td>
                        <td className="mono" style={{ textAlign: 'right' }}>
                          {moeda(p.valorPago)}
                        </td>
                        <td className="mono">{p.numeroParcelas}x</td>
                        <td className="acoes-cell">
                          <button
                            type="button"
                            className="acao-icone acao-excluir"
                            onClick={() => removerPagamento(p.chave)}
                            aria-label={`Remover pagamento em ${p.carteira.nomeCarteira}`}
                            title="Remover"
                          >
                            ✕
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
          ) : (
            <div className="pdv-edicao-pagamento">
              <div className="pdv-linha-valor-parcelas">
                {carteirasDaCategoria.length > 1 && (
                  <div className="pdv-campo-carteira">
                    <label htmlFor="pdv-carteira-edicao">Tipo de Carteira *</label>
                    <select
                      id="pdv-carteira-edicao"
                      value={idCarteiraEdicao ?? ''}
                      onChange={(e) => selecionarCarteiraEdicao(Number(e.target.value))}
                    >
                      <option value="" disabled>
                        Selecione…
                      </option>
                      {carteirasDaCategoria.map((c) => (
                        <option key={c.idCarteira} value={c.idCarteira}>
                          {c.nomeCarteira}
                          {c.percDesconto ? ` — ${c.percDesconto.toString().replace('.', ',')}% desconto` : ''}
                          {c.percAcrescimo ? ` — ${c.percAcrescimo.toString().replace('.', ',')}% acréscimo` : ''}
                        </option>
                      ))}
                    </select>
                  </div>
                )}

                {carteiraEdicao && categoriaAberta === 'VALE_MERCADORIA' && (
                  <div className="pdv-campo-valor-pago">
                    <label htmlFor="pdv-numero-vale">Número do Vale *</label>
                    <div style={{ display: 'flex', gap: 8 }}>
                      <input
                        id="pdv-numero-vale"
                        className="mono"
                        autoFocus
                        inputMode="numeric"
                        style={{ width: 110 }}
                        value={numeroValeTexto}
                        onChange={(e) => {
                          setNumeroValeTexto(e.target.value.replace(/\D/g, ''))
                          setValeResolvido(null)
                          setErroVale(null)
                        }}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') {
                            e.preventDefault()
                            buscarValeMercadoria()
                          }
                        }}
                      />
                      <button type="button" className="btn ghost" disabled={buscandoVale} onClick={buscarValeMercadoria}>
                        {buscandoVale ? 'Buscando…' : 'Buscar'}
                      </button>
                    </div>
                  </div>
                )}

                {carteiraEdicao && categoriaAberta !== 'VALE_MERCADORIA' && (
                  <div className="pdv-campo-valor-pago">
                    <label htmlFor="pdv-valor-pago">Valor Pago *</label>
                    <input
                      id="pdv-valor-pago"
                      className="mono"
                      autoFocus
                      value={valorPagoTexto}
                      onChange={(e) => setValorPagoTexto(mascararMoeda(e.target.value))}
                      onBlur={(e) => {
                        const completo = completarMoeda(e.target.value)
                        const numero = desmascararMoeda(completo)
                        setValorPagoTexto(numero > valorPagoMaximoEdicao ? formatarMoeda(valorPagoMaximoEdicao) : completo)
                      }}
                      onFocus={(e) => e.target.select()}
                    />
                  </div>
                )}

                {carteiraEdicao && categoriaAberta !== 'VALE_MERCADORIA' && !soAVistaEdicao && opcoesParcelasEdicao.length > 1 && (
                  <div className="pdv-campo-parcelas">
                    <label htmlFor="pdv-parcelas-edicao">Número de Parcelas *</label>
                    <select
                      id="pdv-parcelas-edicao"
                      value={numeroParcelasEdicao}
                      onChange={(e) => setNumeroParcelasEdicao(Number(e.target.value))}
                    >
                      {opcoesParcelasEdicao.map((n) => (
                        <option key={n} value={n}>
                          {n}x
                        </option>
                      ))}
                    </select>
                  </div>
                )}
              </div>

              {carteiraEdicao && categoriaAberta === 'VALE_MERCADORIA' && (
                <>
                  {erroVale && (
                    <p className="erro-campo" style={{ marginTop: 4 }}>
                      {erroVale}
                    </p>
                  )}
                  {valeResolvido && (
                    <p className="muted" style={{ marginTop: 4 }}>
                      Vale nº {valeResolvido.idDevolucao} — valor: <strong>{moeda(valeResolvido.valorVale)}</strong>
                    </p>
                  )}
                </>
              )}

              {carteiraEdicao && categoriaAberta !== 'VALE_MERCADORIA' && (
                <>
                  {carteiraEdicao.percDesconto ? (
                    <p className="muted" style={{ marginTop: 4 }}>
                      Valor máximo permitido: {moeda(valorPagoMaximoEdicao)} (desconto de{' '}
                      {carteiraEdicao.percDesconto.toString().replace('.', ',')}% sobre o valor a pagar).
                    </p>
                  ) : null}
                  {coberturaEdicao !== valorPagoNumero && valorPagoNumero > 0 && (
                    <p className="muted" style={{ marginTop: 4 }}>
                      Cobre {moeda(coberturaEdicao)} do saldo a pagar.
                    </p>
                  )}

                  {categoriaAberta === 'CREDIARIO' && previaEdicao.length > 0 && (
                    <div className="table-wrap" style={{ marginTop: 12, maxHeight: 200 }}>
                      <table className="table table-compacta">
                        <thead>
                          <tr>
                            <th>Nº Parcela</th>
                            <th>Vencimento</th>
                            <th style={{ textAlign: 'right' }}>Valor</th>
                            <th>Situação</th>
                          </tr>
                        </thead>
                        <tbody>
                          {previaEdicao.map((p) => (
                            <tr key={p.numeroParcela}>
                              <td className="mono">{p.numeroParcela}</td>
                              <td>{p.vencimento.toLocaleDateString('pt-BR')}</td>
                              <td className="mono" style={{ textAlign: 'right' }}>
                                {moeda(p.valor)}
                              </td>
                              <td>{soAVistaEdicao ? 'Paga agora' : 'Em aberto'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </>
              )}

              <div className="ajuda-rodape">
                <button type="button" className="btn ghost" onClick={voltarParaCategorias}>
                  Voltar
                </button>
                <button
                  type="button"
                  className="btn"
                  disabled={
                    !carteiraEdicao ||
                    (categoriaAberta === 'VALE_MERCADORIA' ? !valeResolvido : valorPagoNumero <= 0)
                  }
                  onClick={adicionarPagamento}
                >
                  Adicionar Pagamento
                </button>
              </div>
            </div>
          )}
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Cancelar
          </button>
          <button
            type="button"
            className="btn"
            disabled={!podeConfirmar || efetivar.isPending}
            title={!saldoFechado ? 'Distribua o valor pago até fechar o valor a pagar.' : undefined}
            onClick={aoConfirmar}
          >
            {efetivar.isPending ? 'Efetivando…' : 'Confirmar Venda'}
          </button>
        </div>
      </div>

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}

      {mostrarPesquisaCliente && (
        <PesquisaClienteModal
          aoFechar={() => setMostrarPesquisaCliente(false)}
          aoSelecionar={(cliente) => {
            setClienteSelecionado(cliente)
            setMostrarPesquisaCliente(false)
          }}
        />
      )}
      {mostrarPesquisaVendedor && (
        <PesquisaVendedorModal
          aoFechar={() => setMostrarPesquisaVendedor(false)}
          aoSelecionar={(vendedor) => {
            setVendedorSelecionado(vendedor)
            setMostrarPesquisaVendedor(false)
          }}
        />
      )}
    </div>
  )
}
