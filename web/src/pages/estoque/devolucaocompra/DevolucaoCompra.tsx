import CabecalhoModal from '../../../components/CabecalhoModal'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../../components/BotaoFecharTela'
import { IconeEstoque } from '../../../components/Icones'
import Toast from '../../../components/Toast'
import { ApiError } from '../../../lib/api'
import { buscarPermiteQtdDecimal } from '../../../lib/configuracaoGeral'
import {
  efetivarDevolucaoCompra,
  listarEntradasElegiveis,
  listarItensDevolviveis,
  type DevolucaoCompraEfetivada,
  type EntradaElegivel,
} from '../../../lib/devolucaoCompra'
import { listarEmpresasPermitidas, type Empresa } from '../../../lib/empresas'
import { buscarFornecedoresEmissao, LIMITE_BUSCA_EMISSAO, type FornecedorOpcaoEmissao } from '../../../lib/etiquetaEmissao'
import {
  completarQuantidade,
  dataParaIso,
  dataValida,
  desmascararQuantidade,
  formatarMoeda,
  formatarQuantidade,
  mascararData,
  mascararQuantidade,
} from '../../../lib/masks'

const TAMANHO_PAGINA = 50

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

/** Quanto o operador digitou por variação, como texto — a conversão para número só acontece no
 *  envio, para o campo aceitar digitação natural ("1", "1,5") sem o React reescrever no meio. */
type Digitado = Record<number, string>

