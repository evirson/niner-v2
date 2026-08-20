import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AberturaCaixaModal from '../../components/AberturaCaixaModal'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeAjustar, IconeConfirmar, IconeLimpar, IconeLupa, IconePdv } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { buscarStatusCaixa } from '../../lib/caixa'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { formatarMoeda, formatarQuantidade } from '../../lib/masks'
import {
  buscarProdutoPorCodigo,
  interpretarCodigoBarras,
  type ItemLedger,
  type PdvProduto,
  type VendaEfetivada,
} from '../../lib/pdv'
import type { Orcamento } from '../../lib/orcamento'
import { useRotinaCritica } from '../../lib/rotinaCritica'
import { maiusculas } from '../../lib/texto'
import AlteraQuantidadeModal from './AlteraQuantidadeModal'
import ComprovantePapeletaModal from './ComprovantePapeletaModal'
import FormaPagamentoModal from './FormaPagamentoModal'
import PesquisaProdutoModal from './PesquisaProdutoModal'
import PuxarOrcamentoModal from './PuxarOrcamentoModal'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function totalLinha(item: ItemLedger): number {
  return item.qtd * item.precoUnit
}

function variacaoTexto(produto: PdvProduto): string | null {
  if (!produto.variacaoCor && !produto.variacaoTamanho) return null
  return [produto.variacaoCor, produto.variacaoTamanho].filter(Boolean).join(' · ')
}

/**
 * PDV (Frente de Caixa, docs/telas/pdv.md) — busca/leitura de produto e efetivação de venda
 * de verdade (API `com.vetor.niner.vendas`, 2026-07-28): grava venda + baixa de estoque real
 * (trigger existente, P1) + parcela(s) em contas_receber a partir da forma de pagamento
 * escolhida no F5. v1 sem cliente/vendedor/multi-empresa/desconto — ver "Escopo desta versão"
 * em docs/telas/pdv.md.
 */
