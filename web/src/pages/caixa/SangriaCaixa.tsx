import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeContaCorrente } from '../../components/Icones'
import SeletorPlanoContas from '../../components/SeletorPlanoContas'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarOpcoesContaCorrente } from '../../lib/contaCorrente'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { completarMoeda, desmascararMoeda, formatarMoeda, mascararMoeda } from '../../lib/masks'
import { buscarContextoSangria, listarSangrias, registrarSangria } from '../../lib/sangria'
import { maiusculas } from '../../lib/texto'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}

/**
 * Sangria de Caixa — tirar dinheiro da gaveta e mandar para uma conta bancária.
 *
 * ⭐ **Sangria é transferência, não saída.** O destino é obrigatório: o servidor grava o débito no
 * caixa e o crédito na conta corrente na mesma transação. Decisão do dono do produto em
 * 2026-08-29 — *"sempre será depositada numa conta bancária, ou vai pro caixa central que tb está
 * definido como uma conta bancária"*. Dinheiro que sai sem destino desaparece do fluxo.
 *
 * ⚠️ **A tela não escolhe o caixa.** É sempre o caixa aberto do próprio usuário, resolvido no
 * servidor — por isso a tela primeiro pergunta o contexto e só então deixa digitar.
 *
 * ⚠️ O `disponivel` é só da **carteira de abertura** (o dinheiro físico). Um caixa que vendeu
 * R$ 3.000 no cartão e R$ 200 em dinheiro tem R$ 200 para sangrar — o resto não está na gaveta.
 */
