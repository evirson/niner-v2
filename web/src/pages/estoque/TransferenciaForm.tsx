import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeEstoque, IconeExcluir, IconeLupa } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { listarEmpresas } from '../../lib/empresas'
import { useEu } from '../../lib/eu'
import { completarQuantidade, desmascararQuantidade, formatarMoeda, formatarQuantidade, mascararQuantidade } from '../../lib/masks'
import { buscarProdutoPorCodigo, interpretarCodigoBarras, type PdvProduto } from '../../lib/pdv'
import { criarTransferencia } from '../../lib/transferencias'
import { maiusculas } from '../../lib/texto'
import EscolherDestinoModal from './EscolherDestinoModal'
import PesquisaProdutoModal from '../pdv/PesquisaProdutoModal'

const CHAVE_TELA = 'estoque.transferencia.form'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

interface ItemLinha {
  idVariacao: number
  descricao: string
  variacao: string | null
  sku: string
  estoqueOrigem: number
  precoVenda: number
  qtdTexto: string
}

function variacaoTexto(p: PdvProduto): string | null {
  if (!p.variacaoCor && !p.variacaoTamanho) return null
  return [p.variacaoCor, p.variacaoTamanho].filter(Boolean).join(' · ')
}

export default function TransferenciaForm() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: eu } = useEu()

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas })
  const empresaOrigem = empresas?.find((e) => e.idEmpresa === eu?.empresa.idEmpresa)
  const opcoesDestino = (empresas ?? []).filter((e) => e.ativo && e.idEmpresa !== eu?.empresa.idEmpresa)

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const [idEmpresaDestino, setIdEmpresaDestino] = useState<number | ''>('')
  const [itens, setItens] = useState<ItemLinha[]>([])
  const [mostrarPesquisa, setMostrarPesquisa] = useState(false)
  const [valorBarras, setValorBarras] = useState('')
  const [toast, setToast] = useState('')
  const campoBarrasRef = useRef<HTMLInputElement>(null)
  const barrasTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    return () => {
      if (barrasTimeoutRef.current) clearTimeout(barrasTimeoutRef.current)
    }
  }, [])

  // Escolhida a empresa de destino no popup, o foco já vai direto pro código de barras — mesma
  // lógica de entrada única do PDV.
  useEffect(() => {
    if (idEmpresaDestino !== '') campoBarrasRef.current?.focus()
  }, [idEmpresaDestino])

  const criar = useMutation({
    mutationFn: () =>
      criarTransferencia({
        idEmpresaDestino: Number(idEmpresaDestino),
        itens: itens.map((i) => ({ idVariacao: i.idVariacao, qtd: desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) })),
        observacoes: null,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['transferencias'] })
      navigate('/estoque', {
        state: { toast: { texto: 'Transferência realizada com sucesso.', tipo: 'sucesso' } },
      })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível realizar a transferência.'),
  })

  // Lança um produto na lista — usado tanto pela leitura do código de barras quanto pela
  // Pesquisa de Produto (mesma rotina do PDV): se a variação já está na lista, soma `qtd` na
  // quantidade em vez de duplicar a linha. `qtd` é 1 por padrão (leitura simples) — "5*código"
  // no campo de barras já manda a quantidade digitada (ver `interpretarCodigoBarras`).
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
      const estoqueNaOrigem = empresaOrigem
        ? (produto.estoquePorEmpresa.find((e) => e.codigoEmpresa === empresaOrigem.codigoEmpresa)?.qtd ?? 0)
        : 0
      return [
        ...atual,
        {
          idVariacao: produto.idVariacao,
          descricao: produto.descricaoProduto,
          variacao: variacaoTexto(produto),
          sku: produto.sku,
          estoqueOrigem: estoqueNaOrigem,
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
      setToast(e instanceof ApiError ? e.message : 'Não foi possível ler o código de barras.')
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
    // Preenche o código de barras brevemente (como se tivesse sido lido) antes de limpar e
    // devolver o foco — mesma rotina de F2 do PDV.
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

  // Saldo negativo em estoque é permitido de propósito (2026-07-29) — só a quantidade
  // informada precisa ser maior que zero, não há mais bloqueio contra o saldo da origem.
  const algumItemZerado = itens.some((i) => desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) <= 0)
  const podeConfirmar = idEmpresaDestino !== '' && itens.length > 0 && !algumItemZerado

  /**
   * Por que o "Confirmar Transferência" está cinza (2026-09-04).
   *
   * ⚠️ O botão mora na **topbar**, visível de qualquer ponto da tela; a única explicação que
   * existia ("Nenhum produto adicionado ainda") mora lá embaixo, na área da lista, e cobria só uma
   * das três razões. Escolher o destino e zerar uma quantidade deixavam o botão morto **sem
   * diagnóstico nenhum**. Guarda que bloqueia e mensagem que a explica têm de ficar sob a mesma
   * condição, e ao alcance do olho de quem vai clicar.
   */
  const motivoBloqueio = idEmpresaDestino === ''
    ? 'Escolha a empresa de destino.'
    : itens.length === 0
      ? 'Adicione ao menos um produto.'
      : algumItemZerado
        ? 'Há produto com quantidade zerada — informe a quantidade ou remova a linha.'
        : null
  const qtdTotal = itens.reduce((soma, i) => soma + desmascararQuantidade(i.qtdTexto, permiteQtdDecimal), 0)
  const valorTotal = itens.reduce((soma, i) => soma + desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) * i.precoVenda, 0)

  const nomeDestino = opcoesDestino.find((e) => e.idEmpresa === idEmpresaDestino)
  const nomeDestinoTexto = nomeDestino ? (nomeDestino.nomeFantasia ?? nomeDestino.razaoSocial) : null

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>
              Nova Transferência
              {eu?.empresa && nomeDestinoTexto && (
                <span style={{ color: 'var(--ink-muted)', fontWeight: 500, fontSize: 16, marginLeft: 10 }}>
                  · {eu.empresa.nome} → {nomeDestinoTexto}
                </span>
              )}
            </h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
            {motivoBloqueio && (
              <span className="muted" style={{ fontSize: 12, maxWidth: 260, textAlign: 'right' }}>
                {motivoBloqueio}
              </span>
            )}
            <button
              type="button"
              className="btn"
              disabled={!podeConfirmar || criar.isPending}
              onClick={() => criar.mutate()}
              title={motivoBloqueio ?? undefined}
            >
              {criar.isPending ? 'Transferindo…' : 'Confirmar Transferência'}
            </button>
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes form-secoes-larga">
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
                  placeholder={idEmpresaDestino === '' ? 'Selecione a empresa de destino primeiro…' : 'Aguardando leitura…'}
                  autoComplete="off"
                  inputMode="numeric"
                  disabled={idEmpresaDestino === ''}
                  value={valorBarras}
                  onChange={(e) => setValorBarras(maiusculas(e.target.value))}
                  onKeyDown={aoDigitarBarras}
                />
                <p className="pdv-dica">Leia o código de barras do produto e pressione Enter.</p>
                <p className="pdv-dica">Dica: "5*código" lança direto com quantidade 5.</p>
              </div>
              {/* Rótulo invisível com a mesma altura do rótulo real acima do campo de código de
                  barras — garante que o botão comece exatamente na mesma linha do input, em vez
                  de alinhar pelo fundo da coluna (que é mais alta por causa da dica abaixo). */}
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                <div className="pdv-rotulo" style={{ visibility: 'hidden' }}>
                  Pesquisar
                </div>
                <button
                  type="button"
                  className="btn ghost"
                  style={{ height: 50, whiteSpace: 'nowrap' }}
                  disabled={idEmpresaDestino === ''}
                  onClick={() => setMostrarPesquisa(true)}
                >
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
                        <th style={{ textAlign: 'right' }}>Estoque na Origem</th>
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
                            <td className="mono" style={{ textAlign: 'right' }}>
                              {formatarQuantidade(item.estoqueOrigem, permiteQtdDecimal)}
                            </td>
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
                        <td colSpan={3}>
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

      {idEmpresaDestino === '' && eu?.empresa && (
        <EscolherDestinoModal
          nomeOrigem={eu.empresa.nome}
          opcoesDestino={opcoesDestino}
          aoFechar={() => navigate(-1)}
          aoConfirmar={(id) => setIdEmpresaDestino(id)}
        />
      )}

      {mostrarPesquisa && (
        <PesquisaProdutoModal aoFechar={() => setMostrarPesquisa(false)} aoSelecionar={aoSelecionarNaPesquisa} />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
