import { useQuery } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeRelatorio } from '../../components/Icones'
import { listarCategoriasProduto } from '../../lib/categoriasProduto'
import { ApiError } from '../../lib/api'
import { listarEmpresas } from '../../lib/empresas'
import { useEu } from '../../lib/eu'
import { dataParaIso, dataValida, formatarMoeda, formatarQuantidade } from '../../lib/masks'
import { listarMarcas } from '../../lib/produtos'
import {
  gerarRelatorioMovimentacaoProdutos,
  type FiltrosRelatorioMovimentacaoProdutos,
  type LinhaAnaliticaMovimentacao,
  type LinhaKardex,
  type LinhaSinteticaMovimentacao,
} from '../../lib/relatorioMovimentacaoProdutos'
import { gerarPdfCapturaRelatorioMovimentacaoProdutos } from '../../lib/relatorioMovimentacaoProdutosCaptura'
import FiltrosMovimentacaoProdutosModal, {
  FILTROS_MOVIMENTACAO_VAZIOS,
  OPCOES_MODELO,
  ROTULO_TIPO_MOVIMENTO,
  type FiltrosMovimentacaoTexto,
} from './FiltrosMovimentacaoProdutosModal'
import PesquisaVariacaoModal from './PesquisaVariacaoModal'

type Direcao = 'ASC' | 'DESC'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

function compararValores(a: unknown, b: unknown): number {
  if (typeof a === 'string' && typeof b === 'string') return a.localeCompare(b)
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return 0
}

function ordenar<T extends Record<string, unknown>>(linhas: T[], chave: string, direcao: Direcao): T[] {
  const copia = [...linhas]
  copia.sort((a, b) => {
    const cmp = compararValores(a[chave] ?? '', b[chave] ?? '')
    return direcao === 'ASC' ? cmp : -cmp
  })
  return copia
}

const estiloTooltipGrafico = { background: 'var(--surface)', border: '1px solid var(--line)', borderRadius: 8, fontSize: 13 }
const estiloEixoGrafico = { fontSize: 12, fill: 'var(--ink-muted)' }

function GraficoBarraColuna({ dados, cor = 'var(--accent)' }: { dados: { rotulo: string; valor: number }[]; cor?: string }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <BarChart data={dados} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="0" stroke="var(--line)" vertical={false} />
        <XAxis dataKey="rotulo" tick={estiloEixoGrafico} axisLine={{ stroke: 'var(--line)' }} tickLine={false} />
        <YAxis tick={estiloEixoGrafico} axisLine={false} tickLine={false} width={70} tickFormatter={(v) => moeda(v)} />
        <Tooltip contentStyle={estiloTooltipGrafico} formatter={(valor) => [moeda(Number(valor)), 'Valor']} />
        <Bar dataKey="valor" fill={cor} radius={[4, 4, 0, 0]} maxBarSize={28} />
      </BarChart>
    </ResponsiveContainer>
  )
}

function GraficoLinha({ dados, cor = 'var(--accent)' }: { dados: { rotulo: string; valor: number }[]; cor?: string }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <LineChart data={dados} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="0" stroke="var(--line)" vertical={false} />
        <XAxis dataKey="rotulo" interval={Math.max(0, Math.ceil(dados.length / 8) - 1)} tick={estiloEixoGrafico} axisLine={{ stroke: 'var(--line)' }} tickLine={false} />
        <YAxis tick={estiloEixoGrafico} axisLine={false} tickLine={false} width={70} tickFormatter={(v) => moeda(v)} />
        <Tooltip contentStyle={estiloTooltipGrafico} formatter={(valor) => [moeda(Number(valor)), 'Valor movimentado']} />
        <Line type="monotone" dataKey="valor" stroke={cor} strokeWidth={2} dot={false} activeDot={{ r: 4 }} />
      </LineChart>
    </ResponsiveContainer>
  )
}

