import { Fragment, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { IconeFechar } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { useEu } from '../../lib/eu'
import { buscarDetalhePesquisaVenda } from '../../lib/pesquisaVendas'
import { formatarMoeda, mascararCpfCnpj } from '../../lib/masks'
import ComprovantePapeletaModal from '../pdv/ComprovantePapeletaModal'
import CancelamentoVendaModal from './CancelamentoVendaModal'

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

type Aba = 'geral' | 'produtos' | 'caixa' | 'parcelas'

/**
 * Detalhamento da venda selecionada na Pesquisa de Vendas, em popup (2026-07-31) — antes
 * aparecia empilhado abaixo da grid, exigindo scroll da página inteira; agora abre em modal
 * próprio, que rola internamente (mesmo padrão de CancelamentoVendaModal/LancamentosCarteiraModal).
 *
 * **Abas (2026-08-18)** — as 4 seções (Dados Gerais/Produtos Vendidos/Movimentação de Caixa/
 * Parcelas de Crediário) eram empilhadas sem abas de propósito (pedido original: comparar
 * produtos com recebimentos ao mesmo tempo); revertido a pedido do dono do produto. "Parcelas de
 * Crediário" só aparece como aba quando a venda tem crediário (`detalhe.temParcelasCredario`) —
 * sem isso a aba ficaria vazia pra maioria das vendas (dinheiro/cartão/PIX).
 *
 * **Reimprimir papeleta (2026-08-18)** — reaproveita `ComprovantePapeletaModal` em modo
 * `reimpressao` (mesmo componente da extinta tela de Reimpressão de Papeleta de Venda, removida
 * do menu por ficar redundante com este botão), empilhado por cima deste popup — zero lógica
 * nova de impressão/PDF/WhatsApp.
 *
 * **Cancelar Venda (2026-08-18)** — a rotina de Cancelamento de Venda migrou pra cá (o item saiu
 * do menu): reaproveita `CancelamentoVendaModal` tal como estava, só trocando o prop de
 * `venda: VendaParaCancelamento` para `idVenda: number` — era a única coisa que ele de fato usava
 * do objeto da linha da grade antiga, o resto vem todo da própria query de detalhe do modal.
 * Botão só aparece pra ADMIN e quando a venda ainda não está cancelada; as regras de bloqueio
 * (crediário recebido, caixa fechado) continuam resolvidas dentro do modal reaproveitado.
 */
export default function DetalheVendaModal({ idVenda, aoFechar }: { idVenda: number; aoFechar: () => void }) {
  const queryClient = useQueryClient()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const {
    data: detalhe,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['pesquisa-venda-detalhe', idVenda],
    queryFn: () => buscarDetalhePesquisaVenda(idVenda),
  })

  const [aba, setAba] = useState<Aba>('geral')
  const [mostrarReimpressao, setMostrarReimpressao] = useState(false)
  const [mostrarCancelamento, setMostrarCancelamento] = useState(false)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  return (
    <Fragment>
    <div className="modal-overlay" onClick={aoFechar}>
      <div
        className="modal modal-largo"
        role="dialog"
        aria-label={`Venda nº ${idVenda}`}
        onClick={(e) => e.stopPropagation()}
        // Tamanho fixo (2026-08-18, pedido do dono do produto): antes a altura seguia o
        // conteúdo, e trocar de aba fazia o popup encolher/crescer a cada clique — só a área
        // rolável interna (abaixo) muda de conteúdo, o modal em si nunca muda de tamanho.
        style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', height: '78vh' }}
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
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {detalhe && !detalhe.cancelada && ehAdmin && (
              <button type="button" className="btn ghost" onClick={() => setMostrarCancelamento(true)}>
                Cancelar venda
              </button>
            )}
            {/* Venda cancelada não tem papeleta (2026-08-20): o botão some, e o servidor
                recusa o endpoint de qualquer jeito. Oferecer uma ação que vai falhar é pior que
                não oferecer — e um cupom impresso de venda cancelada circula afirmando uma venda
                que não existe mais. */}
            {detalhe && !detalhe.cancelada && (
              <button type="button" className="btn ghost" onClick={() => setMostrarReimpressao(true)}>
                Reimprimir papeleta
              </button>
            )}
            <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
              <IconeFechar />
            </button>
          </div>
        </div>

        {isLoading ? (
          <p className="muted">Carregando detalhamento…</p>
        ) : error || !detalhe ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar o detalhamento.'}</p>
        ) : (
          <>
            <div className="abas-nav" role="tablist">
              <button
                type="button"
                role="tab"
                aria-selected={aba === 'geral'}
                className={`aba-botao ${aba === 'geral' ? 'ativa' : ''}`}
                onClick={() => setAba('geral')}
              >
                Dados Gerais
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={aba === 'produtos'}
                className={`aba-botao ${aba === 'produtos' ? 'ativa' : ''}`}
                onClick={() => setAba('produtos')}
              >
                Produtos Vendidos
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={aba === 'caixa'}
                className={`aba-botao ${aba === 'caixa' ? 'ativa' : ''}`}
                onClick={() => setAba('caixa')}
              >
                Movimentação de Caixa
              </button>
              {detalhe.temParcelasCredario && (
                <button
                  type="button"
                  role="tab"
                  aria-selected={aba === 'parcelas'}
                  className={`aba-botao ${aba === 'parcelas' ? 'ativa' : ''}`}
                  onClick={() => setAba('parcelas')}
                >
                  Parcelas de Crediário
                </button>
              )}
            </div>

            <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
              {aba === 'geral' && (
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
                    <p className="muted" style={{ marginTop: -8 }}>
                      Cancelada em {detalhe.dataCancelamento && formatarDataHora(detalhe.dataCancelamento)} por{' '}
                      {detalhe.nomeUsuarioCancelamento} — motivo: {detalhe.motivoCancelamento}
                    </p>
                  )}
                </>
              )}

              {aba === 'produtos' && (
                <div className="table-wrap">
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
              )}

              {aba === 'caixa' && (
                <div className="table-wrap">
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
              )}

              {aba === 'parcelas' && detalhe.temParcelasCredario && (
                <div className="table-wrap">
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
              )}
            </div>
          </>
        )}
      </div>
    </div>

    {mostrarReimpressao && (
      <ComprovantePapeletaModal idVenda={idVenda} reimpressao aoFechar={() => setMostrarReimpressao(false)} />
    )}
    {mostrarCancelamento && (
      <CancelamentoVendaModal
        idVenda={idVenda}
        aoFechar={() => setMostrarCancelamento(false)}
        aoCancelarComSucesso={() => {
          setMostrarCancelamento(false)
          queryClient.invalidateQueries({ queryKey: ['pesquisa-venda-detalhe', idVenda] })
          queryClient.invalidateQueries({ queryKey: ['pesquisa-vendas'] })
          setAviso({ texto: `Venda nº ${idVenda} cancelada com sucesso.`, tipo: 'sucesso' })
        }}
      />
    )}
    {aviso && <Toast mensagem={aviso.texto} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}
    </Fragment>
  )
}
