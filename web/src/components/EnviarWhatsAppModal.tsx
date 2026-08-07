import { useState, type FormEvent } from 'react'
import { celularValido, mascararTelefone } from '../lib/masks'

/**
 * Popup genérico "confirme o destinatário antes de enviar por WhatsApp" — pré-preenche com o
 * celular já cadastrado (se houver), mas sempre deixa o usuário revisar/trocar o número antes de
 * confirmar (pedido explícito, 2026-08-07: nunca dispara o envio direto sem essa checagem visual).
 * Reutilizável por qualquer tela que precise do mesmo fluxo (hoje: comprovante de Recebimento de
 * Crediário; candidato natural depois: Papeleta de Venda).
 */
export default function EnviarWhatsAppModal({
  telefoneInicial,
  enviando,
  erro,
  aoConfirmar,
  aoFechar,
}: {
  telefoneInicial: string | null
  enviando: boolean
  erro: string | null
  aoConfirmar: (telefone: string) => void
  aoFechar: () => void
}) {
  const [telefone, setTelefone] = useState(telefoneInicial ? mascararTelefone(telefoneInicial) : '')
  const [erroCampo, setErroCampo] = useState('')

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    if (!celularValido(telefone)) {
      setErroCampo('Informe um celular válido, com DDD (11 dígitos).')
      return
    }
    setErroCampo('')
    aoConfirmar(telefone)
  }

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Enviar por WhatsApp" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Enviar por WhatsApp</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Confirme (ou troque) o celular que vai receber o link do comprovante.
        </p>
        <form onSubmit={submeter}>
          <label htmlFor="whatsapp-telefone">Celular do destinatário *</label>
          <input
            id="whatsapp-telefone"
            autoFocus
            value={telefone}
            onChange={(e) => {
              setTelefone(mascararTelefone(e.target.value))
              setErroCampo('')
            }}
            onFocus={(e) => e.target.select()}
            placeholder="(00) 00000-0000"
            disabled={enviando}
          />
          {(erroCampo || erro) && <p className="erro-campo">{erroCampo || erro}</p>}
          <div className="ajuda-rodape">
            <button type="button" className="btn ghost" onClick={aoFechar} disabled={enviando}>
              Cancelar
            </button>
            <button type="submit" className="btn" disabled={enviando}>
              {enviando ? 'Enviando…' : 'OK'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
