import { useQuery } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { aguardarPintura } from '../../lib/temaClaroParaCaptura'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeRelatorio } from '../../components/Icones'
import { listarCategoriasProduto } from '../../lib/categoriasProduto'
import { ApiError } from '../../lib/api'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { listarEmpresas } from '../../lib/empresas'
import { formatarMoeda, formatarQuantidade } from '../../lib/masks'
import { listarMarcas } from '../../lib/produtos'
import {
  gerarRelatorioEstoque,
  type FiltrosRelatorioEstoque,
  type LinhaAnalitica,
  type LinhaInventario,
  type LinhaSintetica,
} from '../../lib/relatorioEstoque'
import { gerarPdfCapturaRelatorioEstoque } from '../../lib/relatorioEstoqueCaptura'
import { useEu } from '../../lib/eu'
import FiltrosEstoqueModal, {
  FILTROS_ESTOQUE_VAZIOS,
  OPCOES_MODELO,
  OPCOES_SITUACAO,
  OPCOES_TIPO_QUANTIDADE,
  type FiltrosEstoqueTexto,
} from './FiltrosEstoqueModal'

type Direcao = 'ASC' | 'DESC'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function compararValores(a: unknown, b: unknown): number {
  if (typeof a === 'string' && typeof b === 'string') return a.localeCompare(b)
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return 0
}

/** Ordena linhas que têm `qtdPorEmpresa` (Sintético/Analítico) — chave `empresa-N` compara pelo
 *  índice N da lista posicional; qualquer outra chave é um campo comum (descrição/marca/...).
 *
 *  A restrição é só `{ qtdPorEmpresa: number[] }`: exigir também `Record<string, unknown>` fazia
 *  `LinhaSintetica`/`LinhaAnalitica` (interfaces) não casarem — interface não ganha assinatura de
 *  índice implícita em TypeScript, e o acesso dinâmico por `chave` é resolvido no cast abaixo. */
function ordenarComEmpresa<T extends { qtdPorEmpresa: number[] }>(
  linhas: T[],
  chave: string,
  direcao: Direcao,
): T[] {
  const copia = [...linhas]
  copia.sort((a, b) => {
    let cmp: number
    if (chave.startsWith('empresa-')) {
      const indice = Number(chave.slice('empresa-'.length))
      cmp = (a.qtdPorEmpresa[indice] ?? 0) - (b.qtdPorEmpresa[indice] ?? 0)
    } else {
      cmp = compararValores(
        (a as Record<string, unknown>)[chave] ?? '',
        (b as Record<string, unknown>)[chave] ?? '',
      )
    }
    return direcao === 'ASC' ? cmp : -cmp
  })
  return copia
}

function ordenarInventario(linhas: LinhaInventario[], chave: keyof LinhaInventario, direcao: Direcao): LinhaInventario[] {
  const copia = [...linhas]
  copia.sort((a, b) => {
    const cmp = compararValores(a[chave] ?? '', b[chave] ?? '')
    return direcao === 'ASC' ? cmp : -cmp
  })
  return copia
}

/**
 * Relatório de Estoque (2026-08-04) — filtros comuns (Empresas/Marca/Categorias/Tipo de
 * Quantidade/Situação do Produto) e 3 modelos alternativos escolhidos no popup de filtros
 * (Inventário/Sintético/Analítico, mesmo padrão do totalizador Analítico×Agrupado do Relatório
 * de Vendas): a troca de modelo muda as colunas da grid, não a tela. Sintético/Analítico abrem
 * uma coluna de quantidade por empresa selecionada (dinâmico); Inventário soma tudo num total só
 * mais custo unitário/total. Mesmo padrão de tela dos demais relatórios: popup obrigatório antes
 * da 1ª geração, cabeçalho/rodapé fixos na grid (`.relatorio-corpo-fixo`), colunas ordenáveis
 * client-side, PDF por captura visual.
 */
