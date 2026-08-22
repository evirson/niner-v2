import { useQuery } from '@tanstack/react-query'
import { Fragment, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeRelatorio } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { useEu } from '../../lib/eu'
import { listarEmpresas } from '../../lib/empresas'
import { dataParaIso, dataValida, formatarMoeda } from '../../lib/masks'
import {
  gerarRelatorioComissoes,
  type ColunaOrdenacaoComissao,
  type DirecaoOrdenacao,
  type FiltrosRelatorioComissoes,
} from '../../lib/relatorioComissoes'
import { gerarPdfCapturaRelatorioComissoes } from '../../lib/relatorioComissoesCaptura'
import FiltrosComissoesModal, { FILTROS_VAZIOS, type FiltrosTexto } from './FiltrosComissoesModal'

const COLUNAS: Array<{ chave: ColunaOrdenacaoComissao; rotulo: string; alinhamento?: 'right' }> = [
  { chave: 'nomeEmpresa', rotulo: 'Empresa' },
  { chave: 'nomeFuncionario', rotulo: 'Funcionário' },
  { chave: 'valorVenda', rotulo: 'Valor da Venda', alinhamento: 'right' },
  { chave: 'valorDevolucao', rotulo: 'Valor da Devolução', alinhamento: 'right' },
  { chave: 'valorLiquido', rotulo: 'Valor Líquido', alinhamento: 'right' },
  { chave: 'quantidadeVendas', rotulo: 'Nº Vendas', alinhamento: 'right' },
  { chave: 'ticketMedio', rotulo: 'Ticket Médio', alinhamento: 'right' },
  { chave: 'percComissao', rotulo: '% Comissão', alinhamento: 'right' },
  { chave: 'valorComissao', rotulo: 'Valor Comissão', alinhamento: 'right' },
]

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function percentual(v: number): string {
  return `${formatarMoeda(v)}%`
}

function periodoPreenchido(inicial: string, final: string): boolean {
  return dataValida(inicial) && dataValida(final)
}

/**
 * Relatório de Comissões (docs/telas/relatorio-comissoes.md) — usa o Relatório de Contas a
 * Receber como referência de padrão de tela (popup de filtros, PDF por captura visual, grid
 * "banda clássica" com subtotal por empresa e cabeçalho/rodapé fixos). Conteúdo mais simples: sem
 * KPIs nem gráficos, só uma grid — uma linha por (empresa, funcionário).
 *
 * Filtros em popup (2026-08-03): a tela não mostra mais os campos de filtro embutidos — abre
 * direto o popup `FiltrosComissoesModal` (nada é buscado antes disso) e o botão "Filtros" no topo
 * reabre o popup pra gerar outro relatório. Enquanto nenhum relatório foi gerado, a única saída
 * do popup é "Voltar" (sai da tela); depois da 1ª geração, "Cancelar" só fecha o popup sem
 * alterar o relatório já exibido.
 */
export default function RelatorioComissoes() {
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [aplicado, setAplicado] = useState<FiltrosTexto>(FILTROS_VAZIOS)
  const [rascunho, setRascunho] = useState<FiltrosTexto>(FILTROS_VAZIOS)
  const [modalAberto, setModalAberto] = useState(true)
  const [relatorioGerado, setRelatorioGerado] = useState(false)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoComissao>('nomeFuncionario')
  const [direcao, setDirecao] = useState<DirecaoOrdenacao>('ASC')
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const conteudoRef = useRef<HTMLDivElement>(null)

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas, enabled: ehAdmin })

  const dataInicialIso = dataValida(aplicado.dataInicial) ? dataParaIso(aplicado.dataInicial) : null
  const dataFinalIso = dataValida(aplicado.dataFinal) ? dataParaIso(aplicado.dataFinal) : null

  const filtros: FiltrosRelatorioComissoes = {
    dataInicial: dataInicialIso ?? '',
    dataFinal: dataFinalIso ?? '',
    idsEmpresa: ehAdmin ? aplicado.idsEmpresa : undefined,
    ordenarPor,
    direcao,
  }

  const podeGerarRascunho = periodoPreenchido(rascunho.dataInicial, rascunho.dataFinal)

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['relatorio-comissoes', filtros],
    queryFn: () => gerarRelatorioComissoes(filtros),
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

  function ordenarPorColuna(coluna: ColunaOrdenacaoComissao) {
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

  function aguardarPintura(): Promise<void> {
    return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  }

  const handleGerarPdf = async () => {
    if (!conteudoRef.current) return
    setGerandoPdf(true)
    try {
      await aguardarPintura()
      await gerarPdfCapturaRelatorioComissoes(conteudoRef.current, eu?.empresa.nome ?? '—')
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
            <h1>Relatório de Comissões</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.comissoes.tela" />
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
                  <span>
                    <span className="muted">Período: </span>
                    <strong>{aplicado.dataInicial} a {aplicado.dataFinal}</strong>
                  </span>
                  <span>
                    <span className="muted">Empresa(s): </span>
                    <strong>{textoEmpresasFiltradas()}</strong>
                  </span>
                </div>
              </>
            )}

            <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>
              Comissões por Vendedor
            </p>
            <div className={`card table-wrap${gerandoPdf ? ' pdf-expandido' : ''}`}>
              {data.linhas.length === 0 ? (
                <p className="muted">Nenhuma venda ou devolução encontrada para os filtros informados.</p>
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
                        <Fragment key={`${linha.idEmpresa}-${linha.idFuncionario}`}>
                          <tr>
                            <td>{linha.nomeEmpresa}</td>
                            <td>{linha.nomeFuncionario}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorVenda)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorDevolucao)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorLiquido)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{linha.quantidadeVendas}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.ticketMedio)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{percentual(linha.percComissao)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorComissao)}</td>
                          </tr>
                          {subtotal && (
                            <tr className="linha-subtotal">
                              <td colSpan={2}>
                                <strong>Subtotal — {subtotal.nomeEmpresa}</strong>
                              </td>
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorVenda)}</strong></td>
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorDevolucao)}</strong></td>
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorLiquido)}</strong></td>
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{subtotal.quantidadeVendas}</strong></td>
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.ticketMedio)}</strong></td>
                              <td />
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorComissao)}</strong></td>
                            </tr>
                          )}
                        </Fragment>
                      )
                    })}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td colSpan={2}>
                        <strong>Total Geral</strong>
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorVenda)}</strong></td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorDevolucao)}</strong></td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorLiquido)}</strong></td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{data.totalGeral.quantidadeVendas}</strong></td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.ticketMedio)}</strong></td>
                      <td />
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorComissao)}</strong></td>
                    </tr>
                  </tfoot>
                </table>
              )}
            </div>
          </div>
        )}
      </div>

      {modalAberto && (
        <FiltrosComissoesModal
          valores={rascunho}
          aoMudar={(parcial) => setRascunho((r) => ({ ...r, ...parcial }))}
          ehAdmin={!!ehAdmin}
          empresas={empresas ?? []}
          podeGerar={podeGerarRascunho}
          primeiraVez={!relatorioGerado}
          aoGerar={handleGerar}
          aoFechar={relatorioGerado ? fecharModal : () => navigate(-1)}
        />
      )}
    </div>
  )
}
