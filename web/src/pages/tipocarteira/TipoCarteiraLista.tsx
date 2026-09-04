import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
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
  IconeTipoCarteira,
  IconeUltimaPagina,
} from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  ROTULO_CATEGORIA_CARTEIRA,
  excluirTipoCarteira,
  listarTiposCarteira,
  type ColunaOrdenacaoTipoCarteira,
  type TipoCarteira,
  invalidarTiposCarteira,
} from '../../lib/tiposCarteira'
import { maiusculas } from '../../lib/texto'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'
import { fecharAoClicarNoFundo } from '../../lib/modais'
import { useNavegacaoDeGrid } from '../../lib/useNavegacaoDeGrid'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

/**
 * Estado da grade (busca/página/ordenação) — viaja no `state` da navegação para o formulário
 * (visualizar/editar) e volta dele (Salvar/Cancelar), pra grade não resetar pra página 1 /
 * ordenação padrão ao voltar (2026-07-30, pedido explícito).
 */
export interface EstadoListaTipoCarteira {
  busca: string
  pagina: number
  ordenarPor: ColunaOrdenacaoTipoCarteira
  direcao: 'ASC' | 'DESC'
}

const COLUNAS: Array<{ chave: ColunaOrdenacaoTipoCarteira; rotulo: string }> = [
  { chave: 'nomeCarteira', rotulo: 'Nome' },
  { chave: 'prazoPagamento', rotulo: 'Prazo (dias)' },
  { chave: 'taxaAdministradora', rotulo: 'Taxa Adm. (%)' },
  { chave: 'categoriaCarteira', rotulo: 'Categoria' },
  { chave: 'percDesconto', rotulo: '% Desconto' },
  { chave: 'percAcrescimo', rotulo: '% Acréscimo' },
]

/** `null` = não informado (taxa administradora, ou nem desconto nem acréscimo se aplicam). */
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
 * Listagem de tipo de carteira — forma de pagamento completa (categoria/prazo/parcelas/taxa/
 * desconto/acréscimo). Absorveu a listagem de Moeda em 2026-07-28 — colunas "% Desconto"/
 * "% Acréscimo" vieram de lá. Sem filtro de status e sem fallback de inativar na exclusão
 * (não existe coluna `ativo`).
 */
export default function TipoCarteiraLista() {
  // ⛔ RBAC: o admin pode conceder só "acessar" — sem isto o operador preenchia a ficha
  // inteira e perdia tudo num 403 no Salvar (auditoria 2026-08-29). Conveniência de
  // interface, nunca segurança: quem recusa é o @Acao do controller (P4).
  const acoes = usePermissaoDaTela('tipos-carteira')
  const location = useLocation()
  const estadoRecebido = (
    location.state as { toast?: { texto: string; tipo: TipoToast }; listaEstado?: EstadoListaTipoCarteira } | null
  )?.listaEstado
  const [busca, setBusca] = useState(estadoRecebido?.busca ?? '')
  const [carteiraParaExcluir, setCarteiraParaExcluir] = useState<TipoCarteira | null>(null)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(
    () => (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  useEffect(() => {
    if (location.state) window.history.replaceState({}, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(estadoRecebido?.pagina ?? 1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoTipoCarteira>(estadoRecebido?.ordenarPor ?? 'nomeCarteira')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>(estadoRecebido?.direcao ?? 'ASC')

  // Pula a primeira execução: ao voltar do form com página/ordenação restauradas (ver acima),
  // este efeito não pode zerar a página de novo — só reage a mudanças feitas pelo usuário depois.
  const primeiraRenderizacao = useRef(true)
  useEffect(() => {
    if (primeiraRenderizacao.current) {
      primeiraRenderizacao.current = false
      return
    }
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
      invalidarTiposCarteira(queryClient)
      setCarteiraParaExcluir(null)
      setAviso({ texto: 'Tipo de carteira excluído.', tipo: 'sucesso' })
    },
    onError: (e: unknown) => {
      setCarteiraParaExcluir(null)
      setAviso({
        texto: e instanceof ApiError ? e.message : 'Não foi possível excluir o tipo de carteira.',
        tipo: 'erro',
      })
    },
  })

  const carteiras: TipoCarteira[] = data?.itens ?? []

  // Navegação por ↑/↓ na grid, com a linha corrente realçada (2026-09-04).

  const { propsDaLinha } = useNavegacaoDeGrid(carteiras.map((linha) => linha.idCarteira))
  const estadoAtual: EstadoListaTipoCarteira = { busca, pagina, ordenarPor, direcao }

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
            {acoes.incluir && (
              <Link className="btn" to="/tipos-carteira/novo">
                ＋ Novo tipo de carteira
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
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {carteiras.map((tc, indice) => (
                <tr key={tc.idCarteira} {...propsDaLinha(tc.idCarteira, indice)}>
                  <td>{tc.nomeCarteira}</td>
                  <td>{tc.prazoPagamento}</td>
                  <td>{formatarTaxaOuTraco(tc.taxaAdministradora)}</td>
                  <td>{ROTULO_CATEGORIA_CARTEIRA[tc.categoriaCarteira]}</td>
                  <td>{formatarTaxaOuTraco(tc.percDesconto)}</td>
                  <td>{formatarTaxaOuTraco(tc.percAcrescimo)}</td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/tipos-carteira/${tc.idCarteira}/visualizar`}
                      state={{ listaEstado: estadoAtual }}
                      aria-label={`Visualizar ${tc.nomeCarteira}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    {acoes.alterar && (
                      <Link
                        className="acao-icone acao-editar"
                        to={`/tipos-carteira/${tc.idCarteira}`}
                        state={{ listaEstado: estadoAtual }}
                        aria-label={`Editar ${tc.nomeCarteira}`}
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
                        onClick={() => setCarteiraParaExcluir(tc)}
                        aria-label={`Excluir ${tc.nomeCarteira}`}
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
        <div className="modal-overlay" onClick={fecharAoClicarNoFundo(() => setCarteiraParaExcluir(null))}>
          <div className="modal" role="dialog" aria-label="Confirmar exclusão" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Excluir tipo de carteira?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{carteiraParaExcluir.nomeCarteira}</strong>? Se
              estiver em uso em contas a receber ou lançamento de caixa, a exclusão será bloqueada.
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
