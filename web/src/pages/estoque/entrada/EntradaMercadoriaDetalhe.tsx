import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import AjudaDaTela from '../../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../../components/BotaoFecharTela'
import { IconeEstoque } from '../../../components/Icones'
import { buscarEntrada } from '../../../lib/entradaMercadoria'
import { formatarMoeda } from '../../../lib/masks'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

export default function EntradaMercadoriaDetalhe() {
  const { id } = useParams()

  const { data: entrada, isLoading } = useQuery({
    queryKey: ['entrada-mercadoria', id],
    queryFn: () => buscarEntrada(Number(id)),
  })

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>Entrada {id}</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="estoque.entrada.lista" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        {isLoading || !entrada ? (
          <p className="muted">Carregando…</p>
        ) : (
          <div className="card form-secoes form-secoes-larga">
            <section className="section">
              <p className="section-label">Identificação</p>
              <div className="form-grid">
                <div className="col-4">
                  <label>Fornecedor</label>
                  <div className="pdv-selecao-valor">
                    <span>{entrada.nomeFornecedor ?? '—'}</span>
                  </div>
                </div>
                <div className="col-4">
                  <label>Nota Fiscal</label>
                  <div className="pdv-selecao-valor">
                    <span>{entrada.notaFiscal ?? '—'}</span>
                  </div>
                </div>
                <div className="col-4">
                  <label>Data</label>
                  <div className="pdv-selecao-valor">
                    <span>{formatarData(entrada.dataMovimento)}</span>
                  </div>
                </div>
                {entrada.chaveNfe && (
                  <div className="col-12">
                    <label>Chave da NF-e</label>
                    <div className="pdv-selecao-valor">
                      <span className="mono">{entrada.chaveNfe}</span>
                    </div>
                  </div>
                )}
              </div>
            </section>

            {entrada.cancelada && (
              <section className="section">
                <p className="erro-campo" style={{ margin: 0 }}>
                  Entrada cancelada em {entrada.dataCancelamento ? formatarData(entrada.dataCancelamento) : '—'}.
                  {entrada.motivoCancelamento ? ` Motivo: ${entrada.motivoCancelamento}` : ''}
                </p>
              </section>
            )}

            <section className="section">
              <p className="section-label" style={{ margin: 0 }}>
                Produtos
              </p>
              <div className="table-wrap" style={{ marginTop: 10 }}>
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>SKU</th>
                      <th>Descrição</th>
                      <th>Variação</th>
                      <th style={{ textAlign: 'right' }}>Quantidade</th>
                      <th style={{ textAlign: 'right' }}>Custo Unit.</th>
                      <th style={{ textAlign: 'right' }}>Acréscimo</th>
                      <th style={{ textAlign: 'right' }}>Valor Total</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entrada.itens.map((item) => (
                      <tr key={item.idMovimentoDetalhe}>
                        <td className="mono">{item.sku}</td>
                        <td>{item.descricaoProduto}</td>
                        <td>{[item.variacaoCor, item.variacaoTamanho].filter(Boolean).join(' · ') || '—'}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>
                          {item.qtd}
                        </td>
                        <td className="mono" style={{ textAlign: 'right' }}>
                          {moeda(item.precoCusto)}
                        </td>
                        <td className="mono" style={{ textAlign: 'right' }}>
                          {moeda(item.valorAcrescimo)}
                        </td>
                        <td className="mono" style={{ textAlign: 'right' }}>
                          {moeda(item.valorTotal)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td colSpan={6}>
                        <strong>Total</strong>
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}>
                        <strong>{moeda(entrada.valorTotal)}</strong>
                      </td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            </section>
          </div>
        )}
      </div>
    </div>
  )
}