export default function DevolucaoCompra() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [filtrosAberto, setFiltrosAberto] = useState(true)
  /** Já houve uma busca? É o que decide se o ✕ do popup volta para a grade ou sai da tela. */
  const [jaPesquisou, setJaPesquisou] = useState(false)
  const [buscaFornecedor, setBuscaFornecedor] = useState('')
  const [fornecedorEscolhido, setFornecedorEscolhido] = useState<FornecedorOpcaoEmissao | null>(null)
  const [idEmpresaFiltro, setIdEmpresaFiltro] = useState<number | ''>('')
  const [notaFiscalTexto, setNotaFiscalTexto] = useState('')
  const [dataInicialTexto, setDataInicialTexto] = useState('')
  const [dataFinalTexto, setDataFinalTexto] = useState('')
  const [pagina, setPagina] = useState(1)

  const [entrada, setEntrada] = useState<EntradaElegivel | null>(null)
  const [marcados, setMarcados] = useState<Set<number>>(new Set())
  const [qtds, setQtds] = useState<Digitado>({})
  const [confirmando, setConfirmando] = useState(false)
  const [resultado, setResultado] = useState<DevolucaoCompraEfetivada | null>(null)
  const [toast, setToast] = useState<{ texto: string; tipo: 'erro' | 'sucesso' } | null>(null)

  const { data: empresas } = useQuery({ queryKey: ['empresas-permitidas'], queryFn: listarEmpresasPermitidas })
  /**
   * ⚠️ Esta tela era a ÚNICA que movimenta estoque ignorando `cfg_permite_qtd_decimal` (achado de
   * auditoria, 2026-08-21): usava as máscaras de PESO, fixas em 3 casas. Com o parâmetro desligado
   * — o caso comum — todo o sistema mostrava "5" e recusava fração, e aqui aparecia "5,000" e
   * aceitava 2,5. O servidor gravava, e daí em diante o estoque real ficava fracionário enquanto
   * todas as demais telas o exibiam arredondado. O guard correspondente entrou no
   * `DevolucaoCompraService` (P4: a regra vale no servidor, a tela só não deixa digitar).
   */
  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const { data: fornecedoresEncontrados } = useQuery({
    queryKey: ['etiqueta-emissao-fornecedores', buscaFornecedor],
    queryFn: () => buscarFornecedoresEmissao(buscaFornecedor),
    enabled: buscaFornecedor.trim().length > 0 && !fornecedorEscolhido,
  })

  const notaFiscalFiltro = notaFiscalTexto.trim() ? Number(notaFiscalTexto.trim()) : undefined
  const dataInicialIso = dataValida(dataInicialTexto) ? (dataParaIso(dataInicialTexto) ?? undefined) : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? (dataParaIso(dataFinalTexto) ?? undefined) : undefined

  const partesFiltro: string[] = []
  if (fornecedorEscolhido) partesFiltro.push(fornecedorEscolhido.razaoSocial)
  if (idEmpresaFiltro !== '') {
    const emp = empresas?.find((e) => e.idEmpresa === idEmpresaFiltro)
    if (emp) partesFiltro.push(emp.nomeFantasia ?? emp.razaoSocial)
  }
  if (notaFiscalFiltro !== undefined) partesFiltro.push(`Nota ${notaFiscalFiltro}`)
  if (dataInicialIso && dataFinalIso) partesFiltro.push(`${dataInicialTexto} a ${dataFinalTexto}`)
  else if (dataInicialIso) partesFiltro.push(`a partir de ${dataInicialTexto}`)
  else if (dataFinalIso) partesFiltro.push(`até ${dataFinalTexto}`)
  const rotuloFiltro = partesFiltro.length > 0 ? partesFiltro.join(' · ') : 'Todas as entradas'

  const { data: entradas, isLoading } = useQuery({
    queryKey: [
      'devolucao-compra-entradas',
      { f: fornecedorEscolhido?.idFornecedor, idEmpresaFiltro, notaFiscalFiltro, dataInicialIso, dataFinalIso, pagina },
    ],
    queryFn: () =>
      listarEntradasElegiveis({
        idFornecedor: fornecedorEscolhido?.idFornecedor,
        idEmpresa: idEmpresaFiltro === '' ? undefined : idEmpresaFiltro,
        notaFiscal: notaFiscalFiltro,
        dataInicial: dataInicialIso,
        dataFinal: dataFinalIso,
        pagina,
        limite: TAMANHO_PAGINA,
      }),
    enabled: !filtrosAberto,
    placeholderData: (anterior) => anterior,
  })

  const { data: itens, isLoading: carregandoItens } = useQuery({
    queryKey: ['devolucao-compra-itens', entrada?.idMovimento],
    queryFn: () => listarItensDevolviveis(entrada!.idMovimento),
    enabled: entrada != null,
  })

  const escolherEntrada = (e: EntradaElegivel) => {
    setEntrada(e)
    setMarcados(new Set())
    setQtds({})
  }

  /** Marcar já preenche a quantidade com o máximo devolvível: devolução total é o caso comum, e
   *  quem quer devolver menos edita. Desmarcar limpa — deixar o número para trás faria a linha
   *  reaparecer preenchida se o operador marcasse de novo por engano. */
  const alternar = (item: { idVariacao: number; qtdMaxima: number }) => {
    const novo = new Set(marcados)
    const novasQtds = { ...qtds }
    if (novo.has(item.idVariacao)) {
      novo.delete(item.idVariacao)
      delete novasQtds[item.idVariacao]
    } else {
      novo.add(item.idVariacao)
      novasQtds[item.idVariacao] = formatarQuantidade(item.qtdMaxima, permiteQtdDecimal)
    }
    setMarcados(novo)
    setQtds(novasQtds)
  }

  const selecionados = (itens ?? []).filter((i) => marcados.has(i.idVariacao))
  const totalDevolucao = selecionados.reduce(
    (soma, i) => soma + desmascararQuantidade(qtds[i.idVariacao] ?? '0', permiteQtdDecimal) * (i.valorUnitario ?? 0),
    0,
  )
  const excedeu = selecionados.filter((i) => desmascararQuantidade(qtds[i.idVariacao] ?? '0', permiteQtdDecimal) > i.qtdMaxima)
  const zerados = selecionados.filter((i) => desmascararQuantidade(qtds[i.idVariacao] ?? '0', permiteQtdDecimal) <= 0)
  const podeDevolver = selecionados.length > 0 && excedeu.length === 0 && zerados.length === 0

  const devolver = useMutation({
    mutationFn: () =>
      efetivarDevolucaoCompra({
        idMovimentoOrigem: entrada!.idMovimento,
        itens: selecionados.map((i) => ({
          idVariacao: i.idVariacao,
          qtd: desmascararQuantidade(qtds[i.idVariacao] ?? '0', permiteQtdDecimal),
        })),
      }),
    onSuccess: (r) => {
      queryClient.invalidateQueries({ queryKey: ['devolucao-compra-entradas'] })
      queryClient.invalidateQueries({ queryKey: ['devolucao-compra-itens'] })
      setConfirmando(false)
      setResultado(r)
      setEntrada(null)
      setMarcados(new Set())
      setQtds({})
    },
    onError: (e: unknown) => {
      setConfirmando(false)
      setToast({
        texto: e instanceof ApiError ? e.message : 'Não foi possível gerar a devolução.',
        tipo: 'erro',
      })
    },
  })

  const notaAutorizada = resultado?.nota?.situacao === 'AUTORIZADO'

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>Devolução de Produtos Comprados</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="estoque.devolucao-compra" />
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
          <>
            <div className="card table-wrap">
              <h2 style={{ marginTop: 0 }}>1. Escolha a entrada</h2>
              {isLoading ? (
                <p className="muted">Carregando…</p>
              ) : (entradas?.itens.length ?? 0) === 0 ? (
                <p className="muted">
                  Nenhuma entrada devolvível encontrada. Só é possível devolver entrada recebida por XML de
                  NF-e — entrada manual ou por planilha não tem nota de origem para espelhar.
                </p>
              ) : (
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Data</th>
                      <th>Fornecedor</th>
                      <th>Empresa</th>
                      <th>Nota Fiscal</th>
                      <th style={{ textAlign: 'right' }}>Itens</th>
                      <th style={{ textAlign: 'right' }}>Valor Total</th>
                      <th aria-label="Seleção" />
                    </tr>
                  </thead>
                  <tbody>
                    {(entradas?.itens ?? []).map((e) => (
                      <tr
                        key={e.idMovimento}
                        tabIndex={0}
                        className={entrada?.idMovimento === e.idMovimento ? 'linha-selecionada' : ''}
                        onClick={() => escolherEntrada(e)}
                      >
                        <td>{formatarData(e.dataMovimento)}</td>
                        <td>{e.nomeFornecedor ?? '—'}</td>
                        <td>{e.nomeEmpresa ?? '—'}</td>
                        <td>
                          {e.notaFiscal ?? '—'}
                          {e.serieNota != null && `/${e.serieNota}`}
                          {e.temDevolucao && (
                            <span className="badge badge-inativo" style={{ marginLeft: 8 }}>
                              Devolvida em parte
                            </span>
                          )}
                        </td>
                        <td style={{ textAlign: 'right' }}>{e.qtdItens}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>
                          R$ {formatarMoeda(e.valorTotal)}
                        </td>
                        <td>
                          <button type="button" className="btn ghost" onClick={() => escolherEntrada(e)}>
                            Selecionar
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {/* ⛔ A PAGINAÇÃO EXISTIA E ERA INALCANÇÁVEL (auditoria 2026-08-29, rodada 2). O
                  estado `pagina` ia para a query, o servidor devolvia `total`/`totalPaginas` — e
                  `setPagina` só era chamado no Localizar, para voltar à página 1. Não havia barra,
                  nem contador, nem aviso de corte: numa loja que recebe XML todo dia, o operador
                  via as 50 entradas mais recentes e a nota de três meses atrás simplesmente não
                  existia. Pior, a própria tela reforçava a conclusão errada com o texto "só é
                  possível devolver entrada recebida por XML" — ele culpava a origem da entrada. */}
              {(entradas ? Math.ceil(entradas.total / entradas.limite) : 0) > 1 && (
                <div className="paginacao-bar" style={{ marginTop: 8 }}>
                  <span className="muted">
                    {entradas?.total} entradas · página {pagina} de {Math.ceil((entradas?.total ?? 0) / (entradas?.limite || TAMANHO_PAGINA))}
                  </span>
                  <div style={{ display: 'flex', gap: 6 }}>
                    <button
                      type="button"
                      className="btn ghost"
                      disabled={pagina <= 1}
                      onClick={() => setPagina((p) => Math.max(1, p - 1))}
                    >
                      Anterior
                    </button>
                    <button
                      type="button"
                      className="btn ghost"
                      disabled={pagina >= Math.ceil((entradas?.total ?? 0) / (entradas?.limite || TAMANHO_PAGINA))}
                      onClick={() => setPagina((p) => p + 1)}
                    >
                      Próxima
                    </button>
                  </div>
                </div>
              )}
            </div>

            {entrada && (
              <div className="card table-wrap" style={{ marginTop: 12 }}>
                <h2 style={{ marginTop: 0 }}>
                  2. O que devolver — nota {entrada.notaFiscal ?? '—'} de {entrada.nomeFornecedor ?? 'fornecedor'}
                </h2>
                {carregandoItens ? (
                  <p className="muted">Carregando…</p>
                ) : (itens?.length ?? 0) === 0 ? (
                  <p className="muted">
                    Não há nada devolvível nesta entrada: os produtos já foram devolvidos ou não estão mais em
                    estoque. Só é possível devolver ao fornecedor mercadoria que ainda está na loja.
                  </p>
                ) : (
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        <th aria-label="Marcar" />
                        <th>SKU</th>
                        <th>Produto</th>
                        <th style={{ textAlign: 'right' }}>Comprada</th>
                        <th style={{ textAlign: 'right' }}>Já devolvida</th>
                        <th style={{ textAlign: 'right' }}>Em estoque</th>
                        <th style={{ textAlign: 'right' }}>Máximo</th>
                        <th style={{ textAlign: 'right' }}>Devolver</th>
                        <th style={{ textAlign: 'right' }}>Valor</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(itens ?? []).map((i) => {
                        const marcado = marcados.has(i.idVariacao)
                        const digitado = qtds[i.idVariacao] ?? ''
                        const acima = marcado && desmascararQuantidade(digitado || '0', permiteQtdDecimal) > i.qtdMaxima
                        const limitadoPeloEstoque = i.qtdEstoque < i.qtdSaldo
                        return (
                          <tr key={i.idVariacao} className={marcado ? 'linha-selecionada' : ''}>
                            <td>
                              <input
                                type="checkbox"
                                checked={marcado}
                                onChange={() => alternar(i)}
                                aria-label={`Devolver ${i.descricao}`}
                              />
                            </td>
                            <td className="mono">{i.sku}</td>
                            <td>
                              {i.descricao}
                              {(i.variacaoCor || i.variacaoTamanho) && (
                                <span className="muted">
                                  {' '}
                                  {[i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · ')}
                                </span>
                              )}
                            </td>
                            <td className="mono" style={{ textAlign: 'right' }}>
                              {formatarQuantidade(i.qtdComprada, permiteQtdDecimal)}
                            </td>
                            <td className="mono" style={{ textAlign: 'right' }}>
                              {formatarQuantidade(i.qtdDevolvida, permiteQtdDecimal)}
                            </td>
                            <td className="mono" style={{ textAlign: 'right' }}>
                              {formatarQuantidade(i.qtdEstoque, permiteQtdDecimal)}
                            </td>
                            <td
                              className="mono"
                              style={{ textAlign: 'right' }}
                              title={
                                limitadoPeloEstoque
                                  ? 'Limitado pelo estoque: o resto já saiu da loja.'
                                  : 'Limitado pelo saldo da nota.'
                              }
                            >
                              {formatarQuantidade(i.qtdMaxima, permiteQtdDecimal)}
                              {limitadoPeloEstoque && ' *'}
                            </td>
                            <td style={{ textAlign: 'right' }}>
                              <input
                                className="mono"
                                style={{ width: 90, textAlign: 'right' }}
                                disabled={!marcado}
                                value={digitado}
                                onChange={(e) =>
                                  setQtds((q) => ({ ...q, [i.idVariacao]: mascararQuantidade(e.target.value, permiteQtdDecimal) }))
                                }
                                onBlur={(e) =>
                                  setQtds((q) => ({ ...q, [i.idVariacao]: completarQuantidade(e.target.value, permiteQtdDecimal) }))
                                }
                                onFocus={(e) => e.target.select()}
                                aria-invalid={acima}
                              />
                            </td>
                            <td className="mono" style={{ textAlign: 'right' }}>
                              R$ {formatarMoeda(marcado ? desmascararQuantidade(digitado || '0', permiteQtdDecimal) * (i.valorUnitario ?? 0) : 0)}
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                )}
                {(itens ?? []).some((i) => i.qtdEstoque < i.qtdSaldo) && (
                  <p className="muted" style={{ marginTop: 8 }}>
                    * Limitado pelo estoque: parte da mercadoria desta nota já saiu da loja e não pode ser
                    devolvida ao fornecedor.
                  </p>
                )}
              </div>
            )}
          </>
        )}
      </div>

      {!filtrosAberto && entrada && (itens?.length ?? 0) > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {selecionados.length} produto{selecionados.length === 1 ? '' : 's'} · Total R${' '}
              {formatarMoeda(totalDevolucao)}
              {excedeu.length > 0 && ' · quantidade acima do máximo'}
            </span>
            <button type="button" className="btn" disabled={!podeDevolver} onClick={() => setConfirmando(true)}>
              Devolver ao fornecedor
            </button>
          </div>
        </div>
      )}

      {filtrosAberto && (
        <div className="modal-overlay">
          <div className="modal" role="dialog" aria-label="Filtros da Devolução de Produtos Comprados">
            {/* ⚠️ O ✕ só sai da TELA enquanto nada foi pesquisado (auditoria 2026-08-29). Depois
                da primeira busca, a barra "Alterar Filtros" reabre este mesmo popup — e fechar
                com o ✕ jogava o operador para fora, descartando uma pesquisa que ele acabara de
                fazer. Mesmo comportamento do CRM e dos relatórios. */}
            <CabecalhoModal
              titulo="Devolução de Produtos Comprados"
              aoFechar={() => (jaPesquisou ? setFiltrosAberto(false) : navigate(-1))}
            />
            <p className="muted" style={{ marginTop: 4 }}>
              Localize a entrada que originou a mercadoria a devolver.
            </p>

            <div className="form-grid" style={{ marginTop: 12 }}>
              <div className="col-12">
                <label htmlFor="dev-compra-fornecedor">Fornecedor</label>
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
                      id="dev-compra-fornecedor"
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
                <label htmlFor="dev-compra-empresa">Empresa</label>
                <select
                  id="dev-compra-empresa"
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
                <label htmlFor="dev-compra-nota">Nº Nota Fiscal</label>
                <input
                  id="dev-compra-nota"
                  inputMode="numeric"
                  value={notaFiscalTexto}
                  onChange={(e) => setNotaFiscalTexto(e.target.value.replace(/\D/g, ''))}
                />
              </div>

              <div className="col-6">
                <label htmlFor="dev-compra-data-inicial">Data Início da Entrada</label>
                <input
                  id="dev-compra-data-inicial"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={dataInicialTexto}
                  onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
              <div className="col-6">
                <label htmlFor="dev-compra-data-final">Data Final da Entrada</label>
                <input
                  id="dev-compra-data-final"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={dataFinalTexto}
                  onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
            </div>

            <div className="ajuda-rodape">

              <button
                type="button"
                className="btn"
                onClick={() => {
                  setPagina(1)
                  setEntrada(null)
                  setJaPesquisou(true)
                  setFiltrosAberto(false)
                }}
              >
                Localizar
              </button>
            </div>
          </div>
        </div>
      )}

      {confirmando && (
        <div className="modal-overlay" onClick={() => (devolver.isPending ? null : setConfirmando(false))}>
          <div className="modal" role="dialog" aria-label="Confirmar devolução" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Confirmar devolução ao fornecedor</h2>
            <p className="muted" style={{ marginTop: 4 }}>
              {selecionados.length} produto{selecionados.length === 1 ? '' : 's'}, total de R${' '}
              {formatarMoeda(totalDevolucao)}. O estoque será baixado e uma NF-e de saída será emitida para{' '}
              {entrada?.nomeFornecedor ?? 'o fornecedor'}.
            </p>
            <p className="muted">
              A mercadoria só pode ser despachada depois que a nota for autorizada pela SEFAZ.
            </p>
            <div className="ajuda-rodape">
              <button
                type="button"
                className="btn ghost"
                disabled={devolver.isPending}
                onClick={() => setConfirmando(false)}
              >
                Voltar
              </button>
              <button type="button" className="btn" disabled={devolver.isPending} onClick={() => devolver.mutate()}>
                {devolver.isPending ? 'Gerando…' : 'Confirmar devolução'}
              </button>
            </div>
          </div>
        </div>
      )}

      {resultado && (
        <div className="modal-overlay">
          <div className="modal" role="dialog" aria-label="Devolução gerada">
            <CabecalhoModal titulo=<>Devolução nº {resultado.idMovimento} gerada</> aoFechar={() => setResultado(null)} />
            <p className="muted" style={{ marginTop: 4 }}>
              Estoque baixado. Total de R$ {formatarMoeda(resultado.valorTotal)} devolvido para{' '}
              {resultado.nomeFornecedor ?? 'o fornecedor'}.
            </p>
            {resultado.nota ? (
              <p className={notaAutorizada ? 'texto-sucesso' : 'texto-erro'}>{resultado.nota.mensagem}</p>
            ) : (
              <p className="muted">
                Nenhuma nota fiscal foi emitida: a emissão fiscal está desligada para esta empresa.
              </p>
            )}
            {resultado.nota?.chaveAcesso && (
              <p className="mono" style={{ fontSize: '0.85em', wordBreak: 'break-all' }}>
                {resultado.nota.chaveAcesso}
              </p>
            )}
            <div className="ajuda-rodape">

            </div>
          </div>
        </div>
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
