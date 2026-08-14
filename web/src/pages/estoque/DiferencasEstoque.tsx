import { useQuery } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeRelatorio } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { obterDiferencas, type LinhaDiferenca } from '../../lib/estoqueBalanco'
import { gerarPdfCapturaDiferencasEstoque } from '../../lib/estoqueDiferencasCaptura'
import { useEu } from '../../lib/eu'
import { formatarQuantidade } from '../../lib/masks'

type Coluna = 'descricaoProduto' | 'variacaoCor' | 'variacaoTamanho' | 'qtdEstoque' | 'qtdContada' | 'diferenca'
type Direcao = 'ASC' | 'DESC'

const COLUNAS: Array<{ chave: Coluna; rotulo: string; alinhamento?: 'right' }> = [
  { chave: 'descricaoProduto', rotulo: 'Descrição' },
  { chave: 'variacaoCor', rotulo: 'Cor' },
  { chave: 'variacaoTamanho', rotulo: 'Tamanho' },
  { chave: 'qtdEstoque', rotulo: 'Qtd Estoque', alinhamento: 'right' },
  { chave: 'qtdContada', rotulo: 'Qtd Contada', alinhamento: 'right' },
  { chave: 'diferenca', rotulo: 'Diferença', alinhamento: 'right' },
]

function compararValores(a: unknown, b: unknown): number {
  if (typeof a === 'string' && typeof b === 'string') return a.localeCompare(b)
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return 0
}

function ordenarLinhas(linhas: LinhaDiferenca[], coluna: Coluna, direcao: Direcao): LinhaDiferenca[] {
  const copia = [...linhas]
  copia.sort((a, b) => {
    const cmp = compararValores(a[coluna] ?? '', b[coluna] ?? '')
    return direcao === 'ASC' ? cmp : -cmp
  })
  return copia
}

/**
 * Verificar Diferenças de Estoque (2026-08-04) — segue o padrão de relatório do projeto (grid
 * com cabeçalho/rodapé fixos — `.relatorio-corpo-fixo` —, colunas ordenáveis, captura visual em
 * PDF), mas SEM popup de filtros: não existe filtro nenhum pra escolher aqui — a tela é sempre a
 * empresa em que o usuário está logado, é sempre a contagem ativa no momento, então a tela já
 * carrega direto ao abrir. Mostra só as exceções (produto com contagem diferente do estoque,
 * inclusive produto em estoque nunca escaneado no balanço ativo).
 */
export default function DiferencasEstoque() {
  const { data: eu } = useEu()
  const [ordenarPor, setOrdenarPor] = useState<Coluna>('descricaoProduto')
  const [direcao, setDirecao] = useState<Direcao>('ASC')
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const conteudoRef = useRef<HTMLDivElement>(null)

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const { data, isLoading, error } = useQuery({ queryKey: ['balanco-diferencas'], queryFn: obterDiferencas })

  function ordenarPorColuna(coluna: Coluna) {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  function aguardarPintura(): Promise<void> {
    return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  }

  const handleGerarPdf = async () => {
    if (!conteudoRef.current) return
    setGerandoPdf(true)
    try {
      await aguardarPintura()
      await gerarPdfCapturaDiferencasEstoque(conteudoRef.current, eu?.empresa.nome ?? '—')
    } finally {
      setGerandoPdf(false)
    }
  }

  const linhas = ordenarLinhas(data?.linhas ?? [], ordenarPor, direcao)
  const totalEstoque = linhas.reduce((s, l) => s + l.qtdEstoque, 0)
  const totalContado = linhas.reduce((s, l) => s + l.qtdContada, 0)
  const totalDiferenca = linhas.reduce((s, l) => s + l.diferenca, 0)

  function corDiferenca(valor: number): string | undefined {
    if (valor > 0) return 'var(--sucesso)'
    if (valor < 0) return 'var(--danger)'
    return undefined
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRelatorio size={34} />
            <h1>Diferenças de Estoque</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="estoque.diferencas" />
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
        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : error || !data ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar as diferenças.'}</p>
        ) : (
          <div ref={conteudoRef} className={`relatorio-conteudo${gerandoPdf ? ' pdf-expandido' : ''}`}>
            <p className="section-label" style={{ marginTop: 0 }}>
              Filtros Aplicados
            </p>
            <div className="card relatorio-filtros-aplicados" style={{ marginBottom: 16 }}>
              <span>
                <span className="muted">Empresa: </span>
                <strong>{eu?.empresa.nome ?? '—'}</strong>
              </span>
            </div>

            <p className="section-label">Diferenças</p>
            <div className={`card table-wrap${gerandoPdf ? ' pdf-expandido' : ''}`}>
              {!data.existeContagemAtiva ? (
                <p className="muted">Não há nenhuma contagem de estoque em andamento nesta empresa.</p>
              ) : linhas.length === 0 ? (
                <p className="muted">Nenhuma diferença encontrada — a contagem ativa bate com o estoque.</p>
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
                    {linhas.map((linha) => (
                      <tr key={linha.idVariacao}>
                        <td>{linha.descricaoProduto}</td>
                        <td>{linha.variacaoCor ?? '—'}</td>
                        <td>{linha.variacaoTamanho ?? '—'}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>{formatarQuantidade(linha.qtdEstoque, permiteQtdDecimal)}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>{formatarQuantidade(linha.qtdContada, permiteQtdDecimal)}</td>
                        <td className="mono" style={{ textAlign: 'right', color: corDiferenca(linha.diferenca) }}>
                          {linha.diferenca > 0 ? '+' : ''}
                          {formatarQuantidade(linha.diferenca, permiteQtdDecimal)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td colSpan={3}>
                        <strong>Total ({linhas.length} produto(s))</strong>
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{formatarQuantidade(totalEstoque, permiteQtdDecimal)}</strong></td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{formatarQuantidade(totalContado, permiteQtdDecimal)}</strong></td>
                      <td className="mono" style={{ textAlign: 'right', color: corDiferenca(totalDiferenca) }}>
                        <strong>
                          {totalDiferenca > 0 ? '+' : ''}
                          {formatarQuantidade(totalDiferenca, permiteQtdDecimal)}
                        </strong>
                      </td>
                    </tr>
                  </tfoot>
                </table>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
