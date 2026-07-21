import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import {
  IconeCliente,
  IconeEditar,
  IconeEngrenagem,
  IconeExcluir,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import { ApiError } from '../../lib/api'
import {
  excluirCliente,
  listarCategorias,
  listarClientes,
  type Cliente,
  type ColunaOrdenacao,
  type StatusCliente,
} from '../../lib/clientes'
import { useEu } from '../../lib/eu'
import { mascararCpfCnpj, mascararIdWhatsapp, mascararTelefone } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacao; rotulo: string }> = [
  { chave: 'nome', rotulo: 'Nome / Razão Social' },
  { chave: 'cpfCnpj', rotulo: 'CPF/CNPJ' },
  { chave: 'categoria', rotulo: 'Categoria' },
  { chave: 'telefone', rotulo: 'Celular' },
  { chave: 'cidade', rotulo: 'Cidade/UF' },
  { chave: 'status', rotulo: 'Status' },
]

/**
 * Monta a janela de números de página exibida na navegação (estilo grid de tabela legada):
 * até `JANELA_PAGINACAO` números centrados na página atual, sem reticências — os botões
 * "primeira"/"última" nas pontas cobrem o resto.
 */
function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

export default function ClienteLista() {
  const [nome, setNome] = useState('')
  const [status, setStatus] = useState<StatusCliente>('ATIVOS')
  const [idCategoriaCliente, setIdCategoriaCliente] = useState<number | undefined>(undefined)
  const [clienteParaExcluir, setClienteParaExcluir] = useState<Cliente | null>(null)
  const [aviso, setAviso] = useState('')
  const queryClient = useQueryClient()

  const { data: categorias } = useQuery({ queryKey: ['categorias-cliente'], queryFn: listarCategorias })

  // Paginação por número de página (não por scroll infinito nem cursor — permite pular
  // direto para qualquer página, inclusive a última). Tamanho fixo em 50 (2026-07-21, sem
  // seletor — pedido do dono do produto).
  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacao>('nome')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('ASC')

  useEffect(() => {
    setPagina(1)
  }, [nome, status, idCategoriaCliente, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacao) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['clientes', { nome, status, idCategoriaCliente, pagina, ordenarPor, direcao }],
    queryFn: () =>
      listarClientes({
        nome: nome || undefined,
        status,
        idCategoriaCliente,
        pagina,
        tamanho: TAMANHO_PAGINA,
        ordenarPor,
        direcao,
      }),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1

  const excluir = useMutation({
    mutationFn: excluirCliente,
    onSuccess: (resposta) => {
      queryClient.invalidateQueries({ queryKey: ['clientes'] })
      setClienteParaExcluir(null)
      setAviso(
        resposta.acao === 'inativado'
          ? (resposta.motivo ?? 'Cliente inativado (possui vínculos).')
          : 'Cliente excluído.',
      )
    },
    onError: (e: unknown) => {
      setClienteParaExcluir(null)
      setAviso(e instanceof ApiError ? e.message : 'Não foi possível excluir o cliente.')
    },
  })

  const clientes: Cliente[] = data?.itens ?? []
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeCliente size={34} />
            <h1>Clientes</h1>
          </div>
          <div className="topbar-acoes">
            {ehAdmin && (
              <Link
                className="btn ghost ajuda-gatilho"
                to="/clientes/configuracao"
                aria-label="Configurar tela de cliente"
                title="Configurar campos desta tela"
              >
                <IconeEngrenagem />
              </Link>
            )}
            <AjudaDaTela chaveTela="cadastros.cliente.lista" />
            <Link className="btn" to="/clientes/novo">
              ＋ Novo cliente
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
          <select
            value={idCategoriaCliente ?? ''}
            onChange={(e) => setIdCategoriaCliente(e.target.value ? Number(e.target.value) : undefined)}
            aria-label="Filtrar por categoria"
          >
            <option value="">Todas as categorias</option>
            {categorias?.map((c) => (
              <option key={c.idCategoriaCliente} value={c.idCategoriaCliente}>
                {c.nomeCategoria}
              </option>
            ))}
          </select>
          <select value={status} onChange={(e) => setStatus(e.target.value as StatusCliente)} aria-label="Filtrar por status">
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
        ) : clientes.length === 0 ? (
          <p className="muted">Nenhum cliente encontrado.</p>
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
              {clientes.map((c) => (
                <tr key={c.idCliente}>
                  <td>{c.nome}</td>
                  <td className="mono">{c.cpfCnpj ? mascararCpfCnpj(c.cpfCnpj, c.fisicaJuridica) : '—'}</td>
                  <td>{c.nomeCategoria}</td>
                  <td className="mono">
                    {c.telefone ? mascararTelefone(c.telefone) : c.whatsapp ? mascararIdWhatsapp(c.whatsapp) : '—'}
                  </td>
                  <td>{c.cidade ? `${c.cidade}${c.estado ? '/' + c.estado : ''}` : '—'}</td>
                  <td>
                    <span className={`badge ${c.ativo ? '' : 'badge-inativo'}`}>{c.ativo ? 'Ativo' : 'Inativo'}</span>
                  </td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/clientes/${c.idCliente}/visualizar`}
                      aria-label={`Visualizar ${c.nome}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    <Link
                      className="acao-icone acao-editar"
                      to={`/clientes/${c.idCliente}`}
                      aria-label={`Editar ${c.nome}`}
                      title="Editar"
                    >
                      <IconeEditar />
                    </Link>
                    <button
                      type="button"
                      className="acao-icone acao-excluir"
                      disabled={excluir.isPending}
                      onClick={() => setClienteParaExcluir(c)}
                      aria-label={`Excluir ${c.nome}`}
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

      {clientes.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} cliente{data?.totalItens === 1 ? '' : 's'}
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

      {clienteParaExcluir && (
        <div className="modal-overlay" onClick={() => setClienteParaExcluir(null)}>
          <div
            className="modal"
            role="dialog"
            aria-label="Confirmar exclusão"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 style={{ marginTop: 0 }}>Excluir cliente?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{clienteParaExcluir.nome}</strong>? Se houver vendas
              associadas, o cliente será inativado em vez de excluído.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setClienteParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(clienteParaExcluir.idCliente)}
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
