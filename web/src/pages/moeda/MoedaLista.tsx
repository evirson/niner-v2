import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import {
  IconeEditar,
  IconeExcluir,
  IconeMoeda,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { excluirMoeda, listarMoedas, type ColunaOrdenacaoMoeda, type Moeda } from '../../lib/moedas'
import { maiusculas } from '../../lib/texto'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoMoeda; rotulo: string }> = [
  { chave: 'nomeMoeda', rotulo: 'Nome' },
  { chave: 'percDesconto', rotulo: '% Desconto' },
  { chave: 'percAcrescimo', rotulo: '% Acréscimo' },
]

/** `null` = não informado (nem desconto nem acréscimo se aplicam a esta moeda). */
function formatarPercentualOuTraco(valor: number | null): string {
  return valor == null ? '—' : `${valor.toFixed(2).replace('.', ',')}%`
}

/** Janela de números de página — mesmo padrão do resto do domínio. */
function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

/**
 * Listagem de moeda (forma de recebimento). Já nasce com 7 linhas por tenant (seed no
 * signup) — a tela edita essas e permite criar novas. Sem filtro de status e sem fallback de
 * inativar na exclusão (não existe coluna `ativo` — com vínculo a API responde 409). O
 * vínculo com tipo de carteira é gerido pela tela de Tipo de Carteira, não por aqui.
 */
export default function MoedaLista() {
  const [busca, setBusca] = useState('')
  const [moedaParaExcluir, setMoedaParaExcluir] = useState<Moeda | null>(null)
  const [aviso, setAviso] = useState('')
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoMoeda>('nomeMoeda')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('ASC')

  useEffect(() => {
    setPagina(1)
  }, [busca, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoMoeda) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['moedas', { busca, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarMoedas({ busca: busca || undefined, pagina, tamanho: TAMANHO_PAGINA, ordenarPor, direcao }),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1

  const excluir = useMutation({
    mutationFn: excluirMoeda,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['moedas'] })
      setMoedaParaExcluir(null)
      setAviso('Moeda excluída.')
    },
    onError: (e: unknown) => {
      setMoedaParaExcluir(null)
      setAviso(e instanceof ApiError ? e.message : 'Não foi possível excluir a moeda.')
    },
  })

  const moedas: Moeda[] = data?.itens ?? []

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeMoeda size={34} />
            <h1>Moeda</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.moeda.lista" />
            <Link className="btn" to="/moedas/novo">
              ＋ Nova moeda
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
            value={busca}
            onChange={(e) => setBusca(maiusculas(e.target.value))}
            aria-label="Buscar por nome"
          />
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : moedas.length === 0 ? (
          <p className="muted">Nenhuma moeda encontrada.</p>
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
              {moedas.map((m) => (
                <tr key={m.idMoeda}>
                  <td>{m.nomeMoeda}</td>
                  <td>{formatarPercentualOuTraco(m.percDesconto)}</td>
                  <td>{formatarPercentualOuTraco(m.percAcrescimo)}</td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/moedas/${m.idMoeda}/visualizar`}
                      aria-label={`Visualizar ${m.nomeMoeda}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    <Link
                      className="acao-icone acao-editar"
                      to={`/moedas/${m.idMoeda}`}
                      aria-label={`Editar ${m.nomeMoeda}`}
                      title="Editar"
                    >
                      <IconeEditar />
                    </Link>
                    <button
                      type="button"
                      className="acao-icone acao-excluir"
                      disabled={excluir.isPending}
                      onClick={() => setMoedaParaExcluir(m)}
                      aria-label={`Excluir ${m.nomeMoeda}`}
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

      {moedas.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} moeda{data?.totalItens === 1 ? '' : 's'}
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

      {moedaParaExcluir && (
        <div className="modal-overlay" onClick={() => setMoedaParaExcluir(null)}>
          <div className="modal" role="dialog" aria-label="Confirmar exclusão" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Excluir moeda?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{moedaParaExcluir.nomeMoeda}</strong>? Se estiver em
              uso por um tipo de carteira ou lançamento de caixa, a exclusão será bloqueada.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setMoedaParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(moedaParaExcluir.idMoeda)}
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
