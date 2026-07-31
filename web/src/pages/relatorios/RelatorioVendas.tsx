import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import AjudaDaTela from '../../components/AjudaDaTela'
import EmpresaMultiSelect from '../../components/EmpresaMultiSelect'
import { IconeRelatorio } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { hojeISO } from '../../lib/datas'
import { useEu } from '../../lib/eu'
import { listarEmpresas } from '../../lib/empresas'
import { dataParaIso, dataValida, formatarMoeda, isoParaData, mascararData } from '../../lib/masks'
import type { Funcionario } from '../../lib/funcionarios'
import {
  gerarRelatorioVendas,
  rotulosPorCarteira,
  type FiltrosRelatorioVendas,
  type LinhaAgrupadaTotalizador,
  type PontoGrafico,
  type TotalizarPor,
} from '../../lib/relatorioVendas'
import { gerarPdfCapturaRelatorioVendas } from '../../lib/relatorioVendasCaptura'
import PesquisaVendedorModal from '../pdv/PesquisaVendedorModal'
import DrilldownTotalizadorModal from './DrilldownTotalizadorModal'

type PeriodoPreset = 'PERSONALIZADO' | 'MES_ATUAL' | 'MES_ANTERIOR' | 'ULTIMOS_3_MESES' | 'ULTIMOS_6_MESES' | 'ULTIMOS_12_MESES'

const PERIODOS: Array<{ chave: PeriodoPreset; rotulo: string }> = [
  { chave: 'PERSONALIZADO', rotulo: 'Personalizado' },
  { chave: 'MES_ATUAL', rotulo: 'Mês Atual' },
  { chave: 'MES_ANTERIOR', rotulo: 'Mês Anterior' },
  { chave: 'ULTIMOS_3_MESES', rotulo: 'Últimos 3 Meses' },
  { chave: 'ULTIMOS_6_MESES', rotulo: 'Últimos 6 Meses' },
  { chave: 'ULTIMOS_12_MESES', rotulo: 'Últimos 12 Meses' },
]

const TOTALIZADORES: Array<{ chave: TotalizarPor; rotulo: string }> = [
  { chave: 'NAO_TOTALIZAR', rotulo: 'Não Totalizar' },
  { chave: 'DATA_VENDA', rotulo: 'Data da Venda' },
  { chave: 'CLIENTE', rotulo: 'Cliente' },
  { chave: 'VENDEDOR', rotulo: 'Vendedor' },
  { chave: 'OPERADOR_CAIXA', rotulo: 'Operador de Caixa' },
  { chave: 'EMPRESA', rotulo: 'Empresa' },
]

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function percentual(v: number): string {
  return `${formatarMoeda(v)}%`
}

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

function isoDeData(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function primeiroDiaDoMes(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1)
}

function ultimoDiaDoMes(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth() + 1, 0)
}

/** Presets calculam a data em cima do relógio local — mesmo espírito de `hojeISO()`, nunca UTC. */
function calcularPeriodo(preset: PeriodoPreset): { inicio: string; fim: string } {
  const hoje = new Date()
  if (preset === 'MES_ANTERIOR') {
    const mesAnterior = new Date(hoje.getFullYear(), hoje.getMonth() - 1, 1)
    return { inicio: isoDeData(primeiroDiaDoMes(mesAnterior)), fim: isoDeData(ultimoDiaDoMes(mesAnterior)) }
  }
  if (preset === 'ULTIMOS_3_MESES') return { inicio: isoDeData(new Date(hoje.getFullYear(), hoje.getMonth() - 3, hoje.getDate())), fim: hojeISO() }
  if (preset === 'ULTIMOS_6_MESES') return { inicio: isoDeData(new Date(hoje.getFullYear(), hoje.getMonth() - 6, hoje.getDate())), fim: hojeISO() }
  if (preset === 'ULTIMOS_12_MESES') return { inicio: isoDeData(new Date(hoje.getFullYear(), hoje.getMonth() - 12, hoje.getDate())), fim: hojeISO() }
  // MES_ATUAL e fallback
  return { inicio: isoDeData(primeiroDiaDoMes(hoje)), fim: hojeISO() }
}

function formatarRotuloDia(rotulo: string): string {
  const [, m, d] = rotulo.split('-')
  return `${d}/${m}`
}