export default function SangriaCaixa() {
  // ⛔ RBAC (auditoria 2026-08-29, rodada 5): a varredura das rodadas 1-4 cobriu os CADASTROS
  // e deixou as ROTINAS OPERACIONAIS de fora — que é onde o 403 tardio custa mais caro, porque
  // o operador só descobre depois de montar a operação inteira, com o cliente na frente.
  const acoes = usePermissaoDaTela('sangria-caixa')
  const queryClient = useQueryClient()
  const [idContaCorrente, setIdContaCorrente] = useState('')
  const [idPlanoContas, setIdPlanoContas] = useState('')
  const [valorTexto, setValorTexto] = useState('')
  const [observacao, setObservacao] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [sucesso, setSucesso] = useState<string | null>(null)

  const { data: contexto } = useQuery({ queryKey: ['sangria-contexto'], queryFn: buscarContextoSangria })
  const { data: sangrias } = useQuery({ queryKey: ['sangrias'], queryFn: listarSangrias })
  const { data: contas } = useQuery({ queryKey: ['contas-corrente-opcoes'], queryFn: listarOpcoesContaCorrente })

  const disponivel = contexto?.disponivel ?? 0
  const valor = desmascararMoeda(valorTexto)

  const mutacao = useMutation({
    mutationFn: registrarSangria,
    onSuccess: (s) => {
      setSucesso(`Sangria nº ${s.idSangria} registrada — R$ ${formatarMoeda(s.valor)} para ${s.idContaCorrente}.`)
      setValorTexto('')
      setObservacao('')
      // ⚠️ As DUAS chaves: o contexto carrega o disponível, que acabou de mudar. Invalidar só a
      // lista deixaria a tela oferecendo um saldo que não existe mais.
      queryClient.invalidateQueries({ queryKey: ['sangrias'] })
      queryClient.invalidateQueries({ queryKey: ['sangria-contexto'] })
      // O caixa e o extrato da conta mudaram junto — as duas telas leem do mesmo dinheiro.
      queryClient.invalidateQueries({ queryKey: ['caixa-status'] })
      queryClient.invalidateQueries({ queryKey: ['contas-corrente-movimento'] })
    },
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível registrar a sangria.'),
  })

  const enviar = (e: FormEvent) => {
    e.preventDefault()
    if (!idContaCorrente) return setErro('Escolha a conta corrente de destino.')
    if (!idPlanoContas) return setErro('Escolha o plano de contas do lançamento.')
    if (valor <= 0) return setErro('Informe um valor maior que zero.')
    // ⚠️ O servidor confere de novo (é ele quem trava o caixa e relê o saldo). Este aviso existe
    // para o operador não descobrir o problema depois de digitar tudo.
    if (valor > disponivel) {
      return setErro(
        `A sangria de R$ ${formatarMoeda(valor)} é maior que o dinheiro disponível no caixa (R$ ${formatarMoeda(disponivel)}).`,
      )
    }
    mutacao.mutate({ idContaCorrente, valor, idPlanoContas, observacao: observacao.trim() || null })
  }

  return (
    // ⛔ As classes `page`, `page-header`, `page-header-acoes` e `btn-primary` NÃO EXISTEM em
    // styles.css (auditoria 2026-08-29, rodada 4) — a tela mais nova do produto (V094) nasceu
    // inteira fora do padrão e ninguém a viu ainda: sem topbar alinhada, sem o ícone no tamanho
    // dos outros, e com a grade de sangrias saindo como tabela HTML crua, sem borda nem zebra.
    <div className="lista-tela">
      {/* ⚠️ `lista-topo` + `lista-corpo` NÃO são decoração: `lista-tela` é
          `height: 100%` com `flex-direction: column`, e é o `lista-corpo` que carrega o
          `flex: 1; min-height: 0; overflow-y: auto`. Usar só o `lista-tela` (como eu fiz na
          primeira versão desta correção) troca um layout sem estilo nenhum por um layout de
          altura travada SEM área de rolagem — o conteúdo estoura e some numa janela baixa, que é
          o defeito que este projeto já registrou como "altura fixa quebra em outra janela". */}
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeContaCorrente size={34} />
            <h1>Sangria de Caixa</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="sangria-caixa" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      {contexto && !contexto.caixaAberto ? (
        <p className="muted">
          Não há caixa aberto para você agora. Abra o caixa antes de registrar uma sangria.
        </p>
      ) : (
        <>
          <div className="card" style={{ marginBottom: 16 }}>
            <div className="section-label">Disponível para sangria</div>
            <div style={{ fontSize: '1.6em', fontWeight: 600 }}>R$ {formatarMoeda(disponivel)}</div>
            <p className="muted" style={{ marginTop: 4 }}>
              Só o dinheiro em {contexto?.nomeCarteira ?? 'espécie'} — cartão e crediário não estão na gaveta.
            </p>
          </div>

          <form onSubmit={enviar} onKeyDown={aoTeclarEnterNoFormulario}>
            <div className="form-grid">
              <div className="col-4">
                <label htmlFor="idContaCorrente">Conta de destino *</label>
                <select
                  id="idContaCorrente"
                  value={idContaCorrente}
                  onChange={(e) => setIdContaCorrente(e.target.value)}
                  autoFocus
                >
                  <option value="">Selecione…</option>
                  {contas?.map((c) => (
                    <option key={c.idContaCorrente} value={c.idContaCorrente}>
                      {c.idContaCorrente} — {c.descricao}
                    </option>
                  ))}
                </select>
              </div>
              <div className="col-4">
                <label htmlFor="idPlanoContas">Plano de Contas *</label>
                <SeletorPlanoContas id="idPlanoContas" value={idPlanoContas} onChange={setIdPlanoContas} />
              </div>
              <div className="col-2">
                <label htmlFor="valor">Valor *</label>
                <input
                  id="valor"
                  value={valorTexto}
                  onChange={(e) => setValorTexto(mascararMoeda(e.target.value))}
                  onBlur={(e) => setValorTexto(completarMoeda(e.target.value))}
                  onFocus={(e) => e.target.select()}
                  inputMode="decimal"
                />
              </div>
            </div>

            <div className="form-grid">
              <div className="col-10">
                <label htmlFor="observacao">Observação</label>
                <input
                  id="observacao"
                  value={observacao}
                  onChange={(e) => setObservacao(maiusculas(e.target.value))}
                  maxLength={200}
                  placeholder="Ex.: DEPOSITO DO MEIO-DIA"
                />
              </div>
            </div>

            <div className="footer-bar">
              <button type="submit" className="btn" disabled={mutacao.isPending || !acoes.incluir}>
                {mutacao.isPending ? 'Registrando…' : 'Registrar sangria'}
              </button>
            </div>
          </form>

          <p className="section-label" style={{ marginTop: 20 }}>Sangrias deste caixa</p>
          <div className="table-wrap">
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Nº</th>
                  <th>Data</th>
                  <th>Destino</th>
                  <th>Usuário</th>
                  <th>Observação</th>
                  <th style={{ textAlign: 'right' }}>Valor</th>
                </tr>
              </thead>
              <tbody>
                {(sangrias ?? []).length === 0 ? (
                  <tr>
                    <td colSpan={6} className="muted">Nenhuma sangria registrada neste caixa.</td>
                  </tr>
                ) : (
                  sangrias?.map((s) => (
                    <tr key={s.idSangria}>
                      <td>{s.idSangria}</td>
                      <td>{formatarDataHora(s.dataSangria)}</td>
                      <td>{s.idContaCorrente} — {s.descricaoContaCorrente}</td>
                      <td>{s.nomeUsuario}</td>
                      <td>{s.observacao ?? '—'}</td>
                      <td style={{ textAlign: 'right' }}>R$ {formatarMoeda(s.valor)}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
      </div>

      {erro && <Toast mensagem={erro} tipo="erro" aoFechar={() => setErro(null)} />}
      {sucesso && <Toast mensagem={sucesso} tipo="sucesso" aoFechar={() => setSucesso(null)} />}
    </div>
  )
}
