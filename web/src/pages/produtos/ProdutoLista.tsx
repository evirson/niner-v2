import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import {
  IconeEditar,
  IconeEngrenagem,
  IconeExcluir,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProduto,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { listarCategoriasProduto } from '../../lib/categoriasProduto'
import { useEu } from '../../lib/eu'
import { formatarMoeda } from '../../lib/masks'
import {
  excluirProduto,
  listarProdutos,
  type ColunaOrdenacaoProduto,
  type Produto,
  type StatusProduto,
} from '../../lib/produtos'
import { maiusculas } from '../../lib/texto'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoProduto; rotulo: string }> = [
  { chave: 'descricao', rotulo: 'Descrição' },
  { chave: 'marca', rotulo: 'Marca' },
  { chave: 'referencia', rotulo: 'Referência' },
  { chave: 'precoVenda', rotulo: 'Preço de Venda' },
  { chave: 'status', rotulo: 'Status' },
]

/** Janela de números de página (mesmo padrão de `cadastros.cliente`). */
function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

export default function ProdutoLista() {
  const [busca, setBusca] = useState('')
  const [status, setStatus] = useState<StatusProduto>('ATIVOS')
  const [idCategoria, setIdCategoria] = useState('')
  const [produtoParaExcluir, setProdutoParaExcluir] = useState<Produto | null>(null)
  const [aviso, setAviso] = useState('')
  const queryClient = useQueryClient()

  const { data: categorias } = useQuery({
    queryKey: ['categorias-produto', 'filtro-produto'],
    queryFn: listarCategoriasProduto,
  })

  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoProduto>('descricao')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('ASC')

  useEffect(() => {
    setPagina(1)
  }, [busca, status, idCategoria, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoProduto) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['produtos', { busca, status, idCategoria, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarProdutos({
        descricao: busca || undefined,
        idCategoria: idCategoria ? Number(idCategoria) : undefined,
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
    mutationFn: excluirProduto,
    onSuccess: (resposta) => {
      queryClient.invalidateQueries({ queryKey: ['produtos'] })
      setProdutoParaExcluir(null)
      setAviso(
        resposta.acao === 'inativado'
          ? (resposta.motivo ?? 'Produto inativado (possui vínculos).')
          : 'Produto excluído.',
      )
    },
    onError: (e: unknown) => {
      setProdutoParaExcluir(null)
      setAviso(e instanceof ApiError ? e.message : 'Não foi possível excluir o produto.')
    },
  })

  const produtos: Produto[] = data?.itens ?? []
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeProduto size={34} />
            <h1>Produtos</h1>
          </div>
          <div className="topbar-acoes">
            {ehAdmin && (
              <Link
                className="btn ghost ajuda-gatilho"
                to="/produtos/configuracao"
                aria-label="Configurar tela de produto"
                title="Configurar campos desta tela"
              >
                <IconeEngrenagem />
              </Link>
            )}
            <AjudaDaTela chaveTela="catalogo.produto.lista" />
            <Link className="btn" to="/produtos/novo">
              ＋ Novo produto
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
            placeholder="Buscar por descrição…"
            value={busca}
            onChange={(e) => setBusca(maiusculas(e.target.value))}
            aria-label="Buscar por descrição"
          />
          <select
            value={idCategoria}
            onChange={(e) => setIdCategoria(e.target.value)}
            aria-label="Filtrar por categoria"
          >
            <option value="">Todas as categorias</option>
            {categorias?.map((c) => (
              <option key={c.idCategoria} value={c.idCategoria}>
                {c.nomeCategoria}
              </option>
            ))}
          </select>
          <select value={status} onChange={(e) => setStatus(e.target.value as StatusProduto)} aria-label="Filtrar por status">
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
        ) : produtos.length === 0 ? (
          <p className="muted">Nenhum produto encontrado.</p>
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
                <th>Categorias</th>
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {produtos.map((p) => (
                <tr key={p.idProduto}>
                  <td>{p.descricao}</td>
                  <td>{p.marca ?? '—'}</td>
                  <td>{p.referencia ?? '—'}</td>
                  <td className="mono">R$ {formatarMoeda(p.precoVenda)}</td>
                  <td>
                    <span className={`badge ${p.ativo ? '' : 'badge-inativo'}`}>{p.ativo ? 'Ativo' : 'Inativo'}</span>
                  </td>
                  <td>{p.categorias.length > 0 ? p.categorias.map((c) => c.nomeCategoria).join(', ') : '—'}</td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/produtos/${p.idProduto}/visualizar`}
                      aria-label={`Visualizar ${p.descricao}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    <Link
                      className="acao-icone acao-editar"
                      to={`/produtos/${p.idProduto}`}
                      aria-label={`Editar ${p.descricao}`}
                      title="Editar"
                    >
                      <IconeEditar />
                    </Link>
                    <button
                      type="button"
                      className="acao-icone acao-excluir"
                      disabled={excluir.isPending}
                      onClick={() => setProdutoParaExcluir(p)}
                      aria-label={`Excluir ${p.descricao}`}
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

      {produtos.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} produto{data?.totalItens === 1 ? '' : 's'}
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

      {produtoParaExcluir && (
        <div className="modal-overlay" onClick={() => setProdutoParaExcluir(null)}>
          <div
            className="modal"
            role="dialog"
            aria-label="Confirmar exclusão"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 style={{ marginTop: 0 }}>Excluir produto?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{produtoParaExcluir.descricao}</strong>? Se houver
              variações ou imagens associadas, o produto será inativado em vez de excluído.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setProdutoParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(produtoParaExcluir.idProduto)}
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
