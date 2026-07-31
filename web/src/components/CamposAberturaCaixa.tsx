import type { CarteiraParaAbertura } from '../lib/caixa'

/** Campos de Abertura de Caixa — saldo inicial + a moeda (tipo de carteira), sempre "Dinheiro"
 *  (2026-07-31: cartão/PIX/crediário não têm saldo inicial de verdade, só recebem movimento
 *  durante o dia — a API já filtra `listarCarteirasParaAbertura` só pra "Dinheiro", então não há
 *  escolha aqui, só exibição). Compartilhado entre a tela dedicada e o popup obrigatório do
 *  PDV/Recebimento de Crediário (2026-07-30). */
export default function CamposAberturaCaixa({
  carteiras,
  valorTexto,
  aoMudarValor,
  aoFinalizarValor,
}: {
  carteiras: CarteiraParaAbertura[]
  valorTexto: string
  aoMudarValor: (texto: string) => void
  aoFinalizarValor: (texto: string) => void
}) {
  const carteira = carteiras[0]
  return (
    <div className="form-grid">
      <div className="col-6">
        <label>Moeda (Tipo de Carteira)</label>
        <div className="pdv-selecao-valor">
          <span>{carteira?.nomeCarteira ?? '—'}</span>
        </div>
      </div>
      <div className="col-6">
        <label htmlFor="abertura-caixa-valor">Saldo Inicial *</label>
        <input
          id="abertura-caixa-valor"
          className="mono"
          inputMode="decimal"
          value={valorTexto}
          onChange={(e) => aoMudarValor(e.target.value)}
          onBlur={(e) => aoFinalizarValor(e.target.value)}
          onFocus={(e) => e.target.select()}
        />
      </div>
      {!carteira && (
        <div className="col-12">
          <p className="erro">
            Nenhuma carteira "Dinheiro" cadastrada para esta empresa — cadastre uma em Tipo de
            Carteira antes de abrir o caixa.
          </p>
        </div>
      )}
    </div>
  )
}
