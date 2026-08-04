import { useNavigate } from 'react-router-dom'
import { IconeFechar } from './Icones'

/** Botão de fechar (X), canto superior direito de toda tela (2026-08-04) — substitui o antigo
 * botão de texto "Voltar"/"Cancelar". Por padrão volta um passo no histórico do navegador
 * (a tela de onde o usuário realmente veio, não uma rota fixa — uma rota fixa "pula" telas
 * intermediárias em fluxos aninhados, ex. Estoque > Contagem > Zerar Contagem). `onClick`
 * cobre os casos que precisam rodar lógica antes de navegar (ex.: formulário embutido). */
export function BotaoFecharTela({ onClick }: { onClick?: () => void }) {
  const navigate = useNavigate()
  return (
    <button
      type="button"
      className="btn ghost btn-fechar-tela"
      onClick={onClick ?? (() => navigate(-1))}
      aria-label="Fechar"
      title="Fechar"
    >
      <IconeFechar />
    </button>
  )
}
