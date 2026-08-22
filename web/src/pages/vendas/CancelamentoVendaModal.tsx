import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { IconeFechar } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { buscarDetalheParaCancelamento, cancelarVenda } from '../../lib/cancelamentoVenda'
import { formatarDataHora } from '../../lib/datas'
import { formatarMoeda } from '../../lib/masks'
import AvisoModal from '../../components/AvisoModal'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

/**
 * Confirmação de Cancelamento de Venda (RN-06) — mostra o resumo da venda e o que será
 * revertido (itens de estoque + formas de pagamento), exige um motivo (mínimo 15 caracteres —
 * regra da SEFAZ, MOC, vale mesmo pra venda sem NFC-e) e confirmação explícita (Sim/Não).
 * Bloqueio definitivo de crediário com parcela recebida (RN-03) é mostrado aqui com o detalhe de
 * cada parcela — a tela nem tenta cancelar nesse caso, só "Fechar".
 *
 * **Migrado pra dentro do popup de detalhe da Pesquisa de Vendas (2026-08-18)** — o prop era
 * `venda: VendaParaCancelamento` (a linha inteira da grade da extinta tela de Cancelamento de
 * Venda); virou só `idVenda`, que é tudo que este componente de fato usa fora da própria query
 * de detalhe abaixo. Quem chama decide a invalidação de cache depois do sucesso — este modal não
 * sabe mais de onde foi aberto.
 *
 * **Prazo de cancelamento da NFC-e (2026-08-19)** — quando a venda tem NFC-e autorizada
 * (`temNfce`), o backend cancela a nota na SEFAZ antes de reverter qualquer coisa
 * (`docs/telas/cancelamento-venda.md`, seção "Integração fiscal"). `nfcePrazoCancelamentoExpirado`
 * (calculado no servidor, não no relógio do navegador) some com o campo de motivo e o botão por
 * completo quando a janela da SEFAZ (30 min padrão, por UF) já passou — evita que o operador
 * preencha o motivo só pra levar um 409 depois.
 */
