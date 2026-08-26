import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Fragment, useRef, useState } from 'react'
import { BarChart, Bar, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeRelatorio } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { formatarSoData } from '../../lib/datas'
import { listarEmpresas } from '../../lib/empresas'
import { useEu } from '../../lib/eu'
import { dataParaIso, dataValida, formatarMoeda } from '../../lib/masks'
import {
  gerarRelatorioContasPagar,
  type ColunaOrdenacaoContaPagar,
  type DirecaoOrdenacao,
  type FiltrosRelatorioContasPagar,
} from '../../lib/relatorioContasPagar'
import { gerarPdfCapturaRelatorioContasPagar } from '../../lib/relatorioContasPagarCaptura'
import FiltrosContasPagarModal, {
  FILTROS_VAZIOS,
  OPCOES_SITUACAO,
  type FiltrosTextoContasPagar,
} from './FiltrosContasPagarModal'

const COLUNAS: Array<{ chave: ColunaOrdenacaoContaPagar; rotulo: string; alinhamento?: 'right' }> = [
  { chave: 'nomeEmpresa', rotulo: 'Empresa' },
  { chave: 'nomeFornecedor', rotulo: 'Fornecedor' },
  { chave: 'idPlanoContas', rotulo: 'Plano de Contas' },
  { chave: 'notaFiscal', rotulo: 'NF', alinhamento: 'right' },
  { chave: 'numeroDuplicata', rotulo: 'Duplicata' },
  { chave: 'dataLancamento', rotulo: 'Lançamento' },
  { chave: 'dataVencimento', rotulo: 'Vencimento' },
  { chave: 'dataPagamento', rotulo: 'Pagamento' },
  { chave: 'valorPagar', rotulo: 'Valor', alinhamento: 'right' },
  { chave: 'valorPago', rotulo: 'Valor Pago', alinhamento: 'right' },
  { chave: 'valorEmAberto', rotulo: 'Em Aberto', alinhamento: 'right' },
]

const ROTULO_SITUACAO: Record<string, string> = {
  PAGA: 'Paga',
  VENCIDA: 'Vencida',
  A_VENCER: 'A vencer',
}

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

const estiloTooltipGrafico = {
  background: 'var(--surface)',
  border: '1px solid var(--line)',
  borderRadius: 8,
  fontSize: 13,
}
const estiloEixoGrafico = { fontSize: 12, fill: 'var(--ink-muted)' }

/**
 * Mesmo gráfico de barra horizontal dos outros relatórios — duplicado de propósito, cada
 * relatório define os seus (não existe módulo compartilhado de gráficos no projeto ainda).
 *
 * ⚠️ Nada de `color-mix()` aqui: o PDF é captura visual (html2canvas), e ele não resolve
 * `color-mix` — a fatia sairia sem cor no PDF e ninguém notaria na tela.
 */
function GraficoBarraHorizontal({
  dados,
  cor = 'var(--accent)',
  rotuloValor,
}: {
  dados: { rotulo: string; valor: number }[]
  cor?: string
  rotuloValor: string
}) {
  return (
    <ResponsiveContainer width="100%" height={Math.max(160, dados.length * 32)}>
      <BarChart data={dados} layout="vertical" margin={{ top: 4, right: 24, left: 0, bottom: 4 }}>
        <CartesianGrid strokeDasharray="0" stroke="var(--line)" horizontal={false} />
        <XAxis
          type="number"
          tick={estiloEixoGrafico}
          axisLine={{ stroke: 'var(--line)' }}
          tickLine={false}
          tickFormatter={(v) => moeda(v)}
        />
        <YAxis
          type="category"
          dataKey="rotulo"
          tick={estiloEixoGrafico}
          axisLine={false}
          tickLine={false}
          width={180}
        />
        <Tooltip contentStyle={estiloTooltipGrafico} formatter={(valor) => [moeda(Number(valor)), rotuloValor]} />
        <Bar dataKey="valor" fill={cor} radius={[0, 4, 4, 0]} maxBarSize={22} />
      </BarChart>
    </ResponsiveContainer>
  )
}

function periodoPreenchido(inicial: string, final: string): boolean {
  return dataValida(inicial) && dataValida(final)
}

/**
 * Relatório de Contas a Pagar / Pagas (`docs/telas/relatorio-contas-pagar.md`).
 *
 * Mesmo padrão dos demais relatórios: o popup de filtros abre **sozinho** ao entrar (nada é
 * buscado antes de o usuário escolher), o botão "Filtros" reabre, e o PDF é captura visual.
 *
 * ⚠️ **Compra de mercadoria aparece aqui** e não na Lucratividade — este relatório é sobre
 * dinheiro que **sai**, não sobre lucro. Ver a spec, decisão 2.
 */
