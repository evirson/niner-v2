import { useState } from 'react'
import { ApiError } from '../../lib/api'
import { buscarOrcamento, type ItemOrcamento, type Orcamento } from '../../lib/orcamento'
import { formatarMoeda } from '../../lib/masks'

/** Quantidade que o operador decidiu levar de cada item, por `idVariacao`. */
type Levando = Record<number, number>

/**
 * Puxa um orçamento para dentro do PDV (docs/telas/orcamento.md, R2).
 *
 * <h2>⚠️ Só dá para DIMINUIR</h2>
 *
 * <p>A quantidade nasce igual à orçada e o operador só pode reduzir — inclusive a zero, que
 * significa "não levar este item". Aumentar não é permitido, e o preço nunca aparece editável: ele
 * é o <b>congelado</b> na emissão, e é o servidor quem o relê do banco. Se o cliente quiser mais
 * do que orçou, o operador acrescenta o produto normalmente no PDV — isso é venda comum, com preço
 * de cadastro, e não mexe no orçamento.
 *
 * <p>Produto inativado depois da emissão aparece marcado e <b>não pode ser levado</b> (R7) — a
 * quantidade dele é travada em zero aqui, para o operador não descobrir o problema só ao confirmar
 * a venda, com o cliente na frente.
 */
export default function PuxarOrcamentoModal({
  aoFechar,
  aoConfirmar,
}: {
  aoFechar: () => void
  aoConfirmar: (orcamento: Orcamento, levando: Levando) => void
}) {
  const [numero, setNumero] = useState('')
  const [orcamento, setOrcamento] = useState<Orcamento | null>(null)
  const [levando, setLevando] = useState<Levando>({})
  const [erro, setErro] = useState<string | null>(null)
  const [buscando, setBuscando] = useState(false)

  const buscar = async () => {
    const id = Number(numero.trim())
    if (!id) return
    setBuscando(true)
    setErro(null)
    try {
      const o = await buscarOrcamento(id)
      if (o.situacao !== 'ABERTO') {
        // A mensagem do servidor diz o motivo; aqui a tela antecipa o mesmo, sem tentar vender.
        setErro(motivoDeNaoPoder(o))
        setOrcamento(null)
        return
      }
      setOrcamento(o)
      const inicial: Levando = {}
      for (const item of o.itens) {
        inicial[item.idVariacao] = item.produtoInativo ? 0 : item.qtd
      }
      setLevando(inicial)
    } catch (e) {
      setErro(e instanceof ApiError ? e.message : 'Não foi possível abrir o orçamento.')
      setOrcamento(null)
    } finally {
      setBuscando(false)
    }
  }

  const totalLevando = orcamento
    ? orcamento.itens.reduce((s, i) => s + (levando[i.idVariacao] ?? 0) * i.precoVenda, 0)
    : 0
  const parcial = orcamento
    ? orcamento.itens.some((i) => (levando[i.idVariacao] ?? 0) < i.qtd)
    : false
  const algumItem = Object.values(levando).some((q) => q > 0)

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div
        className="modal modal-largo"
        role="dialog"
        aria-label="Puxar orçamento"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 style={{ marginTop: 0 }}>Puxar orçamento</h2>

        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
          <div style={{ flex: '0 0 180px' }}>
            <label htmlFor="numero-orcamento">Número do orçamento</label>
            <input
              id="numero-orcamento"
              autoFocus
              inputMode="numeric"
              className="mono"
              value={numero}
              onChange={(e) => setNumero(e.target.value.replace(/\D/g, ''))}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  buscar()
                }
              }}
            />
          </div>
          <button type="button" className="btn ghost" disabled={!numero.trim() || buscando} onClick={buscar}>
            {buscando ? 'Buscando…' : 'Buscar'}
          </button>
        </div>

        {erro && <p className="erro-campo">{erro}</p>}

        {orcamento && (
          <>
            <p className="muted" style={{ marginTop: 12 }}>
              {orcamento.nomeCliente} · Vendedor {orcamento.nomeFuncionario} · Válido até{' '}
              <strong>{formatarDataSimples(orcamento.dataValidade)}</strong>
            </p>
            <p className="muted" style={{ marginTop: 0 }}>
              Cliente e vendedor já vão preenchidos na venda. A quantidade pode ser{' '}
              <strong>reduzida</strong> (zero = não levar), nunca aumentada — o preço é o do
              orçamento.
            </p>

            <div className="table-wrap" style={{ maxHeight: '44vh' }}>
              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>SKU</th>
                    <th>Produto</th>
                    <th style={{ textAlign: 'right' }}>Orçado</th>
                    <th style={{ textAlign: 'right' }}>Levar</th>
                    <th style={{ textAlign: 'right' }}>Preço</th>
                    <th style={{ textAlign: 'right' }}>Total</th>
                  </tr>
                </thead>
                <tbody>
                  {orcamento.itens.map((i) => (
                    <tr key={i.idOrcamentoItem} className={i.produtoInativo ? 'linha-selecionada' : ''}>
                      <td className="mono">{i.sku}</td>
                      <td>
                        {i.descricao}
                        {(i.variacaoCor || i.variacaoTamanho) && (
                          <span className="muted">
                            {' '}
                            {[i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · ')}
                          </span>
                        )}
                        {i.produtoInativo && (
                          <span className="badge badge-inativo" style={{ marginLeft: 8 }}>
                            inativado — não pode ser vendido
                          </span>
                        )}
                      </td>
                      <td style={{ textAlign: 'right' }}>{i.qtd}</td>
                      <td style={{ textAlign: 'right' }}>
                        <input
                          className="mono"
                          inputMode="numeric"
                          style={{ width: 80, textAlign: 'right' }}
                          disabled={i.produtoInativo}
                          value={String(levando[i.idVariacao] ?? 0)}
                          onFocus={(e) => e.target.select()}
                          onChange={(e) => {
                            const digitos = e.target.value.replace(/\D/g, '')
                            // Teto = quantidade orçada. Digitar mais simplesmente não sobe.
                            const q = Math.min(digitos === '' ? 0 : Number(digitos), i.qtd)
                            setLevando((atual) => ({ ...atual, [i.idVariacao]: q }))
                          }}
                        />
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.precoVenda)}</td>
                      <td className="mono" style={{ textAlign: 'right' }}>
                        R$ {formatarMoeda((levando[i.idVariacao] ?? 0) * i.precoVenda)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <p style={{ textAlign: 'right', marginTop: 12 }}>
              Total a levar <strong className="mono" style={{ fontSize: 18 }}>R$ {formatarMoeda(totalLevando)}</strong>
            </p>

            {parcial && (
              <p className="muted">
                ⚠️ O cliente está levando menos do que foi orçado. O orçamento será fechado como
                <strong> vendido em parte</strong> — o que sobrar <strong>não</strong> poderá ser
                vendido por ele depois.
              </p>
            )}
          </>
        )}

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Fechar
          </button>
          <button
            type="button"
            className="btn"
            disabled={!orcamento || !algumItem}
            onClick={() => orcamento && aoConfirmar(orcamento, levando)}
          >
            Levar para a venda
          </button>
        </div>
      </div>
    </div>
  )
}

function formatarDataSimples(iso: string): string {
  const [ano, mes, dia] = iso.split('-')
  return `${dia}/${mes}/${ano}`
}

/** Mensagem específica por estado — "não pode" sem motivo manda o operador adivinhar. */
function motivoDeNaoPoder(o: Orcamento): string {
  switch (o.situacao) {
    case 'VENCIDO':
      return `O orçamento nº ${o.idOrcamento} venceu em ${formatarDataSimples(o.dataValidade)} e não pode virar venda. Emita um novo.`
    case 'CANCELADO':
      return `O orçamento nº ${o.idOrcamento} foi cancelado (${o.motivoCancelamento ?? 'sem motivo registrado'}).`
    case 'VENDIDO':
      return `O orçamento nº ${o.idOrcamento} já virou a venda nº ${o.idVenda}.`
    case 'VENDIDO_PARCIAL':
      return `O orçamento nº ${o.idOrcamento} já virou a venda nº ${o.idVenda} (o cliente levou parte). O que sobrou precisa de uma venda nova.`
    default:
      return 'Este orçamento não pode virar venda.'
  }
}

export type { ItemOrcamento, Levando }
