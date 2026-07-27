import { useEffect } from 'react'

export type TipoToast = 'erro' | 'sucesso'

/**
 * Pop-up de aviso/erro/sucesso (canto superior direito), para mensagens que não devem
 * depender de o usuário rolar até o rodapé da tela para ver (docs/telas/cliente.md).
 * Fecha sozinho depois de alguns segundos ou no clique do usuário. `tipo` define a cor —
 * vermelho para erro/alerta (padrão), verde para sucesso — sempre com letra branca
 * (2026-07-24, pedido do dono do produto: nenhuma mensagem de erro/alerta ou de sucesso
 * fica só num aviso inline na página, todas viram popup).
 */
export default function Toast({
  mensagem,
  tipo = 'erro',
  aoFechar,
}: {
  mensagem: string
  tipo?: TipoToast
  aoFechar: () => void
}) {
  useEffect(() => {
    const t = setTimeout(aoFechar, 6000)
    return () => clearTimeout(t)
  }, [mensagem, aoFechar])

  return (
    <div className={`toast toast-${tipo}`} role={tipo === 'erro' ? 'alert' : 'status'}>
      <span>{mensagem}</span>
      <button type="button" className="toast-fechar" aria-label="Fechar aviso" onClick={aoFechar}>
        ×
      </button>
    </div>
  )
}
