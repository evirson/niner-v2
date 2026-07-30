import type { CarteiraParaAbertura } from '../lib/caixa'

/** Campos de Abertura de Caixa — moeda (tipo de carteira) + saldo inicial. Compartilhado entre
 *  a tela dedicada e o popup obrigatório do PDV/Recebimento de Crediário (2026-07-30). */
export default function CamposAberturaCaixa({
  carteiras,
  idCarteira,
  valorTexto,
  aoMudarCarteira,
  aoMudarValor,
  aoFinalizarValor,
}: {
  carteiras: CarteiraParaAbertura[]
  idCarteira: number | ''
  valorTexto: string
  aoMudarCarteira: (id: number) => void
  aoMudarValor: (texto: string) => void
  aoFinalizarValor: (texto: string) => void
}) {
  return (
    <div className="form-grid">
      <div className="col-6">
        <label htmlFor="abertura-caixa-carteira">Moeda (Tipo de Carteira) *</label>
        <select
          id="abertura-caixa-carteira"
          value={idCarteira}
          onChange={(e) => aoMudarCarteira(Number(e.target.value))}
        >
          <option value="">Selecione…</option>
          {carteiras.map((c) => (
            <option key={c.idCarteira} value={c.idCarteira}>
              {c.nomeCarteira}
            </option>
          ))}
        </select>
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
    </div>
  )
}
