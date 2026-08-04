import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeDevolucaoProduto, IconeExcluir, IconeLupa } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import {
  buscarVendedorDaVenda,
  efetivarDevolucao,
  type DevolucaoEfetivada,
  type VendedorDaVenda,
} from '../../lib/devolucaoProduto'
import { useEu } from '../../lib/eu'
import { completarQuantidade, desmascararQuantidade, formatarMoeda, formatarQuantidade, mascararQuantidade } from '../../lib/masks'
import { buscarProdutoPorCodigo, interpretarCodigoBarras, type PdvProduto } from '../../lib/pdv'
import PesquisaProdutoModal from '../pdv/PesquisaProdutoModal'
import ComprovanteValeModal from './ComprovanteValeModal'

const CHAVE_TELA = 'vendas.devolucaoproduto.form'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

interface ItemLinha {
  idVariacao: number
  descricao: string
  variacao: string | null
  precoVenda: number
  qtdTexto: string
}

function variacaoTexto(p: PdvProduto): string | null {
  if (!p.variacaoLinha && !p.variacaoColuna) return null
  return [p.variacaoLinha, p.variacaoColuna].filter(Boolean).join(' · ')
}

export default function DevolucaoProduto() {
  const navigate = useNavigate()
  const { data: eu } = useEu()

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const [numeroVendaTexto, setNumeroVendaTexto] = useState('')
  const [vendedor, setVendedor] = useState<VendedorDaVenda | null>(null)
  const [buscandoVendedor, setBuscandoVendedor] = useState(false)

  const [itens, setItens] = useState<ItemLinha[]>([])
  const [mostrarPesquisa, setMostrarPesquisa] = useState(false)
  const [valorBarras, setValorBarras] = useState('')
  const [toast, setToast] = useState<{ texto: string; tipo: 'erro' | 'sucesso' } | null>(null)
  /** Vale gerado pela última devolução gravada — abre o comprovante automaticamente (2026-08-03). */
  const [valeGerado, setValeGerado] = useState<DevolucaoEfetivada | null>(null)
  const campoBarrasRef = useRef<HTMLInputElement>(null)
  const barrasTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    campoBarrasRef.current?.focus()
    return () => {
      if (barrasTimeoutRef.current) clearTimeout(barrasTimeoutRef.current)
    }
  }, [])

  const buscarVendedor = async () => {
    const numero = Number(numeroVendaTexto.trim())
    if (!numeroVendaTexto.trim() || !Number.isFinite(numero) || numero <= 0) {
      setVendedor(null)
      return
    }
    setBuscandoVendedor(true)
    try {
      const resultado = await buscarVendedorDaVenda(numero)
      setVendedor(resultado)
      if (!resultado.idFuncionario) {
        setToast({ texto: 'Venda encontrada, mas sem vendedor associado.', tipo: 'erro' })
      }
    } catch (e) {
      setVendedor(null)
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível localizar a venda.', tipo: 'erro' })
    } finally {
      setBuscandoVendedor(false)
    }
  }

  const aoDigitarNumeroVenda: React.KeyboardEventHandler<HTMLInputElement> = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      buscarVendedor()
    }
  }

  const efetivar = useMutation({
    mutationFn: () =>
      efetivarDevolucao({
        numeroVenda: numeroVendaTexto.trim() ? Number(numeroVendaTexto.trim()) : null,
        itens: itens.map((i) => ({ idVariacao: i.idVariacao, qtd: desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) })),
      }),
    onSuccess: (resultado) => {
      setToast({ texto: 'Devolução gravada com sucesso.', tipo: 'sucesso' })
      setItens([])
      setNumeroVendaTexto('')
      setVendedor(null)
      setValeGerado(resultado)
    },
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível gravar a devolução.', tipo: 'erro' }),
  })

  // Lança um produto na grid — se a variação já está na lista, soma `qtd` na quantidade em vez
  // de duplicar a linha (mesma rotina do PDV/Transferência).
  const lancarProduto = (produto: PdvProduto, qtd: number = 1) => {
    setItens((atual) => {
      const existente = atual.find((i) => i.idVariacao === produto.idVariacao)
      if (existente) {
        return atual.map((i) =>
          i.idVariacao === produto.idVariacao
            ? { ...i, qtdTexto: formatarQuantidade(desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) + qtd, permiteQtdDecimal) }
            : i,
        )
      }
      return [
        ...atual,
        {
          idVariacao: produto.idVariacao,
          descricao: produto.descricaoProduto,
          variacao: variacaoTexto(produto),
          precoVenda: produto.precoVenda,
          qtdTexto: formatarQuantidade(qtd, permiteQtdDecimal),
        },
      ]
    })
  }

  const lerCodigo = async (valorDigitado: string) => {
    const { qtd, codigo } = interpretarCodigoBarras(valorDigitado)
    try {
      const produto = await buscarProdutoPorCodigo(codigo)
      lancarProduto(produto, qtd)
    } catch (e) {
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível ler o código de barras.', tipo: 'erro' })
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

  const aoSelecionarNaPesquisa = (produto: PdvProduto) => {
    setMostrarPesquisa(false)
    lancarProduto(produto)
    setValorBarras(produto.sku)
    if (barrasTimeoutRef.current) clearTimeout(barrasTimeoutRef.current)
    barrasTimeoutRef.current = setTimeout(() => {
      setValorBarras('')
      campoBarrasRef.current?.focus()
    }, 400)
  }

  const removerItem = (idVariacao: number) => {
    setItens((atual) => atual.filter((i) => i.idVariacao !== idVariacao))
  }

  const alterarQtd = (idVariacao: number, texto: string) => {
    setItens((atual) =>
      atual.map((i) => (i.idVariacao === idVariacao ? { ...i, qtdTexto: mascararQuantidade(texto, permiteQtdDecimal) } : i)),
    )
  }

  const aoSairQtd = (idVariacao: number) => {
    setItens((atual) =>
      atual.map((i) => (i.idVariacao === idVariacao ? { ...i, qtdTexto: completarQuantidade(i.qtdTexto, permiteQtdDecimal) } : i)),
    )
  }

  const algumItemZerado = itens.some((i) => desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) <= 0)
  const podeConfirmar = itens.length > 0 && !algumItemZerado
  const qtdTotal = itens.reduce((soma, i) => soma + desmascararQuantidade(i.qtdTexto, permiteQtdDecimal), 0)
  const valorTotal = itens.reduce((soma, i) => soma + desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) * i.precoVenda, 0)

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeDevolucaoProduto size={34} />
            <h1>Devolução de Produtos</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
            <button
              type="button"
              className="btn"
              disabled={!podeConfirmar || efetivar.isPending}
              onClick={() => efetivar.mutate()}
            >
              {efetivar.isPending ? 'Gravando…' : 'Gravar Devolução'}
            </button>
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes form-secoes-larga">
          <section className="section">
            <p className="section-label" style={{ margin: 0 }}>
              Venda de Origem (opcional)
            </p>
            <p className="muted" style={{ marginTop: 4 }}>
              Informe o número da venda só para identificar o vendedor — não fica registrado na devolução, serve
              apenas para, no futuro, descontar a comissão de quem vendeu.
            </p>
            <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', marginTop: 10 }}>
              <div style={{ maxWidth: 220 }}>
                <label>Número da Venda</label>
                <input
                  type="text"
                  inputMode="numeric"
                  autoComplete="off"
                  placeholder="Opcional"
                  value={numeroVendaTexto}
                  onChange={(e) => {
                    setNumeroVendaTexto(e.target.value.replace(/\D/g, ''))
                    setVendedor(null)
                  }}
                  onKeyDown={aoDigitarNumeroVenda}
                />
              </div>
              <button type="button" className="btn ghost" onClick={buscarVendedor} disabled={buscandoVendedor}>
                {buscandoVendedor ? 'Buscando…' : 'Buscar Vendedor'}
              </button>
              {vendedor && (
                <p className="muted" style={{ margin: 0 }}>
                  {vendedor.idFuncionario
                    ? <>Vendedor: <strong>{vendedor.nomeFuncionario}</strong></>
                    : 'Sem vendedor associado a esta venda.'}
                </p>
              )}
            </div>
          </section>

          <section className="section">
            <p className="section-label" style={{ margin: 0 }}>
              Produtos
            </p>

            <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', marginTop: 10 }}>
              <div className="pdv-campo-codigo-barras" style={{ flex: 1 }}>
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
                  onChange={(e) => setValorBarras(e.target.value)}
                  onKeyDown={aoDigitarBarras}
                />
                <p className="pdv-dica">Leia o código de barras do produto devolvido e pressione Enter.</p>
                <p className="pdv-dica">Dica: "5*código" lança direto com quantidade 5.</p>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                <div className="pdv-rotulo" style={{ visibility: 'hidden' }}>
                  Pesquisar
                </div>
                <button type="button" className="btn ghost" style={{ height: 50, whiteSpace: 'nowrap' }} onClick={() => setMostrarPesquisa(true)}>
                  <IconeLupa /> Pesquisar Produto
                </button>
              </div>
            </div>

            {itens.length === 0 ? (
              <p className="muted" style={{ marginTop: 12 }}>
                Nenhum produto adicionado ainda.
              </p>
            ) : (
              <div className="table-wrap" style={{ marginTop: 12 }}>
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Descrição</th>
                      <th>Variação</th>
                      <th style={{ textAlign: 'right' }}>Quantidade</th>
                      <th style={{ textAlign: 'right' }}>Valor Unitário</th>
                      <th style={{ textAlign: 'right' }}>Valor Total</th>
                      <th aria-label="Ações" />
                    </tr>
                  </thead>
                  <tbody>
                    {itens.map((item) => {
                      const qtdNumero = desmascararQuantidade(item.qtdTexto, permiteQtdDecimal)
                      const zerado = qtdNumero <= 0
                      return (
                        <tr key={item.idVariacao}>
                          <td>{item.descricao}</td>
                          <td>{item.variacao ?? '—'}</td>
                          <td style={{ textAlign: 'right' }}>
                            <input
                              className="mono"
                              style={{ width: 110, textAlign: 'right' }}
                              inputMode={permiteQtdDecimal ? undefined : 'numeric'}
                              value={item.qtdTexto}
                              onChange={(e) => alterarQtd(item.idVariacao, e.target.value)}
                              onBlur={() => aoSairQtd(item.idVariacao)}
                              onFocus={(e) => e.target.select()}
                            />
                            {zerado && <p className="erro-campo">Informe uma quantidade maior que zero.</p>}
                          </td>
                          <td className="mono" style={{ textAlign: 'right' }}>
                            {moeda(item.precoVenda)}
                          </td>
                          <td className="mono" style={{ textAlign: 'right' }}>
                            {moeda(qtdNumero * item.precoVenda)}
                          </td>
                          <td className="acoes-cell">
                            <button
                              type="button"
                              className="acao-icone acao-excluir"
                              onClick={() => removerItem(item.idVariacao)}
                              aria-label={`Remover ${item.descricao}`}
                              title="Remover"
                            >
                              <IconeExcluir />
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td colSpan={2}>
                        <strong>Total</strong>
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}>
                        <strong>{formatarQuantidade(qtdTotal, permiteQtdDecimal)}</strong>
                      </td>
                      <td />
                      <td className="mono" style={{ textAlign: 'right' }}>
                        <strong>{moeda(valorTotal)}</strong>
                      </td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
            )}
          </section>
        </div>
      </div>

      {mostrarPesquisa && (
        <PesquisaProdutoModal aoFechar={() => setMostrarPesquisa(false)} aoSelecionar={aoSelecionarNaPesquisa} />
      )}

      {valeGerado && (
        <ComprovanteValeModal
          devolucao={valeGerado}
          nomeEmpresa={eu?.empresa.nome ?? '—'}
          aoFechar={() => {
            setValeGerado(null)
            campoBarrasRef.current?.focus()
          }}
        />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
