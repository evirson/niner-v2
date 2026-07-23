/**
 * Confirmação exibida quando o usuário salva pressionando Enter num campo de texto (em vez de
 * clicar em "Salvar") — evita salvar sem querer ao digitar (ex.: Enter no CEP). Clicar em
 * "Salvar" diretamente não passa por aqui.
 */
export default function ConfirmarSalvarModal({
  aoConfirmar,
  aoCancelar,
}: {
  aoConfirmar: () => void
  aoCancelar: () => void
}) {
  return (
    <div className="modal-overlay" onClick={aoCancelar}>
      <div className="modal" role="dialog" aria-label="Confirmar salvar" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Salvar dados?</h2>
        <p className="muted">Deseja salvar os dados deste cadastro?</p>
        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoCancelar}>
            Cancelar
          </button>
          <button type="button" className="btn" onClick={aoConfirmar}>
            Salvar
          </button>
        </div>
      </div>
    </div>
  )
}
