import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import CabecalhoModal from '../../../components/CabecalhoModal'
import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../../components/BotaoFecharTela'
import {
  IconeContasPagar,
  IconeEditar,
  IconeExcluir,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../../components/Icones'
import Toast, { type TipoToast } from '../../../components/Toast'
import { ApiError } from '../../../lib/api'
import {
  excluirContaPagar,
  listarContasPagar,
  type ColunaOrdenacaoContaPagar,
  type ContaPagar,
} from '../../../lib/contasPagar'
import { listarEmpresasPermitidas, type Empresa } from '../../../lib/empresas'
import { buscarFornecedoresEmissao, LIMITE_BUSCA_EMISSAO, type FornecedorOpcaoEmissao } from '../../../lib/etiquetaEmissao'
import { dataParaIso, dataValida, formatarMoeda, mascararData } from '../../../lib/masks'
import { usePermissaoDaTela } from '../../../lib/usePermissaoDaTela'
import { fecharAoClicarNoFundo } from '../../../lib/modais'
import { useNavegacaoDeGrid } from '../../../lib/useNavegacaoDeGrid'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

/** `chave: null` = coluna não ordenável (sem correspondente em `COLUNAS_ORDENAVEIS` no backend). */
const COLUNAS: Array<{ chave: ColunaOrdenacaoContaPagar | null; rotulo: string }> = [
  { chave: 'fornecedor', rotulo: 'Fornecedor' },
  { chave: 'empresa', rotulo: 'Empresa' },
  { chave: 'notaFiscal', rotulo: 'Nota Fiscal' },
  { chave: null, rotulo: 'Duplicata' },
  { chave: 'dataVencimento', rotulo: 'Vencimento' },
  { chave: 'dataPagamento', rotulo: 'Pagamento' },
  { chave: 'valorPagar', rotulo: 'Valor a Pagar' },
  { chave: 'valorPago', rotulo: 'Valor Pago' },
]

function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

function formatarData(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString('pt-BR') : '—'
}

/**
 * Contas a Pagar / Pagas (docs/telas/contas-pagar.md) — mesmo padrão de popup de filtros
 * obrigatório ao entrar na tela do fluxo de filtros de Entrada de Produtos por Compra
 * (2026-08-12): "Localizar" aplica os filtros (todos opcionais — em branco lista tudo) e
 * "＋ Nova Conta a Pagar" pula a busca direto pra criação. Grid/ações no padrão de
 * `ContaCorrenteMovimentoLista` (PK surrogate, editável, sem `ativo` — exclusão é sempre
 * definitiva).
 */
export default function ContasPagarLista() {
  // ⛔ RBAC: o admin pode conceder só "acessar" — sem isto o operador preenchia a ficha
  // inteira e perdia tudo num 403 no Salvar (auditoria 2026-08-29). Conveniência de
  // interface, nunca segurança: quem recusa é o @Acao do controller (P4).
  const acoes = usePermissaoDaTela('contas-pagar')
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoContaPagar>('dataVencimento')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('DESC')
  const [contaParaExcluir, setContaParaExcluir] = useState<ContaPagar | null>(null)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(
    () => (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  useEffect(() => {
    if (location.state) window.history.replaceState({}, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const [filtrosAberto, setFiltrosAberto] = useState(true)
  /** ⚠️ O popup abre sozinho e cobre a tela INTEIRA (`.modal-overlay` é `position: fixed; inset: 0`,
   *  então cobre o menu e o ✕ da topbar). Sem saída própria, quem entrasse aqui por engano só
   *  escapava executando uma ação que não queria ("Localizar") ou pelo Voltar do navegador
   *  (auditoria 2026-08-29). Depois da primeira busca o ✕ volta para a grade, em vez de fechar a
   *  tela e descartar a pesquisa — mesmo comportamento do CRM e dos relatórios. */
  const [jaPesquisou, setJaPesquisou] = useState(false)
  const [buscaFornecedor, setBuscaFornecedor] = useState('')
  const [fornecedorEscolhido, setFornecedorEscolhido] = useState<FornecedorOpcaoEmissao | null>(null)
  const [idEmpresaFiltro, setIdEmpresaFiltro] = useState<number | ''>('')
  const [notaFiscalTexto, setNotaFiscalTexto] = useState('')
  const [duplicataTexto, setDuplicataTexto] = useState('')
  const [vencimentoInicialTexto, setVencimentoInicialTexto] = useState('')
  const [vencimentoFinalTexto, setVencimentoFinalTexto] = useState('')
  const [pagamentoInicialTexto, setPagamentoInicialTexto] = useState('')
  const [pagamentoFinalTexto, setPagamentoFinalTexto] = useState('')

  useEffect(() => {
    setPagina(1)
  }, [ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoContaPagar) => {
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
  const vencimentoInicialIso = dataValida(vencimentoInicialTexto) ? (dataParaIso(vencimentoInicialTexto) ?? undefined) : undefined
  const vencimentoFinalIso = dataValida(vencimentoFinalTexto) ? (dataParaIso(vencimentoFinalTexto) ?? undefined) : undefined
  const pagamentoInicialIso = dataValida(pagamentoInicialTexto) ? (dataParaIso(pagamentoInicialTexto) ?? undefined) : undefined
  const pagamentoFinalIso = dataValida(pagamentoFinalTexto) ? (dataParaIso(pagamentoFinalTexto) ?? undefined) : undefined

  const confirmarFiltros = () => {
    setPagina(1)
    setJaPesquisou(true); setFiltrosAberto(false)
  }

  const partesFiltro: string[] = []
  if (fornecedorEscolhido) partesFiltro.push(fornecedorEscolhido.razaoSocial)
  if (idEmpresaFiltro !== '') {
    const empresa = empresas?.find((e) => e.idEmpresa === idEmpresaFiltro)
    if (empresa) partesFiltro.push(empresa.nomeFantasia ?? empresa.razaoSocial)
  }
  if (notaFiscalFiltro !== undefined) partesFiltro.push(`Nota ${notaFiscalFiltro}`)
  if (duplicataTexto.trim()) partesFiltro.push(`Duplicata ${duplicataTexto.trim()}`)
  if (vencimentoInicialIso && vencimentoFinalIso) partesFiltro.push(`Vencimento ${vencimentoInicialTexto} a ${vencimentoFinalTexto}`)
  else if (vencimentoInicialIso) partesFiltro.push(`Vencimento a partir de ${vencimentoInicialTexto}`)
  else if (vencimentoFinalIso) partesFiltro.push(`Vencimento até ${vencimentoFinalTexto}`)
  if (pagamentoInicialIso && pagamentoFinalIso) partesFiltro.push(`Pagamento ${pagamentoInicialTexto} a ${pagamentoFinalTexto}`)
  else if (pagamentoInicialIso) partesFiltro.push(`Pagamento a partir de ${pagamentoInicialTexto}`)
  else if (pagamentoFinalIso) partesFiltro.push(`Pagamento até ${pagamentoFinalTexto}`)
  const rotuloFiltro = partesFiltro.length > 0 ? partesFiltro.join(' · ') : 'Todas as contas'

  const { data, isLoading, isFetching } = useQuery({
    queryKey: [
      'contas-pagar',
      {
        idFornecedor: fornecedorEscolhido?.idFornecedor,
        idEmpresaFiltro,
        notaFiscalFiltro,
        duplicataTexto,
        vencimentoInicialIso,
        vencimentoFinalIso,
        pagamentoInicialIso,
        pagamentoFinalIso,
        pagina,
        ordenarPor,
        direcao,
      },
    ],
    queryFn: () =>
      listarContasPagar({
        idFornecedor: fornecedorEscolhido?.idFornecedor,
        idEmpresa: idEmpresaFiltro === '' ? undefined : idEmpresaFiltro,
        notaFiscal: notaFiscalFiltro,
        numeroDuplicata: duplicataTexto.trim() || undefined,
        dataVencimentoInicial: vencimentoInicialIso,
        dataVencimentoFinal: vencimentoFinalIso,
        dataPagamentoInicial: pagamentoInicialIso,
        dataPagamentoFinal: pagamentoFinalIso,
        pagina,
        tamanho: TAMANHO_PAGINA,
        ordenarPor,
        direcao,
      }),
    enabled: !filtrosAberto,
    placeholderData: (anterior) => anterior,
  })

  const excluir = useMutation({
    mutationFn: excluirContaPagar,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['contas-pagar'] })
      setContaParaExcluir(null)
      setAviso({ texto: 'Conta a pagar excluída.', tipo: 'sucesso' })
    },
    onError: (e: unknown) => {
      setContaParaExcluir(null)
      setAviso({ texto: e instanceof ApiError ? e.message : 'Não foi possível excluir a conta a pagar.', tipo: 'erro' })
    },
  })

  const totalPaginas = data?.totalPaginas ?? 1
  const contas: ContaPagar[] = data?.itens ?? []
  // Navegação por ↑/↓ na grid, com a linha corrente realçada (2026-09-04).
  const { propsDaLinha } = useNavegacaoDeGrid(contas.map((linha) => linha.idContaPagar))

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeContasPagar size={34} />
            <h1>Contas a Pagar / Pagas</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.contaspagar.lista" />
            {acoes.incluir && (
              <Link className="btn" to="/contas-pagar/nova">
                ＋ Nova Conta a Pagar
              </Link>
            )}
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
            ) : contas.length === 0 ? (
              <p className="muted">Nenhuma conta a pagar encontrada.</p>
            ) : (
              <table className="table table-compacta">
                <thead>
                  <tr>
                    {COLUNAS.map((c) => {
                      if (c.chave === null) {
                        return <th key={c.rotulo}>{c.rotulo}</th>
                      }
                      const ativa = ordenarPor === c.chave
                      return (
                        <th
                          key={c.chave}
                          className="th-ordenavel"
                          onClick={() => ordenarPorColuna(c.chave!)}
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
                  {contas.map((c, indice) => (
                    <tr key={c.idContaPagar} {...propsDaLinha(c.idContaPagar, indice)}>
                      <td>{c.nomeFornecedor}</td>
                      <td>{c.nomeEmpresa}</td>
                      <td>{c.notaFiscal ?? '—'}</td>
                      <td>{c.numeroDuplicata ?? '—'}</td>
                      <td>{formatarData(c.dataVencimento)}</td>
                      <td>{formatarData(c.dataPagamento)}</td>
                      <td className="mono" style={{ textAlign: 'right' }}>
                        R$ {formatarMoeda(c.valorPagar)}
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}>
                        R$ {formatarMoeda(c.valorPago)}
                      </td>
                      <td className="acoes-cell">
                        <Link
                          className="acao-icone acao-visualizar"
                          to={`/contas-pagar/${c.idContaPagar}/visualizar`}
                          aria-label={`Visualizar conta a pagar ${c.idContaPagar}`}
                          title="Visualizar"
                        >
                          <IconeOlho />
                        </Link>
                        {acoes.alterar && (
                          <Link
                            className="acao-icone acao-editar"
                            to={`/contas-pagar/${c.idContaPagar}`}
                            aria-label={`Editar conta a pagar ${c.idContaPagar}`}
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
                            onClick={() => setContaParaExcluir(c)}
                            aria-label={`Excluir conta a pagar ${c.idContaPagar}`}
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
        )}
      </div>

      {!filtrosAberto && contas.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} conta{data?.totalItens === 1 ? '' : 's'}
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
          <div className="modal" role="dialog" aria-label="Filtros de Contas a Pagar" onClick={(e) => e.stopPropagation()}>
            <CabecalhoModal
              titulo="Contas a Pagar / Pagas"
              aoFechar={() => (jaPesquisou ? setFiltrosAberto(false) : navigate(-1))}
            />
            <p className="muted" style={{ marginTop: 4 }}>
              Filtre as contas já lançadas, ou pule direto para uma nova conta a pagar.
            </p>

            <div className="form-grid" style={{ marginTop: 12 }}>
              <div className="col-12">
                <label htmlFor="filtro-cp-fornecedor">Fornecedor</label>
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
                      id="filtro-cp-fornecedor"
                      autoFocus
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
                    {/* A busca corta no servidor e a tela não dizia nada (auditoria 2026-08-21,
                        item 33) — ver LIMITE_BUSCA_EMISSAO. */}
                    {fornecedoresEncontrados && fornecedoresEncontrados.length === LIMITE_BUSCA_EMISSAO && (
                      <p className="muted" style={{ marginTop: 6 }}>
                        Mostrando os primeiros {LIMITE_BUSCA_EMISSAO} — refine a busca para ver mais.
                      </p>
                    )}
                  </>
                )}
              </div>

              <div className="col-6">
                <label htmlFor="filtro-cp-empresa">Empresa</label>
                <select
                  id="filtro-cp-empresa"
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
                <label htmlFor="filtro-cp-nota">Nº Nota Fiscal</label>
                <input
                  id="filtro-cp-nota"
                  inputMode="numeric"
                  value={notaFiscalTexto}
                  onChange={(e) => setNotaFiscalTexto(e.target.value.replace(/\D/g, ''))}
                />
              </div>
              <div className="col-6">
                <label htmlFor="filtro-cp-duplicata">Duplicata</label>
                <input id="filtro-cp-duplicata" value={duplicataTexto} onChange={(e) => setDuplicataTexto(e.target.value.toUpperCase())} />
              </div>

              <div className="col-6">
                <label htmlFor="filtro-cp-venc-inicial">Início Vencimento</label>
                <input
                  id="filtro-cp-venc-inicial"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={vencimentoInicialTexto}
                  onChange={(e) => setVencimentoInicialTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
              <div className="col-6">
                <label htmlFor="filtro-cp-venc-final">Final Vencimento</label>
                <input
                  id="filtro-cp-venc-final"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={vencimentoFinalTexto}
                  onChange={(e) => setVencimentoFinalTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
              <div className="col-6">
                <label htmlFor="filtro-cp-pag-inicial">Início Pagamento</label>
                <input
                  id="filtro-cp-pag-inicial"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={pagamentoInicialTexto}
                  onChange={(e) => setPagamentoInicialTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
              <div className="col-6">
                <label htmlFor="filtro-cp-pag-final">Final Pagamento</label>
                <input
                  id="filtro-cp-pag-final"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={pagamentoFinalTexto}
                  onChange={(e) => setPagamentoFinalTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
            </div>

            <div className="ajuda-rodape">
              {/* ⛔ O SEGUNDO botão, e o de maior tráfego (auditoria 2026-08-29, rodada 3): a
                  guarda da rodada 1 pegou só o da topbar, e este popup de filtros ABRE SOZINHO na
                  entrada da tela — é a primeira coisa oferecida, antes do Localizar. Sem a guarda
                  aqui, a correção não cobria justamente o caminho que o operador usa. */}
              {acoes.incluir && (
                <button type="button" className="btn ghost" onClick={() => navigate('/contas-pagar/nova')}>
                  ＋ Nova Conta a Pagar
                </button>
              )}
              <button type="button" className="btn" onClick={confirmarFiltros}>
                Localizar
              </button>
            </div>
          </div>
        </div>
      )}

      {contaParaExcluir && (
        <div className="modal-overlay" onClick={fecharAoClicarNoFundo(() => setContaParaExcluir(null))}>
          <div className="modal" role="dialog" aria-label="Confirmar exclusão" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Excluir conta a pagar?</h2>
            <p className="muted">
              Tem certeza que deseja excluir a conta a pagar de <strong>{contaParaExcluir.nomeFornecedor}</strong> (vencimento{' '}
              {formatarData(contaParaExcluir.dataVencimento)}, R$ {formatarMoeda(contaParaExcluir.valorPagar)})?
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setContaParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(contaParaExcluir.idContaPagar)}
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