function GraficoBarraHorizontal({ dados, cor = 'var(--danger)' }: { dados: { rotulo: string; valor: number }[]; cor?: string }) {
  return (
    <ResponsiveContainer width="100%" height={Math.max(160, dados.length * 32)}>
      <BarChart data={dados} layout="vertical" margin={{ top: 4, right: 24, left: 0, bottom: 4 }}>
        <CartesianGrid strokeDasharray="0" stroke="var(--line)" horizontal={false} />
        <XAxis type="number" tick={estiloEixoGrafico} axisLine={{ stroke: 'var(--line)' }} tickLine={false} tickFormatter={(v) => moeda(v)} />
        <YAxis type="category" dataKey="rotulo" tick={estiloEixoGrafico} axisLine={false} tickLine={false} width={160} />
        <Tooltip contentStyle={estiloTooltipGrafico} formatter={(valor) => [moeda(Number(valor)), 'Perda a custo']} />
        <Bar dataKey="valor" fill={cor} radius={[0, 4, 4, 0]} maxBarSize={22} />
      </BarChart>
    </ResponsiveContainer>
  )
}

const COLUNAS_ANALITICO: Array<{ chave: string; rotulo: string; alinhamento?: 'right' }> = [
  { chave: 'nomeEmpresa', rotulo: 'Empresa' },
  { chave: 'dataMovimento', rotulo: 'Data/Hora' },
  { chave: 'tipoMovimento', rotulo: 'Tipo' },
  { chave: 'sku', rotulo: 'SKU' },
  { chave: 'descricaoProduto', rotulo: 'Produto' },
  { chave: 'variacao', rotulo: 'Variação' },
  { chave: 'entrada', rotulo: 'Entrada', alinhamento: 'right' },
  { chave: 'saida', rotulo: 'Saída', alinhamento: 'right' },
  { chave: 'custoUnitario', rotulo: 'Custo Unit.', alinhamento: 'right' },
  { chave: 'valorMovimentado', rotulo: 'Valor', alinhamento: 'right' },
  { chave: 'documento', rotulo: 'Documento' },
  { chave: 'nomeFuncionario', rotulo: 'Operador' },
]

const COLUNAS_KARDEX: Array<{ chave: string; rotulo: string; alinhamento?: 'right' }> = [
  { chave: 'dataMovimento', rotulo: 'Data/Hora' },
  { chave: 'tipoMovimento', rotulo: 'Tipo' },
  { chave: 'documento', rotulo: 'Documento' },
  { chave: 'nomeFuncionario', rotulo: 'Operador' },
  { chave: 'entrada', rotulo: 'Entrada', alinhamento: 'right' },
  { chave: 'saida', rotulo: 'Saída', alinhamento: 'right' },
  { chave: 'saldoApos', rotulo: 'Saldo Após', alinhamento: 'right' },
]

/**
 * Relatório de Movimentação de Produtos / Kardex (docs/telas/relatorio-movimentacao-produtos.md,
 * 2026-08-04) — ledger de estoque a partir de `produto_movimento_mestre`/`_detalhe`, diferente do
 * Relatório de Estoque (fotografia do saldo atual). 3 modelos: Analítico (uma linha por
 * movimento), Kardex por Produto (uma variação + uma empresa, saldo corrido), Sintético (totais
 * por tipo). Mesmo padrão de tela do Relatório de Vendas — filtros em popup, KPIs/gráficos
 * (Analítico/Sintético só), grid com `.grid-altura-fixa`, PDF em 2 capturas (topo sempre numa
 * página, grid sempre começando em página nova).
 */
