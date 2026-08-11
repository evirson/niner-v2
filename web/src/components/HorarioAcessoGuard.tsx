import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useEu, type Eu } from '../lib/eu'
import { useRotinaCritica } from '../lib/rotinaCritica'
import Toast from './Toast'

const INTERVALO_MS = 30_000

/** Espelha `HorarioAcessoService.TOLERANCIA_MINUTOS_PADRAO` (backend) — só usado aqui como
 *  denominador do anel (o "cheio" visual); o valor de verdade que decide quando bloquear
 *  sempre vem do backend (`segundosRestantesTolerancia` / o 403 do filtro). Os dois precisam
 *  ficar em sincronia, senão o anel começa "incompleto" mesmo com a tolerância cheia. */
const TOLERANCIA_SEGUNDOS = 15 * 60

/** Mesmo texto de `HorarioAcessoService.MENSAGEM_FORA_DA_JANELA` (backend) — casar exatamente
 *  pra distinguir "acabou o horário de acesso" de qualquer outro 401/403 e disparar o logoff
 *  gracioso (espera terminar rotina crítica) em vez do genérico "Sessão expirada.". */
const MENSAGEM_FORA_DA_JANELA = 'Fora do horário de acesso permitido.'

function formatarMmSs(segundos: number): string {
  const m = Math.floor(segundos / 60)
  const s = segundos % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/**
 * Sem UI própria além do anel de aviso e de um Toast pontual — só ativo pra OPERADOR (ADMIN
 * nunca é afetado pela regra, então nem faz sentido pollar). Reaproveita a queryKey `['eu']` de
 * `useEu()` com `refetchInterval`: não duplica chamada com outras telas que já leem `useEu()`,
 * todas compartilham o mesmo cache.
 *
 * Assim que o backend expõe `segundosRestantesTolerancia` (só nos minutos finais de tolerância
 * — `HorarioAcessoService.segundosRestantesTolerancia`), mostra o anel no canto superior
 * esquerdo com uma contagem regressiva local de 1 em 1 segundo (puramente visual, resincronizada
 * a cada poll); o bloqueio de verdade continua vindo sempre do backend (403 do
 * `HorarioAcessoFilter`), nunca do timer do navegador.
 */
export default function HorarioAcessoGuard() {
  const { data: eu } = useEu()
  const ativo = eu?.usuario.papel === 'OPERADOR'
  const { logout } = useAuth()
  const { emAndamento } = useRotinaCritica()
  const [desconectando, setDesconectando] = useState(false)
  const [aviso, setAviso] = useState('')
  const [segundosRestantes, setSegundosRestantes] = useState<number | null>(null)

  const { data, error } = useQuery({
    queryKey: ['eu'],
    queryFn: () => api<Eu>('/api/v1/eu'),
    enabled: ativo,
    refetchInterval: ativo ? INTERVALO_MS : false,
    retry: false,
  })

  // Resincroniza com o valor de verdade do servidor a cada poll bem-sucedido.
  useEffect(() => {
    if (data) setSegundosRestantes(data.segundosRestantesTolerancia)
  }, [data])

  // Contagem regressiva local, 1s por vez, só visual — entre um poll e outro.
  useEffect(() => {
    if (segundosRestantes === null || segundosRestantes <= 0 || desconectando) return
    const t = setTimeout(() => setSegundosRestantes((s) => (s === null ? null : Math.max(0, s - 1))), 1000)
    return () => clearTimeout(t)
  }, [segundosRestantes, desconectando])

  // O timer local chegou a zero: mesmo aviso final de quando o backend responde 403.
  useEffect(() => {
    if (segundosRestantes === 0 && !desconectando) {
      setDesconectando(true)
      setAviso('Seu horário de acesso terminou — você será desconectado.')
    }
  }, [segundosRestantes, desconectando])

  // Rede de segurança: se por algum motivo o timer local não pegou o fim exato, o próximo poll
  // autenticado que voltar bloqueado (403 com a mensagem exata) dispara o mesmo aviso.
  useEffect(() => {
    if (desconectando) return
    if (error instanceof ApiError && error.message === MENSAGEM_FORA_DA_JANELA) {
      setDesconectando(true)
      setAviso('Seu horário de acesso terminou — você será desconectado.')
    }
  }, [error, desconectando])

  // Reage à queda da flag (ex.: PDV terminou de emitir/imprimir a venda) — sem polling: o
  // próprio React já re-executa este efeito quando `emAndamento` muda de valor.
  useEffect(() => {
    if (desconectando && !emAndamento) {
      logout()
    }
  }, [desconectando, emAndamento, logout])

  return (
    <>
      {segundosRestantes !== null && segundosRestantes > 0 && !desconectando && (
        <AnelDeAviso segundos={segundosRestantes} />
      )}
      {aviso && <Toast mensagem={aviso} aoFechar={() => setAviso('')} />}
    </>
  )
}

function AnelDeAviso({ segundos }: { segundos: number }) {
  const raio = 22
  const circunferencia = 2 * Math.PI * raio
  const progresso = Math.max(0, Math.min(1, segundos / TOLERANCIA_SEGUNDOS))
  const offset = circunferencia * (1 - progresso)

  return (
    <div className="horario-acesso-aviso" role="status" aria-live="polite">
      <svg width="52" height="52" viewBox="0 0 52 52">
        <circle cx="26" cy="26" r={raio} className="horario-acesso-aviso-trilha" />
        <circle
          cx="26"
          cy="26"
          r={raio}
          className="horario-acesso-aviso-anel"
          style={{ strokeDasharray: circunferencia, strokeDashoffset: offset }}
          transform="rotate(-90 26 26)"
        />
        <text x="26" y="30" textAnchor="middle" className="horario-acesso-aviso-numero">
          {formatarMmSs(segundos)}
        </text>
      </svg>
      <span className="horario-acesso-aviso-texto">Horário de acesso encerra em breve</span>
    </div>
  )
}