export default function RelatorioEstoque() {
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [aplicado, setAplicado] = useState<FiltrosEstoqueTexto>(FILTROS_ESTOQUE_VAZIOS)
  const [rascunho, setRascunho] = useState<FiltrosEstoqueTexto>(FILTROS_ESTOQUE_VAZIOS)
  const [modalAberto, setModalAberto] = useState(true)
  const [relatorioGerado, setRelatorioGerado] = useState(false)
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const conteudoRef = useRef<HTMLDivElement>(null)

  const [ordenarPorInventario, setOrdenarPorInventario] = useState<keyof LinhaInventario>('descricaoProduto')
  const [direcaoInventario, setDirecaoInventario] = useState<Direcao>('ASC')
  const [ordenarPorSintetico, setOrdenarPorSintetico] = useState('descricaoProduto')
  const [direcaoSintetico, setDirecaoSintetico] = useState<Direcao>('ASC')
  const [ordenarPorAnalitico, setOrdenarPorAnalitico] = useState('descricaoProduto')
  const [direcaoAnalitico, setDirecaoAnalitico] = useState<Direcao>('ASC')

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas, enabled: ehAdmin })
  const { data: marcas } = useQuery({ queryKey: ['produtos-marcas'], queryFn: listarMarcas })
  const { data: categorias } = useQuery({ queryKey: ['categorias-produto'], queryFn: listarCategoriasProduto })

  const filtros: FiltrosRelatorioEstoque = {
    modelo: aplicado.modelo,
    idsEmpresa: ehAdmin ? aplicado.idsEmpresa : undefined,
    marcas: aplicado.marcas,
    idsCategoria: aplicado.idsCategoria,
    tipoQuantidade: aplicado.tipoQuantidade,
    situacao: aplicado.situacao,
  }

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['relatorio-estoque', filtros],
    queryFn: () => gerarRelatorioEstoque(filtros),
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

  function ordenarPorColunaInventario(chave: keyof LinhaInventario) {
    if (chave === ordenarPorInventario) {
      setDirecaoInventario((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPorInventario(chave)
      setDirecaoInventario('ASC')
    }
  }

  function ordenarPorColunaSintetico(chave: string) {
    if (chave === ordenarPorSintetico) {
      setDirecaoSintetico((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPorSintetico(chave)
      setDirecaoSintetico('ASC')
    }
  }

  function ordenarPorColunaAnalitico(chave: string) {
    if (chave === ordenarPorAnalitico) {
      setDirecaoAnalitico((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPorAnalitico(chave)
      setDirecaoAnalitico('ASC')
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

  function textoMarcasFiltradas(): string {
    return aplicado.marcas.length === 0 ? 'Todas as marcas' : aplicado.marcas.join(', ')
  }

  function textoCategoriasFiltradas(): string {
    if (aplicado.idsCategoria.length === 0) return 'Todas as categorias'
    return (
      (categorias ?? [])
        .filter((c) => aplicado.idsCategoria.includes(c.idCategoria))
        .map((c) => c.nomeCategoria)
        .join(', ') || 'Todas as categorias'
    )
  }

  function montarDescricaoFiltros(): Array<{ rotulo: string; valor: string }> {
    return [
      { rotulo: 'Modelo', valor: OPCOES_MODELO.find((o) => o.chave === aplicado.modelo)?.rotulo ?? '—' },
      { rotulo: 'Empresa(s)', valor: textoEmpresasFiltradas() },
      { rotulo: 'Marca(s)', valor: textoMarcasFiltradas() },
      { rotulo: 'Categoria(s)', valor: textoCategoriasFiltradas() },
      { rotulo: 'Tipo de Quantidade', valor: OPCOES_TIPO_QUANTIDADE.find((o) => o.chave === aplicado.tipoQuantidade)?.rotulo ?? '—' },
      { rotulo: 'Situação do Produto', valor: OPCOES_SITUACAO.find((o) => o.chave === aplicado.situacao)?.rotulo ?? '—' },
    ]
  }


  const handleGerarPdf = async () => {
    if (!conteudoRef.current) return
    setGerandoPdf(true)
    try {
      await aguardarPintura()
      await gerarPdfCapturaRelatorioEstoque(conteudoRef.current, eu?.empresa.nome ?? '—')
    } finally {
      setGerandoPdf(false)
    }
  }

  const linhasInventario = ordenarInventario(data?.linhasInventario ?? [], ordenarPorInventario, direcaoInventario)
  const linhasSintetico = ordenarComEmpresa(data?.linhasSintetico ?? [], ordenarPorSintetico, direcaoSintetico)
  const linhasAnalitico = ordenarComEmpresa(data?.linhasAnalitico ?? [], ordenarPorAnalitico, direcaoAnalitico)
  const colunasEmpresa = data?.colunasEmpresa ?? []

  function setaOrdenacao(ativa: boolean, direcao: Direcao) {
    return <span className={`th-seta ${ativa ? 'th-seta-ativa' : ''}`}>{ativa ? (direcao === 'ASC' ? '▲' : '▼') : '⇅'}</span>
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRelatorio size={34} />
            <h1>Relatório de Estoque</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.estoque.tela" />
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

            <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>
              Produtos
            </p>
            <div className={`card table-wrap${gerandoPdf ? ' pdf-expandido' : ''}`}>
              {data.modelo === 'INVENTARIO' &&
                (linhasInventario.length === 0 ? (
                  <p className="muted">Nenhum produto encontrado para os filtros informados.</p>
                ) : (
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        {(
                          [
                            { chave: 'descricaoProduto', rotulo: 'Descrição' },
                            { chave: 'marca', rotulo: 'Marca' },
                            { chave: 'referencia', rotulo: 'Referência' },
                            { chave: 'qtdTotal', rotulo: 'Qtd Total', alinhamento: 'right' as const },
                            { chave: 'custoUnitario', rotulo: 'Custo Unitário', alinhamento: 'right' as const },
                            { chave: 'custoTotal', rotulo: 'Custo Total', alinhamento: 'right' as const },
                          ] satisfies Array<{ chave: keyof LinhaInventario; rotulo: string; alinhamento?: 'right' }>
                        ).map((c) => {
                          const ativa = ordenarPorInventario === c.chave
                          return (
                            <th
                              key={c.chave}
                              className="th-ordenavel"
                              style={c.alinhamento === 'right' ? { textAlign: 'right' } : undefined}
                              onClick={() => ordenarPorColunaInventario(c.chave)}
                              title="Clique para ordenar"
                              aria-sort={ativa ? (direcaoInventario === 'ASC' ? 'ascending' : 'descending') : 'none'}
                            >
                              {c.rotulo}
                              {setaOrdenacao(ativa, direcaoInventario)}
                            </th>
                          )
                        })}
                      </tr>
                    </thead>
                    <tbody>
                      {linhasInventario.map((linha, indice) => (
                        <tr key={indice}>
                          <td>{linha.descricaoProduto}</td>
                          <td>{linha.marca ?? '—'}</td>
                          <td>{linha.referencia ?? '—'}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{formatarQuantidade(linha.qtdTotal, permiteQtdDecimal)}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.custoUnitario)}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.custoTotal)}</td>
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr>
                        <td colSpan={3}>
                          <strong>Total ({linhasInventario.length} produto(s))</strong>
                        </td>
                        <td className="mono" style={{ textAlign: 'right' }}>
                          <strong>{formatarQuantidade(data.totalInventario?.qtdTotal ?? 0, permiteQtdDecimal)}</strong>
                        </td>
                        <td />
                        <td className="mono" style={{ textAlign: 'right' }}>
                          <strong>{moeda(data.totalInventario?.custoTotal ?? 0)}</strong>
                        </td>
                      </tr>
                    </tfoot>
                  </table>
                ))}

              {data.modelo === 'SINTETICO' &&
                (linhasSintetico.length === 0 ? (
                  <p className="muted">Nenhum produto encontrado para os filtros informados.</p>
                ) : (
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        <th
                          className="th-ordenavel"
                          onClick={() => ordenarPorColunaSintetico('descricaoProduto')}
                          title="Clique para ordenar"
                          aria-sort={ordenarPorSintetico === 'descricaoProduto' ? (direcaoSintetico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                        >
                          Descrição
                          {setaOrdenacao(ordenarPorSintetico === 'descricaoProduto', direcaoSintetico)}
                        </th>
                        <th
                          className="th-ordenavel"
                          onClick={() => ordenarPorColunaSintetico('marca')}
                          title="Clique para ordenar"
                          aria-sort={ordenarPorSintetico === 'marca' ? (direcaoSintetico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                        >
                          Marca
                          {setaOrdenacao(ordenarPorSintetico === 'marca', direcaoSintetico)}
                        </th>
                        <th
                          className="th-ordenavel"
                          onClick={() => ordenarPorColunaSintetico('referencia')}
                          title="Clique para ordenar"
                          aria-sort={ordenarPorSintetico === 'referencia' ? (direcaoSintetico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                        >
                          Referência
                          {setaOrdenacao(ordenarPorSintetico === 'referencia', direcaoSintetico)}
                        </th>
                        {colunasEmpresa.map((coluna, i) => {
                          const chave = `empresa-${i}`
                          const ativa = ordenarPorSintetico === chave
                          return (
                            <th
                              key={coluna.idEmpresa}
                              className="th-ordenavel"
                              style={{ textAlign: 'right' }}
                              onClick={() => ordenarPorColunaSintetico(chave)}
                              title="Clique para ordenar"
                              aria-sort={ativa ? (direcaoSintetico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                            >
                              {coluna.nomeEmpresa}
                              {setaOrdenacao(ativa, direcaoSintetico)}
                            </th>
                          )
                        })}
                        <th
                          className="th-ordenavel"
                          style={{ textAlign: 'right' }}
                          onClick={() => ordenarPorColunaSintetico('qtdTotal')}
                          title="Clique para ordenar"
                          aria-sort={ordenarPorSintetico === 'qtdTotal' ? (direcaoSintetico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                        >
                          Qtd Total
                          {setaOrdenacao(ordenarPorSintetico === 'qtdTotal', direcaoSintetico)}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {linhasSintetico.map((linha: LinhaSintetica, indice) => (
                        <tr key={indice}>
                          <td>{linha.descricaoProduto}</td>
                          <td>{linha.marca ?? '—'}</td>
                          <td>{linha.referencia ?? '—'}</td>
                          {linha.qtdPorEmpresa.map((qtd, i) => (
                            <td key={i} className="mono" style={{ textAlign: 'right' }}>
                              {formatarQuantidade(qtd, permiteQtdDecimal)}
                            </td>
                          ))}
                          <td className="mono" style={{ textAlign: 'right' }}>{formatarQuantidade(linha.qtdTotal, permiteQtdDecimal)}</td>
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr>
                        <td colSpan={3}>
                          <strong>Total ({linhasSintetico.length} produto(s))</strong>
                        </td>
                        {(data.totalSintetico?.qtdPorEmpresa ?? []).map((qtd, i) => (
                          <td key={i} className="mono" style={{ textAlign: 'right' }}>
                            <strong>{formatarQuantidade(qtd, permiteQtdDecimal)}</strong>
                          </td>
                        ))}
                        <td className="mono" style={{ textAlign: 'right' }}>
                          <strong>{formatarQuantidade(data.totalSintetico?.qtdTotal ?? 0, permiteQtdDecimal)}</strong>
                        </td>
                      </tr>
                    </tfoot>
                  </table>
                ))}

              {data.modelo === 'ANALITICO' &&
                (linhasAnalitico.length === 0 ? (
                  <p className="muted">Nenhum produto encontrado para os filtros informados.</p>
                ) : (
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        {(
                          [
                            { chave: 'descricaoProduto', rotulo: 'Descrição' },
                            { chave: 'marca', rotulo: 'Marca' },
                            { chave: 'referencia', rotulo: 'Referência' },
                            { chave: 'variacaoCor', rotulo: 'Cor' },
                            { chave: 'variacaoTamanho', rotulo: 'Tamanho' },
                          ] satisfies Array<{ chave: string; rotulo: string }>
                        ).map((c) => {
                          const ativa = ordenarPorAnalitico === c.chave
                          return (
                            <th
                              key={c.chave}
                              className="th-ordenavel"
                              onClick={() => ordenarPorColunaAnalitico(c.chave)}
                              title="Clique para ordenar"
                              aria-sort={ativa ? (direcaoAnalitico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                            >
                              {c.rotulo}
                              {setaOrdenacao(ativa, direcaoAnalitico)}
                            </th>
                          )
                        })}
                        {colunasEmpresa.map((coluna, i) => {
                          const chave = `empresa-${i}`
                          const ativa = ordenarPorAnalitico === chave
                          return (
                            <th
                              key={coluna.idEmpresa}
                              className="th-ordenavel"
                              style={{ textAlign: 'right' }}
                              onClick={() => ordenarPorColunaAnalitico(chave)}
                              title="Clique para ordenar"
                              aria-sort={ativa ? (direcaoAnalitico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                            >
                              {coluna.nomeEmpresa}
                              {setaOrdenacao(ativa, direcaoAnalitico)}
                            </th>
                          )
                        })}
                        <th
                          className="th-ordenavel"
                          style={{ textAlign: 'right' }}
                          onClick={() => ordenarPorColunaAnalitico('qtdTotal')}
                          title="Clique para ordenar"
                          aria-sort={ordenarPorAnalitico === 'qtdTotal' ? (direcaoAnalitico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                        >
                          Qtd Total
                          {setaOrdenacao(ordenarPorAnalitico === 'qtdTotal', direcaoAnalitico)}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {linhasAnalitico.map((linha: LinhaAnalitica, indice) => (
                        <tr key={indice}>
                          <td>{linha.descricaoProduto}</td>
                          <td>{linha.marca ?? '—'}</td>
                          <td>{linha.referencia ?? '—'}</td>
                          <td>{linha.variacaoCor ?? '—'}</td>
                          <td>{linha.variacaoTamanho ?? '—'}</td>
                          {linha.qtdPorEmpresa.map((qtd, i) => (
                            <td key={i} className="mono" style={{ textAlign: 'right' }}>
                              {formatarQuantidade(qtd, permiteQtdDecimal)}
                            </td>
                          ))}
                          <td className="mono" style={{ textAlign: 'right' }}>{formatarQuantidade(linha.qtdTotal, permiteQtdDecimal)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                ))}
            </div>
          </div>
        )}
      </div>

      {modalAberto && (
        <FiltrosEstoqueModal
          valores={rascunho}
          aoMudar={(parcial) => setRascunho((r) => ({ ...r, ...parcial }))}
          ehAdmin={!!ehAdmin}
          empresas={empresas ?? []}
          marcas={marcas ?? []}
          categorias={categorias ?? []}
          primeiraVez={!relatorioGerado}
          aoGerar={handleGerar}
          aoFechar={relatorioGerado ? fecharModal : () => navigate(-1)}
        />
      )}
    </div>
  )
}
