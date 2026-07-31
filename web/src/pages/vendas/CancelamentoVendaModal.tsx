import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError } from '../../lib/api'
import {
  buscarDetalheParaCancelamento,
  cancelarVenda,
  type VendaParaCancelamento,
} from '../../lib/cancelamentoVenda'
import { formatarDataHora } from '../../lib/datas'
import { formatarMoeda } from '../../lib/masks'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

/**
 * Confirmação de Cancelamento de Venda (RN-06) — mostra o resumo da venda e o que será
 * revertido (itens de estoque + formas de pagamento), exige um motivo e confirmação explícita
 * (Sim/Não). Bloqueio definitivo de crediário com parcela recebida (RN-03) é mostrado aqui com
 * o detalhe de cada parcela — a tela nem tenta cancelar nesse caso, só "Fechar".
 */
export default function CancelamentoVendaModal({
  venda,
  aoFechar,
  aoCancelarComSucesso,
}: {
  venda: VendaParaCancelamento
  aoFechar: () => void
  aoCancelarComSucesso: () => void
}) {
  const queryClient = useQueryClient()
  const [motivo, setMotivo] = useState('')
  const [erro, setErro] = useState('')

  const {
    data: detalhe,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['cancelamento-venda-detalhe', venda.idVenda],
    queryFn: () => buscarDetalheParaCancelamento(venda.idVenda),
    retry: 1,
  })

  const cancelar = useMutation({
    mutationFn: () => cancelarVenda(venda.idVenda, motivo.trim()),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['vendas-cancelamento'] })
      aoCancelarComSucesso()
    },
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível cancelar a venda.'),
  })

  const confirmar = () => {
    if (!motivo.trim()) {
      setErro('Informe o motivo do cancelamento.')
      return
    }
    setErro('')
    cancelar.mutate()
  }

  const jaCancelada = detalhe?.cancelada ?? false
  const bloqueada = detalhe?.bloqueadaCredario ?? false

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div
        className="modal modal-largo"
        role="dialog"
        aria-label="Cancelamento de venda"
        onClick={(e) => e.stopPropagation()}
        style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
      >
        <div className="lightbox-topo" style={{ marginBottom: 12, flexShrink: 0 }}>
          <h2 style={{ margin: 0 }}>Cancelamento de Venda nº {venda.idVenda}</h2>
          <button type="button" className="btn ghost" onClick={aoFechar} aria-label="Fechar">
            ✕
          </button>
        </div>

        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : error || !detalhe ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar a venda.'}</p>
        ) : (
          <>
            <div className="form-grid" style={{ flexShrink: 0, marginBottom: 16 }}>
              <div className="col-4">
                <label>Empresa</label>
                <div className="pdv-selecao-valor">
                  <span>{detalhe.nomeEmpresa}</span>
                </div>
              </div>
              <div className="col-4">
                <label>Data da Venda</label>
                <div className="pdv-selecao-valor">
                  <span>{formatarDataHora(detalhe.dataVenda)}</span>
                </div>
              </div>
              <div className="col-4">
                <label>Valor da Venda</label>
                <div className="pdv-selecao-valor">
                  <span className="mono">{moeda(detalhe.valorVenda)}</span>
                </div>
              </div>
              <div className="col-6">
                <label>Cliente</label>
                <div className="pdv-selecao-valor">
                  <span>{detalhe.nomeCliente ?? '—'}</span>
                </div>
              </div>
              <div className="col-6">
                <label>Vendedor</label>
                <div className="pdv-selecao-valor">
                  <span>{detalhe.nomeFuncionario ?? '—'}</span>
                </div>
              </div>
            </div>

            <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
              <p className="section-label" style={{ marginTop: 0 }}>
                Itens (voltarão ao estoque)
              </p>
              <div className="table-wrap" style={{ maxHeight: 220 }}>
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Produto</th>
                      <th>Variação</th>
                      <th>Qtd.</th>
                      <th>Preço</th>
                      <th>Valor</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detalhe.itens.map((item, indice) => (
                      <tr key={indice}>
                        <td>{item.descricaoProduto}</td>
                        <td>{[item.variacaoLinha, item.variacaoColuna].filter(Boolean).join(' / ') || '—'}</td>
                        <td className="mono">{item.qtd}</td>
                        <td className="mono">{moeda(item.precoVenda)}</td>
                        <td className="mono">{moeda(item.valorItem)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <p className="section-label" style={{ marginTop: 16 }}>
                Formas de Pagamento
              </p>
              <div className="table-wrap" style={{ maxHeight: 180 }}>
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Carteira</th>
                      <th>Categoria</th>
                      <th>Valor</th>
                      <th>Situação</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detalhe.pagamentos.map((p, indice) => (
                      <tr key={indice}>
                        <td>{p.nomeCarteira}</td>
                        <td>{p.categoriaCarteira}</td>
                        <td className="mono">{moeda(p.valorPago)}</td>
                        <td>
                          <span className={`badge ${p.quitado ? '' : 'badge-inativo'}`}>
                            {p.quitado ? 'Quitado' : 'Em aberto'}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {jaCancelada && (
                <p className="erro" style={{ marginTop: 16 }}>
                  A venda nº {detalhe.idVenda} já foi cancelada em {formatarDataHora(detalhe.dataCancelamento)} por{' '}
                  {detalhe.nomeUsuarioCancelamento}. Motivo: {detalhe.motivoCancelamento}
                </p>
              )}

              {!jaCancelada && bloqueada && (
                <div style={{ marginTop: 16 }}>
                  <p className="erro">
                    Esta venda é de crediário e já possui parcela(s) recebida(s). Não é possível cancelá-la, em
                    nenhuma hipótese. Cancele os recebimentos, para depois cancelar a venda.
                  </p>
                  <div className="table-wrap" style={{ maxHeight: 160, marginTop: 8 }}>
                    <table className="table table-compacta">
                      <thead>
                        <tr>
                          <th>Parcela</th>
                          <th>Vencimento</th>
                          <th>Recebida em</th>
                          <th>Valor Recebido</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detalhe.parcelasRecebidas.map((p, indice) => (
                          <tr key={indice}>
                            <td>{p.numeroParcela}</td>
                            <td>{formatarDataHora(p.dataVencimento)}</td>
                            <td>{formatarDataHora(p.dataRecebimento)}</td>
                            <td className="mono">{moeda(p.valorRecebido)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
            </div>

            {!jaCancelada && !bloqueada && (
              <div style={{ flexShrink: 0, marginTop: 16 }}>
                <label htmlFor="motivo-cancelamento">Motivo do Cancelamento *</label>
                <textarea
                  id="motivo-cancelamento"
                  rows={3}
                  value={motivo}
                  onChange={(e) => setMotivo(e.target.value.toUpperCase())}
                  autoFocus
                />
                {erro && <p className="erro-campo">{erro}</p>}
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 12 }}>
                  <button type="button" className="btn" disabled={cancelar.isPending} onClick={confirmar}>
                    {cancelar.isPending ? 'Cancelando…' : 'Sim, Cancelar Venda'}
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
