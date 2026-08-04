import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import {
  IconeEditar,
  IconeExcluir,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
  IconeUsuario,
} from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { useEu } from '../../lib/eu'
import { maiusculas } from '../../lib/texto'
import {
  excluirUsuario,
  listarUsuarios,
  type ColunaOrdenacaoUsuario,
  type StatusUsuario,
  type Usuario,
} from '../../lib/usuarios'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoUsuario; rotulo: string }> = [
  { chave: 'nome', rotulo: 'Nome' },
  { chave: 'email', rotulo: 'E-mail' },
  { chave: 'papel', rotulo: 'Papel' },
  { chave: 'status', rotulo: 'Status' },
]

/** Mesma janela de paginação centrada na página atual de `cadastros.funcionario`. */
function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

export default function UsuarioLista() {
  const location = useLocation()
  const [nome, setNome] = useState('')
  const [status, setStatus] = useState<StatusUsuario>('ATIVOS')
  const [usuarioParaExcluir, setUsuarioParaExcluir] = useState<Usuario | null>(null)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(
    () => (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  useEffect(() => {
    if (location.state) window.history.replaceState({}, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoUsuario>('nome')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('ASC')

  useEffect(() => {
    setPagina(1)
  }, [nome, status, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoUsuario) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['usuarios', { nome, status, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarUsuarios({
        nome: nome || undefined,
        status,
        pagina,
        tamanho: TAMANHO_PAGINA,
        ordenarPor,
        direcao,
      }),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1

  const { data: eu } = useEu()

  const excluir = useMutation({
    mutationFn: excluirUsuario,
    onSuccess: (resposta) => {
      queryClient.invalidateQueries({ queryKey: ['usuarios'] })
      setUsuarioParaExcluir(null)
      setAviso({
        texto:
          resposta.acao === 'inativado'
            ? (resposta.motivo ?? 'Usuário inativado (possui vínculos).')
            : 'Usuário excluído.',
        tipo: 'sucesso',
      })
    },
    onError: (e: unknown) => {
      setUsuarioParaExcluir(null)
      setAviso({
        texto: e instanceof ApiError ? e.message : 'Não foi possível excluir o usuário.',
        tipo: 'erro',
      })
    },
  })

  const usuarios: Usuario[] = data?.itens ?? []

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeUsuario size={34} />
            <h1>Usuários</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="identidade.usuario.lista" />
            <Link className="btn" to="/usuarios/novo">
              ＋ Novo usuário
            </Link>
            <BotaoFecharTela />
          </div>
        </div>

        {aviso && <Toast mensagem={aviso.texto} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}

        <div className="card filtros-bar">
          <input
            placeholder="Buscar por nome ou e-mail…"
            value={nome}
            onChange={(e) => setNome(maiusculas(e.target.value))}
            aria-label="Buscar por nome ou e-mail"
          />
          <select value={status} onChange={(e) => setStatus(e.target.value as StatusUsuario)} aria-label="Filtrar por status">
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
        ) : usuarios.length === 0 ? (
          <p className="muted">Nenhum usuário encontrado.</p>
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
                <th>Empresas</th>
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {usuarios.map((u) => (
                <tr key={u.idUsuario}>
                  <td>{u.nome}</td>
                  <td className="mono">{u.email}</td>
                  <td>
                    <span className={`badge ${u.administrador ? '' : 'badge-inativo'}`}>
                      {u.administrador ? 'Administrador' : 'Operador'}
                    </span>
                  </td>
                  <td>
                    <span className={`badge ${u.ativo ? '' : 'badge-inativo'}`}>{u.ativo ? 'Ativo' : 'Inativo'}</span>
                  </td>
                  <td>{u.empresas.map((e) => e.nomeEmpresa).join(', ') || '—'}</td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/usuarios/${u.idUsuario}/visualizar`}
                      aria-label={`Visualizar ${u.nome}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    <Link
                      className="acao-icone acao-editar"
                      to={`/usuarios/${u.idUsuario}`}
                      aria-label={`Editar ${u.nome}`}
                      title="Editar"
                    >
                      <IconeEditar />
                    </Link>
                    <button
                      type="button"
                      className="acao-icone acao-excluir"
                      disabled={excluir.isPending || u.idUsuario === eu?.usuario.idUsuario}
                      onClick={() => setUsuarioParaExcluir(u)}
                      aria-label={`Excluir ${u.nome}`}
                      title={u.idUsuario === eu?.usuario.idUsuario ? 'Você não pode excluir a própria conta' : 'Excluir'}
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

      {usuarios.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} usuário{data?.totalItens === 1 ? '' : 's'}
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

      {usuarioParaExcluir && (
        <div className="modal-overlay" onClick={() => setUsuarioParaExcluir(null)}>
          <div
            className="modal"
            role="dialog"
            aria-label="Confirmar exclusão"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 style={{ marginTop: 0 }}>Excluir usuário?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{usuarioParaExcluir.nome}</strong>? Se houver caixa(s)
              associado(s), o usuário será inativado em vez de excluído.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setUsuarioParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(usuarioParaExcluir.idUsuario)}
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
