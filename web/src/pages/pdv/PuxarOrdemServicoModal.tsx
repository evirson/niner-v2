import { qtdImpressa } from '../../lib/ordemServicoImpressao'
import { useState } from 'react'
import CabecalhoModal from '../../components/CabecalhoModal'
import { ApiError } from '../../lib/api'
import { formatarMoeda } from '../../lib/masks'
import {
  buscarOrdemServico,
  listarOrdensServico,
  type LinhaOs,
  type OrdemServico,
} from '../../lib/ordensServico'
import { maiusculas } from '../../lib/texto'
import { fecharAoClicarNoFundo } from '../../lib/modais'

/**
 * Busca e puxa uma **Ordem de Serviço** para dentro do PDV (S4, `docs/MODULOSERVICOS.md` §4.2).
 *
 * <h2>⛔ Por que não é o popup do orçamento com outro título</h2>
 *
 * <p>OS não é orçamento (decisão do dono do produto, 2026-08-28): o orçamento é uma proposta
 * comercial imutável que pode virar venda parcial, e a OS é um trabalho executado — o serviço já
 * foi prestado, a peça já foi aplicada no carro. Por isso <b>a OS vem inteira</b>: não existe
 * "levar só metade da mão de obra que já foi feita". Aqui não há campo de quantidade.
 *
 * <p>⚠️ Só aparecem as OS <b>CONCLUÍDAS</b> (DS18) — e a filtragem é do servidor, não da tela.
 * Oferecer uma OS em execução seria oferecer algo que o PDV recusaria em seguida; e faturar antes
 * de concluir cobraria por um trabalho que ainda pode mudar de tamanho.
 */