const estiloTooltip = { background: 'var(--surface)', border: '1px solid var(--line)', borderRadius: 8, fontSize: 13 }
const estiloEixo = { fontSize: 12, fill: 'var(--ink-muted)' }

function GraficoLinha({ dados }: { dados: PontoGrafico[] }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <LineChart data={dados} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="0" stroke="var(--line)" vertical={false} />
        <XAxis
          dataKey="rotulo"
          tickFormatter={formatarRotuloDia}
          interval={Math.max(0, Math.ceil(dados.length / 8) - 1)}
          tick={estiloEixo}
          axisLine={{ stroke: 'var(--line)' }}
          tickLine={false}
        />
        <YAxis tick={estiloEixo} axisLine={false} tickLine={false} width={70} tickFormatter={(v) => moeda(v)} />
        <Tooltip
          contentStyle={estiloTooltip}
          labelFormatter={(rotulo) => formatarRotuloDia(String(rotulo ?? ''))}
          formatter={(valor) => [moeda(Number(valor)), 'Valor vendido']}
        />
        <Line type="monotone" dataKey="valor" stroke="var(--accent)" strokeWidth={2} dot={false} activeDot={{ r: 4 }} />
      </LineChart>
    </ResponsiveContainer>
  )
}

function GraficoBarraHorizontal({ dados }: { dados: PontoGrafico[] }) {
  return (
    <ResponsiveContainer width="100%" height={Math.max(160, dados.length * 32)}>
      <BarChart data={dados} layout="vertical" margin={{ top: 4, right: 24, left: 0, bottom: 4 }}>
        <CartesianGrid strokeDasharray="0" stroke="var(--line)" horizontal={false} />
        <XAxis type="number" tick={estiloEixo} axisLine={{ stroke: 'var(--line)' }} tickLine={false} tickFormatter={(v) => moeda(v)} />
        <YAxis type="category" dataKey="rotulo" tick={estiloEixo} axisLine={false} tickLine={false} width={140} />
        <Tooltip contentStyle={estiloTooltip} formatter={(valor) => [moeda(Number(valor)), 'Valor']} />
        <Bar dataKey="valor" fill="var(--accent)" radius={[0, 4, 4, 0]} maxBarSize={22} />
      </BarChart>
    </ResponsiveContainer>
  )
}

function GraficoBarraColuna({ dados }: { dados: PontoGrafico[] }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <BarChart data={dados} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="0" stroke="var(--line)" vertical={false} />
        <XAxis dataKey="rotulo" tick={estiloEixo} axisLine={{ stroke: 'var(--line)' }} tickLine={false} />
        <YAxis tick={estiloEixo} axisLine={false} tickLine={false} width={70} tickFormatter={(v) => moeda(v)} />
        <Tooltip contentStyle={estiloTooltip} formatter={(valor) => [moeda(Number(valor)), 'Valor vendido']} />
        <Bar dataKey="valor" fill="var(--accent)" radius={[4, 4, 0, 0]} maxBarSize={24} />
      </BarChart>
    </ResponsiveContainer>
  )
}

/**
 * Relatório de Vendas (docs/telas/relatorio-vendas.md) — 1ª tela do grupo Relatórios, define o
 * padrão de tela de filtro de relatório: filtros → KPIs → composição do faturamento → gráficos
 * → grid totalizável com drill-down. Qualquer papel; empresa segue o mesmo padrão de Pesquisa
 * de Vendas (ADMIN filtra livremente — aqui em multi-select —, OPERADOR fixo na própria empresa).
 */
