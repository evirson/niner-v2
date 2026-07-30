import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AberturaCaixaModal from '../../components/AberturaCaixaModal'
import AjudaDaTela from '../../components/AjudaDaTela'
import { IconeRecebimentoCrediario } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarStatusCaixa } from '../../lib/caixa'
import { completarMoeda, desmascararMoeda, formatarMoeda, mascararMoeda } from '../../lib/masks'
import {
  buscarClientesCrediario,
  efetivarRecebimentoCrediario,
  listarCarteirasCrediario,
  listarParcelasCrediario,
  type CarteiraDisponivel,
  type ClienteCrediario,
} from '../../lib/recebimentoCrediario'
import ComprovanteRecebimentoModal from './ComprovanteRecebimentoModal'
import EscolherFormaPagamentoModal from './EscolherFormaPagamentoModal'
import { ROTULO_CATEGORIA_CARTEIRA } from '../../lib/tiposCarteira'
import { maiusculas } from '../../lib/texto'

const TOLERANCIA_SALDO = 0.01

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

function arredondar2(v: number): number {
  return Math.round((v + Number.EPSILON) * 100) / 100
}

interface LinhaPagamento {
  chave: string
  carteira: CarteiraDisponivel
  valorPago: number
}

/**
 * Recebimento de Crediário (2026-07-29, spec fornecida pelo dono do produto — RN001–RN013):
 * busca o cliente, lista as parcelas de crediário em aberto (multa/juros já calculados pelo
 * servidor), seleção múltipla com totais no rodapé, e uma ou mais formas de pagamento cobrindo
 * o valor exato das parcelas selecionadas (RN008: sem desconto/acréscimo de tipo de carteira
 * nesta rotina — diferente do PDV).
 */
