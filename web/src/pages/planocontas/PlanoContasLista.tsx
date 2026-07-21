import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import {
  IconeEditar,
  IconeExcluir,
  IconeOlho,
  IconePaginaAnterior,
  IconePlanoContas,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import { ApiError } from '../../lib/api'
import {
  excluirPlanoContas,
  listarPlanosContas,
  type ColunaOrdenacaoPlanoContas,
  type PlanoContas,
} from '../../lib/planoContas'
import { maiusculas } from '../../lib/texto'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoPlanoContas; rotulo: string }> = [
  { chave: 'codigo', rotulo: 'Código' },
  { chave: 'descricao', rotulo: 'Descrição' },
  { chave: 'tipoMovimento', rotulo: 'Tipo de movimento' },
  { chave: 'incluiDre', rotulo: 'DRE' },
  { chave: 'incluiFluxoCaixa', rotulo: 'Fluxo de caixa' },
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

/**
 * Listagem de plano de contas. Diferenças em relação a Clientes/Funcionários, vindas do
 * schema (`cfg_plano_contas`, V016): sem filtro de status e sem fallback de inativar na
 * exclusão (não existe coluna `ativo` — com vínculo a API responde 409), e sem tela de
 * configuração de campos (todos os campos são NOT NULL, nada a configurar — por isso também
 * não há ícone ⚙).
 */
export default function PlanoContasLista() {
  const [busca, setBusca] = useState('')
  const [planoParaExcluir, setPlanoParaExcluir] = useState<PlanoContas | null>(null)
  const [aviso, setAviso] = useState('')
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoPlanoContas>('codigo')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('ASC')

  useEffect(() => {
    setPagina(1)
  }, [busca, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoPlanoContas) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['planos-contas', { busca, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarPlanosContas({
        busca: busca || undefined,
        pagina,
        tamanho: TAMANHO_PAGINA,
        ordenarPor,
        direcao,
      }),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1

  const excluir = useMutation({
    mutationFn: excluirPlanoContas,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['planos-contas'] })
      setPlanoParaExcluir(null)
      setAviso('Plano de contas excluído.')
    },
    onError: (e: unknown) => {
      setPlanoParaExcluir(null)
      setAviso(e instanceof ApiError ? e.message : 'Não foi possível excluir o plano de contas.')
    },
  })

  const planos: PlanoContas[] = data?.itens ?? []

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePlanoContas size={34} />
            <h1>Plano de Contas</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="cadastros.planocontas.lista" />
            <Link className="btn" to="/planos-contas/novo">
              ＋ Novo plano de contas
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
            placeholder="Buscar por código ou descrição…"
            value={busca}
            onChange={(e) => setBusca(maiusculas(e.target.value))}
            aria-label="Buscar por código ou descrição"
          />
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : planos.length === 0 ? (
          <p className="muted">Nenhum plano de contas encontrado.</p>
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
              {planos.map((p) => (
                <tr key={p.idPlanoContas}>
                  <td className="mono">{p.idPlanoContas}</td>
                  <td>{p.descricao}</td>
                  <td>{p.tipoMovimento}</td>
                  <td>{p.incluiDre ? 'Sim' : '—'}</td>
                  <td>{p.incluiFluxoCaixa ? 'Sim' : '—'}</td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/planos-contas/${encodeURIComponent(p.idPlanoContas)}/visualizar`}
                      aria-label={`Visualizar ${p.idPlanoContas}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    <Link
                      className="acao-icone acao-editar"
                      to={`/planos-contas/${encodeURIComponent(p.idPlanoContas)}`}
                      aria-label={`Editar ${p.idPlanoContas}`}
                      title="Editar"
                    >
                      <IconeEditar />
                    </Link>
                    <button
                      type="button"
                      className="acao-icone acao-excluir"
                      disabled={excluir.isPending}
                      onClick={() => setPlanoParaExcluir(p)}
                      aria-label={`Excluir ${p.idPlanoContas}`}
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

      {planos.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} plano{data?.totalItens === 1 ? '' : 's'} de contas
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

      {planoParaExcluir && (
        <div className="modal-overlay" onClick={() => setPlanoParaExcluir(null)}>
          <div
            className="modal"
            role="dialog"
            aria-label="Confirmar exclusão"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 style={{ marginTop: 0 }}>Excluir plano de contas?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{planoParaExcluir.idPlanoContas} —{' '}
              {planoParaExcluir.descricao}</strong>? Se estiver em uso por fornecedor ou contas a
              pagar, a exclusão será bloqueada.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setPlanoParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(planoParaExcluir.idPlanoContas)}
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