export default function RelatorioVendas() {
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [periodoPreset, setPeriodoPreset] = useState<PeriodoPreset>('MES_ATUAL')
  const periodoInicial = calcularPeriodo('MES_ATUAL')
  const [dataInicialTexto, setDataInicialTexto] = useState(isoParaData(periodoInicial.inicio))
  const [dataFinalTexto, setDataFinalTexto] = useState(isoParaData(periodoInicial.fim))
  const [idsEmpresa, setIdsEmpresa] = useState<number[]>([])
  const [vendedor, setVendedor] = useState<Funcionario | null>(null)
  const [totalizarPor, setTotalizarPor] = useState<TotalizarPor>('NAO_TOTALIZAR')
  const [mostrarBuscaVendedor, setMostrarBuscaVendedor] = useState(false)
  const [grupoSelecionado, setGrupoSelecionado] = useState<LinhaAgrupadaTotalizador | null>(null)
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const topoRef = useRef<HTMLDivElement>(null)
  const gridRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (periodoPreset === 'PERSONALIZADO') return
    const { inicio, fim } = calcularPeriodo(periodoPreset)
    setDataInicialTexto(isoParaData(inicio))
    setDataFinalTexto(isoParaData(fim))
  }, [periodoPreset])

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas, enabled: ehAdmin })

  const dataInicialIso = dataValida(dataInicialTexto) ? dataParaIso(dataInicialTexto) : null
  const dataFinalIso = dataValida(dataFinalTexto) ? dataParaIso(dataFinalTexto) : null
  const podeBuscar = !!dataInicialIso && !!dataFinalIso

  const filtros: FiltrosRelatorioVendas = {
    dataInicial: dataInicialIso ?? '',
    dataFinal: dataFinalIso ?? '',
    idsEmpresa: ehAdmin ? idsEmpresa : undefined,
    idFuncionario: vendedor?.idFuncionario,
    totalizarPor,
  }

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['relatorio-vendas', filtros],
    queryFn: () => gerarRelatorioVendas(filtros),
    enabled: podeBuscar,
    placeholderData: (anterior) => anterior,
  })

  const handleGerarPdf = async () => {
    if (!topoRef.current || !gridRef.current) return
    setGerandoPdf(true)
    try {
      const corFundo = getComputedStyle(document.documentElement).getPropertyValue('--ground').trim() || '#12181a'
      await gerarPdfCapturaRelatorioVendas(topoRef.current, gridRef.current, corFundo)
    } finally {
      setGerandoPdf(false)
    }
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRelatorio size={34} />
            <h1>Relatório de Vendas</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.vendas.tela" />
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

        <div className="card filtros-bar">
          <select value={periodoPreset} onChange={(e) => setPeriodoPreset(e.target.value as PeriodoPreset)} aria-label="Período de vendas">
            {PERIODOS.map((p) => (
              <option key={p.chave} value={p.chave}>
                {p.rotulo}
              </option>
            ))}
          </select>
          <input
            className="mono"
            placeholder="dd/mm/aaaa"
            value={dataInicialTexto}
            onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data inicial"
            disabled={periodoPreset !== 'PERSONALIZADO'}
            style={{ maxWidth: 130 }}
          />
          <input
            className="mono"
            placeholder="dd/mm/aaaa"
            value={dataFinalTexto}
            onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data final"
            disabled={periodoPreset !== 'PERSONALIZADO'}
            style={{ maxWidth: 130 }}
          />
          {ehAdmin && <EmpresaMultiSelect empresas={empresas ?? []} selecionadas={idsEmpresa} aoAlterar={setIdsEmpresa} />}
          <button type="button" className="btn ghost" onClick={() => setMostrarBuscaVendedor(true)}>
            {vendedor ? vendedor.nome : 'Vendedor…'}
          </button>
          {vendedor && (
            <button type="button" className="btn ghost" onClick={() => setVendedor(null)} aria-label="Limpar vendedor">
              ✕
            </button>
          )}
          <select value={totalizarPor} onChange={(e) => setTotalizarPor(e.target.value as TotalizarPor)} aria-label="Totalizar por">
            {TOTALIZADORES.map((t) => (
              <option key={t.chave} value={t.chave}>
                {t.rotulo}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="lista-corpo">
        {!podeBuscar ? (
          <p className="muted">Informe um período válido.</p>
        ) : isLoading ? (
          <p className="muted">Carregando relatório…</p>
        ) : error || !data ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível gerar o relatório.'}</p>
        ) : (
          <div>
          <div ref={topoRef}>
            {isFetching && <p className="muted">Atualizando…</p>}

            <p className="section-label">Resumo do Período</p>
            <div className="relatorio-kpis-grid">
              <div className="relatorio-kpi-card">
                <p className="card-title">Ticket Médio</p>
                <p className="relatorio-kpi-valor">{moeda(data.kpis.ticketMedioValor)}</p>
                <p className="muted">Nº Vendas: {data.kpis.ticketMedioNVendas}</p>
              </div>
              <div className="relatorio-kpi-card">
                <p className="card-title">% Médio de Desconto</p>
                <p className="relatorio-kpi-valor">{percentual(data.kpis.percentualMedioDesconto)}</p>
                <p className="muted">Valor Desconto: {moeda(data.kpis.valorDesconto)}</p>
              </div>
              <div className="relatorio-kpi-card">
                <p className="card-title">Devoluções</p>
                <p className="relatorio-kpi-valor">{percentual(data.kpis.percentualDevolucao)}</p>
                <p className="muted">Valor Devolução: {moeda(data.kpis.valorDevolucao)}</p>
              </div>
              <div className="relatorio-kpi-card">
                <p className="card-title">Itens Vendidos / Média</p>
                <p className="relatorio-kpi-valor">{formatarMoeda(data.kpis.itensVendidos)}</p>
                <p className="muted">Média por venda: {formatarMoeda(data.kpis.mediaItensPorVenda)}</p>
              </div>
            </div>

            <p className="section-label" style={{ marginTop: 24 }}>
              Composição do Faturamento
            </p>
            <div className="relatorio-composicao-grid">
              <div className="relatorio-kpi-card relatorio-composicao-card">
                <p className="card-title">Valor Bruto</p>
                <p className="relatorio-kpi-valor">{moeda(data.composicaoFaturamento.valorBruto)}</p>
              </div>
              <div className="relatorio-kpi-card relatorio-composicao-card">
                <p className="card-title">Descontos</p>
                <p className="relatorio-kpi-valor">{moeda(data.composicaoFaturamento.descontos)}</p>
              </div>
              <div className="relatorio-kpi-card relatorio-composicao-card">
                <p className="card-title">Acréscimos</p>
                <p className="relatorio-kpi-valor">{moeda(data.composicaoFaturamento.acrescimos)}</p>
              </div>
              <div className="relatorio-kpi-card relatorio-composicao-card">
                <p className="card-title">Devoluções</p>
                <p className="relatorio-kpi-valor">{moeda(data.composicaoFaturamento.devolucoes)}</p>
              </div>
              <div className="relatorio-kpi-card relatorio-composicao-card destaque">
                <p className="card-title">Venda Líquida</p>
                <p className="relatorio-kpi-valor">{moeda(data.composicaoFaturamento.vendaLiquida)}</p>
              </div>
            </div>

            <p className="section-label" style={{ marginTop: 24 }}>
              Gráficos
            </p>
            <div className="relatorio-graficos-grid">
              <div className="card relatorio-grafico-card">
                <p className="section-label">Valor Vendido por Dia</p>
                <GraficoLinha dados={data.graficos.porDia} />
              </div>
              <div className="card relatorio-grafico-card">
                <p className="section-label">Top 10 Marcas</p>
                <GraficoBarraHorizontal dados={data.graficos.topMarcas} />
              </div>
              <div className="card relatorio-grafico-card">
                <p className="section-label">Top 10 Vendedores</p>
                <GraficoBarraHorizontal dados={data.graficos.topVendedores} />
              </div>
              <div className="card relatorio-grafico-card">
                <p className="section-label">Top 10 Clientes</p>
                <GraficoBarraHorizontal dados={data.graficos.topClientes} />
              </div>
              <div className="card relatorio-grafico-card">
                <p className="section-label">Recebimentos por Tipo de Carteira</p>
                <GraficoBarraHorizontal dados={rotulosPorCarteira(data.graficos.porCarteira)} />
              </div>
              <div className="card relatorio-grafico-card">
                <p className="section-label">Por Hora da Venda</p>
                <GraficoBarraColuna dados={data.graficos.porHora} />
              </div>
              <div className="card relatorio-grafico-card" style={{ gridColumn: '1 / -1' }}>
                <p className="section-label">Por Dia da Semana</p>
                <GraficoBarraColuna dados={data.graficos.porDiaSemana} />
              </div>
            </div>
          </div>

          <div ref={gridRef}>
            <p className="section-label" style={{ marginTop: 24 }}>
              {data.totalizador.tipo === 'ANALITICO' ? 'Vendas do Período' : 'Totalizado por ' + TOTALIZADORES.find((t) => t.chave === totalizarPor)?.rotulo}
            </p>
            <div className="card table-wrap">
              {data.totalizador.tipo === 'ANALITICO' ? (
                (data.totalizador.linhasAnaliticas ?? []).length === 0 ? (
                  <p className="muted">Nenhuma venda encontrada para os filtros informados.</p>
                ) : (
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        <th>Empresa</th>
                        <th>Nº Venda</th>
                        <th>Data/Hora</th>
                        <th>Cliente</th>
                        <th>Vendedor</th>
                        <th>Operador</th>
                        <th>Qtd Produtos</th>
                        <th>Valor Venda</th>
                        <th>Acréscimos</th>
                        <th>Descontos</th>
                        <th>Valor Líquido</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.totalizador.linhasAnaliticas!.map((i) => (
                        <tr key={i.idVenda}>
                          <td>{i.nomeEmpresa}</td>
                          <td className="mono">{i.idVenda}</td>
                          <td>{formatarDataHora(i.dataHoraVenda)}</td>
                          <td>{i.nomeCliente ?? '—'}</td>
                          <td>{i.nomeVendedor ?? '—'}</td>
                          <td>{i.nomeOperador ?? '—'}</td>
                          <td className="mono">{i.qtdProdutos}</td>
                          <td className="mono">{moeda(i.valorVenda)}</td>
                          <td className="mono">{moeda(i.acrescimos)}</td>
                          <td className="mono">{moeda(i.descontos)}</td>
                          <td className="mono">{moeda(i.valorLiquido)}</td>
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr>
                        <td colSpan={6}>
                          <strong>Total ({data.totalizador.linhasAnaliticas!.length} vendas)</strong>
                        </td>
                        <td className="mono">
                          {formatarMoeda(data.totalizador.linhasAnaliticas!.reduce((s, i) => s + i.qtdProdutos, 0))}
                        </td>
                        <td className="mono">{moeda(data.totalizador.linhasAnaliticas!.reduce((s, i) => s + i.valorVenda, 0))}</td>
                        <td className="mono">{moeda(data.totalizador.linhasAnaliticas!.reduce((s, i) => s + i.acrescimos, 0))}</td>
                        <td className="mono">{moeda(data.totalizador.linhasAnaliticas!.reduce((s, i) => s + i.descontos, 0))}</td>
                        <td className="mono">{moeda(data.totalizador.linhasAnaliticas!.reduce((s, i) => s + i.valorLiquido, 0))}</td>
                      </tr>
                    </tfoot>
                  </table>
                )
              ) : (data.totalizador.linhasAgrupadas ?? []).length === 0 ? (
                <p className="muted">Nenhuma venda encontrada para os filtros informados.</p>
              ) : (
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Nome do Totalizador</th>
                      <th>Nº de Vendas</th>
                      <th>Valor da Venda</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.totalizador.linhasAgrupadas!.map((g) => (
                      <tr key={g.chave} tabIndex={0} onClick={() => setGrupoSelecionado(g)}
                          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setGrupoSelecionado(g) } }}>
                        <td>{g.nome}</td>
                        <td className="mono">{g.nVendas}</td>
                        <td className="mono">{moeda(g.valorVenda)}</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td>
                        <strong>Total ({data.totalizador.linhasAgrupadas!.length})</strong>
                      </td>
                      <td className="mono">{data.totalizador.linhasAgrupadas!.reduce((s, g) => s + g.nVendas, 0)}</td>
                      <td className="mono">{moeda(data.totalizador.linhasAgrupadas!.reduce((s, g) => s + g.valorVenda, 0))}</td>
                    </tr>
                  </tfoot>
                </table>
              )}
            </div>
          </div>
          </div>
        )}
      </div>

      {mostrarBuscaVendedor && (
        <PesquisaVendedorModal
          aoFechar={() => setMostrarBuscaVendedor(false)}
          aoSelecionar={(f) => {
            setVendedor(f)
            setMostrarBuscaVendedor(false)
          }}
        />
      )}
      {grupoSelecionado && (
        <DrilldownTotalizadorModal
          filtros={filtros}
          chave={grupoSelecionado.chave}
          nomeGrupo={grupoSelecionado.nome}
          aoFechar={() => setGrupoSelecionado(null)}
        />
      )}
    </div>
  )
}