export default function PuxarOrdemServicoModal({
  aoFechar,
  aoConfirmar,
}: {
  aoFechar: () => void
  aoConfirmar: (os: OrdemServico) => void
}) {
  const [busca, setBusca] = useState('')
  const [resultados, setResultados] = useState<LinhaOs[] | null>(null)
  const [os, setOs] = useState<OrdemServico | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [carregando, setCarregando] = useState(false)

  const localizar = async () => {
    setCarregando(true)
    setErro(null)
    setOs(null)
    try {
      const pagina = await listarOrdensServico({
        situacao: 'CONCLUIDA',
        busca: busca.trim() || undefined,
        limite: 50,
      })
      setResultados(pagina.itens)
      if (pagina.itens.length === 0) {
        setErro('Nenhuma ordem de serviço concluída com esses filtros.')
      }
    } catch (e) {
      setErro(e instanceof ApiError ? e.message : 'Não foi possível localizar ordens de serviço.')
      setResultados(null)
    } finally {
      setCarregando(false)
    }
  }

  /** ⚠️ Relê a OS ao abrir: entre a busca e o clique alguém pode tê-la cancelado ou faturado — e
   *  quem descobre isso agora é o operador, não o servidor com o cliente na frente. */
  const abrir = async (idOrdemServico: number) => {
    setCarregando(true)
    setErro(null)
    try {
      const encontrada = await buscarOrdemServico(idOrdemServico)
      if (encontrada.situacao !== 'CONCLUIDA') {
        setErro(
          encontrada.situacao === 'FATURADA'
            ? `A OS nº ${idOrdemServico} já virou a venda nº ${encontrada.idVenda}.`
            : encontrada.situacao === 'CANCELADA'
              ? `A OS nº ${idOrdemServico} foi cancelada e não pode ser faturada.`
              : `A OS nº ${idOrdemServico} ainda não foi concluída.`,
        )
        setOs(null)
        return
      }
      setOs(encontrada)
    } catch (e) {
      setErro(e instanceof ApiError ? e.message : 'Não foi possível abrir a ordem de serviço.')
      setOs(null)
    } finally {
      setCarregando(false)
    }
  }

  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
      {/* `overflow: hidden` + coluna flex faz o MIOLO rolar, não o modal inteiro — senão o título
          e o ✕ saem de vista numa OS com muitos itens (mesma razão do popup do orçamento). */}
      <div
        className="modal modal-largo"
        role="dialog"
        aria-label="Puxar ordem de serviço"
        onClick={(e) => e.stopPropagation()}
        style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
      >
        <CabecalhoModal titulo="Puxar Ordem de Serviço" aoFechar={aoFechar} />

        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 12 }}>
          <div style={{ flex: 1, minWidth: 240 }}>
            <label htmlFor="os-busca">Número, cliente ou objeto do serviço</label>
            <input
              id="os-busca"
              autoFocus
              placeholder="Ex.: ABC1D23, MARIA, 42"
              value={busca}
              onChange={(e) => setBusca(maiusculas(e.target.value))}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  localizar()
                }
              }}
            />
          </div>
          <button type="button" className="btn" disabled={carregando} onClick={localizar}>
            {carregando ? 'Buscando…' : 'Localizar'}
          </button>
        </div>

        {erro && <p className="erro-campo">{erro}</p>}

        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
          {os ? (
            <>
              <p className="muted" style={{ marginTop: 0 }}>
                <strong>OS nº {os.idOrdemServico}</strong> · {os.nomeCliente} · {os.objetoServico} ·
                Responsável {os.nomeFuncionario}
              </p>

              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>SKU</th>
                    <th>Descrição</th>
                    <th>Tipo</th>
                    <th style={{ textAlign: 'right' }}>Qtde</th>
                    <th style={{ textAlign: 'right' }}>Preço</th>
                    <th style={{ textAlign: 'right' }}>Total</th>
                  </tr>
                </thead>
                <tbody>
                  {os.itens.map((i) => (
                    <tr key={i.idOrdemServicoItem}>
                      <td className="mono">{i.sku}</td>
                      <td>
                        {i.descricaoProduto}
                        {(i.variacaoCor || i.variacaoTamanho) && (
                          <span className="muted">
                            {' '}
                            {[i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · ')}
                          </span>
                        )}
                        {i.nomeFuncionario && <span className="muted"> · {i.nomeFuncionario}</span>}
                      </td>
                      <td>{i.tipoItem === 'SERVICO' ? 'Serviço' : 'Peça'}</td>
              {/* Interpolar o `number` cru saía "2.5" com PONTO desde que a OS aceita decimal
                  (2,5 litros de óleo) — a mesma correção que a via impressa recebeu. */}
                      <td style={{ textAlign: 'right' }}>{qtdImpressa(i.qtdProduto)}</td>
                      <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.precoVenda)}</td>
                      <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.total)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <p style={{ textAlign: 'right', marginTop: 12 }}>
                Serviços <strong className="mono">R$ {formatarMoeda(os.totalServicos)}</strong> · Peças{' '}
                <strong className="mono">R$ {formatarMoeda(os.totalPecas)}</strong>
                {os.valorDesconto > 0 && (
                  <> · Desconto <strong className="mono">R$ {formatarMoeda(os.valorDesconto)}</strong></>
                )}{' '}
                · Total <strong className="mono" style={{ fontSize: 18 }}>R$ {formatarMoeda(os.total)}</strong>
              </p>
            </>
          ) : resultados && resultados.length > 0 ? (
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Nº</th>
                  <th>Cliente</th>
                  <th>Objeto do serviço</th>
                  <th style={{ textAlign: 'right' }}>Total</th>
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {resultados.map((l) => (
                  <tr key={l.idOrdemServico}>
                    <td className="mono">{l.idOrdemServico}</td>
                    <td>{l.nomeCliente}</td>
                    <td>{l.objetoServico}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(l.total)}</td>
                    <td className="acoes-cell">
                      <button type="button" className="btn ghost" onClick={() => abrir(l.idOrdemServico)}>
                        Abrir
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="muted">
              Localize a ordem de serviço concluída que o cliente veio pagar. Ela entra inteira na
              venda — serviços e peças — e você ainda pode acrescentar itens no PDV.
            </p>
          )}
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Voltar
          </button>
          <button type="button" className="btn" disabled={!os} onClick={() => os && aoConfirmar(os)}>
            Puxar para a venda
          </button>
        </div>
      </div>
    </div>
  )
}
