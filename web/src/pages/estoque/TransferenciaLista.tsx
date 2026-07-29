import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import {
  IconeEstoque,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { listarTransferencias } from '../../lib/transferencias'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

export default function TransferenciaLista() {
  const location = useLocation()
  const [pagina, setPagina] = useState(1)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(
    () => (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  useEffect(() => {
    if (location.state) window.history.replaceState({}, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['transferencias', { pagina }],
    queryFn: () => listarTransferencias(pagina, TAMANHO_PAGINA),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1
  const transferencias = data?.itens ?? []

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>Transferências entre Empresas</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="estoque.transferencia.lista" />
            <Link className="btn" to="/estoque/nova">
              ＋ Nova Transferência
            </Link>
          </div>
        </div>

        {aviso && <Toast mensagem={aviso.texto} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : transferencias.length === 0 ? (
            <p className="muted">Nenhuma transferência realizada ainda.</p>
          ) : (
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Data</th>
                  <th>Origem</th>
                  <th>Destino</th>
                  <th>Usuário</th>
                  <th style={{ textAlign: 'right' }}>Itens</th>
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {transferencias.map((t) => (
                  <tr key={t.idTransferencia}>
                    <td className="mono">{formatarData(t.dataTransferencia)}</td>
                    <td>{t.empresaOrigem.nomeEmpresa}</td>
                    <td>{t.empresaDestino.nomeEmpresa}</td>
                    <td>{t.nomeUsuario}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>
                      {t.itens.length}
                    </td>
                    <td className="acoes-cell">
                      <Link
                        className="acao-icone acao-visualizar"
                        to={`/estoque/${t.idTransferencia}`}
                        aria-label={`Visualizar transferência ${t.idTransferencia}`}
                        title="Visualizar"
                      >
                        <IconeOlho />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {transferencias.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} transferência{data?.totalItens === 1 ? '' : 's'}
              {isFetching && ' · atualizando…'}
            </span>
            <div className="paginacao-paginas">
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina(1)}
                disabled={pagina <= 1 || isFetching}
                aria-label="Primeira página"
                title="Primeira página"
              >
                <IconePrimeiraPagina />
              </button>
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina((p) => Math.max(1, p - 1))}
                disabled={pagina <= 1 || isFetching}
                aria-label="Página anterior"
                title="Página anterior"
              >
                <IconePaginaAnterior />
              </button>
              {paginasVisiveis(pagina, totalPaginas).map((p) => (
                <button
                  key={p}
                  type="button"
                  className={`btn ghost paginacao-numero ${p === pagina ? 'ativa' : ''}`}
                  onClick={() => setPagina(p)}
                  disabled={isFetching}
                  aria-current={p === pagina ? 'page' : undefined}
                >
                  {p}
                </button>
              ))}
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina((p) => Math.min(totalPaginas, p + 1))}
                disabled={pagina >= totalPaginas || isFetching}
                aria-label="Próxima página"
                title="Próxima página"
              >
                <IconeProximaPagina />
              </button>
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina(totalPaginas)}
                disabled={pagina >= totalPaginas || isFetching}
                aria-label="Última página"
                title="Última página"
              >
                <IconeUltimaPagina />
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
