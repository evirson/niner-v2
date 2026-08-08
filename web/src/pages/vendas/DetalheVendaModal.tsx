import { useQuery } from '@tanstack/react-query'
import { IconeFechar } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { buscarDetalhePesquisaVenda } from '../../lib/pesquisaVendas'
import { formatarMoeda, mascararCpfCnpj } from '../../lib/masks'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

function corSituacaoParcela(situacao: string): string {
  if (situacao === 'PAGA') return 'var(--sucesso)'
  if (situacao === 'VENCIDA') return 'var(--danger)'
  return 'var(--ink-muted)'
}

function rotuloSituacaoParcela(situacao: string): string {
  if (situacao === 'PAGA') return 'Paga'
  if (situacao === 'VENCIDA') return 'Vencida'
  return 'Aberta'
}

/**
 * Detalhamento da venda selecionada na Pesquisa de Vendas, em popup (2026-07-31) — antes
 * aparecia empilhado abaixo da grid, exigindo scroll da página inteira; agora abre em modal
 * próprio, que rola internamente (mesmo padrão de CancelamentoVendaModal/LancamentosCarteiraModal).
 */
export default function DetalheVendaModal({ idVenda, aoFechar }: { idVenda: number; aoFechar: () => void }) {
  const {
    data: detalhe,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['pesquisa-venda-detalhe', idVenda],
    queryFn: () => buscarDetalhePesquisaVenda(idVenda),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div
        className="modal modal-largo"
        role="dialog"
        aria-label={`Venda nº ${idVenda}`}
        onClick={(e) => e.stopPropagation()}
        style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
      >
        <div className="lightbox-topo" style={{ marginBottom: 12, flexShrink: 0 }}>
          <div className="titulo-tela">
            {detalhe && (
              <>
                <h2 style={{ margin: 0 }}>Venda nº {detalhe.idVenda}</h2>
                {detalhe.cancelada ? (
                  <span className="badge badge-inativo">Cancelada</span>
                ) : (
                  <span className="badge">Faturada</span>
                )}
              </>
            )}
          </div>
          <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
            <IconeFechar />
          </button>
        </div>

        <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
        {isLoading ? (
          <p className="muted">Carregando detalhamento…</p>
        ) : error || !detalhe ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar o detalhamento.'}</p>
        ) : (
          <>
            <div className="form-grid" style={{ marginBottom: 16 }}>
              <div className="col-3">
                <label htmlFor="det-empresa">Empresa</label>
                <input id="det-empresa" className="campo-leitura" readOnly tabIndex={-1} value={detalhe.nomeEmpresa} />
              </div>
              <div className="col-3">
                <label htmlFor="det-data">Data da venda</label>
                <input id="det-data" className="campo-leitura" readOnly tabIndex={-1} value={formatarDataHora(detalhe.dataVenda)} />
              </div>
              <div className="col-3">
                <label htmlFor="det-cliente">Cliente</label>
                <input id="det-cliente" className="campo-leitura" readOnly tabIndex={-1} value={detalhe.nomeCliente ?? '—'} />
              </div>
              <div className="col-3">
                <label htmlFor="det-documento">CPF/CNPJ</label>
                <input
                  id="det-documento"
                  className="campo-leitura"
                  readOnly
                  tabIndex={-1}
                  value={detalhe.cpfCnpj && detalhe.fisicaJuridica !== null ? mascararCpfCnpj(detalhe.cpfCnpj, detalhe.fisicaJuridica) : '—'}
                />
              </div>
              <div className="col-3">
                <label htmlFor="det-vendedor">Vendedor</label>
                <input id="det-vendedor" className="campo-leitura" readOnly tabIndex={-1} value={detalhe.nomeFuncionario ?? '—'} />
              </div>
              <div className="col-6">
                <label htmlFor="det-condicao">Condição de pagamento</label>
                <input id="det-condicao" className="campo-leitura" readOnly tabIndex={-1} value={detalhe.condicaoPagamento} />
              </div>
              <div className="col-3">
                <label htmlFor="det-desconto">Desconto</label>
                <input id="det-desconto" className="campo-leitura mono" readOnly tabIndex={-1} value={moeda(detalhe.desconto)} />
              </div>
              <div className="col-3">
                <label htmlFor="det-total">Valor total</label>
                <input id="det-total" className="campo-leitura mono" readOnly tabIndex={-1} value={moeda(detalhe.valorTotal)} />
              </div>
              <div className="col-3">
                <label htmlFor="det-recebido">Recebido</label>
                <input id="det-recebido" className="campo-leitura mono" readOnly tabIndex={-1} value={moeda(detalhe.recebido)} />
              </div>
              <div className="col-3">
                <label htmlFor="det-a-receber">A receber</label>
                <input id="det-a-receber" className="campo-leitura mono" readOnly tabIndex={-1} value={moeda(detalhe.aReceber)} />
              </div>
            </div>

            {detalhe.cancelada && (
              <p className="muted" style={{ marginTop: -8, marginBottom: 16 }}>
                Cancelada em {detalhe.dataCancelamento && formatarDataHora(detalhe.dataCancelamento)} por{' '}
                {detalhe.nomeUsuarioCancelamento} — motivo: {detalhe.motivoCancelamento}
              </p>
            )}

            <h3>Produtos vendidos</h3>
            <div className="table-wrap" style={{ marginBottom: 16, maxHeight: 220 }}>
              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>Código</th>
                    <th>Descrição</th>
                    <th>Quantidade</th>
                    <th>Valor unitário</th>
                    <th>Desconto</th>
                    <th>Valor total</th>
                  </tr>
                </thead>
                <tbody>
                  {detalhe.itens.map((item, indice) => (
                    <tr key={indice}>
                      <td className="mono">{item.codigo}</td>
                      <td>
                        {item.descricaoProduto}
                        {(item.variacaoCor || item.variacaoTamanho) && (
                          <span className="muted"> ({[item.variacaoCor, item.variacaoTamanho].filter(Boolean).join(' / ')})</span>
                        )}
                      </td>
                      <td className="mono">{item.qtd}</td>
                      <td className="mono">{moeda(item.valorUnitario)}</td>
                      <td className="mono">{moeda(item.valorDesconto)}</td>
                      <td className="mono">{moeda(item.valorItem)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <h3>Movimentação de caixa</h3>
            <div className="table-wrap" style={{ marginBottom: 16, maxHeight: 220 }}>
              {detalhe.movimentosCaixa.length === 0 ? (
                <p className="muted">Nenhuma movimentação de caixa para esta venda.</p>
              ) : (
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Data/Hora</th>
                      <th>Tipo de operação</th>
                      <th>Forma de pagamento</th>
                      <th>Documento</th>
                      <th>C/D</th>
                      <th>Valor</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detalhe.movimentosCaixa.map((m, indice) => (
                      <tr key={indice}>
                        <td>{formatarDataHora(m.dataHora)}</td>
                        <td>{m.tipoOperacao}</td>
                        <td>{m.nomeCarteira}</td>
                        <td>{m.origem}</td>
                        <td className="mono">{m.creditoDebito}</td>
                        <td className="mono">{moeda(m.valor)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            {detalhe.temParcelasCredario && (
              <>
                <h3>Parcelas de crediário</h3>
                <div className="table-wrap" style={{ maxHeight: 220 }}>
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        <th>Parcela</th>
                        <th>Vencimento</th>
                        <th>Valor</th>
                        <th>Situação</th>
                        <th>Data de pagamento</th>
                        <th>Valor pago</th>
                        <th>Juros</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detalhe.parcelas.map((p, indice) => (
                        <tr key={indice}>
                          <td className="mono">{p.numeroParcela}/{p.totalParcelas}</td>
                          <td>{formatarData(p.dataVencimento)}</td>
                          <td className="mono">{moeda(p.valor)}</td>
                          <td style={{ color: corSituacaoParcela(p.situacao) }}>{rotuloSituacaoParcela(p.situacao)}</td>
                          <td>{p.dataPagamento ? formatarData(p.dataPagamento) : '—'}</td>
                          <td className="mono">{p.dataPagamento ? moeda(p.valorPago) : '—'}</td>
                          <td className="mono">{moeda(p.valorJuros)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </>
        )}
        </div>
      </div>
    </div>
  )
}