export default function Pdv() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { setEmAndamento } = useRotinaCritica()
  const { data: statusCaixa } = useQuery({ queryKey: ['caixa-status'], queryFn: buscarStatusCaixa })
  const caixaFechado = statusCaixa !== undefined && !statusCaixa.aberto
  const [ledger, setLedger] = useState<ItemLedger[]>([])
  const [selecionado, setSelecionado] = useState<string | null>(null)
  const [valorBarras, setValorBarras] = useState('')
  const [flashMsg, setFlashMsg] = useState<string | null>(null)
  const [teclaAtiva, setTeclaAtiva] = useState<string | null>(null)
  const [mostrarPesquisa, setMostrarPesquisa] = useState(false)
  const [mostrarAlteraQtd, setMostrarAlteraQtd] = useState(false)
  const [mostrarFormaPagamento, setMostrarFormaPagamento] = useState(false)
  const [idVendaPapeleta, setIdVendaPapeleta] = useState<number | null>(null)
  /**
   * Orçamento puxado para esta venda (V058) e o cliente/vendedor que vieram junto.
   *
   * <p>⚠️ O `idOrcamento` viaja até `efetivarVenda`: é ele que faz o servidor usar o preço
   * congelado e recusar quantidade maior que a orçada. Limpar a venda limpa isto também — senão
   * a venda seguinte carimbaria um orçamento que não tem nada a ver com ela.
   */
  const [orcamentoPuxado, setOrcamentoPuxado] = useState<Orcamento | null>(null)
  const [mostrarPuxarOrcamento, setMostrarPuxarOrcamento] = useState(false)
  const campoBarrasRef = useRef<HTMLInputElement>(null)
  const linhasLedgerRef = useRef<Array<HTMLDivElement | null>>([])
  const flashTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const ativaTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const barrasTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const algumModalAberto =
    mostrarPesquisa || mostrarAlteraQtd || mostrarFormaPagamento || mostrarPuxarOrcamento ||
    idVendaPapeleta !== null || caixaFechado

  useEffect(() => {
    campoBarrasRef.current?.focus()
    return () => {
      if (flashTimeoutRef.current) clearTimeout(flashTimeoutRef.current)
      if (ativaTimeoutRef.current) clearTimeout(ativaTimeoutRef.current)
      if (barrasTimeoutRef.current) clearTimeout(barrasTimeoutRef.current)
    }
  }, [])

  const itemSelecionado = ledger.find((i) => i.codigo === selecionado) ?? null

  const mostrarFlash = (texto: string) => {
    setFlashMsg(texto)
    if (flashTimeoutRef.current) clearTimeout(flashTimeoutRef.current)
    flashTimeoutRef.current = setTimeout(() => setFlashMsg(null), 1600)
  }

  const piscarTecla = (id: string) => {
    setTeclaAtiva(id)
    if (ativaTimeoutRef.current) clearTimeout(ativaTimeoutRef.current)
    ativaTimeoutRef.current = setTimeout(() => setTeclaAtiva(null), 160)
  }

  const selecionarLinha = (codigo: string) => {
    setSelecionado(codigo)
  }

  /** Navegação por ↑/↓ nos Produtos Vendidos (2026-07-28) — move a seleção e o foco de teclado. */
  const navegarLedger = (direcao: 1 | -1) => {
    if (ledger.length === 0) return
    const indiceAtual = ledger.findIndex((i) => i.codigo === selecionado)
    const novoIndice = Math.min(Math.max(indiceAtual + direcao, 0), ledger.length - 1)
    selecionarLinha(ledger[novoIndice].codigo)
    linhasLedgerRef.current[novoIndice]?.focus()
  }

  /** `qtd` é 1 por padrão (leitura simples) — "5*código" no campo de barras já manda a
   *  quantidade digitada (ver {@link interpretarCodigoBarras}), somada se o item já estiver na venda. */
  const lancarProduto = (produto: PdvProduto, qtd: number = 1) => {
    setLedger((atual) => {
      const existente = atual.find((i) => i.codigo === produto.sku)
      if (existente) {
        return atual.map((i) => (i.codigo === produto.sku ? { ...i, qtd: i.qtd + qtd } : i))
      }
      return [
        ...atual,
        {
          idVariacao: produto.idVariacao,
          codigo: produto.sku,
          descricao: produto.descricaoProduto,
          variacao: variacaoTexto(produto),
          qtd,
          precoUnit: produto.precoVenda,
          urlImagem: produto.urlImagem,
        },
      ]
    })
    selecionarLinha(produto.sku)
  }

  const lerCodigo = async (valorDigitado: string) => {
    const { qtd, codigo } = interpretarCodigoBarras(valorDigitado)
    try {
      const produto = await buscarProdutoPorCodigo(codigo)
      lancarProduto(produto, qtd)
    } catch (e) {
      mostrarFlash(e instanceof ApiError ? e.message : 'Não foi possível ler o código de barras.')
    }
  }

  const aoDigitarBarras: React.KeyboardEventHandler<HTMLInputElement> = (e) => {
    if (e.key === 'Enter' && valorBarras.trim()) {
      e.preventDefault()
      const valor = valorBarras.trim()
      setValorBarras('')
      lerCodigo(valor)
    }
  }

  const f2Pesquisa = () => {
    piscarTecla('f2')
    setMostrarPesquisa(true)
  }

  const aoSelecionarNaPesquisa = (produto: PdvProduto) => {
    setMostrarPesquisa(false)
    // Preenche o código de barras automaticamente (como se tivesse sido lido) antes de
    // lançar o item, pra deixar visível de onde a linha veio.
    setValorBarras(produto.sku)
    lancarProduto(produto)
    if (barrasTimeoutRef.current) clearTimeout(barrasTimeoutRef.current)
    barrasTimeoutRef.current = setTimeout(() => {
      setValorBarras('')
      campoBarrasRef.current?.focus()
    }, 400)
  }

  const f3AlteraQtd = () => {
    piscarTecla('f3')
    setMostrarAlteraQtd(true)
  }

  const aoAlterarQtd = (codigo: string, novaQtd: number) => {
    setLedger((atual) => atual.map((i) => (i.codigo === codigo ? { ...i, qtd: novaQtd } : i)))
  }

  const aoRemoverItem = (codigo: string) => {
    setLedger((atual) => atual.filter((i) => i.codigo !== codigo))
    setSelecionado((atual) => (atual === codigo ? null : atual))
  }

  const f4LimpaTela = () => {
    piscarTecla('f4')
    setLedger([])
    setSelecionado(null)
    setValorBarras('')
    setMostrarPesquisa(false)
    setMostrarAlteraQtd(false)
    setMostrarFormaPagamento(false)
    campoBarrasRef.current?.focus()
  }

  const f5EfetivaVenda = () => {
    if (ledger.length === 0) {
      mostrarFlash('Nenhum item na venda.')
      return
    }
    // Rotina crítica (docs/telas/usuario.md, 2026-08-11): do início da forma de pagamento até
    // fechar o comprovante — o logoff automático por fim de horário de acesso espera terminar.
    setEmAndamento(true)
    setMostrarFormaPagamento(true)
  }

  /** F6 abre o orçamento. ⚠️ Só com a venda vazia: puxar por cima de itens já lançados
   *  misturaria preço congelado com preço de cadastro sem o operador perceber. */
  const f6PuxarOrcamento = () => {
    if (ledger.length > 0) {
      mostrarFlash('Limpe a tela (F4) antes de puxar um orçamento.')
      return
    }
    setTeclaAtiva('f6')
    setMostrarPuxarOrcamento(true)
  }

  const aoVendaEfetivada = (resultado: VendaEfetivada) => {
    setMostrarFormaPagamento(false)
    mostrarFlash(`Venda #${resultado.idVenda} efetivada — ${moeda(resultado.valorLiquido)}.`)
    setLedger([])
    setSelecionado(null)
    // ⚠️ Limpar o orçamento junto: sem isto, a PRÓXIMA venda carimbaria um orçamento que não tem
    // nada a ver com ela — e o servidor recusaria (já vendido), com o operador sem entender.
    setOrcamentoPuxado(null)
    setIdVendaPapeleta(resultado.idVenda)
  }

  useEffect(() => {
    const aoTeclar = (e: KeyboardEvent) => {
      if (algumModalAberto) return
      // F2/F3/F4/F5 e ↑/↓ têm que disparar as funções desta tela mesmo com o foco no campo de
      // código de barras (onde o foco fica por padrão) — nenhuma delas insere caractere, então
      // não há conflito com a digitação. Fundamental: `preventDefault` tem que rodar ANTES de
      // qualquer `return` baseado em foco, senão o atalho nativo do navegador vence (F5 recarrega
      // a página — perderia a venda em andamento — F3 abre a busca do navegador, etc.).
      if (e.key === 'ArrowDown') { e.preventDefault(); navegarLedger(1); return }
      if (e.key === 'ArrowUp') { e.preventDefault(); navegarLedger(-1); return }
      if (e.key === 'F2') { e.preventDefault(); f2Pesquisa(); return }
      if (e.key === 'F3') { e.preventDefault(); f3AlteraQtd(); return }
      if (e.key === 'F4') { e.preventDefault(); f4LimpaTela(); return }
      if (e.key === 'F5') { e.preventDefault(); f5EfetivaVenda(); return }
      if (e.key === 'F6') { e.preventDefault(); f6PuxarOrcamento(); return }
    }
    document.addEventListener('keydown', aoTeclar)
    return () => document.removeEventListener('keydown', aoTeclar)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selecionado, ledger, algumModalAberto])

  const qtdTotal = ledger.reduce((soma, i) => soma + i.qtd, 0)
  const valorTotal = ledger.reduce((soma, i) => soma + totalLinha(i), 0)
  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePdv size={34} />
            <h1>PDV — Frente de Caixa</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="pdv.tela" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo" style={{ display: 'flex', flexDirection: 'column' }}>
        <div className="pdv-frame">
          <div className="pdv-barra-descricao">
            {itemSelecionado ? (
              <>
                {itemSelecionado.descricao}
                {itemSelecionado.variacao && <span className="pdv-variacao"> — {itemSelecionado.variacao}</span>}
              </>
            ) : (
              'Aguardando leitura do código de barras…'
            )}
          </div>

          <div className="pdv-corpo">
            <div className="pdv-coluna-esq">
              <div className="pdv-linha-produto">
                <div className="pdv-foto-produto">
                  {itemSelecionado?.urlImagem ? (
                    <img src={itemSelecionado.urlImagem} alt={itemSelecionado.descricao} />
                  ) : (
                    <>
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5}>
                        <rect x="3" y="5" width="18" height="14" rx="2" />
                        <circle cx="8.5" cy="10" r="1.5" />
                        <path d="M21 15l-5-4-4 3.5-2-1.5-4 3" />
                      </svg>
                      <span className="pdv-rotulo">{itemSelecionado ? itemSelecionado.descricao : 'Foto do produto'}</span>
                    </>
                  )}
                </div>
                <div className="pdv-stats-produto">
                  <div className="pdv-stat-box">
                    <span className="pdv-rotulo">Quantidade</span>
                    <span className="pdv-valor">
                      {itemSelecionado ? formatarQuantidade(itemSelecionado.qtd, permiteQtdDecimal) : '—'}
                    </span>
                  </div>
                  <div className="pdv-stat-box">
                    <span className="pdv-rotulo">Valor Unitário</span>
                    <span className="pdv-valor">{itemSelecionado ? moeda(itemSelecionado.precoUnit) : '—'}</span>
                  </div>
                  <div className="pdv-stat-box">
                    <span className="pdv-rotulo">Total Produto</span>
                    <span className="pdv-valor">{itemSelecionado ? moeda(totalLinha(itemSelecionado)) : '—'}</span>
                  </div>
                </div>
              </div>

              <div className="pdv-campo-codigo-barras">
                <div className="pdv-rotulo">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6}>
                    <path d="M4 5v14M8 5v14M11 5v14M13 5v14M17 5v14M20 5v14" />
                  </svg>
                  Código de Barras
                </div>
                <input
                  ref={campoBarrasRef}
                  type="text"
                  placeholder="Aguardando leitura…"
                  autoComplete="off"
                  inputMode="numeric"
                  value={valorBarras}
                  onChange={(e) => setValorBarras(maiusculas(e.target.value))}
                  onKeyDown={aoDigitarBarras}
                />
                <p className="pdv-dica">Leia o código de barras do produto e pressione Enter.</p>
                <p className="pdv-dica">Dica: "5*código" lança direto com quantidade 5.</p>
              </div>

              <div className="pdv-linha-fkeys">
                <div className={`pdv-tecla${teclaAtiva === 'f2' ? ' pdv-ativa' : ''}`} onClick={f2Pesquisa}>
                  <IconeLupa />
                  <span>
                    <span className="pdv-kbd">F2</span> Pesquisa Produto
                  </span>
                </div>
                <div className={`pdv-tecla${teclaAtiva === 'f3' ? ' pdv-ativa' : ''}`} onClick={f3AlteraQtd}>
                  <IconeAjustar />
                  <span>
                    <span className="pdv-kbd">F3</span> Altera Quantidade
                  </span>
                </div>
                <div className={`pdv-tecla${teclaAtiva === 'f4' ? ' pdv-ativa' : ''}`} onClick={f4LimpaTela}>
                  <IconeLimpar />
                  <span>
                    <span className="pdv-kbd">F4</span> Limpa Tela
                  </span>
                </div>
                <div
                  className={`pdv-tecla${teclaAtiva === 'f6' ? ' pdv-ativa' : ''}`}
                  onClick={f6PuxarOrcamento}
                >
                  <IconeLupa />
                  <span>
                    <span className="pdv-kbd">F6</span> Puxar Orçamento
                  </span>
                </div>
              </div>

              {orcamentoPuxado && (
                <p className="pdv-dica">
                  Venda a partir do <strong>orçamento nº {orcamentoPuxado.idOrcamento}</strong> —
                  preços travados, cliente e vendedor já preenchidos no F5.
                </p>
              )}

              <button type="button" className="pdv-tecla-venda" onClick={f5EfetivaVenda}>
                <IconeConfirmar size={24} />
                <span className="pdv-kbd">F5</span>Efetiva Venda
              </button>
            </div>

            <div className="pdv-coluna-dir">
              <div className="pdv-ledger-cabecalho">
                <span>Código Barras</span>
                <span>Descrição do Produto</span>
                <span className="pdv-num">Qtd</span>
                <span className="pdv-num">Vlr Unitário</span>
                <span className="pdv-num">Vlr Total</span>
              </div>
              <div className="pdv-ledger-corpo">
                {ledger.length === 0 ? (
                  <div className="pdv-ledger-vazio">Nenhum item lançado — leia um código de barras pra começar a venda.</div>
                ) : (
                  ledger.map((item, indice) => (
                    <div
                      key={item.codigo}
                      ref={(el) => {
                        linhasLedgerRef.current[indice] = el
                      }}
                      className={`pdv-ledger-linha${item.codigo === selecionado ? ' pdv-selecionada' : ''}`}
                      tabIndex={0}
                      onClick={() => selecionarLinha(item.codigo)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault()
                          selecionarLinha(item.codigo)
                        }
                      }}
                    >
                      <span className="pdv-cod">{item.codigo}</span>
                      <span>
                        {item.descricao}
                        {item.variacao && <span style={{ color: 'var(--ink-muted)', fontWeight: 400 }}> — {item.variacao}</span>}
                      </span>
                      <span className="pdv-num">{formatarQuantidade(item.qtd, permiteQtdDecimal)}</span>
                      <span className="pdv-num">{moeda(item.precoUnit)}</span>
                      <span className="pdv-num">{moeda(totalLinha(item))}</span>
                    </div>
                  ))
                )}
              </div>
              <div className="pdv-ledger-rodape">
                <div className="pdv-caixa-total">
                  <span className="pdv-rotulo">Qtd Itens</span>
                  <span className="pdv-valor">{formatarQuantidade(qtdTotal, permiteQtdDecimal)}</span>
                </div>
                <div className="pdv-caixa-total pdv-total">
                  <span className="pdv-rotulo">Valor Total da Venda</span>
                  <span className="pdv-valor">{moeda(valorTotal)}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {mostrarPesquisa && (
        <PesquisaProdutoModal aoFechar={() => setMostrarPesquisa(false)} aoSelecionar={aoSelecionarNaPesquisa} />
      )}
      {mostrarAlteraQtd && (
        <AlteraQuantidadeModal
          itens={ledger}
          aoFechar={() => setMostrarAlteraQtd(false)}
          aoAlterarQtd={aoAlterarQtd}
          aoRemover={aoRemoverItem}
        />
      )}
      {mostrarPuxarOrcamento && (
        <PuxarOrcamentoModal
          aoFechar={() => setMostrarPuxarOrcamento(false)}
          aoConfirmar={(orcamento, levando) => {
            // ⚠️ O ledger recebe o preço CONGELADO do orçamento, não o do cadastro — e o servidor
            // relê esse preço do banco na efetivação, então a tela aqui é só o que o operador vê.
            setLedger(
              orcamento.itens
                .filter((i) => (levando[i.idVariacao] ?? 0) > 0)
                .map((i) => ({
                  idVariacao: i.idVariacao,
                  codigo: i.sku,
                  descricao: i.descricao,
                  variacao: [i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · ') || null,
                  qtd: levando[i.idVariacao] ?? 0,
                  precoUnit: i.precoVenda,
                  urlImagem: null,
                })),
            )
            setOrcamentoPuxado(orcamento)
            setSelecionado(null)
            setMostrarPuxarOrcamento(false)
            mostrarFlash(`Orçamento nº ${orcamento.idOrcamento} carregado — ${orcamento.nomeCliente}.`)
          }}
        />
      )}
      {mostrarFormaPagamento && (
        <FormaPagamentoModal
          itens={ledger}
          valorTotal={valorTotal}
          idOrcamento={orcamentoPuxado?.idOrcamento ?? null}
          clienteInicial={
            orcamentoPuxado
              ? { idCliente: orcamentoPuxado.idCliente, nome: orcamentoPuxado.nomeCliente,
                  cpfCnpj: orcamentoPuxado.documentoCliente, telefone: orcamentoPuxado.telefoneCliente }
              : null
          }
          vendedorInicial={
            orcamentoPuxado
              ? { idFuncionario: orcamentoPuxado.idFuncionario, nome: orcamentoPuxado.nomeFuncionario }
              : null
          }
          aoFechar={() => {
            // Cancelou sem efetivar — libera a rotina crítica; a venda nunca chegou a existir.
            setMostrarFormaPagamento(false)
            setEmAndamento(false)
          }}
          aoEfetivada={aoVendaEfetivada}
        />
      )}
      {idVendaPapeleta !== null && (
        <ComprovantePapeletaModal
          idVenda={idVendaPapeleta}
          aoFechar={() => {
            setIdVendaPapeleta(null)
            campoBarrasRef.current?.focus()
            // Só libera a rotina crítica aqui: venda efetivada e comprovante já fechado
            // (impresso/enviado ou dispensado) — não antes.
            setEmAndamento(false)
          }}
        />
      )}

      {flashMsg && <div className="pdv-flash pdv-show">{flashMsg}</div>}

      {caixaFechado && (
        <AberturaCaixaModal
          statusCaixa={statusCaixa}
          aoAbrir={() => queryClient.invalidateQueries({ queryKey: ['caixa-status'] })}
          aoVoltar={() => navigate('/')}
        />
      )}
    </div>
  )
}
