import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import {
  IconeEditar,
  IconeEngrenagem,
  IconeExcluir,
  IconeFuncionario,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import { ApiError } from '../../lib/api'
import {
  excluirFuncionario,
  listarFuncionarios,
  type ColunaOrdenacaoFuncionario,
  type Funcionario,
  type StatusFuncionario,
} from '../../lib/funcionarios'
import { useEu } from '../../lib/eu'
import { formatarPercentual, mascararCpfCnpj, mascararTelefone } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoFuncionario; rotulo: string }> = [
  { chave: 'nome', rotulo: 'Nome' },
  { chave: 'cpf', rotulo: 'CPF' },
  { chave: 'telefone', rotulo: 'Celular' },
  { chave: 'cargo', rotulo: 'Cargo' },
  { chave: 'percComissao', rotulo: '% Comissão' },
  { chave: 'status', rotulo: 'Status' },
]

/**
 * Monta a janela de números de página exibida na navegação (mesmo padrão de
 * `cadastros.cliente`): até `JANELA_PAGINACAO` números centrados na página atual, sem
 * reticências — os botões "primeira"/"última" nas pontas cobrem o resto.
 */
function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

export default function FuncionarioLista() {
  const [nome, setNome] = useState('')
  const [status, setStatus] = useState<StatusFuncionario>('ATIVOS')
  const [funcionarioParaExcluir, setFuncionarioParaExcluir] = useState<Funcionario | null>(null)
  const [aviso, setAviso] = useState('')
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoFuncionario>('nome')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('ASC')

  useEffect(() => {
    setPagina(1)
  }, [nome, status, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoFuncionario) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['funcionarios', { nome, status, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarFuncionarios({
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

  const excluir = useMutation({
    mutationFn: excluirFuncionario,
    onSuccess: (resposta) => {
      queryClient.invalidateQueries({ queryKey: ['funcionarios'] })
      setFuncionarioParaExcluir(null)
      setAviso(
        resposta.acao === 'inativado'
          ? (resposta.motivo ?? 'Funcionário inativado (possui vínculos).')
          : 'Funcionário excluído.',
      )
    },
    onError: (e: unknown) => {
      setFuncionarioParaExcluir(null)
      setAviso(e instanceof ApiError ? e.message : 'Não foi possível excluir o funcionário.')
    },
  })

  const funcionarios: Funcionario[] = data?.itens ?? []
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeFuncionario size={34} />
            <h1>Funcionários</h1>
          </div>
          <div className="topbar-acoes">
            {ehAdmin && (
              <Link
                className="btn ghost ajuda-gatilho"
                to="/funcionarios/configuracao"
                aria-label="Configurar tela de funcionário"
                title="Configurar campos desta tela"
              >
                <IconeEngrenagem />
              </Link>
            )}
            <AjudaDaTela chaveTela="cadastros.funcionario.lista" />
            <Link className="btn" to="/funcionarios/novo">
              ＋ Novo funcionário
            </Link>
          </div>
        </div>

        {aviso && (
          <div className="card aviso-banner" role="status">
            <span>{aviso}</span>
            <button type="button" className="btn ghost" onClick={() => setAviso('')}>
              Ok
            </button>
          </div>
        )}

        <div className="card filtros-bar">
          <input
            placeholder="Buscar por nome…"
            value={nome}
            onChange={(e) => setNome(maiusculas(e.target.value))}
            aria-label="Buscar por nome"
          />
          <select value={status} onChange={(e) => setStatus(e.target.value as StatusFuncionario)} aria-label="Filtrar por status">
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
        ) : funcionarios.length === 0 ? (
          <p className="muted">Nenhum funcionário encontrado.</p>
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
              {funcionarios.map((f) => (
                <tr key={f.idFuncionario}>
                  <td>{f.nome}</td>
                  <td className="mono">{f.cpf ? mascararCpfCnpj(f.cpf, true) : '—'}</td>
                  <td className="mono">{f.telefone ? mascararTelefone(f.telefone) : '—'}</td>
                  <td>{f.cargo ?? '—'}</td>
                  <td className="mono">{formatarPercentual(f.percComissao)}%</td>
                  <td>
                    <span className={`badge ${f.ativo ? '' : 'badge-inativo'}`}>{f.ativo ? 'Ativo' : 'Inativo'}</span>
                  </td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/funcionarios/${f.idFuncionario}/visualizar`}
                      aria-label={`Visualizar ${f.nome}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    <Link
                      className="acao-icone acao-editar"
                      to={`/funcionarios/${f.idFuncionario}`}
                      aria-label={`Editar ${f.nome}`}
                      title="Editar"
                    >
                      <IconeEditar />
                    </Link>
                    <button
                      type="button"
                      className="acao-icone acao-excluir"
                      disabled={excluir.isPending}
                      onClick={() => setFuncionarioParaExcluir(f)}
                      aria-label={`Excluir ${f.nome}`}
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

      {funcionarios.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} funcionário{data?.totalItens === 1 ? '' : 's'}
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

      {funcionarioParaExcluir && (
        <div className="modal-overlay" onClick={() => setFuncionarioParaExcluir(null)}>
          <div
            className="modal"
            role="dialog"
            aria-label="Confirmar exclusão"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 style={{ marginTop: 0 }}>Excluir funcionário?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{funcionarioParaExcluir.nome}</strong>? Se houver
              movimentações de estoque associadas, o funcionário será inativado em vez de excluído.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setFuncionarioParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(funcionarioParaExcluir.idFuncionario)}
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
