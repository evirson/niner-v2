import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeEditar, IconeExcluir, IconeOlho, IconeOrdemServico } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { dataParaIso, dataValida, formatarMoeda, mascararData } from '../../lib/masks'
import {
  SITUACAO_OS,
  cancelarOrdemServico,
  listarOrdensServico,
  type LinhaOs,
  type SituacaoOs,
} from '../../lib/ordensServico'
import { maiusculas } from '../../lib/texto'

const TAMANHO_PAGINA = 50

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

/**
 * Ordens de Serviço (S4, `docs/telas/ordem-servico.md`).
 *
 * <p>A OS é o trabalho que leva tempo: o carro que fica na oficina, o cachorro que fica no banho.
 * Ela nasce ABERTA, anda pelos estados de execução e — concluída — vira venda pelo <b>F5 do
 * PDV</b>, o mesmo caminho do orçamento.
 *
 * <p>⛔ <b>Não existe botão "faturar" aqui.</b> Quem cobra é o PDV, e é lá que moram caixa aberto,
 * formas de pagamento, limite de crédito e emissão fiscal. Uma segunda porta de faturamento teria
 * de reimplementar tudo isso.
 */
export default function OrdensServico() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [busca, setBusca] = useState('')
  const [situacao, setSituacao] = useState<SituacaoOs | ''>('')
  const [dataInicialTexto, setDataInicialTexto] = useState('')
  const [dataFinalTexto, setDataFinalTexto] = useState('')
  const [pagina, setPagina] = useState(1)

  const [cancelando, setCancelando] = useState<LinhaOs | null>(null)
  const [motivo, setMotivo] = useState('')
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  const dataInicialIso = dataValida(dataInicialTexto) ? (dataParaIso(dataInicialTexto) ?? undefined) : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? (dataParaIso(dataFinalTexto) ?? undefined) : undefined

  const { data, isLoading } = useQuery({
    queryKey: ['ordens-servico', { busca, situacao, dataInicialIso, dataFinalIso, pagina }],
    queryFn: () =>
      listarOrdensServico({
        busca: busca || undefined,
        situacao: situacao || undefined,
        dataInicial: dataInicialIso,
        dataFinal: dataFinalIso,
        pagina,
        limite: TAMANHO_PAGINA,
      }),
    placeholderData: (anterior) => anterior,
  })

  const cancelar = useMutation({
    mutationFn: () => cancelarOrdemServico(cancelando!.idOrdemServico, maiusculas(motivo.trim())),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ordens-servico'] })
      // A reserva de estoque foi liberada — as telas que mostram saldo têm de reler.
      queryClient.invalidateQueries({ queryKey: ['pdv-produtos'] })
      setCancelando(null)
      setMotivo('')
      setToast({ texto: 'Ordem de serviço cancelada — as peças voltaram ao estoque.', tipo: 'sucesso' })
    },
    onError: (e: unknown) =>
      setToast({
        texto: e instanceof ApiError ? e.message : 'Não foi possível cancelar a ordem de serviço.',
        tipo: 'erro',
      }),
  })

  const ordens = data?.itens ?? []
  const totalPaginas = Math.max(1, data?.totalPaginas ?? 1)

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeOrdemServico size={34} />
            <h1>Ordens de Serviço</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="vendas.ordemservico.lista" />
            <button type="button" className="btn" onClick={() => navigate('/ordens-servico/nova')}>
              ＋ Nova ordem de serviço
            </button>
            <BotaoFecharTela />
          </div>
        </div>

        <div className="card filtros-bar">
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <div style={{ minWidth: 240 }}>
              <label htmlFor="os-filtro-busca">Cliente, objeto do serviço ou número</label>
              <input
                id="os-filtro-busca"
                autoFocus
                placeholder="Ex.: ABC1D23, MARIA, 42"
                value={busca}
                onChange={(e) => {
                  setBusca(maiusculas(e.target.value))
                  setPagina(1)
                }}
              />
            </div>
            <div>
              <label htmlFor="os-filtro-situacao">Situação</label>
              <select
                id="os-filtro-situacao"
                value={situacao}
                onChange={(e) => {
                  setSituacao(e.target.value as SituacaoOs | '')
                  setPagina(1)
                }}
              >
                <option value="">Todas</option>
                {(Object.keys(SITUACAO_OS) as SituacaoOs[]).map((s) => (
                  <option key={s} value={s}>
                    {SITUACAO_OS[s].rotulo}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="os-filtro-inicio">Aberta de</label>
              <input
                id="os-filtro-inicio"
                className="mono"
                placeholder="dd/mm/aaaa"
                value={dataInicialTexto}
                onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
                onFocus={(e) => e.target.select()}
              />
            </div>
            <div>
              <label htmlFor="os-filtro-fim">até</label>
              <input
                id="os-filtro-fim"
                className="mono"
                placeholder="dd/mm/aaaa"
                value={dataFinalTexto}
                onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
                onFocus={(e) => e.target.select()}
              />
            </div>
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : ordens.length === 0 ? (
            <p className="muted">Nenhuma ordem de serviço encontrada.</p>
          ) : (
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Nº</th>
                  <th>Abertura</th>
                  <th>Cliente</th>
                  <th>Objeto do serviço</th>
                  <th style={{ textAlign: 'right' }}>Total</th>
                  <th>Situação</th>
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {ordens.map((o) => (
                  <tr key={o.idOrdemServico}>
                    <td className="mono">{o.idOrdemServico}</td>
                    <td>{formatarData(o.dataAbertura)}</td>
                    <td>{o.nomeCliente}</td>
                    <td>{o.objetoServico}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(o.total)}</td>
                    <td>
                      <span style={{ color: SITUACAO_OS[o.situacao].cor, fontWeight: 600 }}>
                        {SITUACAO_OS[o.situacao].rotulo}
                      </span>
                      {o.idVenda && <span className="muted"> venda {o.idVenda}</span>}
                    </td>
                    <td className="acoes-cell">
                      <button
                        type="button"
                        className="acao-icone acao-visualizar"
                        title="Ver ordem de serviço"
                        aria-label={`Ver ordem de serviço nº ${o.idOrdemServico}`}
                        onClick={() => navigate(`/ordens-servico/${o.idOrdemServico}/visualizar`)}
                      >
                        <IconeOlho />
                      </button>
                      {/* ⚠️ Editar só enquanto a OS ainda é execução. Faturada é história (virou
                          venda) e cancelada também — a tela do formulário repete a trava, mas
                          oferecer um caminho que vai falhar é pior que não oferecer. */}
                      {o.situacao !== 'FATURADA' && o.situacao !== 'CANCELADA' && (
                        <button
                          type="button"
                          className="acao-icone acao-editar"
                          title="Alterar ordem de serviço"
                          aria-label={`Alterar ordem de serviço nº ${o.idOrdemServico}`}
                          onClick={() => navigate(`/ordens-servico/${o.idOrdemServico}`)}
                        >
                          <IconeEditar />
                        </button>
                      )}
                      {/* ⚠️ Cancelar é a única saída de uma OS que não vai virar venda — e é ela
                          que devolve as peças reservadas ao estoque (DS17). Ícone vermelho porque
                          "desfazer é excluir": quem pode abrir OS não deveria poder cancelar a de
                          ontem, e o servidor cobra a ação EXCLUIR. */}
                      {o.situacao !== 'FATURADA' && o.situacao !== 'CANCELADA' && (
                        <button
                          type="button"
                          className="acao-icone acao-excluir"
                          title="Cancelar ordem de serviço"
                          aria-label={`Cancelar ordem de serviço nº ${o.idOrdemServico}`}
                          onClick={() => {
                            setMotivo('')
                            setCancelando(o)
                          }}
                        >
                          <IconeExcluir />
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {ordens.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} ordem{data?.totalItens === 1 ? '' : 's'} de serviço
            </span>
            <div className="paginacao-paginas">
              <button
                type="button"
                className="btn ghost"
                disabled={pagina <= 1}
                onClick={() => setPagina((p) => Math.max(1, p - 1))}
              >
                Anterior
              </button>
              <span className="mono" style={{ padding: '0 8px' }}>
                {pagina} / {totalPaginas}
              </span>
              <button
                type="button"
                className="btn ghost"
                disabled={pagina >= totalPaginas}
                onClick={() => setPagina((p) => p + 1)}
              >
                Próxima
              </button>
            </div>
          </div>
        </div>
      )}

      {cancelando && (
        <div className="modal-overlay" onClick={() => (cancelar.isPending ? null : setCancelando(null))}>
          <div className="modal" role="dialog" aria-label="Cancelar ordem de serviço" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Cancelar OS nº {cancelando.idOrdemServico}</h2>
            <p className="muted" style={{ marginTop: 4 }}>
              As peças reservadas voltam ao estoque e a OS não pode mais virar venda. Não é possível
              desfazer — se o cliente voltar, abra uma nova.
            </p>
            <label htmlFor="os-motivo-cancelamento">Motivo *</label>
            <input
              id="os-motivo-cancelamento"
              autoFocus
              value={motivo}
              onChange={(e) => setMotivo(maiusculas(e.target.value))}
            />
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" disabled={cancelar.isPending} onClick={() => setCancelando(null)}>
                Voltar
              </button>
              <button
                type="button"
                className="btn"
                disabled={!motivo.trim() || cancelar.isPending}
                onClick={() => cancelar.mutate()}
              >
                {cancelar.isPending ? 'Cancelando…' : 'Cancelar OS'}
              </button>
            </div>
          </div>
        </div>
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
