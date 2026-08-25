import type { ReactNode } from 'react'
import { IconeFechar } from './Icones'

/**
 * Cabeçalho padrão de popup: título à esquerda, **✕ no canto superior direito** (2026-08-25,
 * pedido do dono do produto — *"para as telas ficarem no mesmo padrão"*).
 *
 * <p>Existe para que a próxima tela não precise lembrar do padrão: antes disto, o fechar de popup
 * era um botão "Fechar" no rodapé, repetido em <b>39 lugares</b> com 5 marcações levemente
 * diferentes (`btn ghost`, `btn`, `btn-secundario`, `btn-secondary`…). Um componente torna a
 * divergência impossível de escrever sem querer.
 *
 * <p>⚠️ O rodapé do popup fica para a **ação que a tela existe para fazer** — "Salvar",
 * "Imprimir", "Levar para a venda". Fechar não é ação de negócio; é saída, e saída mora no ✕.
 *
 * @param titulo   o que a `<h2>` mostra
 * @param aoFechar o mesmo handler que o antigo botão "Fechar" chamava
 * @param acoes    opcional — algo entre o título e o ✕ (ex.: um seletor, um contador)
 */
export default function CabecalhoModal({
  titulo,
  aoFechar,
  acoes,
}: {
  titulo: ReactNode
  aoFechar: () => void
  acoes?: ReactNode
}) {
  return (
    <div className="lightbox-topo" style={{ flexShrink: 0 }}>
      <h2 style={{ margin: 0 }}>{titulo}</h2>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        {acoes}
        <button
          type="button"
          className="btn ghost btn-fechar-tela"
          onClick={aoFechar}
          aria-label="Fechar"
          title="Fechar"
        >
          <IconeFechar />
        </button>
      </div>
    </div>
  )
}