export default function RecebimentoCrediario() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: statusCaixa } = useQuery({ queryKey: ['caixa-status'], queryFn: buscarStatusCaixa })
  const caixaFechado = statusCaixa !== undefined && !statusCaixa.aberto

  const [nome, setNome] = useState('')
  const [cpf, setCpf] = useState('')
  const [celular, setCelular] = useState('')
  const [clienteSelecionado, setClienteSelecionado] = useState<ClienteCrediario | null>(null)
  const [idsSelecionadas, setIdsSelecionadas] = useState<number[]>([])
  const [categoriaAberta, setCategoriaAberta] = useState<CarteiraDisponivel['categoriaCarteira'] | null>(null)
  const [idCarteiraEdicao, setIdCarteiraEdicao] = useState<number | null>(null)
  const [valorPagoTexto, setValorPagoTexto] = useState('')
  const [pagamentos, setPagamentos] = useState<LinhaPagamento[]>([])
  const [modalPagamentoAberto, setModalPagamentoAberto] = useState(false)
  const [idLoteComprovante, setIdLoteComprovante] = useState<number | null>(null)
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)
  const linhasRef = useRef<Array<HTMLTableRowElement | null>>([])

  const termoBusca = Boolean(nome.trim() || cpf.trim() || celular.trim())
  const { data: clientes, isFetching: buscandoClientes } = useQuery({
    queryKey: ['recebimento-crediario-clientes', nome, cpf, celular],
    queryFn: () =>
      buscarClientesCrediario({
        nome: nome.trim() || undefined,
        cpf: cpf.trim() || undefined,
        celular: celular.trim() || undefined,
      }),
    enabled: termoBusca && !clienteSelecionado,
  })

  const { data: parcelas, isLoading: carregandoParcelas } = useQuery({
    queryKey: ['recebimento-crediario-parcelas', clienteSelecionado?.idCliente],
    queryFn: () => listarParcelasCrediario(clienteSelecionado!.idCliente),
    enabled: clienteSelecionado !== null,
  })

  const { data: carteiras } = useQuery({
    queryKey: ['recebimento-crediario-carteiras'],
    queryFn: listarCarteirasCrediario,
  })

  const limparSelecaoEPagamentos = () => {
    setIdsSelecionadas([])
    setPagamentos([])
    setCategoriaAberta(null)
    setIdCarteiraEdicao(null)
    setValorPagoTexto('')
  }

  const selecionarCliente = (cliente: ClienteCrediario) => {
    setClienteSelecionado(cliente)
    limparSelecaoEPagamentos()
  }

  const trocarCliente = () => {
    setClienteSelecionado(null)
    limparSelecaoEPagamentos()
    setNome('')
    setCpf('')
    setCelular('')
  }

  /** Muda o total selecionado — qualquer forma de pagamento já lançada perde o sentido. */
  const alternarSelecao = (id: number) => {
    setIdsSelecionadas((atual) => (atual.includes(id) ? atual.filter((x) => x !== id) : [...atual, id]))
    setPagamentos([])
    setCategoriaAberta(null)
    setIdCarteiraEdicao(null)
    setValorPagoTexto('')
  }

  const navegarLinhas = (indiceAtual: number, direcao: 1 | -1) => {
    const total = parcelas?.length ?? 0
    if (total === 0) return
    const novoIndice = Math.min(Math.max(indiceAtual + direcao, 0), total - 1)
    linhasRef.current[novoIndice]?.focus()
  }

  const parcelasSelecionadas = (parcelas ?? []).filter((p) => idsSelecionadas.includes(p.idContaReceber))
  const valorTotalRecebimento = arredondar2(parcelasSelecionadas.reduce((s, p) => s + p.totalAPagar, 0))

  const valorPagoAcumulado = arredondar2(pagamentos.reduce((s, p) => s + p.valorPago, 0))
  const saldoAPagar = arredondar2(valorTotalRecebimento - valorPagoAcumulado)
  const saldoFechado = pagamentos.length > 0 && Math.abs(saldoAPagar) < TOLERANCIA_SALDO

  const carteirasDaCategoria = categoriaAberta ? (carteiras ?? []).filter((c) => c.categoriaCarteira === categoriaAberta) : []
  const carteiraEdicao = carteirasDaCategoria.find((c) => c.idCarteira === idCarteiraEdicao) ?? null
  const valorPagoMaximo = Math.max(saldoAPagar, 0)
  const valorPagoNumero = Math.min(desmascararMoeda(valorPagoTexto || '0'), valorPagoMaximo)

  const abrirCategoria = (categoria: CarteiraDisponivel['categoriaCarteira']) => {
    const daCategoria = (carteiras ?? []).filter((c) => c.categoriaCarteira === categoria)
    if (daCategoria.length === 0) return
    setCategoriaAberta(categoria)
    const unica = daCategoria.length === 1 ? daCategoria[0] : null
    setIdCarteiraEdicao(unica?.idCarteira ?? null)
    setValorPagoTexto(formatarMoeda(Math.max(saldoAPagar, 0)))
  }

  const selecionarCarteiraEdicao = (idCarteira: number) => {
    setIdCarteiraEdicao(idCarteira)
    setValorPagoTexto(formatarMoeda(Math.max(saldoAPagar, 0)))
  }

  const voltarParaCategorias = () => {
    setCategoriaAberta(null)
    setIdCarteiraEdicao(null)
    setValorPagoTexto('')
  }

  const adicionarPagamento = () => {
    if (!carteiraEdicao || valorPagoNumero <= 0) return
    setPagamentos((atual) => [
      ...atual,
      { chave: `${carteiraEdicao.idCarteira}-${Date.now()}`, carteira: carteiraEdicao, valorPago: valorPagoNumero },
    ])
    voltarParaCategorias()
    setModalPagamentoAberto(false)
  }

  const removerPagamento = (chave: string) => {
    setPagamentos((atual) => atual.filter((p) => p.chave !== chave))
  }

  const fecharModalPagamento = () => {
    setModalPagamentoAberto(false)
    voltarParaCategorias()
  }

  const mudarValorPagoTexto = (texto: string) => setValorPagoTexto(mascararMoeda(texto))

  const finalizarValorPagoTexto = (texto: string) => {
    const numero = Math.min(desmascararMoeda(completarMoeda(texto)), valorPagoMaximo)
    setValorPagoTexto(formatarMoeda(numero))
  }

  const efetivar = useMutation({
    mutationFn: () => {
      if (!clienteSelecionado) throw new ApiError(400, 'Selecione o cliente.')
      return efetivarRecebimentoCrediario({
        idCliente: clienteSelecionado.idCliente,
        idsContaReceber: idsSelecionadas,
        pagamentos: pagamentos.map((p) => ({ idCarteira: p.carteira.idCarteira, valorPago: p.valorPago })),
      })
    },
    onSuccess: (resultado) => {
      queryClient.invalidateQueries({ queryKey: ['recebimento-crediario-parcelas'] })
      setToast({
        texto: `Recebimento #${resultado.idLoteRecebimento} efetivado — ${resultado.qtdParcelas} parcela(s), ${moeda(resultado.valorTotal)}.`,
        tipo: 'sucesso',
      })
      trocarCliente()
      setIdLoteComprovante(resultado.idLoteRecebimento)
    },
    onError: (e: unknown) =>
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível efetivar o recebimento.', tipo: 'erro' }),
  })

  const cancelar = () => {
    if (clienteSelecionado) trocarCliente()
    else navigate('/')
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRecebimentoCrediario size={34} />
            <h1>Recebimento de Crediário</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.recebimentocrediario.tela" />
            <button type="button" className="btn ghost" onClick={cancelar}>
              {clienteSelecionado ? 'Trocar Cliente' : 'Voltar'}
            </button>
            <button
              type="button"
              className="btn"
              disabled={!saldoFechado || efetivar.isPending}
              onClick={() => efetivar.mutate()}
            >
              {efetivar.isPending ? 'Recebendo…' : 'Efetivar Recebimento'}
            </button>
          </div>
        </div>
      </div>

      <div className="lista-corpo recebimento-lista-corpo">
        <div className="card form-secoes form-secoes-larga recebimento-card">
          {!clienteSelecionado ? (
            <section className="section">
              <p className="section-label">Buscar Cliente</p>
              <p className="muted" style={{ marginTop: -4 }}>
                Informe nome, CPF ou celular (pelo menos um dos três).
              </p>
              <div className="form-grid">
                <div className="col-4">
                  <label htmlFor="busca-nome">Nome</label>
                  <input id="busca-nome" value={nome} onChange={(e) => setNome(maiusculas(e.target.value))} />
                </div>
                <div className="col-4">
                  <label htmlFor="busca-cpf">CPF</label>
                  <input
                    id="busca-cpf"
                    inputMode="numeric"
                    value={cpf}
                    onChange={(e) => setCpf(e.target.value.replace(/\D/g, ''))}
                  />
                </div>
                <div className="col-4">
                  <label htmlFor="busca-celular">Celular</label>
                  <input
                    id="busca-celular"
                    inputMode="numeric"
                    value={celular}
                    onChange={(e) => setCelular(e.target.value.replace(/\D/g, ''))}
                  />
                </div>
              </div>

              {termoBusca && (
                <div className="table-wrap" style={{ marginTop: 12, maxHeight: 320 }}>
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        <th>Nome</th>
                        <th>CPF/CNPJ</th>
                        <th>Celular</th>
                      </tr>
                    </thead>
                    <tbody>
                      {buscandoClientes ? (
                        <tr>
                          <td colSpan={3}>
                            <p className="muted" style={{ margin: '8px 0' }}>
                              Buscando…
                            </p>
                          </td>
                        </tr>
                      ) : !clientes || clientes.length === 0 ? (
                        <tr>
                          <td colSpan={3}>
                            <p className="muted" style={{ margin: '8px 0' }}>
                              Nenhum cliente encontrado.
                            </p>
                          </td>
                        </tr>
                      ) : (
                        clientes.map((cliente) => (
                          <tr
                            key={cliente.idCliente}
                            tabIndex={0}
                            onClick={() => selecionarCliente(cliente)}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter' || e.key === ' ') {
                                e.preventDefault()
                                selecionarCliente(cliente)
                              }
                            }}
                          >
                            <td>{cliente.nome}</td>
                            <td className="mono">{cliente.cpfCnpj ?? '—'}</td>
                            <td className="mono">{cliente.telefone ?? '—'}</td>
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          ) : (
            <>
              <section className="section">
                <p className="section-label">Cliente</p>
                <div className="pdv-selecao-valor">
                  <span>
                    {clienteSelecionado.nome}
                    {clienteSelecionado.cpfCnpj && <span className="muted"> — {clienteSelecionado.cpfCnpj}</span>}
                  </span>
                </div>
              </section>

              <section className="section recebimento-secao-parcelas">
                <p className="section-label">Parcelas em Aberto</p>

                {carregandoParcelas ? (
                  <p className="muted">Carregando…</p>
                ) : !parcelas || parcelas.length === 0 ? (
                  <p className="muted">Nenhuma parcela de crediário em aberto para este cliente.</p>
                ) : (
                  <div className="recebimento-parcelas-layout">
                    <div className="table-wrap recebimento-parcelas-grid">
                      <table className="table table-compacta">
                        <thead>
                          <tr>
                            <th aria-label="Selecionar" />
                            <th>Nº Venda</th>
                            <th>Empresa</th>
                            <th>Data Venda</th>
                            <th>Nº PC</th>
                            <th>Vencimento</th>
                            <th style={{ textAlign: 'right' }}>Vlr. Original</th>
                            <th style={{ textAlign: 'right' }}>Multa + Juros</th>
                            <th style={{ textAlign: 'right' }}>Total a Pagar</th>
                          </tr>
                        </thead>
                        <tbody>
                          {parcelas.map((p, indice) => {
                            const selecionada = idsSelecionadas.includes(p.idContaReceber)
                            return (
                              <tr
                                key={p.idContaReceber}
                                ref={(el) => {
                                  linhasRef.current[indice] = el
                                }}
                                className={selecionada ? 'linha-selecionada' : undefined}
                                tabIndex={0}
                                onClick={() => alternarSelecao(p.idContaReceber)}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter' || e.key === ' ') {
                                    e.preventDefault()
                                    alternarSelecao(p.idContaReceber)
                                  } else if (e.key === 'ArrowDown') {
                                    e.preventDefault()
                                    navegarLinhas(indice, 1)
                                  } else if (e.key === 'ArrowUp') {
                                    e.preventDefault()
                                    navegarLinhas(indice, -1)
                                  }
                                }}
                              >
                                <td onClick={(e) => e.stopPropagation()}>
                                  <input type="checkbox" checked={selecionada} onChange={() => alternarSelecao(p.idContaReceber)} />
                                </td>
                                <td className="mono">{p.idVenda}</td>
                                <td className="mono">{String(p.codigoEmpresa).padStart(2, '0')}</td>
                                <td>{formatarData(p.dataVenda)}</td>
                                <td className="mono">
                                  {String(p.numeroParcela).padStart(2, '0')}/{String(p.totalParcelas).padStart(2, '0')}
                                </td>
                                <td>{formatarData(p.dataVencimento)}</td>
                                <td className="mono" style={{ textAlign: 'right' }}>
                                  {moeda(p.valorOriginal)}
                                </td>
                                <td className="mono" style={{ textAlign: 'right' }}>
                                  {moeda(p.multa + p.juros)}
                                </td>
                                <td className="mono" style={{ textAlign: 'right' }}>
                                  {moeda(p.totalAPagar)}
                                </td>
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>

                    <div className="recebimento-parcelas-sidebar">
                      <div className="pdv-caixa-total">
                        <span className="pdv-rotulo">Selecionadas</span>
                        <span className="pdv-valor">{String(parcelasSelecionadas.length).padStart(2, '0')}</span>
                      </div>
                      <div className="pdv-caixa-total pdv-total">
                        <span className="pdv-rotulo">Valor Selecionado</span>
                        <span className="pdv-valor">{moeda(valorTotalRecebimento)}</span>
                      </div>
                      <div className={`pdv-caixa-total${saldoFechado ? ' pdv-resumo-fechado' : ''}`}>
                        <span className="pdv-rotulo">Valor Recebido</span>
                        <span className="pdv-valor">{moeda(valorPagoAcumulado)}</span>
                      </div>

                      {carteiras && carteiras.length > 0 && (
                        <button
                          type="button"
                          className="btn recebimento-botao-receber"
                          disabled={saldoFechado || parcelasSelecionadas.length === 0}
                          onClick={() => setModalPagamentoAberto(true)}
                        >
                          Receber
                        </button>
                      )}
                    </div>
                  </div>
                )}
              </section>

              {parcelasSelecionadas.length > 0 && (
                <section className="section">
                  <p className="section-label">Forma de Pagamento</p>

                  <div className="pdv-area-pagamentos">
                    {!carteiras || carteiras.length === 0 ? (
                      <p className="muted">
                        Nenhum tipo de carteira liberado para receber crediário — libere um em Tipo de Carteira
                        ("Permite receber parcelas de crediário").
                      </p>
                    ) : pagamentos.length === 0 ? (
                      <p className="muted">Nenhuma forma de pagamento lançada ainda — clique em "Receber" ao lado da grid.</p>
                    ) : (
                      <div className="recebimento-pagamentos-grid">
                        {pagamentos.map((p) => (
                          <div key={p.chave} className="recebimento-pagamento-card">
                            <div className="recebimento-pagamento-card-info">
                              <span className="recebimento-pagamento-card-nome">{p.carteira.nomeCarteira}</span>
                              <span className="muted">{ROTULO_CATEGORIA_CARTEIRA[p.carteira.categoriaCarteira]}</span>
                            </div>
                            <div className="recebimento-pagamento-card-acoes">
                              <span className="mono recebimento-pagamento-card-valor">{moeda(p.valorPago)}</span>
                              <button
                                type="button"
                                className="acao-icone acao-excluir"
                                onClick={() => removerPagamento(p.chave)}
                                aria-label={`Remover pagamento em ${p.carteira.nomeCarteira}`}
                                title="Remover"
                              >
                                ✕
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </section>
              )}
            </>
          )}
        </div>
      </div>

      {modalPagamentoAberto && carteiras && (
        <EscolherFormaPagamentoModal
          carteiras={carteiras}
          categoriaAberta={categoriaAberta}
          carteirasDaCategoria={carteirasDaCategoria}
          idCarteiraEdicao={idCarteiraEdicao}
          carteiraEdicao={carteiraEdicao}
          valorPagoTexto={valorPagoTexto}
          valorPagoNumero={valorPagoNumero}
          aoEscolherCategoria={abrirCategoria}
          aoSelecionarCarteira={selecionarCarteiraEdicao}
          aoMudarValorPagoTexto={mudarValorPagoTexto}
          aoFinalizarValorPagoTexto={finalizarValorPagoTexto}
          aoVoltar={voltarParaCategorias}
          aoAdicionar={adicionarPagamento}
          aoFechar={fecharModalPagamento}
        />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}

      {idLoteComprovante !== null && (
        <ComprovanteRecebimentoModal idLoteRecebimento={idLoteComprovante} aoFechar={() => setIdLoteComprovante(null)} />
      )}

      {caixaFechado && (
        <AberturaCaixaModal
          aoAbrir={() => queryClient.invalidateQueries({ queryKey: ['caixa-status'] })}
          aoVoltar={() => navigate('/')}
        />
      )}
    </div>
  )
}
