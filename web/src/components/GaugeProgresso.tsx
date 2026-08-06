import { useEffect, useState } from 'react'

/**
 * Anel de progresso (SVG) para operações de importação que não têm como reportar percentual
 * real — o backend processa o arquivo inteiro numa única resposta, sem progresso incremental
 * (2026-08-06, pedido do dono do produto: mostrar uma "gauge" durante a análise). Sobe suave
 * até ~92% enquanto espera (passos cada vez menores, nunca "trava" visualmente) e o chamador
 * desmonta o componente quando a resposta chega — dá sensação de progresso sem fingir uma
 * precisão que o sistema não tem.
 */
export default function GaugeProgresso({ rotulo }: { rotulo: string }) {
  const [progresso, setProgresso] = useState(0)

  useEffect(() => {
    setProgresso(0)
    const intervalo = setInterval(() => {
      setProgresso((p) => (p >= 92 ? p : p + (92 - p) * 0.08))
    }, 150)
    return () => clearInterval(intervalo)
  }, [])

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
    </div>
  )
}
