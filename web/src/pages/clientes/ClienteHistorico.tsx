import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeHistorico, IconeSetaBaixo, IconeSetaCima } from '../../components/Icones'
import { buscarHistoricoCliente } from '../../lib/clienteHistorico'
import { buscarCliente } from '../../lib/clientes'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { formatarDataHora } from '../../lib/datas'
import { formatarMoeda, formatarQuantidade } from '../../lib/masks'

const CHAVE_TELA = 'cadastros.cliente.historico'

function moeda(valor: number): string {
  return `R$ ${formatarMoeda(valor)}`
}

/**
 * Histórico do cliente (2026-07-23, pedido do dono do produto): compras (venda física),
 * parcelas (contas a receber) e resumo das parcelas de crediário em aberto. Só leitura — não
 * existe ainda lançamento de venda nem baixa de parcela, então a tela pode aparecer vazia até
 * essas tabelas terem dados.
 */
export default function ClienteHistorico() {
  const { id } = useParams()
  const idCliente = Number(id)

  // Linha selecionada de cada grid — clique, Enter/Espaço ou ▲/▼ destaca a linha, como uma
  // grid navegável de verdade (2026-07-24, modelo do sistema legado). A compra selecionada
  // filtra os painéis de Produtos da Compra e Histórico de Parcelas (master-detail, 2026-07-27).
  const [compraSelecionada, setCompraSelecionada] = useState<number | null>(null)
  const [parcelaSelecionada, setParcelaSelecionada] = useState<number | null>(null)
  const linhasCompraRef = useRef<Array<HTMLTableRowElement | null>>([])

  const { data: cliente } = useQuery({
    queryKey: ['cliente', id],
    queryFn: () => buscarCliente(idCliente),
  })

  const { data: historico, isLoading } = useQuery({
    queryKey: ['cliente-historico', id],
    queryFn: () => buscarHistoricoCliente(idCliente),
  })

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  // Ao carregar, a primeira compra já vem selecionada — igual ao modelo, que sempre abre
  // com uma linha em destaque.
  useEffect(() => {
    if (historico && historico.compras.length > 0 && compraSelecionada === null) {
      setCompraSelecionada(historico.compras[0].idVenda)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [historico])

  const selecionarCompra = (idVenda: number) => {
    setCompraSelecionada(idVenda)
  }

  const navegarCompra = (indiceAtual: number, direcao: 1 | -1) => {
    const compras = historico?.compras ?? []
    const novoIndice = indiceAtual + direcao
    if (novoIndice < 0 || novoIndice >= compras.length) return
    selecionarCompra(compras[novoIndice].idVenda)
    linhasCompraRef.current[novoIndice]?.focus()
  }

  const indiceCompraSelecionada = (historico?.compras ?? []).findIndex((v) => v.idVenda === compraSelecionada)

  const produtosExibidos =
    compraSelecionada === null
      ? []
      : (historico?.produtos ?? []).filter((i) => i.idVenda === compraSelecionada)

  const parcelasExibidas =
    compraSelecionada === null
      ? []
      : (historico?.parcelas ?? []).filter((p) => p.idVenda === compraSelecionada)

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeHistorico size={34} />
            <h1>Histórico{cliente ? ` — ${cliente.nome}` : ''}</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo historico-corpo">
        {isLoading || !historico ? (
          <p className="muted">Carregando…</p>
        ) : (
          <>
            <div className="historico-grid">
              <div className="card historico-coluna-esquerda">
                <div className="secao-cabecalho">
                  <p className="section-label">Histórico de Compras</p>
                  {historico.compras.length > 1 && (
                    <div className="topbar-acoes">
                      <span className="muted" style={{ fontSize: 12 }}>
                        Navegar:
                      </span>
                      <button
                        type="button"
                        className="btn ghost"
                        aria-label="Compra anterior"
                        title="Compra anterior"
                        disabled={indiceCompraSelecionada <= 0}
                        onClick={() => navegarCompra(indiceCompraSelecionada, -1)}
                      >
                        <IconeSetaCima />
                      </button>
                      <button
                        type="button"
                        className="btn ghost"
                        aria-label="Próxima compra"
                        title="Próxima compra"
                        disabled={
                          indiceCompraSelecionada < 0 || indiceCompraSelecionada >= historico.compras.length - 1
                        }
                        onClick={() => navegarCompra(indiceCompraSelecionada, 1)}
                      >
                        <IconeSetaBaixo />
                      </button>
                    </div>
                  )}
                </div>
                <div className="table-wrap">
                  {historico.compras.length === 0 ? (
                    <p className="muted">Nenhuma compra encontrada.</p>
                  ) : (
                    <table className="table table-compacta">
                      <thead>
                        <tr>
                          <th>Empresa</th>
                          <th>Nº Venda</th>
                          <th>Data/Hora Venda</th>
                          <th>Valor</th>
                        </tr>
                      </thead>
                      <tbody>
                        {historico.compras.map((v, indice) => (
                          <tr
                            key={v.idVenda}
                            ref={(el) => {
                              linhasCompraRef.current[indice] = el
                            }}
                            className={v.idVenda === compraSelecionada ? 'linha-selecionada' : undefined}
                            tabIndex={0}
                            onClick={() => selecionarCompra(v.idVenda)}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter' || e.key === ' ') {
                                e.preventDefault()
                                selecionarCompra(v.idVenda)
                              } else if (e.key === 'ArrowDown') {
                                e.preventDefault()
                                navegarCompra(indice, 1)
                              } else if (e.key === 'ArrowUp') {
                                e.preventDefault()
                                navegarCompra(indice, -1)
                              }
                            }}
                          >
                            <td className="mono">{v.codigoEmpresa}</td>
                            <td className="mono">{v.idVenda}</td>
                            <td>{formatarDataHora(v.dataVenda)}</td>
                            <td>{moeda(v.valor)}</td>
                          </tr>
                        ))}
                      </tbody>
                      <tfoot>
                        <tr>
                          <td colSpan={3}>
                            <strong>Total</strong>
                          </td>
                          <td>
                            <strong>{moeda(historico.compras.reduce((soma, v) => soma + v.valor, 0))}</strong>
                          </td>
                        </tr>
                      </tfoot>
                    </table>
                  )}
                </div>
              </div>

              <div className="historico-coluna-direita">
                <div className="card historico-painel-produtos">
                  <div className="secao-cabecalho">
                    <p className="section-label">
                      Produtos da Compra
                      {compraSelecionada !== null && (
                        <span className="secao-subtitulo"> — venda nº {compraSelecionada}</span>
                      )}
                    </p>
                  </div>
                  <div className="table-wrap">
                    {produtosExibidos.length === 0 ? (
                      <p className="muted">Nenhum produto encontrado.</p>
                    ) : (
                      <table className="table table-compacta">
                        <thead>
                          <tr>
                            <th>Descrição do Produto</th>
                            <th>Cor</th>
                            <th>Tamanho</th>
                            <th>Qtd Vendida</th>
                            <th>Preço de Venda</th>
                          </tr>
                        </thead>
                        <tbody>
                          {produtosExibidos.map((item, indice) => (
                            <tr key={`${item.idVenda}-${indice}`}>
                              <td>{item.descricaoProduto}</td>
                              <td>{item.variacaoCor ?? '—'}</td>
                              <td>{item.variacaoTamanho ?? '—'}</td>
                              <td className="mono">{formatarQuantidade(item.qtdVendida, permiteQtdDecimal)}</td>
                              <td>{moeda(item.precoVenda)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                </div>

                <div className="card historico-painel-parcelas">
                  <div className="secao-cabecalho">
                    <p className="section-label">
                      Histórico das Parcelas
                      {compraSelecionada !== null && (
                        <span className="secao-subtitulo"> — venda nº {compraSelecionada}</span>
                      )}
                    </p>
                  </div>
                  <div className="table-wrap">
                    {parcelasExibidas.length === 0 ? (
                      <p className="muted">Nenhuma parcela encontrada.</p>
                    ) : (
                      <table className="table table-compacta">
                        <thead>
                          <tr>
                            <th>Tipo Parcela</th>
                            <th>Nº Parcela</th>
                            <th>Vencimento</th>
                            <th>Pagamento</th>
                            <th>Valor a Pagar</th>
                            <th>Valor Pago</th>
                          </tr>
                        </thead>
                        <tbody>
                          {parcelasExibidas.map((p) => (
                            <tr
                              key={p.idContaReceber}
                              className={p.idContaReceber === parcelaSelecionada ? 'linha-selecionada' : undefined}
                              tabIndex={0}
                              onClick={() => setParcelaSelecionada(p.idContaReceber)}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter' || e.key === ' ') {
                                  e.preventDefault()
                                  setParcelaSelecionada(p.idContaReceber)
                                }
                              }}
                            >
                              <td>{p.nomeCarteira}</td>
                              <td className="mono">{p.numeroParcela}</td>
                              <td>{formatarDataHora(p.dataVencimento)}</td>
                              <td>{p.dataPagamento ? formatarDataHora(p.dataPagamento) : '—'}</td>
                              <td>{moeda(p.valorAPagar)}</td>
                              <td>{moeda(p.valorPago)}</td>
                            </tr>
                          ))}
                        </tbody>
                        <tfoot>
                          <tr>
                            <td colSpan={4}>
                              <strong>Total</strong>
                            </td>
                            <td>
                              <strong>{moeda(parcelasExibidas.reduce((soma, p) => soma + p.valorAPagar, 0))}</strong>
                            </td>
                            <td>
                              <strong>{moeda(parcelasExibidas.reduce((soma, p) => soma + p.valorPago, 0))}</strong>
                            </td>
                          </tr>
                        </tfoot>
                      </table>
                    )}
                  </div>
                </div>
              </div>
            </div>

            <div className="card" style={{ marginTop: 20 }}>
              <p className="section-label">Resumo das Parcelas de Crediário</p>
              <div className="resumo-crediario-grid">
                <div className="resumo-crediario-card">
                  <p className="card-title">Parcelas Vencidas</p>
                  <p>Valor Total: <strong>{moeda(historico.resumoCrediario.vencidas.valorTotal)}</strong></p>
                  <p>Juros + Multa: <strong>{moeda(historico.resumoCrediario.vencidas.valorJurosMulta)}</strong></p>
                  <p>Nº Parcelas: <strong>{historico.resumoCrediario.vencidas.numeroParcelas}</strong></p>
                </div>
                <div className="resumo-crediario-card">
                  <p className="card-title">Parcelas a Vencer</p>
                  <p>Valor Total: <strong>{moeda(historico.resumoCrediario.aVencer.valorTotal)}</strong></p>
                  <p>Nº Parcelas: <strong>{historico.resumoCrediario.aVencer.numeroParcelas}</strong></p>
                </div>
                <div className="resumo-crediario-card">
                  <p className="card-title">Parcelas Total</p>
                  <p>Valor Total: <strong>{moeda(historico.resumoCrediario.total.valorTotal)}</strong></p>
                  <p>Juros + Multa: <strong>{moeda(historico.resumoCrediario.total.valorJurosMulta)}</strong></p>
                  <p>Nº Parcelas: <strong>{historico.resumoCrediario.total.numeroParcelas}</strong></p>
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
