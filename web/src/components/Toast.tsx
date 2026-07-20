import { useEffect } from 'react'

/**
 * Pop-up de aviso/erro (canto superior direito), para mensagens que não devem depender de
 * o usuário rolar até o rodapé da tela para ver (docs/telas/cliente.md). Fecha sozinho
 * depois de alguns segundos ou no clique do usuário.
 */
export default function Toast({ mensagem, aoFechar }: { mensagem: string; aoFechar: () => void }) {
  useEffect(() => {
    const t = setTimeout(aoFechar, 6000)
    return () => clearTimeout(t)
  }, [mensagem, aoFechar])

  return (
    <div className="toast" role="alert">
      <span>{mensagem}</span>
      <button type="button" className="toast-fechar" aria-label="Fechar aviso" onClick={aoFechar}>
        ×
      </button>
    </div>
  )
}
