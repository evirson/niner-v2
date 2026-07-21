import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import {
  IconeEditar,
  IconeEngrenagem,
  IconeExcluir,
  IconeFornecedor,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import { ApiError } from '../../lib/api'
import {
  excluirFornecedor,
  listarFornecedores,
  type ColunaOrdenacaoFornecedor,
  type Fornecedor,
  type StatusFornecedor,
} from '../../lib/fornecedores'
import { listarPlanosContas } from '../../lib/planoContas'
import { useEu } from '../../lib/eu'
import { mascararCpfCnpj, mascararTelefone } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoFornecedor; rotulo: string }> = [
  { chave: 'razaoSocial', rotulo: 'Razão Social' },
  { chave: 'cnpj', rotulo: 'CNPJ' },
  { chave: 'planoContas', rotulo: 'Plano de Contas' },
  { chave: 'telefone', rotulo: 'Telefone' },
  { chave: 'cidade', rotulo: 'Cidade/UF' },
  { chave: 'status', rotulo: 'Status' },
]

/**
 * Janela de números de página (mesmo padrão de `cadastros.cliente`): até
 * `JANELA_PAGINACAO` números centrados na página atual, primeira/última nas pontas.
 */
function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

export default function FornecedorLista() {
  const [busca, setBusca] = useState('')
  const [status, setStatus] = useState<StatusFornecedor>('ATIVOS')
  const [idPlanoContas, setIdPlanoContas] = useState('')
  const [fornecedorParaExcluir, setFornecedorParaExcluir] = useState<Fornecedor | null>(null)
  const [aviso, setAviso] = useState('')
  const queryClient = useQueryClient()

  // Filtro por plano de contas (análogo ao filtro de categoria do cliente).
  const { data: planos } = useQuery({
    queryKey: ['planos-contas', 'filtro-fornecedor'],
    queryFn: () => listarPlanosContas({ tamanho: 100 }),
  })

  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoFornecedor>('razaoSocial')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('ASC')

  useEffect(() => {
    setPagina(1)
  }, [busca, status, idPlanoContas, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoFornecedor) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['fornecedores', { busca, status, idPlanoContas, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarFornecedores({
        razaoSocial: busca || undefined,
        idPlanoContas: idPlanoContas || undefined,
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
    mutationFn: excluirFornecedor,
    onSuccess: (resposta) => {
      queryClient.invalidateQueries({ queryKey: ['fornecedores'] })
      setFornecedorParaExcluir(null)
      setAviso(
        resposta.acao === 'inativado'
          ? (resposta.motivo ?? 'Fornecedor inativado (possui vínculos).')
          : 'Fornecedor excluído.',
      )
    },
    onError: (e: unknown) => {
      setFornecedorParaExcluir(null)
      setAviso(e instanceof ApiError ? e.message : 'Não foi possível excluir o fornecedor.')
    },
  })

  const fornecedores: Fornecedor[] = data?.itens ?? []
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeFornecedor size={34} />
            <h1>Fornecedores</h1>
          </div>
          <div className="topbar-acoes">
            {ehAdmin && (
              <Link
                className="btn ghost ajuda-gatilho"
                to="/fornecedores/configuracao"
                aria-label="Configurar tela de fornecedor"
                title="Configurar campos desta tela"
              >
                <IconeEngrenagem />
              </Link>
            )}
            <AjudaDaTela chaveTela="cadastros.fornecedor.lista" />
            <Link className="btn" to="/fornecedores/novo">
              ＋ Novo fornecedor
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
            placeholder="Buscar por razão social ou fantasia…"
            value={busca}
            onChange={(e) => setBusca(maiusculas(e.target.value))}
            aria-label="Buscar por razão social ou nome fantasia"
          />
          <select
            value={idPlanoContas}
            onChange={(e) => setIdPlanoContas(e.target.value)}
            aria-label="Filtrar por plano de contas"
          >
            <option value="">Todos os planos de contas</option>
            {planos?.itens.map((p) => (
              <option key={p.idPlanoContas} value={p.idPlanoContas}>
                {p.idPlanoContas} — {p.descricao}
              </option>
            ))}
          </select>
          <select value={status} onChange={(e) => setStatus(e.target.value as StatusFornecedor)} aria-label="Filtrar por status">
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
        ) : fornecedores.length === 0 ? (
          <p className="muted">Nenhum fornecedor encontrado.</p>
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
              {fornecedores.map((f) => (
                <tr key={f.idFornecedor}>
                  <td>{f.razaoSocial}</td>
                  <td className="mono">{f.cnpj ? mascararCpfCnpj(f.cnpj, false) : '—'}</td>
                  <td>{f.idPlanoContas} — {f.descricaoPlanoContas}</td>
                  <td className="mono">{f.telefone ? mascararTelefone(f.telefone) : '—'}</td>
                  <td>{f.cidade ? `${f.cidade}${f.estado ? '/' + f.estado : ''}` : '—'}</td>
                  <td>
                    <span className={`badge ${f.ativo ? '' : 'badge-inativo'}`}>{f.ativo ? 'Ativo' : 'Inativo'}</span>
                  </td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/fornecedores/${f.idFornecedor}/visualizar`}
                      aria-label={`Visualizar ${f.razaoSocial}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    <Link
                      className="acao-icone acao-editar"
                      to={`/fornecedores/${f.idFornecedor}`}
                      aria-label={`Editar ${f.razaoSocial}`}
                      title="Editar"
                    >
                      <IconeEditar />
                    </Link>
                    <button
                      type="button"
                      className="acao-icone acao-excluir"
                      disabled={excluir.isPending}
                      onClick={() => setFornecedorParaExcluir(f)}
                      aria-label={`Excluir ${f.razaoSocial}`}
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

      {fornecedores.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} fornecedor{data?.totalItens === 1 ? '' : 'es'}
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

      {fornecedorParaExcluir && (
        <div className="modal-overlay" onClick={() => setFornecedorParaExcluir(null)}>
          <div
            className="modal"
            role="dialog"
            aria-label="Confirmar exclusão"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 style={{ marginTop: 0 }}>Excluir fornecedor?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{fornecedorParaExcluir.razaoSocial}</strong>? Se
              houver movimentações ou contas a pagar associadas, o fornecedor será inativado em vez de
              excluído.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setFornecedorParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(fornecedorParaExcluir.idFornecedor)}
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