export default function RelatorioMovimentacaoProdutos() {
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [aplicado, setAplicado] = useState<FiltrosMovimentacaoTexto>(FILTROS_MOVIMENTACAO_VAZIOS)
  const [rascunho, setRascunho] = useState<FiltrosMovimentacaoTexto>(FILTROS_MOVIMENTACAO_VAZIOS)
  const [modalAberto, setModalAberto] = useState(true)
  const [mostrarBuscaVariacao, setMostrarBuscaVariacao] = useState(false)
  const [relatorioGerado, setRelatorioGerado] = useState(false)
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const topoRef = useRef<HTMLDivElement>(null)
  const gridRef = useRef<HTMLDivElement>(null)

  const [ordenarPorAnalitico, setOrdenarPorAnalitico] = useState('dataMovimento')
  const [direcaoAnalitico, setDirecaoAnalitico] = useState<Direcao>('DESC')
  const [ordenarPorKardex, setOrdenarPorKardex] = useState('dataMovimento')
  const [direcaoKardex, setDirecaoKardex] = useState<Direcao>('ASC')
  const [ordenarPorSintetico, setOrdenarPorSintetico] = useState('tipoMovimento')
  const [direcaoSintetico, setDirecaoSintetico] = useState<Direcao>('ASC')

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas, enabled: ehAdmin })
  const { data: marcas } = useQuery({ queryKey: ['produtos-marcas'], queryFn: listarMarcas })
  const { data: categorias } = useQuery({ queryKey: ['categorias-produto'], queryFn: listarCategoriasProduto })

  const dataInicialIso = dataValida(aplicado.dataInicial) ? dataParaIso(aplicado.dataInicial) : null
  const dataFinalIso = dataValida(aplicado.dataFinal) ? dataParaIso(aplicado.dataFinal) : null

  const filtros: FiltrosRelatorioMovimentacaoProdutos = {
    modelo: aplicado.modelo,
    dataInicial: dataInicialIso ?? '',
    dataFinal: dataFinalIso ?? '',
    idsEmpresa: ehAdmin ? aplicado.idsEmpresa : undefined,
    tipos: aplicado.tipos,
    marcas: aplicado.marcas,
    idsCategoria: aplicado.idsCategoria,
    idVariacaoKardex: aplicado.variacaoKardex?.idVariacao,
    idEmpresaKardex: ehAdmin ? (aplicado.idEmpresaKardex ?? undefined) : undefined,
  }

  const periodoPreenchido = dataValida(rascunho.dataInicial) && dataValida(rascunho.dataFinal)
  const podeGerarRascunho =
    rascunho.modelo === 'KARDEX'
      ? periodoPreenchido && !!rascunho.variacaoKardex && (!ehAdmin || !!rascunho.idEmpresaKardex)
      : periodoPreenchido

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['relatorio-movimentacao-produtos', filtros],
    queryFn: () => gerarRelatorioMovimentacaoProdutos(filtros),
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

  function ordenarPorColuna(chave: string, atual: string, setChave: (c: string) => void, direcao: Direcao, setDirecao: (d: Direcao) => void) {
    if (chave === atual) {
      setDirecao(direcao === 'ASC' ? 'DESC' : 'ASC')
    } else {
      setChave(chave)
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

  function textoTiposFiltrados(): string {
    return aplicado.tipos.length === 0 ? 'Todos os tipos' : aplicado.tipos.map((t) => ROTULO_TIPO_MOVIMENTO[t]).join(', ')
  }

  function montarDescricaoFiltros(): Array<{ rotulo: string; valor: string }> {
    const base = [
      { rotulo: 'Modelo', valor: OPCOES_MODELO.find((o) => o.chave === aplicado.modelo)?.rotulo ?? '—' },
      { rotulo: 'Período', valor: `${aplicado.dataInicial} a ${aplicado.dataFinal}` },
    ]
    if (aplicado.modelo === 'KARDEX') {
      return [
        ...base,
        // ⚠️ O par `nomeFantasia ?? razaoSocial`, como em `textoEmpresasFiltradas()` acima e em todo
        // o resto do projeto: `nomeFantasia` é OPCIONAL (o signup cria a empresa só com razão
        // social). Sem o segundo termo, o cabeçalho do Kardex dizia "Empresa: —" para uma empresa
        // que ESTÁ filtrada — e como o PDF é captura visual, o arquivo do contador saía afirmando
        // isso, com duas empresas gerando dois relatórios indistinguíveis pelo cabeçalho.
        {
          rotulo: 'Empresa',
          valor: ehAdmin
            ? (() => {
                const e = (empresas ?? []).find((x) => x.idEmpresa === aplicado.idEmpresaKardex)
                return e ? (e.nomeFantasia ?? e.razaoSocial) : '—'
              })()
            : (eu?.empresa.nome ?? '—'),
        },
        { rotulo: 'Produto', valor: aplicado.variacaoKardex ? `${aplicado.variacaoKardex.sku} — ${aplicado.variacaoKardex.descricaoProduto}` : '—' },
      ]
    }
    return [
      ...base,
      { rotulo: 'Empresa(s)', valor: textoEmpresasFiltradas() },
      { rotulo: 'Tipo(s) de Movimento', valor: textoTiposFiltrados() },
    ]
  }

  function aguardarPintura(): Promise<void> {
    return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  }

  const handleGerarPdf = async () => {
    if (!topoRef.current || !gridRef.current) return
    setGerandoPdf(true)
    try {
      await aguardarPintura()
      await gerarPdfCapturaRelatorioMovimentacaoProdutos(topoRef.current, gridRef.current, eu?.empresa.nome ?? '—')
    } finally {
      setGerandoPdf(false)
    }
  }

  const linhaComVariacao = (l: LinhaAnaliticaMovimentacao) => ({
    ...l,
    variacao: [l.variacaoCor, l.variacaoTamanho].filter(Boolean).join(' · ') || null,
  })

  const linhasAnalitico = ordenar(
    (data?.linhasAnalitico ?? []).map(linhaComVariacao) as unknown as Array<Record<string, unknown>>,
    ordenarPorAnalitico,
    direcaoAnalitico,
  ) as unknown as Array<LinhaAnaliticaMovimentacao & { variacao: string | null }>

  const linhasKardex = ordenar(
    (data?.linhasKardex ?? []) as unknown as Array<Record<string, unknown>>,
    ordenarPorKardex,
    direcaoKardex,
  ) as unknown as LinhaKardex[]

  const linhasSintetico = ordenar(
    (data?.linhasSintetico ?? []) as unknown as Array<Record<string, unknown>>,
    ordenarPorSintetico,
    direcaoSintetico,
  ) as unknown as LinhaSinteticaMovimentacao[]

  function setaOrdenacao(ativa: boolean, direcao: Direcao) {
    return <span className={`th-seta ${ativa ? 'th-seta-ativa' : ''}`}>{ativa ? (direcao === 'ASC' ? '▲' : '▼') : '⇅'}</span>
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRelatorio size={34} />
            <h1>Movimentação de Produtos</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.movimentacaoprodutos.tela" />
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
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível gerar o relatório.'}</p>
        ) : (
          <div>
            <div ref={topoRef}>
              {isFetching && (
              <p className="muted" data-sem-impressao>
                Atualizando…
              </p>
            )}

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

              {data.modelo === 'KARDEX' && data.cabecalhoKardex ? (
                <>
                  <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>
                    {data.cabecalhoKardex.sku} — {data.cabecalhoKardex.descricaoProduto}
                    {data.cabecalhoKardex.variacaoCor || data.cabecalhoKardex.variacaoTamanho
                      ? ` (${[data.cabecalhoKardex.variacaoCor, data.cabecalhoKardex.variacaoTamanho].filter(Boolean).join(' · ')})`
                      : ''}
                  </p>
                  <div className="relatorio-kpis-grid">
                    <div className="relatorio-kpi-card">
                      <p className="card-title">Empresa</p>
                      <p className="relatorio-kpi-valor" style={{ fontSize: 16 }}>{data.cabecalhoKardex.nomeEmpresa}</p>
                    </div>
                    <div className="relatorio-kpi-card">
                      <p className="card-title">Saldo Inicial</p>
                      <p className="relatorio-kpi-valor cor-accent">{formatarQuantidade(data.cabecalhoKardex.saldoInicial, true)}</p>
                    </div>
                    <div className="relatorio-kpi-card">
                      <p className="card-title">Saldo Final</p>
                      <p className="relatorio-kpi-valor cor-accent">{formatarQuantidade(data.cabecalhoKardex.saldoFinal, true)}</p>
                    </div>
                  </div>
                </>
              ) : (
                data.kpis && (
                  <>
                    <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>
                      Resumo do Período (só movimentação física — exclui Reserva/Liberação de Reserva)
                    </p>
                    <div className="relatorio-kpis-grid">
                      <div className="relatorio-kpi-card">
                        <p className="card-title">Entrada Física</p>
                        <p className="relatorio-kpi-valor cor-sucesso">{moeda(data.kpis.valorEntradaFisica)}</p>
                        <p className="muted">Qtd: {formatarQuantidade(data.kpis.qtdEntradaFisica, true)}</p>
                      </div>
                      <div className="relatorio-kpi-card">
                        <p className="card-title">Saída Física</p>
                        <p className="relatorio-kpi-valor cor-danger">{moeda(data.kpis.valorSaidaFisica)}</p>
                        <p className="muted">Qtd: {formatarQuantidade(data.kpis.qtdSaidaFisica, true)}</p>
                      </div>
                      <div className="relatorio-kpi-card">
                        <p className="card-title">Saldo Líquido Físico</p>
                        <p className={`relatorio-kpi-valor ${data.kpis.saldoLiquidoFisico >= 0 ? 'cor-sucesso' : 'cor-danger'}`}>
                          {moeda(data.kpis.saldoLiquidoFisico)}
                        </p>
                      </div>
                    </div>

                    {data.graficos && (
                      <>
                        <p className="section-label" style={{ marginTop: 24 }}>
                          Gráficos
                        </p>
                        <div className="relatorio-graficos-grid">
                          <div className="card relatorio-grafico-card">
                            <p className="section-label">Movimentação por Tipo</p>
                            <GraficoBarraColuna
                              dados={data.graficos.porTipo.map((p) => ({ rotulo: ROTULO_TIPO_MOVIMENTO[p.rotulo as keyof typeof ROTULO_TIPO_MOVIMENTO] ?? p.rotulo, valor: p.valor }))}
                            />
                          </div>
                          <div className="card relatorio-grafico-card">
                            <p className="section-label">Movimentação por Dia</p>
                            <GraficoLinha dados={data.graficos.porDia} />
                          </div>
                          <div className="card relatorio-grafico-card" style={{ gridColumn: '1 / -1' }}>
                            <p className="section-label">Top Ajustes Negativos por Produto (perda a custo)</p>
                            {data.graficos.topAjustesNegativos.length === 0 ? (
                              <p className="muted">Nenhum ajuste negativo no período — ótimo sinal.</p>
                            ) : (
                              <GraficoBarraHorizontal dados={data.graficos.topAjustesNegativos} />
                            )}
                          </div>
                        </div>
                      </>
                    )}
                  </>
                )
              )}
            </div>

            <div ref={gridRef} className="relatorio-grid-conteudo">
              <p className="section-label" style={{ marginTop: 24 }}>
                {data.modelo === 'ANALITICO' ? 'Movimentações do Período' : data.modelo === 'KARDEX' ? 'Ficha do Produto' : 'Totais por Tipo'}
              </p>
              <div className="card table-wrap grid-altura-fixa">
                {data.modelo === 'ANALITICO' &&
                  (linhasAnalitico.length === 0 ? (
                    <p className="muted">Nenhuma movimentação encontrada para os filtros informados.</p>
                  ) : (
                    <table className="table table-compacta tabela-adensada tabela-movimentacao-produtos">
                      <thead>
                        <tr>
                          {COLUNAS_ANALITICO.map((c) => {
                            const ativa = ordenarPorAnalitico === c.chave
                            return (
                              <th
                                key={c.chave}
                                className="th-ordenavel"
                                style={c.alinhamento === 'right' ? { textAlign: 'right' } : undefined}
                                onClick={() => ordenarPorColuna(c.chave, ordenarPorAnalitico, setOrdenarPorAnalitico, direcaoAnalitico, setDirecaoAnalitico)}
                                title="Clique para ordenar"
                                aria-sort={ativa ? (direcaoAnalitico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                              >
                                {c.rotulo}
                                {setaOrdenacao(ativa, direcaoAnalitico)}
                              </th>
                            )
                          })}
                        </tr>
                      </thead>
                      <tbody>
                        {linhasAnalitico.map((l, indice) => (
                          <tr key={indice}>
                            <td>{l.nomeEmpresa}</td>
                            <td>{formatarDataHora(l.dataMovimento)}</td>
                            <td>
                              {ROTULO_TIPO_MOVIMENTO[l.tipoMovimento]}
                              {!l.movimentoFisico && <span className="muted"> (reserva)</span>}
                            </td>
                            <td className="mono">{l.sku}</td>
                            <td>{l.descricaoProduto}</td>
                            <td>{l.variacao ?? '—'}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{l.entrada > 0 ? formatarQuantidade(l.entrada, true) : '—'}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{l.saida > 0 ? formatarQuantidade(l.saida, true) : '—'}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(l.custoUnitario)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(l.valorMovimentado)}</td>
                            <td>{l.documento}</td>
                            <td>{l.nomeFuncionario ?? '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  ))}

                {data.modelo === 'KARDEX' &&
                  (linhasKardex.length === 0 ? (
                    <p className="muted">Nenhuma movimentação encontrada para os filtros informados.</p>
                  ) : (
                    <table className="table table-compacta tabela-adensada">
                      <thead>
                        <tr>
                          {COLUNAS_KARDEX.map((c) => {
                            const ativa = ordenarPorKardex === c.chave
                            return (
                              <th
                                key={c.chave}
                                className="th-ordenavel"
                                style={c.alinhamento === 'right' ? { textAlign: 'right' } : undefined}
                                onClick={() => ordenarPorColuna(c.chave, ordenarPorKardex, setOrdenarPorKardex, direcaoKardex, setDirecaoKardex)}
                                title="Clique para ordenar"
                                aria-sort={ativa ? (direcaoKardex === 'ASC' ? 'ascending' : 'descending') : 'none'}
                              >
                                {c.rotulo}
                                {setaOrdenacao(ativa, direcaoKardex)}
                              </th>
                            )
                          })}
                        </tr>
                      </thead>
                      <tbody>
                        {linhasKardex.map((l, indice) => (
                          <tr key={indice}>
                            <td>{formatarDataHora(l.dataMovimento)}</td>
                            <td>
                              {ROTULO_TIPO_MOVIMENTO[l.tipoMovimento]}
                              {!l.movimentoFisico && <span className="muted"> (reserva)</span>}
                            </td>
                            <td>{l.documento}</td>
                            <td>{l.nomeFuncionario ?? '—'}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{l.entrada > 0 ? formatarQuantidade(l.entrada, true) : '—'}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{l.saida > 0 ? formatarQuantidade(l.saida, true) : '—'}</td>
                            <td className="mono" style={{ textAlign: 'right' }}><strong>{formatarQuantidade(l.saldoApos, true)}</strong></td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  ))}

                {data.modelo === 'SINTETICO' &&
                  (linhasSintetico.length === 0 ? (
                    <p className="muted">Nenhuma movimentação encontrada para os filtros informados.</p>
                  ) : (
                    <table className="table table-compacta">
                      <thead>
                        <tr>
                          {(
                            [
                              { chave: 'tipoMovimento', rotulo: 'Tipo de Movimento' },
                              { chave: 'qtdEntrada', rotulo: 'Qtd Entrada', alinhamento: 'right' as const },
                              { chave: 'qtdSaida', rotulo: 'Qtd Saída', alinhamento: 'right' as const },
                              { chave: 'valorEntrada', rotulo: 'Valor Entrada', alinhamento: 'right' as const },
                              { chave: 'valorSaida', rotulo: 'Valor Saída', alinhamento: 'right' as const },
                            ]
                          ).map((c) => {
                            const ativa = ordenarPorSintetico === c.chave
                            return (
                              <th
                                key={c.chave}
                                className="th-ordenavel"
                                style={c.alinhamento === 'right' ? { textAlign: 'right' } : undefined}
                                onClick={() => ordenarPorColuna(c.chave, ordenarPorSintetico, setOrdenarPorSintetico, direcaoSintetico, setDirecaoSintetico)}
                                title="Clique para ordenar"
                                aria-sort={ativa ? (direcaoSintetico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                              >
                                {c.rotulo}
                                {setaOrdenacao(ativa, direcaoSintetico)}
                              </th>
                            )
                          })}
                        </tr>
                      </thead>
                      <tbody>
                        {linhasSintetico.map((l, indice) => (
                          <tr key={indice}>
                            <td>
                              {ROTULO_TIPO_MOVIMENTO[l.tipoMovimento]}
                              {!l.movimentoFisico && <span className="muted"> (reserva)</span>}
                            </td>
                            <td className="mono" style={{ textAlign: 'right' }}>{formatarQuantidade(l.qtdEntrada, true)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{formatarQuantidade(l.qtdSaida, true)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(l.valorEntrada)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(l.valorSaida)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {modalAberto && (
        <FiltrosMovimentacaoProdutosModal
          valores={rascunho}
          aoMudar={(parcial) => setRascunho((r) => ({ ...r, ...parcial }))}
          ehAdmin={!!ehAdmin}
          empresas={empresas ?? []}
          marcas={marcas ?? []}
          categorias={categorias ?? []}
          podeGerar={podeGerarRascunho}
          primeiraVez={!relatorioGerado}
          aoGerar={handleGerar}
          aoFechar={relatorioGerado ? fecharModal : () => navigate(-1)}
          aoAbrirBuscaVariacao={() => setMostrarBuscaVariacao(true)}
        />
      )}
      {mostrarBuscaVariacao && (
        <PesquisaVariacaoModal
          aoFechar={() => setMostrarBuscaVariacao(false)}
          aoSelecionar={(v) => {
            setRascunho((r) => ({ ...r, variacaoKardex: v }))
            setMostrarBuscaVariacao(false)
          }}
        />
      )}
    </div>
  )
}
