import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../../components/BotaoFecharTela'
import {
  IconeEstoque,
  IconeExcluir,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../../components/Icones'
import Toast from '../../../components/Toast'
import { ApiError } from '../../../lib/api'
import { listarEmpresasPermitidas, type Empresa } from '../../../lib/empresas'
import {
  cancelarEntrada,
  listarEntradas,
  type ColunaOrdenacaoEntrada,
  type EntradaResumoResponse,
} from '../../../lib/entradaMercadoria'
import { buscarFornecedoresEmissao, type FornecedorOpcaoEmissao } from '../../../lib/etiquetaEmissao'
import { useEu } from '../../../lib/eu'
import { dataParaIso, dataValida, formatarMoeda, mascararData } from '../../../lib/masks'
import { maiusculas } from '../../../lib/texto'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoEntrada; rotulo: string }> = [
  { chave: 'dataMovimento', rotulo: 'Data' },
  { chave: 'fornecedor', rotulo: 'Fornecedor' },
  { chave: 'notaFiscal', rotulo: 'Nota Fiscal' },
]

function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

const ORIGENS: Record<string, string> = {
  'entrada manual': 'Manual',
  'entrada xml': 'XML',
  'entrada planilha': 'Planilha',
}

export default function EntradaMercadoriaLista() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: eu } = useEu()
  const isAdmin = eu?.usuario.papel === 'ADMIN'
  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoEntrada>('dataMovimento')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('DESC')
  const [entradaParaCancelar, setEntradaParaCancelar] = useState<EntradaResumoResponse | null>(null)
  const [motivo, setMotivo] = useState('')
  const [toast, setToast] = useState<{ texto: string; tipo: 'erro' | 'sucesso' } | null>(null)

  /** Popup de filtros obrigatório ao entrar na tela (mesmo padrão do CRM/Cancelamento de
   *  Devolução, 2026-08-19) — "Localizar" aplica os filtros (todos opcionais: em branco
   *  equivale a listar tudo, igual o comportamento de sempre), "＋ Nova entrada" pula a busca
   *  direto pra tela de criação e "Fechar" volta pra tela anterior (`navigate(-1)`, mesmo
   *  comportamento do BotaoFecharTela do cabeçalho) sem abrir a listagem. */
  const [filtrosAberto, setFiltrosAberto] = useState(true)
  const [buscaFornecedor, setBuscaFornecedor] = useState('')
  const [fornecedorEscolhido, setFornecedorEscolhido] = useState<FornecedorOpcaoEmissao | null>(null)
  const [idEmpresaFiltro, setIdEmpresaFiltro] = useState<number | ''>('')
  const [notaFiscalTexto, setNotaFiscalTexto] = useState('')
  const [dataInicialTexto, setDataInicialTexto] = useState('')
  const [dataFinalTexto, setDataFinalTexto] = useState('')

  useEffect(() => {
    setPagina(1)
  }, [ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoEntrada) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data: empresas } = useQuery({ queryKey: ['empresas-permitidas'], queryFn: listarEmpresasPermitidas })

  const { data: fornecedoresEncontrados } = useQuery({
    queryKey: ['etiqueta-emissao-fornecedores', buscaFornecedor],
    queryFn: () => buscarFornecedoresEmissao(buscaFornecedor),
    enabled: buscaFornecedor.trim().length > 0 && !fornecedorEscolhido,
  })

  const notaFiscalFiltro = notaFiscalTexto.trim() ? Number(notaFiscalTexto.trim()) : undefined
  const dataInicialIso = dataValida(dataInicialTexto) ? (dataParaIso(dataInicialTexto) ?? undefined) : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? (dataParaIso(dataFinalTexto) ?? undefined) : undefined

  const confirmarFiltros = () => {
    setPagina(1)
    setFiltrosAberto(false)
  }

  const partesFiltro: string[] = []
  if (fornecedorEscolhido) partesFiltro.push(fornecedorEscolhido.razaoSocial)
  if (idEmpresaFiltro !== '') {
    const empresa = empresas?.find((e) => e.idEmpresa === idEmpresaFiltro)
    if (empresa) partesFiltro.push(empresa.nomeFantasia ?? empresa.razaoSocial)
  }
  if (notaFiscalFiltro !== undefined) partesFiltro.push(`Nota ${notaFiscalFiltro}`)
  if (dataInicialIso && dataFinalIso) partesFiltro.push(`${dataInicialTexto} a ${dataFinalTexto}`)
  else if (dataInicialIso) partesFiltro.push(`a partir de ${dataInicialTexto}`)
  else if (dataFinalIso) partesFiltro.push(`até ${dataFinalTexto}`)
  const rotuloFiltro = partesFiltro.length > 0 ? partesFiltro.join(' · ') : 'Todas as entradas'

  const { data, isLoading, isFetching } = useQuery({
    queryKey: [
      'entradas-mercadoria',
      { idFornecedor: fornecedorEscolhido?.idFornecedor, idEmpresaFiltro, notaFiscalFiltro, dataInicialIso, dataFinalIso, pagina, ordenarPor, direcao },
    ],
    queryFn: () =>
      listarEntradas({
        idFornecedor: fornecedorEscolhido?.idFornecedor,
        idEmpresa: idEmpresaFiltro === '' ? undefined : idEmpresaFiltro,
        notaFiscal: notaFiscalFiltro,
        dataInicial: dataInicialIso,
        dataFinal: dataFinalIso,
        pagina,
        tamanho: TAMANHO_PAGINA,
        ordenarPor,
        direcao,
      }),
    enabled: !filtrosAberto,
    placeholderData: (anterior) => anterior,
  })

  const cancelar = useMutation({
    mutationFn: () => cancelarEntrada(entradaParaCancelar!.idMovimento, motivo.trim()),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['entradas-mercadoria'] })
      setEntradaParaCancelar(null)
      setMotivo('')
      setToast({ texto: 'Entrada cancelada — estoque estornado.', tipo: 'sucesso' })
    },
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível cancelar a entrada.', tipo: 'erro' }),
  })

  const totalPaginas = data?.totalPaginas ?? 1
  const entradas = data?.itens ?? []

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>Entrada de Produtos por Compra</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="estoque.entrada.lista" />
            <button type="button" className="btn" onClick={() => navigate('/entrada-produtos-compra/nova')}>
              ＋ Nova entrada
            </button>
            <BotaoFecharTela />
          </div>
        </div>

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
          ) : entradas.length === 0 ? (
            <p className="muted">Nenhuma entrada encontrada.</p>
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
                        <span className={`th-seta ${ativa ? 'th-seta-ativa' : ''}`}>{ativa ? (direcao === 'ASC' ? '▲' : '▼') : '⇅'}</span>
                      </th>
                    )
                  })}
                  <th style={{ textAlign: 'right' }}>Qtde de Itens</th>
                  <th style={{ textAlign: 'right' }}>Valor Total</th>
                  <th>Origem</th>
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {entradas.map((e) => (
                  <tr key={e.idMovimento}>
                    <td>{formatarData(e.dataMovimento)}</td>
                    <td>{e.nomeFornecedor ?? '—'}</td>
                    <td>{e.notaFiscal ?? '—'}</td>
                    <td style={{ textAlign: 'right' }}>{e.qtdItens}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>
                      R$ {formatarMoeda(e.valorTotal)}
                    </td>
                    <td>
                      {ORIGENS[e.origem] ?? e.origem}
                      {e.cancelada && (
                        <span className="badge badge-inativo" style={{ marginLeft: 8 }}>
                          Cancelada
                        </span>
                      )}
                    </td>
                    <td className="acoes-cell">
                      <Link
                        className="acao-icone acao-visualizar"
                        to={`/entrada-produtos-compra/${e.idMovimento}`}
                        aria-label={`Visualizar entrada nº ${e.idMovimento}`}
                        title="Visualizar"
                      >
                        <IconeOlho />
                      </Link>
                      {isAdmin && !e.cancelada && (
                        <button
                          type="button"
                          className="acao-icone acao-excluir"
                          onClick={() => setEntradaParaCancelar(e)}
                          aria-label={`Cancelar entrada nº ${e.idMovimento}`}
                          title="Cancelar"
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
        )}
      </div>

      {!filtrosAberto && entradas.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} entrada{data?.totalItens === 1 ? '' : 's'}
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

      {filtrosAberto && (
        <div className="modal-overlay">
          <div className="modal" role="dialog" aria-label="Filtros de Entrada de Produtos" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Entrada de Produtos por Compra</h2>
            <p className="muted" style={{ marginTop: 4 }}>
              Filtre as entradas já lançadas, ou pule direto para uma nova entrada.
            </p>

            <div className="form-grid" style={{ marginTop: 12 }}>
              <div className="col-12">
                <label htmlFor="filtro-entrada-fornecedor">Fornecedor</label>
                {fornecedorEscolhido ? (
                  <p className="muted" style={{ marginTop: 8 }}>
                    <strong>{fornecedorEscolhido.razaoSocial}</strong>{' '}
                    <button
                      type="button"
                      className="btn ghost"
                      onClick={() => {
                        setFornecedorEscolhido(null)
                        setBuscaFornecedor('')
                      }}
                    >
                      Trocar
                    </button>
                  </p>
                ) : (
                  <>
                    <input
                      id="filtro-entrada-fornecedor"
                      placeholder="Buscar fornecedor…"
                      value={buscaFornecedor}
                      onChange={(e) => setBuscaFornecedor(e.target.value.toUpperCase())}
                    />
                    {fornecedoresEncontrados && fornecedoresEncontrados.length > 0 && (
                      <div className="table-wrap" style={{ maxHeight: 140, marginTop: 8 }}>
                        <table className="table table-compacta">
                          <tbody>
                            {fornecedoresEncontrados.map((f) => (
                              <tr key={f.idFornecedor} tabIndex={0} onClick={() => setFornecedorEscolhido(f)}>
                                <td>{f.razaoSocial}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </>
                )}
              </div>

              <div className="col-6">
                <label htmlFor="filtro-entrada-empresa">Empresa de Entrada</label>
                <select
                  id="filtro-entrada-empresa"
                  value={idEmpresaFiltro}
                  onChange={(e) => setIdEmpresaFiltro(e.target.value === '' ? '' : Number(e.target.value))}
                >
                  <option value="">Todas as empresas</option>
                  {(empresas ?? []).map((emp: Empresa) => (
                    <option key={emp.idEmpresa} value={emp.idEmpresa}>
                      {emp.nomeFantasia ?? emp.razaoSocial}
                    </option>
                  ))}
                </select>
              </div>
              <div className="col-6">
                <label htmlFor="filtro-entrada-nota">Nº Nota Fiscal</label>
                <input
                  id="filtro-entrada-nota"
                  inputMode="numeric"
                  value={notaFiscalTexto}
                  onChange={(e) => setNotaFiscalTexto(e.target.value.replace(/\D/g, ''))}
                />
              </div>

              <div className="col-6">
                <label htmlFor="filtro-entrada-data-inicial">Data Início da Entrada</label>
                <input
                  id="filtro-entrada-data-inicial"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={dataInicialTexto}
                  onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
              <div className="col-6">
                <label htmlFor="filtro-entrada-data-final">Data Final da Entrada</label>
                <input
                  id="filtro-entrada-data-final"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={dataFinalTexto}
                  onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
            </div>

            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => navigate(-1)}>
                Fechar
              </button>
              <button type="button" className="btn ghost" onClick={() => navigate('/entrada-produtos-compra/nova')}>
                ＋ Nova entrada
              </button>
              <button type="button" className="btn" onClick={confirmarFiltros}>
                Localizar
              </button>
            </div>
          </div>
        </div>
      )}

      {entradaParaCancelar && (
        <div className="modal-overlay" onClick={() => (cancelar.isPending ? null : setEntradaParaCancelar(null))}>
          <div className="modal" role="dialog" aria-label="Cancelar entrada" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Cancelar entrada nº {entradaParaCancelar.idMovimento}</h2>
            <p className="muted" style={{ marginTop: 4 }}>
              O estoque recebido nesta entrada será estornado e eventuais contas a pagar geradas por ela serão
              removidas. A entrada continua visível na lista, marcada como cancelada.
            </p>
            <label htmlFor="cancelar-entrada-motivo">Motivo do cancelamento *</label>
            <input
              id="cancelar-entrada-motivo"
              autoFocus
              value={motivo}
              onChange={(e) => setMotivo(maiusculas(e.target.value))}
            />
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" disabled={cancelar.isPending} onClick={() => setEntradaParaCancelar(null)}>
                Voltar
              </button>
              <button
                type="button"
                className="btn"
                disabled={!motivo.trim() || cancelar.isPending}
                onClick={() => cancelar.mutate()}
              >
                {cancelar.isPending ? 'Cancelando…' : 'Cancelar entrada'}
              </button>
            </div>
          </div>
        </div>
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
