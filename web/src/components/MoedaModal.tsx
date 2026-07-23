import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../lib/api'
import { completarPercentual, desmascararPercentual, mascararPercentual } from '../lib/masks'
import { criarMoeda } from '../lib/moedas'
import { maiusculas } from '../lib/texto'
import Toast from './Toast'

/**
 * Criação rápida de moeda embutida no formulário de Tipo de Carteira — mesmo papel do
 * `PlanoContasModal` no cadastro de fornecedor: se a moeda que o usuário quer vincular ainda
 * não existe, ele cria sem sair da tela. A gestão completa (editar nome/percentuais depois)
 * continua na tela própria (/moedas).
 */
export default function MoedaModal({
  aoFechar,
  aoCriar,
}: {
  aoFechar: () => void
  aoCriar: (idMoeda: number, nomeMoeda: string) => void
}) {
  const queryClient = useQueryClient()
  const [nomeMoeda, setNomeMoeda] = useState('')
  const [percDesconto, setPercDesconto] = useState('')
  const [percAcrescimo, setPercAcrescimo] = useState('')
  const [toast, setToast] = useState('')

  const criar = useMutation({
    mutationFn: () =>
      criarMoeda({
        nomeMoeda: maiusculas(nomeMoeda.trim()),
        percDesconto: percDesconto.trim() ? desmascararPercentual(percDesconto) : null,
        percAcrescimo: percAcrescimo.trim() ? desmascararPercentual(percAcrescimo) : null,
      }),
    onSuccess: (moeda) => {
      queryClient.invalidateQueries({ queryKey: ['moedas'] })
      aoCriar(moeda.idMoeda, moeda.nomeMoeda)
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível criar a moeda.'),
  })

  const valido = nomeMoeda.trim().length > 0

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Nova moeda" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Nova moeda</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Criação rápida — a gestão completa fica na tela Moeda.
        </p>

        <label htmlFor="modal-moeda-nome">Nome *</label>
        <input
          id="modal-moeda-nome"
          autoFocus
          value={nomeMoeda}
          onChange={(e) => setNomeMoeda(maiusculas(e.target.value))}
        />

        <p className="muted" style={{ marginTop: 4 }}>
          Desconto OU acréscimo — nunca os dois. Deixe em branco se não se aplicar.
        </p>

        <label htmlFor="modal-moeda-desconto">% Desconto</label>
        <input
          id="modal-moeda-desconto"
          inputMode="decimal"
          placeholder="0,00"
          value={percDesconto}
          onChange={(e) => {
            const valor = mascararPercentual(e.target.value)
            setPercDesconto(valor)
            if (Number(valor.replace(',', '.')) > 0) setPercAcrescimo('')
          }}
          onBlur={() => setPercDesconto((v) => completarPercentual(v))}
        />

        <label htmlFor="modal-moeda-acrescimo">% Acréscimo</label>
        <input
          id="modal-moeda-acrescimo"
          inputMode="decimal"
          placeholder="0,00"
          value={percAcrescimo}
          onChange={(e) => {
            const valor = mascararPercentual(e.target.value)
            setPercAcrescimo(valor)
            if (Number(valor.replace(',', '.')) > 0) setPercDesconto('')
          }}
          onBlur={() => setPercAcrescimo((v) => completarPercentual(v))}
        />

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Cancelar
          </button>
          <button type="button" className="btn" disabled={!valido || criar.isPending} onClick={() => criar.mutate()}>
            {criar.isPending ? 'Criando…' : 'Criar'}
          </button>
        </div>
      </div>

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
