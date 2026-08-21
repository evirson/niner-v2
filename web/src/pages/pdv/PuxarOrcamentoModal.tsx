import { useState } from 'react'
import { ApiError } from '../../lib/api'
import {
  buscarOrcamento,
  listarOrcamentos,
  type ItemOrcamento,
  type Orcamento,
  type OrcamentoResumo,
} from '../../lib/orcamento'
import { maiusculas } from '../../lib/texto'
import { formatarMoeda } from '../../lib/masks'

/** Quantidade que o operador decidiu levar de cada item, por `idVariacao`. */
type Levando = Record<number, number>

/**
 * Busca e puxa um orçamento para dentro do PDV (docs/telas/orcamento.md, R2). Desde 2026-08-21
 * o popup LOCALIZA (número, cliente ou vendedor, só os em aberto) antes de abrir — o operador
 * raramente tem o número na mão; ele tem o nome de quem está na frente dele.
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
  const [nomeCliente, setNomeCliente] = useState('')
  const [nomeVendedor, setNomeVendedor] = useState('')
  const [resultados, setResultados] = useState<OrcamentoResumo[] | null>(null)
  const [orcamento, setOrcamento] = useState<Orcamento | null>(null)
  const [levando, setLevando] = useState<Levando>({})
  const [erro, setErro] = useState<string | null>(null)
  const [buscando, setBuscando] = useState(false)

  /**
   * Localiza orçamentos por número, cliente ou vendedor (2026-08-21) — **só os ABERTOS**.
   *
   * <p>Filtrar a situação no servidor, e não na tela, é o que evita oferecer ao operador um
   * orçamento que o PDV vai recusar em seguida: vencido, cancelado ou já vendido não têm por que
   * aparecer numa lista cujo único propósito é virar venda.
   */
  const localizar = async () => {
    setBuscando(true)
    setErro(null)
    setOrcamento(null)
    try {
      const pagina = await listarOrcamentos({
        situacao: 'ABERTO',
        numero: numero.trim() ? Number(numero.trim()) : undefined,
        nomeCliente: nomeCliente.trim() || undefined,
        nomeFuncionario: nomeVendedor.trim() || undefined,
        limite: 50,
      })
      setResultados(pagina.itens)
      if (pagina.itens.length === 0) {
        setErro('Nenhum orçamento em aberto com esses filtros.')
      }
    } catch (e) {
      setErro(e instanceof ApiError ? e.message : 'Não foi possível localizar orçamentos.')
      setResultados(null)
    } finally {
      setBuscando(false)
    }
  }

  /** Abre o orçamento escolhido na lista. ⚠️ Continua checando a situação: entre a busca e o
   *  clique o documento pode ter vencido — ler um vencido é justamente o que o marca (R6). */
  const abrir = async (idOrcamento: number) => {
    setBuscando(true)
    setErro(null)
    try {
      const o = await buscarOrcamento(idOrcamento)
      if (o.situacao !== 'ABERTO') {
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
        aria-label="Buscar orçamento"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 style={{ marginTop: 0 }}>Buscar Orçamento</h2>

        {/* Três filtros de mesma largura + o botão, numa grade só — os campos ficam alinhados em
            cima e embaixo, em vez de cada um com o tamanho do próprio conteúdo. Enter em qualquer
            um deles localiza, que é como se busca de balcão: digita e aperta. */}
        <div className="orcamento-busca-filtros">
          <div>
            <label htmlFor="orc-numero">Número</label>
            <input
              id="orc-numero"
              autoFocus
              inputMode="numeric"
              className="mono"
              value={numero}
              onChange={(e) => setNumero(e.target.value.replace(/\D/g, ''))}
              onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), localizar())}
            />
          </div>
          <div>
            <label htmlFor="orc-cliente">Cliente</label>
            <input
              id="orc-cliente"
              value={nomeCliente}
              onChange={(e) => setNomeCliente(maiusculas(e.target.value))}
              onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), localizar())}
            />
          </div>
          <div>
            <label htmlFor="orc-vendedor">Vendedor</label>
            <input
              id="orc-vendedor"
              value={nomeVendedor}
              onChange={(e) => setNomeVendedor(maiusculas(e.target.value))}
              onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), localizar())}
            />
          </div>
          <button type="button" className="btn" disabled={buscando} onClick={localizar}>
            {buscando ? 'Buscando…' : 'Localizar'}
          </button>
        </div>
        <p className="muted" style={{ margin: '6px 0 0' }}>
          Só aparecem orçamentos <strong>em aberto</strong> — vencido, cancelado ou já vendido não
          vira venda. Sem preencher nada, lista os últimos.
        </p>

        {erro && <p className="erro-campo">{erro}</p>}

        {resultados && resultados.length > 0 && !orcamento && (
          <div className="table-wrap" style={{ maxHeight: '42vh', marginTop: 10 }}>
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th style={{ textAlign: 'right' }}>Nº</th>
                  <th>Cliente</th>
                  <th>Vendedor</th>
                  <th>Válido até</th>
                  <th style={{ textAlign: 'right' }}>Itens</th>
                  <th style={{ textAlign: 'right' }}>Total</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {resultados.map((o) => (
                  <tr key={o.idOrcamento}>
                    <td className="mono" style={{ textAlign: 'right' }}>{o.idOrcamento}</td>
                    <td>{o.nomeCliente}</td>
                    <td>{o.nomeFuncionario}</td>
                    <td className="mono">{formatarDataSimples(o.dataValidade)}</td>
                    <td style={{ textAlign: 'right' }}>{o.qtdItens}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(o.valorTotal)}</td>
                    <td style={{ textAlign: 'right' }}>
                      <button type="button" className="btn ghost" disabled={buscando} onClick={() => abrir(o.idOrcamento)}>
                        Abrir
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {orcamento && (
          <>
            <p style={{ margin: '10px 0 0' }}>
              <button type="button" className="btn ghost" onClick={() => setOrcamento(null)}>
                ← Escolher outro
              </button>
            </p>
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
