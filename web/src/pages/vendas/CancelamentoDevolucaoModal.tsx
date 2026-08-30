import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { IconeFechar } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import {
  buscarDetalheParaCancelamentoDevolucao,
  cancelarDevolucao,
  type DevolucaoParaCancelamento,
} from '../../lib/cancelamentoDevolucao'
import { formatarDataHora } from '../../lib/datas'
import { formatarMoeda } from '../../lib/masks'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'
import AvisoModal from '../../components/AvisoModal'
import { fecharAoClicarNoFundo } from '../../lib/modais'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

/**
 * Confirmação de Cancelamento de Devolução de Produtos — mostra o resumo do vale e os itens
 * que serão retirados do estoque (inverso da devolução original), exige um motivo e
 * confirmação explícita (Sim/Não). Vale já usado ou já cancelado nunca chega a mostrar o
 * formulário de motivo — só o aviso do porquê não é possível cancelar.
 */
export default function CancelamentoDevolucaoModal({
  devolucao,
  aoFechar,
  aoCancelarComSucesso,
}: {
  devolucao: DevolucaoParaCancelamento
  aoFechar: () => void
  aoCancelarComSucesso: () => void
}) {
  // O servidor exige EXCLUIR em `cancelamento-devolucao-produtos`. Sem isto, o operador localizava
  // a devolução, escrevia os 15+ caracteres de motivo que a tela exige, confirmava — e levava 403
  // com o cliente na frente.
  const acoes = usePermissaoDaTela('cancelamento-devolucao-produtos')
  const queryClient = useQueryClient()
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
    queryKey: ['cancelamento-devolucao-detalhe', devolucao.idDevolucao],
    queryFn: () => buscarDetalheParaCancelamentoDevolucao(devolucao.idDevolucao),
    retry: 1,
  })

  const cancelar = useMutation({
    mutationFn: () => cancelarDevolucao(devolucao.idDevolucao, motivo.trim()),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['devolucoes-cancelamento'] })
      aoCancelarComSucesso()
    },
    onError: (e: unknown) => setErroServidor(e instanceof ApiError ? e.message : 'Não foi possível cancelar a devolução.'),
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
  const jaUsado = detalhe?.valeUsado ?? false

  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
      <div
        className="modal modal-largo"
        role="dialog"
        aria-label="Cancelamento de devolução"
        onClick={(e) => e.stopPropagation()}
        style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
      >
        <div className="lightbox-topo" style={{ marginBottom: 12, flexShrink: 0 }}>
          <h2 style={{ margin: 0 }}>Cancelamento de Devolução — Vale nº {devolucao.idDevolucao}</h2>
          <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
            <IconeFechar />
          </button>
        </div>

        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : error || !detalhe ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar a devolução.'}</p>
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
                <label>Data da Devolução</label>
                <div className="pdv-selecao-valor">
                  <span>{formatarDataHora(detalhe.dataDevolucao)}</span>
                </div>
              </div>
              <div className="col-4">
                <label>Valor do Vale</label>
                <div className="pdv-selecao-valor">
                  <span className="mono">{moeda(detalhe.valorVale)}</span>
                </div>
              </div>
            </div>

            <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
              <p className="section-label" style={{ marginTop: 0 }}>
                Itens (sairão do estoque)
              </p>
              <div className="table-wrap" style={{ maxHeight: 260 }}>
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

              {jaCancelada && (
                <p className="erro" style={{ marginTop: 16 }}>
                  A devolução nº {detalhe.idDevolucao} já foi cancelada em {formatarDataHora(detalhe.dataCancelamento)} por{' '}
                  {detalhe.nomeUsuarioCancelamento}. Motivo: {detalhe.motivoCancelamento}
                </p>
              )}

              {!jaCancelada && jaUsado && (
                <p className="erro" style={{ marginTop: 16 }}>
                  O vale-mercadoria nº {detalhe.idDevolucao} já foi usado numa venda. Não é possível cancelar uma
                  devolução cujo vale já foi resgatado.
                </p>
              )}
            </div>

            {!jaCancelada && !jaUsado && (
              <div style={{ flexShrink: 0, marginTop: 16 }}>
                <label htmlFor="motivo-cancelamento-devolucao">Motivo do Cancelamento *</label>
                <textarea
                  id="motivo-cancelamento-devolucao"
                  rows={3}
                  value={motivo}
                  onChange={(e) => setMotivo(e.target.value.toUpperCase())}
                  autoFocus
                />
                {erro && <p className="erro-campo">{erro}</p>}
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 12 }}>
                  <button type="button" className="btn" disabled={cancelar.isPending || !acoes.excluir} onClick={confirmar}>
                    {cancelar.isPending ? 'Cancelando…' : 'Sim, Cancelar Devolução'}
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
      {erroServidor && (
        <AvisoModal titulo="Cancelamento de devolução" mensagem={erroServidor} aoFechar={() => setErroServidor('')} />
      )}
    </div>
  )
}
