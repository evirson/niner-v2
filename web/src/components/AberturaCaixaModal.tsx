import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { ApiError } from '../lib/api'
import { abrirCaixa, listarCarteirasParaAbertura, type CaixaStatus } from '../lib/caixa'
import { completarMoeda, desmascararMoeda, formatarMoeda, mascararMoeda } from '../lib/masks'
import CamposAberturaCaixa from './CamposAberturaCaixa'
import Toast from './Toast'

/**
 * Popup obrigatório (2026-07-30): quando o PDV ou o Recebimento de Crediário detectam que não
 * há caixa aberto hoje para o usuário/empresa, este popup bloqueia a tela até a abertura ser
 * confirmada — sem opção de "cancelar e continuar mesmo assim"; nessas duas telas a única saída
 * é "Voltar", que sai da tela (pedido do dono do produto).
 *
 * <p>Terceiro uso desde 2026-08-15: **Contas a Pagar / Pagas**, quando o operador escolhe pagar
 * com "Caixa da loja" e não há caixa aberto (a baixa em dinheiro grava `caixa_detalhe` e exige
 * caixa — ver `docs/telas/contas-pagar.md`). Lá o popup **não** é obrigatório-de-entrada: pagar
 * pela conta corrente, ou só editar a conta, não precisa de caixa. Por isso o `aoVoltar` daquela
 * tela apenas limpa a origem escolhida e devolve o operador ao formulário, em vez de navegar pra
 * fora. O componente não decide isso — quem passa o `aoVoltar` decide.
 *
 * <p><b>Reabertura no mesmo dia (2026-08-19):</b> só pode existir 1 {@code caixa_mestre} por
 * empresa+usuário+dia — se o usuário já tinha aberto e fechado o caixa hoje, o backend REABRE o
 * mesmo caixa em vez de criar outro (`CaixaService.abrir`). Este popup só precisa saber disso pra
 * pré-preencher o saldo inicial com o valor já usado hoje, em vez de sempre começar em zero — o
 * `statusCaixa` já vem com {@code idCaixa}/{@code saldoInicial} preenchidos nesse caso, mesmo com
 * {@code aberto: false} (ver `CaixaService.status`). O operador pode manter ou alterar o valor.
 */
export default function AberturaCaixaModal({
  aoAbrir,
  aoVoltar,
  statusCaixa,
}: {
  aoAbrir: () => void
  aoVoltar: () => void
  statusCaixa?: CaixaStatus
}) {
  const reabrindoCaixaDeHoje = statusCaixa?.idCaixa != null
  const [idCarteira, setIdCarteira] = useState<number | ''>('')
  const [valorTexto, setValorTexto] = useState(
    reabrindoCaixaDeHoje && statusCaixa?.saldoInicial != null ? formatarMoeda(statusCaixa.saldoInicial) : '0,00',
  )
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
          {reabrindoCaixaDeHoje
            ? 'Você já tinha aberto e fechado o caixa hoje — ele será reaberto (não é um caixa novo). O saldo inicial abaixo veio da última abertura; altere se precisar.'
            : 'Não há caixa aberto hoje para este usuário — informe o saldo inicial para continuar.'}
        </p>

        <CamposAberturaCaixa
          carteiras={carteiras ?? []}
          valorTexto={valorTexto}
          aoMudarValor={(t) => setValorTexto(mascararMoeda(t))}
          aoFinalizarValor={(t) => setValorTexto(formatarMoeda(desmascararMoeda(completarMoeda(t))))}
        />

        {/* ⚠️ Toast, e não parágrafo inline: isto é a FALHA DA AÇÃO que o operador acabou de
            disparar (403 de RBAC, 409 de caixa já aberto por outro terminal, "nenhuma carteira
            Dinheiro cadastrada"), não um aviso de campo. Saía em cinza-avermelhado de 14px entre o
            campo e o rodapé, sem `role="alert"`, num popup com dois botões logo abaixo — fácil não
            registrar que houve erro e clicar de novo. E não era limitação técnica: `.toast` é
            z-index 200 contra os 100 do `.modal-overlay`, e nove modais do projeto já fazem assim.
            Este é o popup que TODO operador vê todo dia ao abrir o PDV. */}
        {erro && <Toast mensagem={erro} tipo="erro" aoFechar={() => setErro(null)} />}

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoVoltar}>
            Voltar
          </button>
          <button type="button" className="btn" disabled={idCarteira === '' || abrir.isPending} onClick={() => abrir.mutate()}>
            {abrir.isPending ? 'Abrindo…' : reabrindoCaixaDeHoje ? 'Reabrir Caixa' : 'Abrir Caixa'}
          </button>
        </div>
      </div>
    </div>
  )
}
