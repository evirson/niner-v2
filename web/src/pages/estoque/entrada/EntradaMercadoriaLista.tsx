import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../../components/BotaoFecharTela'
import {
  IconeEstoque,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../../components/Icones'
import { listarEntradas, type ColunaOrdenacaoEntrada } from '../../../lib/entradaMercadoria'
import { formatarMoeda } from '../../../lib/masks'

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
  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoEntrada>('dataMovimento')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('DESC')

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

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['entradas-mercadoria', { pagina, ordenarPor, direcao }],
    queryFn: () => listarEntradas({ pagina, tamanho: TAMANHO_PAGINA, ordenarPor, direcao }),
    placeholderData: (anterior) => anterior,
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
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : entradas.length === 0 ? (
            <p className="muted">Nenhuma entrada registrada ainda.</p>
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
                    <td>{ORIGENS[e.origem] ?? e.origem}</td>
                    <td className="acoes-cell">
                      <Link
                        className="acao-icone acao-visualizar"
                        to={`/entrada-produtos-compra/${e.idMovimento}`}
                        aria-label={`Visualizar entrada nº ${e.idMovimento}`}
                        title="Visualizar"
                      >
                        <IconeOlho />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {entradas.length > 0 && (
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
    </div>
  )
}
