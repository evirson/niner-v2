import type { CarteiraDisponivel } from '../../lib/recebimentoCrediario'
import { ROTULO_CATEGORIA_CARTEIRA } from '../../lib/tiposCarteira'

const CATEGORIAS_ORDEM: CarteiraDisponivel['categoriaCarteira'][] = ['AVISTA', 'CARTAO_DEBITO', 'CARTAO_CREDITO']

/**
 * Popup de "Receber" (2026-07-29, revisado no mesmo dia): escolhe a categoria e já define
 * Tipo de Carteira/Valor Pago no próprio popup — sem passo intermediário na tela principal.
 * "Voltar" retorna à lista de categorias sem fechar o popup; "Adicionar Pagamento" lança o
 * pagamento e fecha (RecebimentoCrediario.tsx controla o estado, o popup só exibe).
 */
export default function EscolherFormaPagamentoModal({
  carteiras,
  categoriaAberta,
  carteirasDaCategoria,
  idCarteiraEdicao,
  carteiraEdicao,
  valorPagoTexto,
  valorPagoNumero,
  aoEscolherCategoria,
  aoSelecionarCarteira,
  aoMudarValorPagoTexto,
  aoFinalizarValorPagoTexto,
  aoVoltar,
  aoAdicionar,
  aoFechar,
}: {
  carteiras: CarteiraDisponivel[]
  categoriaAberta: CarteiraDisponivel['categoriaCarteira'] | null
  carteirasDaCategoria: CarteiraDisponivel[]
  idCarteiraEdicao: number | null
  carteiraEdicao: CarteiraDisponivel | null
  valorPagoTexto: string
  valorPagoNumero: number
  aoEscolherCategoria: (categoria: CarteiraDisponivel['categoriaCarteira']) => void
  aoSelecionarCarteira: (idCarteira: number) => void
  aoMudarValorPagoTexto: (texto: string) => void
  aoFinalizarValorPagoTexto: (texto: string) => void
  aoVoltar: () => void
  aoAdicionar: () => void
  aoFechar: () => void
}) {
  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Receber" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Receber</h2>

        {categoriaAberta === null ? (
          <>
            <p className="muted" style={{ marginTop: -4 }}>
              Escolha a forma de pagamento.
            </p>
            <div className="recebimento-categorias-botoes">
              {CATEGORIAS_ORDEM.map((categoria) => {
                const disponivel = carteiras.some((c) => c.categoriaCarteira === categoria)
                return (
                  <button
                    key={categoria}
                    type="button"
                    className="btn pdv-botao-categoria-coluna"
                    disabled={!disponivel}
                    onClick={() => aoEscolherCategoria(categoria)}
                    title={disponivel ? undefined : 'Nenhum tipo de carteira liberado nesta categoria'}
                  >
                    {ROTULO_CATEGORIA_CARTEIRA[categoria]}
                  </button>
                )
              })}
            </div>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={aoFechar}>
                Cancelar
              </button>
            </div>
          </>
        ) : (
          <div className="pdv-edicao-pagamento">
            <div className="pdv-linha-valor-parcelas">
              {carteirasDaCategoria.length > 1 && (
                <div className="pdv-campo-carteira">
                  <label htmlFor="carteira-edicao">Tipo de Carteira *</label>
                  <select
                    id="carteira-edicao"
                    value={idCarteiraEdicao ?? ''}
                    onChange={(e) => aoSelecionarCarteira(Number(e.target.value))}
                  >
                    <option value="" disabled>
                      Selecione…
                    </option>
                    {carteirasDaCategoria.map((c) => (
                      <option key={c.idCarteira} value={c.idCarteira}>
                        {c.nomeCarteira}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              {carteiraEdicao && (
                <div className="pdv-campo-valor-pago">
                  <label htmlFor="valor-pago">Valor Pago *</label>
                  <input
                    id="valor-pago"
                    className="mono"
                    autoFocus
                    value={valorPagoTexto}
                    onChange={(e) => aoMudarValorPagoTexto(e.target.value)}
                    onBlur={(e) => aoFinalizarValorPagoTexto(e.target.value)}
                    onFocus={(e) => e.target.select()}
                  />
                </div>
              )}
            </div>

            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={aoVoltar}>
                Voltar
              </button>
              <button type="button" className="btn" disabled={!carteiraEdicao || valorPagoNumero <= 0} onClick={aoAdicionar}>
                Adicionar Pagamento
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