export default function CancelamentoVendaModal({
  idVenda,
  aoFechar,
  aoCancelarComSucesso,
}: {
  idVenda: number
  aoFechar: () => void
  aoCancelarComSucesso: () => void
}) {
  const [motivo, setMotivo] = useState('')
  const [erro, setErro] = useState('')
  /**
   * Erro de NEGÓCIO vindo do servidor (auditoria 2026-08-21, item 10).
   *
   * ⚠️ Separado do `erro` acima, que é validação local ("informe o motivo") e continua inline
   * junto ao campo — ali o texto curto ao lado do campo é o certo. O que vinha errado era a
   * recusa do servidor ("caixa de hoje fechado", "prazo da SEFAZ passou"): saía no mesmo
   * `erro-campo`, contra a convenção do projeto (toda mensagem de erro vira popup), e a
   * mensagem multilinha **colapsava numa linha só**, escondendo a instrução do que fazer.
   */
  const [erroServidor, setErroServidor] = useState('')

  const {
    data: detalhe,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['cancelamento-venda-detalhe', idVenda],
    queryFn: () => buscarDetalheParaCancelamento(idVenda),
    retry: 1,
  })

  const cancelar = useMutation({
    mutationFn: () => cancelarVenda(idVenda, motivo.trim()),
    onSuccess: aoCancelarComSucesso,
    onError: (e: unknown) => setErroServidor(e instanceof ApiError ? e.message : 'Não foi possível cancelar a venda.'),
  })

  const confirmar = () => {
    if (motivo.trim().length < 15) {
      setErro('Informe o motivo do cancelamento (mínimo 15 caracteres).')
      return
    }
    setErro('')
    cancelar.mutate()
  }

  const jaCancelada = detalhe?.cancelada ?? false
  const bloqueada = detalhe?.bloqueadaCredario ?? false
  const temNfce = detalhe?.nfceDataAutorizacao != null
  const prazoExpirado = detalhe?.nfcePrazoCancelamentoExpirado ?? false
  /**
   * Quarta recusa antecipada (auditoria 2026-08-21, item 9) — as outras três (já cancelada,
   * crediário recebido, prazo da SEFAZ) a tela já avisava antes. Esta faltava, e era a mais
   * frustrante: o ADMIN escrevia 15+ caracteres de justificativa para só então levar o erro.
   *
   * ⚠️ `?? true` no default: enquanto o detalhe não chegou, NÃO bloquear. Assumir fechado
   * esconderia o campo de motivo por um instante a cada abertura do modal.
   */
  const caixaFechadoHoje = detalhe ? !detalhe.caixaAbertoHoje : false

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
          <h2 style={{ margin: 0 }}>Cancelamento de Venda nº {idVenda}</h2>
          <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
            <IconeFechar />
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
                        <td>{[item.variacaoCor, item.variacaoTamanho].filter(Boolean).join(' / ') || '—'}</td>
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

              {/* Prazo de cancelamento da NFC-e (2026-08-19) — a SEFAZ só aceita cancelar a nota
                  dentro de uma janela curta contada da autorização (30 min padrão para NFC-e,
                  configurável por UF em cfg_uf_autorizador; bem diferente da NF-e, 24h). Mostrar
                  isso ANTES de tentar evita o usuário preencher o motivo só pra levar um erro
                  genérico depois — e se o prazo já passou, a venda nem tenta (a nota não pode
                  ficar cancelada sem a venda, nem a venda sem a nota — §10.1). */}
              {!jaCancelada && !bloqueada && temNfce && prazoExpirado && (
                <p className="erro" style={{ marginTop: 16 }}>
                  A NFC-e desta venda foi autorizada em {formatarDataHora(detalhe.nfceDataAutorizacao)} e o prazo de{' '}
                  {detalhe.nfcePrazoCancelamentoMinutos} minutos que a SEFAZ dá para cancelá-la já passou (ia até{' '}
                  {formatarDataHora(detalhe.nfcePrazoCancelamentoExpiraEm)}). Não é mais possível cancelar esta
                  venda por aqui — a nota fiscal só pode ser desfeita agora por uma NF-e de devolução.
                </p>
              )}
              {!jaCancelada && !bloqueada && temNfce && !prazoExpirado && (
                <p className="muted" style={{ marginTop: 16, fontSize: 13 }}>
                  NFC-e autorizada em {formatarDataHora(detalhe.nfceDataAutorizacao)} — a SEFAZ permite cancelá-la
                  até {formatarDataHora(detalhe.nfcePrazoCancelamentoExpiraEm)} ({detalhe.nfcePrazoCancelamentoMinutos}{' '}
                  minutos de prazo). Depois disso, esta venda não poderá mais ser cancelada por aqui.
                </p>
              )}
              {/* Caixa de hoje fechado (RN-02) — mesmo espírito do aviso de prazo acima: dizer
                  ANTES, com o caminho da solução, em vez de recusar depois da justificativa. */}
              {!jaCancelada && !bloqueada && !prazoExpirado && caixaFechadoHoje && (
                <p className="erro" style={{ marginTop: 16 }}>
                  O caixa de hoje da empresa {detalhe.nomeEmpresa} está fechado. Abra o caixa antes de cancelar
                  esta venda — o estorno do dinheiro precisa de um caixa aberto para ser lançado.
                </p>
              )}
            </div>

            {!jaCancelada && !bloqueada && !prazoExpirado && !caixaFechadoHoje && (
              <div style={{ flexShrink: 0, marginTop: 16 }}>
                <label htmlFor="motivo-cancelamento">Motivo do Cancelamento *</label>
                <textarea
                  id="motivo-cancelamento"
                  rows={3}
                  value={motivo}
                  onChange={(e) => setMotivo(e.target.value.toUpperCase())}
                  autoFocus
                />
                <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                  Mínimo 15 caracteres — se esta venda tiver NFC-e autorizada, a SEFAZ exige essa
                  justificativa para cancelar a nota junto com a venda.
                </p>
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

      {erroServidor && (
        <AvisoModal titulo="Cancelamento de venda" mensagem={erroServidor} aoFechar={() => setErroServidor('')} />
      )}
    </div>
  )
}
