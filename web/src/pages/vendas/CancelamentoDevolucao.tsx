import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeCancelamentoVenda, IconeOlho } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import {
  listarDevolucoesParaCancelamento,
  type ColunaOrdenacaoCancelamentoDevolucao,
  type DevolucaoParaCancelamento,
} from '../../lib/cancelamentoDevolucao'
import { hojeISO } from '../../lib/datas'
import { dataParaIso, dataValida, formatarMoeda, isoParaData, mascararData } from '../../lib/masks'
import { ApiError } from '../../lib/api'
import CancelamentoDevolucaoModal from './CancelamentoDevolucaoModal'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoCancelamentoDevolucao; rotulo: string }> = [
  { chave: 'idDevolucao', rotulo: 'Nº Vale' },
  { chave: 'nomeEmpresa', rotulo: 'Empresa' },
  { chave: 'dataDevolucao', rotulo: 'Data' },
  { chave: 'valorVale', rotulo: 'Valor' },
]

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

function primeiroDiaDoMesISO(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`
}

function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

/**
 * Cancelamento de Devolução de Produtos (2026-08-11) — ADMIN e OPERADOR (rota fora de
 * `RequireAdmin`; OPERADOR só enxerga/cancela devoluções da empresa em que está logado,
 * restrição aplicada no servidor). Os filtros (nº do vale, data inicial e final) ficam num
 * popup obrigatório ao entrar na tela (mesmo padrão do CRM) — só ao confirmar a grid aparece.
 * Fora da busca por número, só vales ainda não usados e ainda não cancelados aparecem — regra
 * do dono do produto: "só existe cancelamento se o vale ainda não foi usado".
 */
export default function CancelamentoDevolucao() {
  const [filtrosAberto, setFiltrosAberto] = useState(true)
  const [idDevolucaoTexto, setIdDevolucaoTexto] = useState('')
  const [dataInicialTexto, setDataInicialTexto] = useState(isoParaData(primeiroDiaDoMesISO()))
  const [dataFinalTexto, setDataFinalTexto] = useState(isoParaData(hojeISO()))
  const [erroFiltros, setErroFiltros] = useState('')
  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoCancelamentoDevolucao>('dataDevolucao')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('DESC')
  const [devolucaoSelecionada, setDevolucaoSelecionada] = useState<DevolucaoParaCancelamento | null>(null)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  const primeiraRenderizacao = useRef(true)
  useEffect(() => {
    if (primeiraRenderizacao.current) {
      primeiraRenderizacao.current = false
      return
    }
    setPagina(1)
  }, [ordenarPor, direcao])

  const idDevolucao = idDevolucaoTexto.trim() ? Number(idDevolucaoTexto.trim()) : undefined
  const dataInicialIso = dataValida(dataInicialTexto) ? dataParaIso(dataInicialTexto) ?? undefined : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? dataParaIso(dataFinalTexto) ?? undefined : undefined
  const podeBuscar = idDevolucao !== undefined || (dataInicialIso !== undefined && dataFinalIso !== undefined)

  const confirmarFiltros = () => {
    if (!podeBuscar) {
      setErroFiltros('Informe o número do vale, ou a data inicial e final.')
      return
    }
    setErroFiltros('')
    setPagina(1)
    setFiltrosAberto(false)
  }

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['devolucoes-cancelamento', { idDevolucao, dataInicialIso, dataFinalIso, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarDevolucoesParaCancelamento({
        idDevolucao,
        dataInicial: dataInicialIso,
        dataFinal: dataFinalIso,
        pagina,
        tamanho: TAMANHO_PAGINA,
        ordenarPor,
        direcao,
      }),
    enabled: !filtrosAberto && podeBuscar,
    placeholderData: (anterior) => anterior,
  })

  const ordenarPorColuna = (coluna: ColunaOrdenacaoCancelamentoDevolucao) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const devolucoes: DevolucaoParaCancelamento[] = data?.itens ?? []
  const totalPaginas = data?.totalPaginas ?? 1

  const rotuloFiltro =
    idDevolucao !== undefined
      ? `Vale nº ${idDevolucao}`
      : dataInicialIso && dataFinalIso
        ? `${dataInicialTexto} a ${dataFinalTexto}`
        : ''

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeCancelamentoVenda size={34} />
            <h1>Cancelamento de Devolução de Produtos</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="vendas.cancelamentodevolucao.lista" />
            <BotaoFecharTela />
          </div>
        </div>

        {aviso && <Toast mensagem={aviso.texto} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}

        {!filtrosAberto && (
          <div className="card filtros-bar">
            <span className="muted">{rotuloFiltro}</span>
            <button type="button" className="btn ghost" onClick={() => setFiltrosAberto(true)}>
              Alterar Filtros
            </button>
          </div>
        )}
      </div>

      <div className="lista-corpo">
        {!filtrosAberto && (
          <div className="card table-wrap">
            {isLoading ? (
              <p className="muted">Carregando…</p>
            ) : error ? (
              <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível buscar as devoluções.'}</p>
            ) : devolucoes.length === 0 ? (
              <p className="muted">Nenhuma devolução encontrada.</p>
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
                  {devolucoes.map((d) => (
                    <tr key={d.idDevolucao}>
                      <td className="mono">{d.idDevolucao}</td>
                      <td>{d.nomeEmpresa}</td>
                      <td>{formatarData(d.dataDevolucao)}</td>
                      <td className="mono">{moeda(d.valorVale)}</td>
                      <td className="acoes-cell">
                        <button
                          type="button"
                          className="acao-icone acao-visualizar"
                          onClick={() => setDevolucaoSelecionada(d)}
                          aria-label={`Ver devolução nº ${d.idDevolucao}`}
                          title={d.cancelada || d.valeUsado ? 'Visualizar' : 'Visualizar / Cancelar'}
                        >
                          <IconeOlho />
                        </button>
                        {d.cancelada ? (
                          <span className="badge badge-inativo">Cancelada</span>
                        ) : d.valeUsado ? (
                          <span className="badge" title="Vale-mercadoria já usado — cancelamento bloqueado">
                            🔒 Usado
                          </span>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}
      </div>

      {!filtrosAberto && devolucoes.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} devolução{data?.totalItens === 1 ? '' : 'ões'}
              {isFetching && ' · atualizando…'}
            </span>
            <div className="paginacao-paginas">
              <button type="button" className="btn ghost paginacao-seta" onClick={() => setPagina(1)}
                      disabled={pagina <= 1 || isFetching} aria-label="Primeira página" title="Primeira página">
                «
              </button>
              <button type="button" className="btn ghost paginacao-seta" onClick={() => setPagina((p) => Math.max(1, p - 1))}
                      disabled={pagina <= 1 || isFetching} aria-label="Página anterior" title="Página anterior">
                ‹
              </button>
              {paginasVisiveis(pagina, totalPaginas).map((p) => (
                <button key={p} type="button" className={`btn ghost paginacao-numero ${p === pagina ? 'ativa' : ''}`}
                        onClick={() => setPagina(p)} disabled={isFetching} aria-current={p === pagina ? 'page' : undefined}>
                  {p}
                </button>
              ))}
              <button type="button" className="btn ghost paginacao-seta" onClick={() => setPagina((p) => Math.min(totalPaginas, p + 1))}
                      disabled={pagina >= totalPaginas || isFetching} aria-label="Próxima página" title="Próxima página">
                ›
              </button>
              <button type="button" className="btn ghost paginacao-seta" onClick={() => setPagina(totalPaginas)}
                      disabled={pagina >= totalPaginas || isFetching} aria-label="Última página" title="Última página">
                »
              </button>
            </div>
          </div>
        </div>
      )}

      {filtrosAberto && (
        <div className="modal-overlay">
          <div className="modal" role="dialog" aria-label="Filtros de Cancelamento de Devolução" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Cancelamento de Devolução de Produtos</h2>
            <p className="muted" style={{ marginTop: 4 }}>
              Informe o número do vale, ou a data inicial e final da devolução.
            </p>

            <div className="form-grid" style={{ marginTop: 12 }}>
              <div className="col-12">
                <label>Nº do Vale de Devolução</label>
                <input
                  placeholder="Deixe em branco para buscar por período…"
                  value={idDevolucaoTexto}
                  onChange={(e) => setIdDevolucaoTexto(e.target.value.replace(/\D/g, ''))}
                  aria-label="Número do vale de devolução"
                />
              </div>
              <div className="col-6">
                <label>Data Inicial</label>
                <input
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={dataInicialTexto}
                  onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                  aria-label="Data inicial"
                  disabled={!!idDevolucaoTexto}
                />
              </div>
              <div className="col-6">
                <label>Data Final</label>
                <input
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={dataFinalTexto}
                  onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                  aria-label="Data final"
                  disabled={!!idDevolucaoTexto}
                />
              </div>
            </div>

            {erroFiltros && <p className="erro-campo">{erroFiltros}</p>}

            <div className="ajuda-rodape" style={{ justifyContent: 'flex-end' }}>
              <button type="button" className="btn" onClick={confirmarFiltros}>
                Localizar Devoluções
              </button>
            </div>
          </div>
        </div>
      )}

      {devolucaoSelecionada && (
        <CancelamentoDevolucaoModal
          devolucao={devolucaoSelecionada}
          aoFechar={() => setDevolucaoSelecionada(null)}
          aoCancelarComSucesso={() => {
            setAviso({ texto: `Devolução nº ${devolucaoSelecionada.idDevolucao} cancelada com sucesso.`, tipo: 'sucesso' })
            setDevolucaoSelecionada(null)
          }}
        />
      )}
    </div>
  )
}
