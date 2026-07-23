import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import {
  IconeEditar,
  IconeExcluir,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeTipoCarteira,
  IconeUltimaPagina,
} from '../../components/Icones'
import { ApiError } from '../../lib/api'
import {
  excluirTipoCarteira,
  listarTiposCarteira,
  type ColunaOrdenacaoTipoCarteira,
  type TipoCarteira,
} from '../../lib/tiposCarteira'
import { maiusculas } from '../../lib/texto'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoTipoCarteira; rotulo: string }> = [
  { chave: 'nomeCarteira', rotulo: 'Nome' },
  { chave: 'prazoPagamento', rotulo: 'Prazo (dias)' },
  { chave: 'taxaAdministradora', rotulo: 'Taxa Adm. (%)' },
]

/** `null` = tipo de carteira não cobra taxa administradora (2026-07-23, campo opcional). */
function formatarTaxaOuTraco(valor: number | null): string {
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
 * Listagem de tipo de carteira (prazo/parcelas/taxa do crediário, cartão etc.). Sem filtro de
 * status e sem fallback de inativar na exclusão (não existe coluna `ativo`). A coluna
 * "Moedas" mostra, só leitura, quais formas de recebimento usam este tipo de carteira — o
 * vínculo (`moeda_detalhe`) é editado no formulário desta tela, não numa tela separada.
 */
export default function TipoCarteiraLista() {
  const [busca, setBusca] = useState('')
  const [carteiraParaExcluir, setCarteiraParaExcluir] = useState<TipoCarteira | null>(null)
  const [aviso, setAviso] = useState('')
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoTipoCarteira>('nomeCarteira')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('ASC')

  useEffect(() => {
    setPagina(1)
  }, [busca, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoTipoCarteira) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['tipos-carteira', { busca, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarTiposCarteira({ busca: busca || undefined, pagina, tamanho: TAMANHO_PAGINA, ordenarPor, direcao }),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1

  const excluir = useMutation({
    mutationFn: excluirTipoCarteira,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tipos-carteira'] })
      setCarteiraParaExcluir(null)
      setAviso('Tipo de carteira excluído.')
    },
    onError: (e: unknown) => {
      setCarteiraParaExcluir(null)
      setAviso(e instanceof ApiError ? e.message : 'Não foi possível excluir o tipo de carteira.')
    },
  })

  const carteiras: TipoCarteira[] = data?.itens ?? []

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeTipoCarteira size={34} />
            <h1>Tipo de Carteira</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.tipocarteira.lista" />
            <Link className="btn" to="/tipos-carteira/novo">
              ＋ Novo tipo de carteira
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
        ) : carteiras.length === 0 ? (
          <p className="muted">Nenhum tipo de carteira encontrado.</p>
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
                <th>Moedas</th>
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {carteiras.map((tc) => (
                <tr key={tc.idCarteira}>
                  <td>{tc.nomeCarteira}</td>
                  <td>{tc.prazoPagamento}</td>
                  <td>{formatarTaxaOuTraco(tc.taxaAdministradora)}</td>
                  <td className="muted">
                    {tc.moedas.length ? tc.moedas.map((m) => m.nomeMoeda).join(', ') : '—'}
                  </td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/tipos-carteira/${tc.idCarteira}/visualizar`}
                      aria-label={`Visualizar ${tc.nomeCarteira}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    <Link
                      className="acao-icone acao-editar"
                      to={`/tipos-carteira/${tc.idCarteira}`}
                      aria-label={`Editar ${tc.nomeCarteira}`}
                      title="Editar"
                    >
                      <IconeEditar />
                    </Link>
                    <button
                      type="button"
                      className="acao-icone acao-excluir"
                      disabled={excluir.isPending}
                      onClick={() => setCarteiraParaExcluir(tc)}
                      aria-label={`Excluir ${tc.nomeCarteira}`}
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

      {carteiras.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} tipo{data?.totalItens === 1 ? '' : 's'} de carteira
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

      {carteiraParaExcluir && (
        <div className="modal-overlay" onClick={() => setCarteiraParaExcluir(null)}>
          <div className="modal" role="dialog" aria-label="Confirmar exclusão" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Excluir tipo de carteira?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{carteiraParaExcluir.nomeCarteira}</strong>? Se
              estiver em uso em contas a receber, a exclusão será bloqueada.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setCarteiraParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(carteiraParaExcluir.idCarteira)}
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
