import { useQuery } from '@tanstack/react-query'
import { Fragment, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { IconeRelatorio } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { formatarSoData } from '../../lib/datas'
import { useEu } from '../../lib/eu'
import { listarEmpresas } from '../../lib/empresas'
import { dataParaIso, dataValida, formatarMoeda } from '../../lib/masks'
import {
  gerarRelatorioContasReceber,
  type ColunaOrdenacaoContaReceber,
  type DirecaoOrdenacao,
  type FiltrosRelatorioContasReceber,
} from '../../lib/relatorioContasReceber'
import { gerarPdfCapturaRelatorioContasReceber } from '../../lib/relatorioContasReceberCaptura'
import FiltrosContasReceberModal, {
  FILTROS_VAZIOS,
  OPCOES_CATEGORIA,
  OPCOES_STATUS,
  type FiltrosTexto,
} from './FiltrosContasReceberModal'

const ROTULO_CATEGORIA: Record<string, string> = {
  CARTAO_DEBITO: 'Cartão Débito',
  CARTAO_CREDITO: 'Cartão Crédito',
  CREDIARIO: 'Crediário',
}

const COLUNAS: Array<{ chave: ColunaOrdenacaoContaReceber; rotulo: string; alinhamento?: 'right' }> = [
  { chave: 'nomeEmpresa', rotulo: 'Empresa' },
  { chave: 'idVenda', rotulo: 'Nº Venda' },
  { chave: 'nomeCliente', rotulo: 'Cliente' },
  { chave: 'nomeCarteira', rotulo: 'Forma de Pagamento' },
  { chave: 'numeroParcela', rotulo: 'Parcela' },
  { chave: 'dataVenda', rotulo: 'Data Venda' },
  { chave: 'dataVencimento', rotulo: 'Vencimento' },
  { chave: 'dataRecebimento', rotulo: 'Recebimento' },
  { chave: 'nomeEmpresaPagamento', rotulo: 'Empresa Pagamento' },
  { chave: 'valorBruto', rotulo: 'Valor Bruto', alinhamento: 'right' },
  { chave: 'taxaAdministrativa', rotulo: 'Taxa Adm.', alinhamento: 'right' },
  { chave: 'valorLiquido', rotulo: 'Valor Líquido', alinhamento: 'right' },
]

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function percentual(v: number): string {
  return `${formatarMoeda(v)}%`
}

/** "01/06" — número da parcela sobre o total de parcelas da mesma linha de pagamento. */
function formatarParcela(numero: number, total: number): string {
  return `${String(numero).padStart(2, '0')}/${String(total).padStart(2, '0')}`
}

function periodoPreenchido(inicial: string, final: string): boolean {
  return dataValida(inicial) && dataValida(final)
}

/**
 * Relatório de Contas a Receber / Recebidas (docs/telas/relatorio-contas-receber.md) — mesmo
 * padrão de tela do Relatório de Comissões (filtros-bar + grid banda, sem KPIs/gráficos), mas
 * com 3 períodos independentes (venda/vencimento/recebimento) em vez de um só. Só as categorias
 * CARTAO_DEBITO/CARTAO_CREDITO/CREDIARIO aparecem — À Vista e Vale-Mercadoria nascem sempre
 * quitados na hora, não fazem sentido aqui.
 *
 * Filtros em popup (2026-08-03): a tela não mostra mais os campos de filtro embutidos — abre
 * direto o popup `FiltrosContasReceberModal` (nada é buscado antes disso) e o botão "Filtros"
 * no topo reabre o popup pra gerar outro relatório. Enquanto nenhum relatório foi gerado, a
 * única saída do popup é "Voltar" (sai da tela); depois da 1ª geração, "Cancelar" só fecha o
 * popup sem alterar o relatório já exibido (rascunho dos campos é reposto a partir do último
 * relatório aplicado toda vez que o popup é reaberto).
 */
export default function RelatorioContasReceber() {
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [aplicado, setAplicado] = useState<FiltrosTexto>(FILTROS_VAZIOS)
  const [rascunho, setRascunho] = useState<FiltrosTexto>(FILTROS_VAZIOS)
  const [modalAberto, setModalAberto] = useState(true)
  const [relatorioGerado, setRelatorioGerado] = useState(false)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoContaReceber>('dataVencimento')
  const [direcao, setDirecao] = useState<DirecaoOrdenacao>('ASC')
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const conteudoRef = useRef<HTMLDivElement>(null)

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas, enabled: ehAdmin })

  const isoOuIndefinido = (texto: string): string | undefined => (dataValida(texto) ? (dataParaIso(texto) ?? undefined) : undefined)

  const filtros: FiltrosRelatorioContasReceber = {
    dataVendaInicial: isoOuIndefinido(aplicado.dataVendaInicial),
    dataVendaFinal: isoOuIndefinido(aplicado.dataVendaFinal),
    dataVencimentoInicial: isoOuIndefinido(aplicado.dataVencimentoInicial),
    dataVencimentoFinal: isoOuIndefinido(aplicado.dataVencimentoFinal),
    dataRecebimentoInicial: isoOuIndefinido(aplicado.dataRecebimentoInicial),
    dataRecebimentoFinal: isoOuIndefinido(aplicado.dataRecebimentoFinal),
    idsEmpresa: ehAdmin ? aplicado.idsEmpresa : undefined,
    status: aplicado.status || undefined,
    categoria: aplicado.categoria || undefined,
    ordenarPor,
    direcao,
  }

  const podeGerarRascunho =
    periodoPreenchido(rascunho.dataVendaInicial, rascunho.dataVendaFinal) ||
    periodoPreenchido(rascunho.dataVencimentoInicial, rascunho.dataVencimentoFinal) ||
    periodoPreenchido(rascunho.dataRecebimentoInicial, rascunho.dataRecebimentoFinal)

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['relatorio-contas-receber', filtros],
    queryFn: () => gerarRelatorioContasReceber(filtros),
    enabled: relatorioGerado,
    placeholderData: (anterior) => anterior,
  })

  function abrirFiltros() {
    setRascunho(aplicado)
    setModalAberto(true)
  }

  function handleGerar() {
    setAplicado(rascunho)
    setRelatorioGerado(true)
    setModalAberto(false)
  }

  function fecharModal() {
    setModalAberto(false)
  }

  function ordenarPorColuna(coluna: ColunaOrdenacaoContaReceber) {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  function textoEmpresasFiltradas(): string {
    if (ehAdmin) {
      return aplicado.idsEmpresa.length === 0
        ? 'Todas as empresas'
        : (empresas ?? [])
            .filter((e) => aplicado.idsEmpresa.includes(e.idEmpresa))
            .map((e) => e.nomeFantasia ?? e.razaoSocial)
            .join(', ') || 'Todas as empresas'
    }
    return eu?.empresa.nome ?? '—'
  }

  function montarDescricaoFiltros(): Array<{ rotulo: string; valor: string }> {
    const linhas: Array<{ rotulo: string; valor: string }> = []
    if (aplicado.dataVendaInicial && aplicado.dataVendaFinal)
      linhas.push({ rotulo: 'Período de Venda', valor: `${aplicado.dataVendaInicial} a ${aplicado.dataVendaFinal}` })
    if (aplicado.dataVencimentoInicial && aplicado.dataVencimentoFinal)
      linhas.push({ rotulo: 'Período de Vencimento', valor: `${aplicado.dataVencimentoInicial} a ${aplicado.dataVencimentoFinal}` })
    if (aplicado.dataRecebimentoInicial && aplicado.dataRecebimentoFinal)
      linhas.push({ rotulo: 'Período de Recebimento', valor: `${aplicado.dataRecebimentoInicial} a ${aplicado.dataRecebimentoFinal}` })
    linhas.push({ rotulo: 'Empresa(s)', valor: textoEmpresasFiltradas() })
    linhas.push({ rotulo: 'Status', valor: OPCOES_STATUS.find((o) => o.chave === aplicado.status)?.rotulo ?? 'Todos' })
    linhas.push({ rotulo: 'Forma de Pagamento', valor: OPCOES_CATEGORIA.find((o) => o.chave === aplicado.categoria)?.rotulo ?? 'Todos' })
    return linhas
  }

  function aguardarPintura(): Promise<void> {
    return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  }

  const handleGerarPdf = async () => {
    if (!conteudoRef.current) return
    setGerandoPdf(true)
    try {
      await aguardarPintura()
      await gerarPdfCapturaRelatorioContasReceber(conteudoRef.current, eu?.empresa.nome ?? '—')
    } finally {
      setGerandoPdf(false)
    }
  }

  const mostrarSubtotais = (data?.subtotaisPorEmpresa.length ?? 0) > 1

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRelatorio size={34} />
            <h1>Contas a Receber / Recebidas</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.contasreceber.tela" />
            {relatorioGerado && (
              <button type="button" className="btn ghost" onClick={abrirFiltros}>
                Filtros
              </button>
            )}
            {data && (
              <button type="button" className="btn ghost" disabled={gerandoPdf} onClick={handleGerarPdf}>
                {gerandoPdf ? 'Gerando PDF…' : 'Gerar PDF'}
              </button>
            )}
            <button type="button" className="btn ghost" onClick={() => navigate('/')}>
              Voltar
            </button>
          </div>
        </div>
      </div>

      <div className="lista-corpo relatorio-corpo-fixo">
        {!relatorioGerado ? (
          <p className="muted">Use o botão Filtros para gerar o relatório.</p>
        ) : isLoading ? (
          <p className="muted">Carregando relatório…</p>
        ) : error || !data ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível gerar o relatório.'}</p>
        ) : (
          <div ref={conteudoRef} className={`relatorio-conteudo${gerandoPdf ? ' pdf-expandido' : ''}`}>
            {isFetching && <p className="muted">Atualizando…</p>}

            {gerandoPdf && (
              <>
                <p className="section-label">Filtros Aplicados</p>
                <div className="card relatorio-filtros-aplicados">
                  {montarDescricaoFiltros().map((f) => (
                    <span key={f.rotulo}>
                      <span className="muted">{f.rotulo}: </span>
                      <strong>{f.valor}</strong>
                    </span>
                  ))}
                </div>
              </>
            )}

            <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>
              Parcelas
            </p>
            <div className={`card table-wrap${gerandoPdf ? ' pdf-expandido' : ''}`}>
              {data.linhas.length === 0 ? (
                <p className="muted">Nenhuma parcela encontrada para os filtros informados.</p>
              ) : (
                <table className="table table-compacta tabela-adensada">
                  <thead>
                    <tr>
                      {COLUNAS.map((c) => {
                        const ativa = ordenarPor === c.chave
                        return (
                          <th
                            key={c.chave}
                            className="th-ordenavel"
                            style={c.alinhamento === 'right' ? { textAlign: 'right' } : undefined}
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
                    </tr>
                  </thead>
                  <tbody>
                    {data.linhas.map((linha, indice) => {
                      const proxima = data.linhas[indice + 1]
                      const fimDoGrupo = mostrarSubtotais && (!proxima || proxima.idEmpresa !== linha.idEmpresa)
                      const subtotal = fimDoGrupo
                        ? data.subtotaisPorEmpresa.find((s) => s.idEmpresa === linha.idEmpresa)
                        : undefined
                      return (
                        <Fragment key={`${linha.idVenda}-${linha.numeroParcela}-${linha.nomeCarteira}`}>
                          <tr>
                            <td>{linha.nomeEmpresa}</td>
                            <td className="mono">{linha.idVenda}</td>
                            <td>{linha.nomeCliente ?? '—'}</td>
                            <td>
                              {linha.nomeCarteira} — {ROTULO_CATEGORIA[linha.categoriaCarteira]}
                            </td>
                            <td className="mono">{formatarParcela(linha.numeroParcela, linha.totalParcelas)}</td>
                            <td>{formatarSoData(linha.dataVenda)}</td>
                            <td>{formatarSoData(linha.dataVencimento)}</td>
                            <td>{linha.dataRecebimento ? formatarSoData(linha.dataRecebimento) : '—'}</td>
                            <td>{linha.nomeEmpresaPagamento ?? '—'}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorBruto)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{percentual(linha.taxaAdministrativa)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorLiquido)}</td>
                          </tr>
                          {subtotal && (
                            <tr className="linha-subtotal">
                              <td colSpan={9}>
                                <strong>Subtotal — {subtotal.nomeEmpresa}</strong>
                              </td>
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorBruto)}</strong></td>
                              <td />
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorLiquido)}</strong></td>
                            </tr>
                          )}
                        </Fragment>
                      )
                    })}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td colSpan={9}>
                        <strong>Total Geral</strong>
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorBruto)}</strong></td>
                      <td />
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorLiquido)}</strong></td>
                    </tr>
                  </tfoot>
                </table>
              )}
            </div>
          </div>
        )}
      </div>

      {modalAberto && (
        <FiltrosContasReceberModal
          valores={rascunho}
          aoMudar={(parcial) => setRascunho((r) => ({ ...r, ...parcial }))}
          ehAdmin={!!ehAdmin}
          empresas={empresas ?? []}
          podeGerar={podeGerarRascunho}
          primeiraVez={!relatorioGerado}
          aoGerar={handleGerar}
          aoFechar={relatorioGerado ? fecharModal : () => navigate('/')}
        />
      )}
    </div>
  )
}
