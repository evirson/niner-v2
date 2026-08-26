import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import CabecalhoModal from '../../components/CabecalhoModal'
import { IconeCanais } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  enviarPedido,
  listarFilaExpedicao,
  listarItensDoPedido,
  separarPedido,
  type PedidoNaFila,
} from '../../lib/canais'
import { formatarMoeda } from '../../lib/masks'

function formatarDataHora(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * Fila de Expedição (R5, M7) — separar e despachar o que foi vendido no marketplace.
 *
 * <p>⚠️ **A fila é "o que já foi pago e ainda não saiu".** Pedido não pago não entra: separar
 * mercadoria para quem pode não pagar é trabalho jogado fora, e a peça sai da prateleira e some do
 * fluxo da loja. A reserva de estoque já segura a peça.
 *
 * <p>⚠️ **Não é ADMIN-only**, ao contrário do resto do módulo de canais: quem separa e embala é o
 * operador. Exigir ADMIN obrigaria o dono a despachar tudo, ou a dar acesso de administrador a
 * quem só precisa de uma lista de itens.
 *
 * <p>⚠️ **Atualiza sozinha a cada 30 s.** A fila muda por fora — pedido novo chega pelo webhook,
 * e um colega pode ter pego a caixa. Uma tela parada faria dois operadores separarem o mesmo
 * pedido; o servidor recusa o segundo, mas o trabalho já teria sido feito.
 */
export default function FilaExpedicao() {
  const queryClient = useQueryClient()
  const [aviso, setAviso] = useState<{ mensagem: string; tipo: 'erro' | 'sucesso' } | null>(null)
  const [expedindo, setExpedindo] = useState<PedidoNaFila | null>(null)
  const [rastreio, setRastreio] = useState('')

  const { data: fila, isLoading } = useQuery({
    queryKey: ['expedicao'],
    queryFn: listarFilaExpedicao,
    refetchInterval: 30_000,
  })

  const { data: itens } = useQuery({
    queryKey: ['expedicao-itens', expedindo?.idPedido],
    queryFn: () => listarItensDoPedido(expedindo!.idPedido),
    enabled: expedindo !== null,
  })

  function tratarErro(e: unknown) {
    // ⚠️ O servidor devolve 409 dizendo o ESTADO REAL do pedido ("está em separação") — trocar
    // isso por um genérico apagaria a única informação que resolve a dúvida do operador.
    setAviso({
      mensagem: e instanceof ApiError && e.message ? e.message : 'Não foi possível falar com o servidor.',
      tipo: 'erro',
    })
    queryClient.invalidateQueries({ queryKey: ['expedicao'] })
  }

  const separar = useMutation({
    mutationFn: (idPedido: number) => separarPedido(idPedido),
    onSuccess: () => {
      setAviso({ mensagem: 'Pedido em separação.', tipo: 'sucesso' })
      queryClient.invalidateQueries({ queryKey: ['expedicao'] })
    },
    onError: tratarErro,
  })

  const enviar = useMutation({
    mutationFn: (p: { idPedido: number; rastreio: string }) => enviarPedido(p.idPedido, p.rastreio),
    onSuccess: () => {
      setAviso({ mensagem: 'Pedido despachado.', tipo: 'sucesso' })
      setExpedindo(null)
      setRastreio('')
      queryClient.invalidateQueries({ queryKey: ['expedicao'] })
    },
    onError: tratarErro,
  })

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeCanais size={34} />
            <h1>Fila de Expedição</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="expedicao.tela" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        {isLoading && <p className="muted">Carregando…</p>}

        {!isLoading && (fila ?? []).length === 0 && (
          <div className="card">
            <strong>Nada para despachar.</strong>
            <p className="muted" style={{ marginBottom: 0 }}>
              Aqui aparecem os pedidos de marketplace <strong>já pagos</strong> que ainda não
              saíram. Pedido aguardando pagamento não entra na fila — o estoque dele já está
              reservado, mas separar antes de o dinheiro entrar é risco de trabalho perdido.
            </p>
          </div>
        )}

        {(fila ?? []).length > 0 && (
          <table className="tabela">
            <thead>
              <tr>
                <th>Pedido</th>
                <th>Canal</th>
                <th>Comprador</th>
                <th style={{ textAlign: 'right' }}>Itens</th>
                <th style={{ textAlign: 'right' }}>Total</th>
                <th>Situação</th>
                <th>Ações</th>
              </tr>
            </thead>
            <tbody>
              {(fila ?? []).map((p) => (
                <tr key={p.idPedido}>
                  <td>
                    <span className="mono">{p.idExterno}</span>
                    <br />
                    <span className="muted">{formatarDataHora(p.criadoEm)}</span>
                  </td>
                  <td>{p.nomeCanal}</td>
                  <td>{p.comprador ?? '—'}</td>
                  <td style={{ textAlign: 'right' }}>{p.itens}</td>
                  <td style={{ textAlign: 'right' }}>{formatarMoeda(p.total)}</td>
                  <td>
                    {p.status === 'EM_SEPARACAO' ? (
                      <span className="badge">
                        Em separação desde {formatarDataHora(p.dataSeparacao)}
                      </span>
                    ) : (
                      <span className="badge badge-ok">Pago</span>
                    )}
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {p.status === 'PAGO' && (
                        <button
                          type="button"
                          className="btn"
                          disabled={separar.isPending}
                          onClick={() => separar.mutate(p.idPedido)}
                        >
                          Separar
                        </button>
                      )}
                      <button
                        type="button"
                        className="btn ghost"
                        onClick={() => {
                          setExpedindo(p)
                          setRastreio(p.codigoRastreio ?? '')
                        }}
                      >
                        {p.status === 'EM_SEPARACAO' ? 'Despachar…' : 'Ver itens…'}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {expedindo && (
        <div className="lightbox-fundo" role="dialog" aria-modal="true">
          <div
            className="lightbox-caixa"
            style={{ maxWidth: 640, display: 'flex', flexDirection: 'column' }}
          >
            <CabecalhoModal
              titulo={`Pedido ${expedindo.idExterno} — ${expedindo.nomeCanal}`}
              aoFechar={() => setExpedindo(null)}
            />

            {/* ⚠️ `flex:1` + `min-height:0`: sem o min-height a lista empurra o popup para fora da
                janela em telas baixas, em vez de rolar por dentro. */}
            <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: 16 }}>
              <table className="tabela">
                <thead>
                  <tr>
                    <th>Produto</th>
                    <th>Código de barras</th>
                    <th style={{ textAlign: 'right' }}>Qtd.</th>
                  </tr>
                </thead>
                <tbody>
                  {(itens ?? []).map((i) => (
                    <tr key={i.sku}>
                      <td>{i.descricao}</td>
                      <td className="mono">{i.sku}</td>
                      <td style={{ textAlign: 'right' }}>{i.quantidade}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              {expedindo.status === 'EM_SEPARACAO' && (
                <div style={{ marginTop: 16 }}>
                  <label htmlFor="rastreio">Código de rastreio (opcional)</label>
                  <input
                    id="rastreio"
                    value={rastreio}
                    maxLength={60}
                    onChange={(e) => setRastreio(e.target.value)}
                  />
                  <p className="muted">
                    No Mercado Envios quem gera a etiqueta e o rastreio é o próprio Mercado Livre —
                    preencha só se você estiver despachando por conta própria.
                  </p>
                </div>
              )}
            </div>

            {/* O rodapé é da AÇÃO que a tela existe para fazer; fechar é o ✕ do canto. */}
            {expedindo.status === 'EM_SEPARACAO' && (
              <div className="lightbox-rodape" style={{ flexShrink: 0, padding: 16 }}>
                <button
                  type="button"
                  className="btn"
                  disabled={enviar.isPending}
                  onClick={() => enviar.mutate({ idPedido: expedindo.idPedido, rastreio })}
                >
                  {enviar.isPending ? 'Despachando…' : 'Confirmar despacho'}
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {aviso && <Toast mensagem={aviso.mensagem} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}
    </div>
  )
}
