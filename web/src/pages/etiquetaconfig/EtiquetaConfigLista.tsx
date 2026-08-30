import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import {
  IconeEditar,
  IconeEtiqueta,
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
  excluirEtiquetaConfig,
  listarEtiquetasConfig,
  type ColunaOrdenacaoEtiquetaConfig,
  type EtiquetaConfig,
} from '../../lib/etiquetaConfig'
import { formatarEtiquetaMm } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

/** Estado da grade — viaja no `state` da navegação, mesmo padrão de `EstadoListaTipoCarteira`. */
export interface EstadoListaEtiquetaConfig {
  busca: string
  pagina: number
  ordenarPor: ColunaOrdenacaoEtiquetaConfig
  direcao: 'ASC' | 'DESC'
}

const COLUNAS: Array<{ chave: ColunaOrdenacaoEtiquetaConfig; rotulo: string }> = [
  { chave: 'nome', rotulo: 'Nome' },
  { chave: 'larguraRoloMm', rotulo: 'Largura do Rolo' },
  { chave: 'numeroColunas', rotulo: 'Colunas' },
  { chave: 'ativo', rotulo: 'Situação' },
]

function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

/**
 * Listagem de Configuração de Etiqueta de Produtos (2026-08-04, docs/telas/configuracao-etiqueta.md).
 * Um tenant pode ter várias configurações nomeadas — impressoras/rolos diferentes por loja/
 * situação, escolhidas na tela de Emissão (Implementações Futuras). Exclusão é sempre real (sem
 * fallback de inativar — nada referencia esta tabela ainda, ver EtiquetaConfigService).
 */
export default function EtiquetaConfigLista() {
  // ⛔ RBAC: o admin pode conceder só "acessar" — sem isto o operador preenchia a ficha
  // inteira e perdia tudo num 403 no Salvar (auditoria 2026-08-29). Conveniência de
  // interface, nunca segurança: quem recusa é o @Acao do controller (P4).
  const acoes = usePermissaoDaTela('etiqueta-configuracao')
  const location = useLocation()
  const estadoRecebido = (
    location.state as { toast?: { texto: string; tipo: TipoToast }; listaEstado?: EstadoListaEtiquetaConfig } | null
  )?.listaEstado
  const [busca, setBusca] = useState(estadoRecebido?.busca ?? '')
  const [configParaExcluir, setConfigParaExcluir] = useState<EtiquetaConfig | null>(null)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(
    () => (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  useEffect(() => {
    if (location.state) window.history.replaceState({}, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(estadoRecebido?.pagina ?? 1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoEtiquetaConfig>(estadoRecebido?.ordenarPor ?? 'nome')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>(estadoRecebido?.direcao ?? 'ASC')

  const primeiraRenderizacao = useRef(true)
  useEffect(() => {
    if (primeiraRenderizacao.current) {
      primeiraRenderizacao.current = false
      return
    }
    setPagina(1)
  }, [busca, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoEtiquetaConfig) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['etiquetas-config', { busca, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarEtiquetasConfig({ busca: busca || undefined, pagina, tamanho: TAMANHO_PAGINA, ordenarPor, direcao }),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1

  const excluir = useMutation({
    mutationFn: excluirEtiquetaConfig,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['etiquetas-config'] })
      setConfigParaExcluir(null)
      setAviso({ texto: 'Configuração de etiqueta excluída.', tipo: 'sucesso' })
    },
    onError: (e: unknown) => {
      setConfigParaExcluir(null)
      setAviso({
        texto: e instanceof ApiError ? e.message : 'Não foi possível excluir a configuração de etiqueta.',
        tipo: 'erro',
      })
    },
  })

  const configs: EtiquetaConfig[] = data?.itens ?? []
  const estadoAtual: EstadoListaEtiquetaConfig = { busca, pagina, ordenarPor, direcao }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEtiqueta size={34} />
            <h1>Configuração de Etiqueta de Produtos</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="configuracoes.etiquetaconfig.lista" />
            {acoes.incluir && (
              <Link className="btn" to="/etiqueta-configuracao/novo">
                ＋ Nova configuração
              </Link>
            )}
            <BotaoFecharTela />
          </div>
        </div>

        {aviso && <Toast mensagem={aviso.texto} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}

        <div className="card filtros-bar">
          <input
            autoFocus
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
        ) : configs.length === 0 ? (
          <p className="muted">Nenhuma configuração de etiqueta encontrada.</p>
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
                <th>Etiqueta</th>
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {configs.map((ec) => (
                <tr key={ec.idConfigEtiqueta}>
                  <td>{ec.nome}</td>
                  <td>{formatarEtiquetaMm(ec.larguraRoloMm)} mm</td>
                  <td>{ec.numeroColunas}</td>
                  <td>{ec.ativo ? 'Ativa' : 'Inativa'}</td>
                  <td>
                    {formatarEtiquetaMm(ec.larguraEtiquetaMm)} × {formatarEtiquetaMm(ec.alturaEtiquetaMm)} mm
                  </td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/etiqueta-configuracao/${ec.idConfigEtiqueta}/visualizar`}
                      state={{ listaEstado: estadoAtual }}
                      aria-label={`Visualizar ${ec.nome}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    {acoes.alterar && (
                      <Link
                        className="acao-icone acao-editar"
                        to={`/etiqueta-configuracao/${ec.idConfigEtiqueta}`}
                        state={{ listaEstado: estadoAtual }}
                        aria-label={`Editar ${ec.nome}`}
                        title="Editar"
                      >
                        <IconeEditar />
                      </Link>
                    )}
                    {acoes.excluir && (
                      <button
                        type="button"
                        className="acao-icone acao-excluir"
                        disabled={excluir.isPending}
                        onClick={() => setConfigParaExcluir(ec)}
                        aria-label={`Excluir ${ec.nome}`}
                        title="Excluir"
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

      {configs.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} configuração{data?.totalItens === 1 ? '' : 'ões'} de etiqueta
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

      {configParaExcluir && (
        <div className="modal-overlay" onClick={() => setConfigParaExcluir(null)}>
          <div className="modal" role="dialog" aria-label="Confirmar exclusão" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Excluir configuração de etiqueta?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{configParaExcluir.nome}</strong>? Esta ação não pode ser desfeita.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setConfigParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(configParaExcluir.idConfigEtiqueta)}
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
