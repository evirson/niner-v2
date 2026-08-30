import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import {
  IconeEditar,
  IconeExcluir,
  IconeFiscal,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  excluirPerfilFiscal,
  listarPerfisFiscais,
  type ColunaOrdenacaoPerfilFiscal,
  type PerfilFiscalLista as PerfilFiscalListaItem,
} from '../../lib/perfilFiscal'
import { maiusculas } from '../../lib/texto'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

export interface EstadoListaPerfilFiscal {
  busca: string
  pagina: number
  ordenarPor: ColunaOrdenacaoPerfilFiscal
  direcao: 'ASC' | 'DESC'
}

const COLUNAS: Array<{ chave: ColunaOrdenacaoPerfilFiscal; rotulo: string }> = [
  { chave: 'nome', rotulo: 'Nome' },
  { chave: 'ativo', rotulo: 'Ativo' },
  { chave: 'criadoEm', rotulo: 'Criado em' },
]

function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

/**
 * Listagem de Perfis Fiscais (docs/telas/fiscal-perfil.md, bloco B2). ADMIN-only. Exclusão cai
 * para inativar quando algum produto aponta para o perfil — mesmo mecanismo de Fornecedor —
 * então o botão não pede confirmação de "vai virar inativo": o resultado aparece no toast.
 */
export default function PerfilFiscalLista() {
  // â RBAC: o admin pode conceder sÃ³ "acessar" â sem isto o operador preenchia a ficha
  // inteira e perdia tudo num 403 no Salvar (auditoria 2026-08-29). ConveniÃªncia de
  // interface, nunca seguranÃ§a: quem recusa Ã© o @Acao do controller (P4).
  const acoes = usePermissaoDaTela('fiscal.perfis')
  const location = useLocation()
  const estadoRecebido = (
    location.state as { toast?: { texto: string; tipo: TipoToast }; listaEstado?: EstadoListaPerfilFiscal } | null
  )?.listaEstado
  const [busca, setBusca] = useState(estadoRecebido?.busca ?? '')
  const [perfilParaExcluir, setPerfilParaExcluir] = useState<PerfilFiscalListaItem | null>(null)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(
    () => (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  useEffect(() => {
    if (location.state) window.history.replaceState({}, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(estadoRecebido?.pagina ?? 1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoPerfilFiscal>(estadoRecebido?.ordenarPor ?? 'nome')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>(estadoRecebido?.direcao ?? 'ASC')

  const primeiraRenderizacao = useRef(true)
  useEffect(() => {
    if (primeiraRenderizacao.current) {
      primeiraRenderizacao.current = false
      return
    }
    setPagina(1)
  }, [busca, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoPerfilFiscal) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['perfis-fiscais', { busca, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarPerfisFiscais({ busca: busca || undefined, pagina, tamanho: TAMANHO_PAGINA, ordenarPor, direcao }),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1

  const excluir = useMutation({
    mutationFn: excluirPerfilFiscal,
    onSuccess: (resultado) => {
      queryClient.invalidateQueries({ queryKey: ['perfis-fiscais'] })
      // O <select> de perfil do ProdutoForm tem chave PRÓPRIA: array não casa por prefixo.
      queryClient.invalidateQueries({ queryKey: ['perfis-fiscais-opcoes'] })
      setPerfilParaExcluir(null)
      setAviso({
        texto: resultado.acao === 'excluido' ? 'Perfil fiscal excluído.' : `Perfil fiscal inativado — ${resultado.motivo}`,
        tipo: 'sucesso',
      })
    },
    onError: (e: unknown) => {
      setPerfilParaExcluir(null)
      setAviso({ texto: e instanceof ApiError ? e.message : 'Não foi possível excluir o perfil fiscal.', tipo: 'erro' })
    },
  })

  const perfis: PerfilFiscalListaItem[] = data?.itens ?? []
  const estadoAtual: EstadoListaPerfilFiscal = { busca, pagina, ordenarPor, direcao }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeFiscal size={34} />
            <h1>Perfis Fiscais</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="fiscal.perfil.tela" />
            {acoes.incluir && (
              <Link className="btn" to="/fiscal/perfis/novo">
                ＋ Novo perfil fiscal
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
          ) : perfis.length === 0 ? (
            <p className="muted">Nenhum perfil fiscal encontrado.</p>
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
                  <th>Regras</th>
                  <th>Regime</th>
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {perfis.map((p) => (
                  <tr key={p.idPerfilFiscal}>
                    <td>{p.nome}</td>
                    <td>
                      <span className={`badge ${p.ativo ? 'badge-sucesso' : ''}`}>{p.ativo ? 'Ativo' : 'Inativo'}</span>
                    </td>
                    <td>{new Date(p.criadoEm).toLocaleDateString('pt-BR')}</td>
                    <td>{p.quantidadeRegras}</td>
                    <td>
                      {!p.atendeSimples && !p.atendeMei ? (
                        <span className="muted">—</span>
                      ) : (
                        <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                          {p.atendeSimples && <span className="badge" title="Tem regra para CRT 1 e/ou 2">Simples</span>}
                          {p.atendeMei && <span className="badge" title="Tem regra para CRT 4">MEI</span>}
                        </div>
                      )}
                    </td>
                    <td className="acoes-cell">
                      <Link
                        className="acao-icone acao-visualizar"
                        to={`/fiscal/perfis/${p.idPerfilFiscal}/visualizar`}
                        state={{ listaEstado: estadoAtual }}
                        aria-label={`Visualizar ${p.nome}`}
                        title="Visualizar"
                      >
                        <IconeOlho />
                      </Link>
                      {acoes.alterar && (
                        <Link
                          className="acao-icone acao-editar"
                          to={`/fiscal/perfis/${p.idPerfilFiscal}`}
                          state={{ listaEstado: estadoAtual }}
                          aria-label={`Editar ${p.nome}`}
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
                          onClick={() => setPerfilParaExcluir(p)}
                          aria-label={`Excluir ${p.nome}`}
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

      {perfis.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} perfil{data?.totalItens === 1 ? '' : 'is'} fiscal{data?.totalItens === 1 ? '' : 'is'}
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

      {perfilParaExcluir && (
        <div className="modal-overlay" onClick={() => setPerfilParaExcluir(null)}>
          <div className="modal" role="dialog" aria-label="Confirmar exclusão" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Excluir perfil fiscal?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{perfilParaExcluir.nome}</strong>? Se algum produto
              apontar para ele, o perfil é inativado em vez de excluído — e continua valendo para
              quem já usava.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setPerfilParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(perfilParaExcluir.idPerfilFiscal)}
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
