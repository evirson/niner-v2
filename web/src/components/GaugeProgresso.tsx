import { useEffect, useState } from 'react'

interface GaugeProgressoProps {
  rotulo: string
  /** Registro atual/total real (2026-08-11, polling de `/importacao/progresso/:id`) — quando
   *  informados (total > 0), o anel mostra o percentual de verdade e o texto "X de Y registros"
   *  em vez da animação simulada abaixo. */
  atual?: number
  total?: number
}

/**
 * Anel de progresso (SVG). Quando `atual`/`total` reais são passados, mostra o percentual de
 * verdade + "X de Y registros". Sem eles (ex.: detecção do arquivo, que só lê o cabeçalho — não
 * há "registro" pra contar), cai no modo simulado antigo (2026-08-06): sobe suave até ~92%
 * enquanto espera (passos cada vez menores, nunca "trava" visualmente) e o chamador desmonta o
 * componente quando a resposta chega — dá sensação de progresso sem fingir uma precisão que não
 * existe nesse caso.
 */
export default function GaugeProgresso({ rotulo, atual, total }: GaugeProgressoProps) {
  const [progressoSimulado, setProgressoSimulado] = useState(0)
  const temProgressoReal = total !== undefined && total > 0 && atual !== undefined

  useEffect(() => {
    if (temProgressoReal) return
    setProgressoSimulado(0)
    const intervalo = setInterval(() => {
      setProgressoSimulado((p) => (p >= 92 ? p : p + (92 - p) * 0.08))
    }, 150)
    return () => clearInterval(intervalo)
  }, [temProgressoReal])

  const progresso = temProgressoReal ? Math.min(100, (atual! / total!) * 100) : progressoSimulado

  const raio = 40
  const circunferencia = 2 * Math.PI * raio
  const offset = circunferencia * (1 - progresso / 100)

  return (
    <div className="gauge-progresso" role="status" aria-live="polite">
      <svg width="96" height="96" viewBox="0 0 96 96">
        <circle cx="48" cy="48" r={raio} className="gauge-progresso-trilha" />
        <circle
          cx="48"
          cy="48"
          r={raio}
          className="gauge-progresso-anel"
          style={{ strokeDasharray: circunferencia, strokeDashoffset: offset }}
          transform="rotate(-90 48 48)"
        />
        <text x="48" y="54" textAnchor="middle" className="gauge-progresso-numero">
          {Math.round(progresso)}%
        </text>
      </svg>
      <p className="muted" style={{ marginTop: 8 }}>{rotulo}</p>
      {temProgressoReal && (
        <p className="muted" style={{ marginTop: 2, fontSize: 12 }}>
          {atual!.toLocaleString('pt-BR')} de {total!.toLocaleString('pt-BR')} registro(s)
        </p>
      )}
    </div>
  )
}
