import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeEstoque } from '../../components/Icones'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { formatarQuantidade } from '../../lib/masks'
import { buscarTransferencia } from '../../lib/transferencias'

function formatarData(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

export default function TransferenciaDetalhe() {
  const { id } = useParams()
  const navigate = useNavigate()

  const { data: transferencia, isLoading } = useQuery({
    queryKey: ['transferencia', id],
    queryFn: () => buscarTransferencia(Number(id)),
  })
  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>Transferência {id}</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="estoque.transferencia.lista" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        {isLoading || !transferencia ? (
          <p className="muted">Carregando…</p>
        ) : (
          <div className="card form-secoes form-secoes-larga">
            <section className="section">
              <p className="section-label">Identificação</p>
              <div className="form-grid">
                <div className="col-4">
                  <label>Empresa de Origem</label>
                  <div className="pdv-selecao-valor">
                    <span>{transferencia.empresaOrigem.nomeEmpresa}</span>
                  </div>
                </div>
                <div className="col-4">
                  <label>Empresa de Destino</label>
                  <div className="pdv-selecao-valor">
                    <span>{transferencia.empresaDestino.nomeEmpresa}</span>
                  </div>
                </div>
                <div className="col-4">
                  <label>Data</label>
                  <div className="pdv-selecao-valor">
                    <span>{formatarData(transferencia.dataTransferencia)}</span>
                  </div>
                </div>
                <div className="col-6">
                  <label>Usuário</label>
                  <div className="pdv-selecao-valor">
                    <span>{transferencia.nomeUsuario}</span>
                  </div>
                </div>
                {transferencia.observacoes && (
                  <div className="col-12">
                    <label>Observações</label>
                    <div className="pdv-selecao-valor">
                      <span>{transferencia.observacoes}</span>
                    </div>
                  </div>
                )}
              </div>
            </section>

            <section className="section">
              <p className="section-label">Produtos transferidos</p>
              <div className="table-wrap">
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Descrição</th>
                      <th>Variação</th>
                      <th>SKU</th>
                      <th style={{ textAlign: 'right' }}>Quantidade</th>
                    </tr>
                  </thead>
                  <tbody>
                    {transferencia.itens.map((item) => (
                      <tr key={item.idVariacao}>
                        <td>{item.descricaoProduto}</td>
                        <td>{[item.variacaoLinha, item.variacaoColuna].filter(Boolean).join(' · ') || '—'}</td>
                        <td className="mono">{item.sku}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>
                          {formatarQuantidade(item.qtd, permiteQtdDecimal)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        )}
      </div>
    </div>
  )
}