export default function RelatorioContasPagar() {
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [aplicado, setAplicado] = useState<FiltrosTextoContasPagar>(FILTROS_VAZIOS)
  const [rascunho, setRascunho] = useState<FiltrosTextoContasPagar>(FILTROS_VAZIOS)
  const [modalAberto, setModalAberto] = useState(true)
  const [relatorioGerado, setRelatorioGerado] = useState(false)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoContaPagar>('dataVencimento')
  const [direcao, setDirecao] = useState<DirecaoOrdenacao>('ASC')
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const conteudoRef = useRef<HTMLDivElement>(null)

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas, enabled: ehAdmin })

  const isoOuIndefinido = (texto: string): string | undefined =>
    dataValida(texto) ? (dataParaIso(texto) ?? undefined) : undefined

  const filtros: FiltrosRelatorioContasPagar = {
    dataLancamentoInicial: isoOuIndefinido(aplicado.dataLancamentoInicial),
    dataLancamentoFinal: isoOuIndefinido(aplicado.dataLancamentoFinal),
    dataVencimentoInicial: isoOuIndefinido(aplicado.dataVencimentoInicial),
    dataVencimentoFinal: isoOuIndefinido(aplicado.dataVencimentoFinal),
    dataPagamentoInicial: isoOuIndefinido(aplicado.dataPagamentoInicial),
    dataPagamentoFinal: isoOuIndefinido(aplicado.dataPagamentoFinal),
    idsEmpresa: ehAdmin ? aplicado.idsEmpresa : undefined,
    idFornecedor: aplicado.idFornecedor ?? undefined,
    idPlanoContas: aplicado.idPlanoContas || undefined,
    situacao: aplicado.situacao || undefined,
    ordenarPor,
    direcao,
  }

  const podeGerarRascunho =
    periodoPreenchido(rascunho.dataLancamentoInicial, rascunho.dataLancamentoFinal) ||
    periodoPreenchido(rascunho.dataVencimentoInicial, rascunho.dataVencimentoFinal) ||
    periodoPreenchido(rascunho.dataPagamentoInicial, rascunho.dataPagamentoFinal)

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['relatorio-contas-pagar', filtros],
    queryFn: () => gerarRelatorioContasPagar(filtros),
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

  function ordenarPorColuna(coluna: ColunaOrdenacaoContaPagar) {
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
    if (aplicado.dataLancamentoInicial && aplicado.dataLancamentoFinal)
      linhas.push({
        rotulo: 'Período de Lançamento',
        valor: `${aplicado.dataLancamentoInicial} a ${aplicado.dataLancamentoFinal}`,
      })
    if (aplicado.dataVencimentoInicial && aplicado.dataVencimentoFinal)
      linhas.push({
        rotulo: 'Período de Vencimento',
        valor: `${aplicado.dataVencimentoInicial} a ${aplicado.dataVencimentoFinal}`,
      })
    if (aplicado.dataPagamentoInicial && aplicado.dataPagamentoFinal)
      linhas.push({
        rotulo: 'Período de Pagamento',
        valor: `${aplicado.dataPagamentoInicial} a ${aplicado.dataPagamentoFinal}`,
      })
    linhas.push({ rotulo: 'Empresa(s)', valor: textoEmpresasFiltradas() })
    linhas.push({ rotulo: 'Fornecedor', valor: aplicado.nomeFornecedor || 'Todos' })
    linhas.push({ rotulo: 'Plano de Contas', valor: aplicado.idPlanoContas || 'Todos' })
    linhas.push({
      rotulo: 'Situação',
      valor: OPCOES_SITUACAO.find((o) => o.chave === aplicado.situacao)?.rotulo ?? 'Todas',
    })
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
      await gerarPdfCapturaRelatorioContasPagar(conteudoRef.current, eu?.empresa.nome ?? '—')
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
            <h1>Contas a Pagar / Pagas</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.contaspagar.tela" />
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
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        {!relatorioGerado ? (
          <p className="muted">Use o botão Filtros para gerar o relatório.</p>
        ) : isLoading ? (
          <p className="muted">Carregando relatório…</p>
        ) : error || !data ? (
          <p className="erro">
            {error instanceof ApiError ? error.message : 'Não foi possível gerar o relatório.'}
          </p>
        ) : (
          <div ref={conteudoRef} className="relatorio-conteudo">
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

            {/* ⚠️ Cinco números SEPARADOS: Vencido + A vencer = Em aberto, e nunca o Total.
                Agregá-los num só faria o lojista somar duas vezes o que deve. */}
            <p className="section-label">Resumo do Período</p>
            <div className="relatorio-kpis">
              <div className="card relatorio-kpi-card">
                <p className="muted">Total no período</p>
                <strong>{moeda(data.kpis.totalPeriodo)}</strong>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">Em aberto</p>
                <strong>{moeda(data.kpis.emAberto)}</strong>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">Vencido</p>
                <strong className="erro">{moeda(data.kpis.vencido)}</strong>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">A vencer</p>
                <strong>{moeda(data.kpis.aVencer)}</strong>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">Pago no período</p>
                <strong>{moeda(data.kpis.pagoNoPeriodo)}</strong>
              </div>
            </div>

            {data.graficoPorPlanoContas.length > 0 && (
              <div className="card relatorio-grafico-card" style={{ marginTop: 16, minHeight: 'auto' }}>
                <p className="section-label">Para onde vai o dinheiro (por plano de contas)</p>
                <GraficoBarraHorizontal dados={data.graficoPorPlanoContas} rotuloValor="Valor" />
              </div>
            )}

            {data.graficoPorFornecedor.length > 0 && (
              <div className="card relatorio-grafico-card" style={{ marginTop: 16, minHeight: 'auto' }}>
                <p className="section-label">Para quem se deve mais (por fornecedor)</p>
                <GraficoBarraHorizontal
                  dados={data.graficoPorFornecedor}
                  cor="var(--accent-2, var(--accent))"
                  rotuloValor="Valor"
                />
              </div>
            )}

            <p className="section-label" style={{ marginTop: 16 }}>
              Contas ({data.linhas.length})
            </p>
            <div className="relatorio-corpo-fixo grid-altura-fixa">
              <table className="table table-compacta tabela-adensada">
                <thead>
                  <tr>
                    {COLUNAS.map((c) => (
                      <th
                        key={c.chave}
                        style={{ textAlign: c.alinhamento, cursor: 'pointer' }}
                        onClick={() => ordenarPorColuna(c.chave)}
                      >
                        {c.rotulo}
                        {ordenarPor === c.chave ? (direcao === 'ASC' ? ' ▲' : ' ▼') : ''}
                      </th>
                    ))}
                    <th>Situação</th>
                  </tr>
                </thead>
                <tbody>
                  {data.linhas.length === 0 && (
                    <tr>
                      <td colSpan={COLUNAS.length + 1} className="muted">
                        Nenhuma conta encontrada com os filtros informados.
                      </td>
                    </tr>
                  )}
                  {/* ⚠️ O subtotal é INTERCALADO na quebra de empresa, não empilhado no rodapé:
                      com três empresas, três subtotais soltos no fim não dizem a que linhas
                      pertencem. Mesmo desenho do Contas a Receber. Empresa é sempre a ordenação
                      primária no servidor, então a quebra é contígua. */}
                  {data.linhas.map((linha, indice) => {
                    const proxima = data.linhas[indice + 1]
                    const fimDoGrupo = mostrarSubtotais && (!proxima || proxima.idEmpresa !== linha.idEmpresa)
                    const subtotal = fimDoGrupo
                      ? data.subtotaisPorEmpresa.find((s) => s.idEmpresa === linha.idEmpresa)
                      : undefined
                    return (
                      <Fragment key={linha.idContaPagar}>
                        <tr>
                          <td>{linha.nomeEmpresa}</td>
                          <td>{linha.nomeFornecedor}</td>
                          <td>
                            <span className="mono">{linha.idPlanoContas}</span> {linha.descricaoPlanoContas}
                          </td>
                          <td className="mono" style={{ textAlign: 'right' }}>{linha.notaFiscal ?? '—'}</td>
                          <td>{linha.numeroDuplicata ?? '—'}</td>
                          <td>{formatarSoData(linha.dataLancamento)}</td>
                          <td>{formatarSoData(linha.dataVencimento)}</td>
                          <td>{linha.dataPagamento ? formatarSoData(linha.dataPagamento) : '—'}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorPagar)}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorPago)}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorEmAberto)}</td>
                          <td>
                            <span className={linha.situacao === 'VENCIDA' ? 'badge badge-erro' : 'badge'}>
                              {ROTULO_SITUACAO[linha.situacao]}
                            </span>
                            {/* ⚠️ "Documento Pago" e "Data de Pagamento" discordam. O relatório
                                MOSTRA em vez de escolher em silêncio qual das duas está certa. */}
                            {linha.divergente && (
                              <div className="muted" style={{ fontSize: 11 }}>
                                ⚠️ “Documento Pago” não bate com a data de pagamento
                              </div>
                            )}
                          </td>
                        </tr>
                        {subtotal && (
                          <tr className="linha-subtotal">
                            <td colSpan={8}>
                              <strong>Subtotal — {subtotal.nomeEmpresa}</strong>
                            </td>
                            <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorPagar)}</strong></td>
                            <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorPago)}</strong></td>
                            <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorEmAberto)}</strong></td>
                            <td />
                          </tr>
                        )}
                      </Fragment>
                    )
                  })}
                </tbody>
                <tfoot>
                  <tr>
                    <td colSpan={8}>
                      <strong>Total Geral</strong>
                    </td>
                    <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorPagar)}</strong></td>
                    <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorPago)}</strong></td>
                    <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorEmAberto)}</strong></td>
                    <td />
                  </tr>
                </tfoot>
              </table>
            </div>
          </div>
        )}
      </div>

      {modalAberto && (
        <FiltrosContasPagarModal
          valores={rascunho}
          aoMudar={(parcial) => setRascunho((r) => ({ ...r, ...parcial }))}
          ehAdmin={ehAdmin}
          empresas={empresas ?? []}
          podeGerar={podeGerarRascunho}
          primeiraVez={!relatorioGerado}
          aoGerar={handleGerar}
          // ⚠️ Na 1ª vez "Voltar" SAI da tela — e com navigate(-1), nunca navigate("/"), que
          // empilharia histórico (correção de auditoria de 2026-08-22, item 17).
          aoFechar={relatorioGerado ? () => setModalAberto(false) : () => navigate(-1)}
        />
      )}
    </div>
  )
}
