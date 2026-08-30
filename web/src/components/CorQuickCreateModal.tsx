import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../lib/api'
import { criarCor, type Cor } from '../lib/cores'
import { maiusculas } from '../lib/texto'
import Toast from './Toast'

/**
 * Cadastro rápido de cor (2026-08-12) — popup dedicado, substitui o antigo padrão "campo +
 * texto + botão inline" (`+ Nova cor…`) nas telas que criam variação (Entrada de Produtos,
 * Emissão de Etiqueta, cadastro rápido de Produto): abre, cadastra, fecha e já devolve a cor
 * criada pronta pra ficar selecionada no lugar de origem — sem exigir mais nenhuma ação do
 * operador.
 */
export default function CorQuickCreateModal({
  aoFechar,
  aoCriar,
}: {
  aoFechar: () => void
  aoCriar: (cor: Cor) => void
}) {
  const queryClient = useQueryClient()
  const [nome, setNome] = useState('')
  const [toast, setToast] = useState('')

  const criar = useMutation({
    mutationFn: criarCor,
    onSuccess: (cor) => {
      queryClient.invalidateQueries({ queryKey: ['cores'] })
      aoCriar(cor)
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível criar a cor.'),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Nova cor" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Nova cor</h2>

        <label htmlFor="nova-cor-nome">Nome da cor *</label>
        <input
          id="nova-cor-nome"
          autoFocus
          value={nome}
          onChange={(e) => setNome(maiusculas(e.target.value))}
          placeholder="ex.: AZUL, PRETO…"
          // ⚠️ `preventDefault` é obrigatório em todo campo cujo Enter tem propósito próprio: sem
          // ele o `iniciarNavegacaoGlobalPorEnter` roda TAMBÉM e move o foco por baixo. Hoje o
          // popup tem um único input, então `focarProximoCampo` devolve false e nada acontece —
          // o defeito é LATENTE, e nasce no dia em que este popup ganhar um segundo campo.
          onKeyDown={(e) => {
            if (e.key === 'Enter' && nome.trim() && !criar.isPending) {
              e.preventDefault()
              criar.mutate(nome.trim())
            }
          }}
        />

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Cancelar
          </button>
          <button type="button" className="btn" disabled={!nome.trim() || criar.isPending} onClick={() => criar.mutate(nome.trim())}>
            {criar.isPending ? 'Criando…' : 'Criar'}
          </button>
        </div>
      </div>

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
