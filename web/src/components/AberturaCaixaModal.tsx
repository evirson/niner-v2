import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { ApiError } from '../lib/api'
import { abrirCaixa, listarCarteirasParaAbertura } from '../lib/caixa'
import { completarMoeda, desmascararMoeda, formatarMoeda, mascararMoeda } from '../lib/masks'
import CamposAberturaCaixa from './CamposAberturaCaixa'

/**
 * Popup obrigatório (2026-07-30): quando o PDV ou o Recebimento de Crediário detectam que não
 * há caixa aberto hoje para o usuário/empresa, este popup bloqueia a tela até a abertura ser
 * confirmada — sem opção de "cancelar e continuar mesmo assim"; a única saída é "Voltar", que
 * sai da tela (pedido do dono do produto).
 */
export default function AberturaCaixaModal({ aoAbrir, aoVoltar }: { aoAbrir: () => void; aoVoltar: () => void }) {
  const [idCarteira, setIdCarteira] = useState<number | ''>('')
  const [valorTexto, setValorTexto] = useState('0,00')
  const [erro, setErro] = useState<string | null>(null)

  const { data: carteiras } = useQuery({ queryKey: ['caixa-carteiras'], queryFn: listarCarteirasParaAbertura })

  useEffect(() => {
    if (idCarteira !== '' || !carteiras || carteiras.length === 0) return
    setIdCarteira(carteiras[0].idCarteira)
  }, [carteiras, idCarteira])

  const abrir = useMutation({
    mutationFn: () => {
      if (idCarteira === '') throw new ApiError(400, 'Selecione a moeda do saldo inicial.')
      return abrirCaixa({ idCarteira, saldoInicial: desmascararMoeda(valorTexto) })
    },
    onSuccess: () => aoAbrir(),
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível abrir o caixa.'),
  })

  return (
    <div className="modal-overlay">
      <div className="modal" role="dialog" aria-label="Abertura de Caixa">
        <h2 style={{ marginTop: 0 }}>Abertura de Caixa</h2>
        <p className="muted" style={{ marginTop: -4 }}>
          Não há caixa aberto hoje para este usuário — informe o saldo inicial para continuar.
        </p>

        <CamposAberturaCaixa
          carteiras={carteiras ?? []}
          valorTexto={valorTexto}
          aoMudarValor={(t) => setValorTexto(mascararMoeda(t))}
          aoFinalizarValor={(t) => setValorTexto(formatarMoeda(desmascararMoeda(completarMoeda(t))))}
        />

        {erro && (
          <p className="erro" style={{ margin: '8px 0' }}>
            {erro}
          </p>
        )}

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoVoltar}>
            Voltar
          </button>
          <button type="button" className="btn" disabled={idCarteira === '' || abrir.isPending} onClick={() => abrir.mutate()}>
            {abrir.isPending ? 'Abrindo…' : 'Abrir Caixa'}
          </button>
        </div>
      </div>
    </div>
  )
}
