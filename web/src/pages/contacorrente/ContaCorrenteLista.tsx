import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import {
  IconeContaCorrente,
  IconeEditar,
  IconeExcluir,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  excluirContaCorrente,
  listarContasCorrente,
  type ColunaOrdenacaoContaCorrente,
  type ContaCorrente,
  type StatusContaCorrente,
} from '../../lib/contaCorrente'
import { maiusculas } from '../../lib/texto'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoContaCorrente; rotulo: string }> = [
  { chave: 'idContaCorrente', rotulo: 'Conta' },
  { chave: 'descricao', rotulo: 'Descrição' },
  { chave: 'nomeEmpresa', rotulo: 'Empresa' },
  { chave: 'idBanco', rotulo: 'Banco/Agência' },
  { chave: 'status', rotulo: 'Status' },
]

function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

/**
 * Listagem de conta corrente (docs/telas/conta-corrente.md) — mesmo padrão estrutural de
 * `cadastros.planocontas` (PK de negócio, código imutável), com filtro de status e fallback de
 * inativar na exclusão (tem `ativo`, diferente de plano de contas).
 */
export default function ContaCorrenteLista() {
  const location = useLocation()
  const [busca, setBusca] = useState('')
  const [status, setStatus] = useState<StatusContaCorrente>('ATIVOS')
  const [contaParaExcluir, setContaParaExcluir] = useState<ContaCorrente | null>(null)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(
    () => (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  useEffect(() => {
    if (location.state) window.history.replaceState({}, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoContaCorrente>('descricao')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('ASC')

  useEffect(() => {
    setPagina(1)
  }, [busca, status, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoContaCorrente) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['contas-corrente', { busca, status, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarContasCorrente({
        busca: busca || undefined,
        status,
        pagina,
        tamanho: TAMANHO_PAGINA,
        ordenarPor,
        direcao,
      }),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1

  const excluir = useMutation({
    mutationFn: excluirContaCorrente,
    onSuccess: (resultado) => {
      queryClient.invalidateQueries({ queryKey: ['contas-corrente'] })
      setContaParaExcluir(null)
      setAviso({
        texto: resultado.acao === 'inativado' ? 'Conta corrente inativada (possui movimentações).' : 'Conta corrente excluída.',
        tipo: 'sucesso',
      })
    },
    onError: (e: unknown) => {
      setContaParaExcluir(null)
      setAviso({ texto: e instanceof ApiError ? e.message : 'Não foi possível excluir a conta corrente.', tipo: 'erro' })
    },
  })

  const contas: ContaCorrente[] = data?.itens ?? []

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeContaCorrente size={34} />
            <h1>Conta Corrente</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.contacorrente.lista" />
            <Link className="btn" to="/contas-corrente/nova">
              ＋ Nova conta corrente
            </Link>
            <BotaoFecharTela />
          </div>
        </div>

        {aviso && <Toast mensagem={aviso.texto} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}

        <div className="card filtros-bar">
          <input
            autoFocus
            placeholder="Buscar por conta ou descrição…"
            value={busca}
            onChange={(e) => setBusca(maiusculas(e.target.value))}
            aria-label="Buscar por conta ou descrição"
          />
          <select value={status} onChange={(e) => setStatus(e.target.value as StatusContaCorrente)} aria-label="Filtrar por status">
            <option value="ATIVOS">Ativos</option>
            <option value="INATIVOS">Inativos</option>
            <option value="TODOS">Todos</option>
          </select>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : contas.length === 0 ? (
          <p className="muted">Nenhuma conta corrente encontrada.</p>
        ) : (
          <table className="table table-compacta">
            <thead>
              <tr>
                {COLUNAS.map((c) => {
                  const ativa = ordenarPor === c.chave
                  return (
                    <th
                      key={c.chave}
                      className="th-ordenavel"
                      onClick={() => ordenarPorColuna(c.chave)}
                      title="Clique para ordenar"
                      aria-sort={ativa ? (direcao === 'ASC' ? 'ascending' : 'descending') : 'none'}
                    >
                      {c.rotulo}
                      <span className={`th-seta ${ativa ? 'th-seta-ativa' : ''}`}>
                        {ativa ? (direcao === 'ASC' ? '▲' : '▼') : '⇅'}
                      </span>
                    </th>
                  )
                })}
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {contas.map((c) => (
                <tr key={c.idContaCorrente}>
                  <td className="mono">{c.idContaCorrente}</td>
                  <td>{c.descricao}</td>
                  <td>{c.nomeEmpresa}</td>
                  <td className="mono">{c.idBanco} / {c.idAgencia}</td>
                  <td>{c.ativo ? 'Ativa' : 'Inativa'}</td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/contas-corrente/${encodeURIComponent(c.idContaCorrente)}/visualizar`}
                      aria-label={`Visualizar ${c.idContaCorrente}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    <Link
                      className="acao-icone acao-editar"
                      to={`/contas-corrente/${encodeURIComponent(c.idContaCorrente)}`}
                      aria-label={`Editar ${c.idContaCorrente}`}
                      title="Editar"
                    >
                      <IconeEditar />
                    </Link>
                    <button
                      type="button"
                      className="acao-icone acao-excluir"
                      disabled={excluir.isPending}
                      onClick={() => setContaParaExcluir(c)}
                      aria-label={`Excluir ${c.idContaCorrente}`}
                      title="Excluir"
                    >
                      <IconeExcluir />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        </div>
      </div>

      {contas.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} conta{data?.totalItens === 1 ? '' : 's'} corrente
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

      {contaParaExcluir && (
        <div className="modal-overlay" onClick={() => setContaParaExcluir(null)}>
          <div className="modal" role="dialog" aria-label="Confirmar exclusão" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Excluir conta corrente?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{contaParaExcluir.idContaCorrente} — {contaParaExcluir.descricao}</strong>?
              Se houver movimentações associadas, a conta será inativada em vez de excluída.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setContaParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(contaParaExcluir.idContaCorrente)}
              >
                {excluir.isPending ? 'Excluindo…' : 'Excluir'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
