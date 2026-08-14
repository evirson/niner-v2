import { useEffect, useRef, useState } from 'react'
import { salvarTema, temaEfetivo, temaSalvo, type Tema } from '../lib/tema'
import { IconeLua, IconeMonitor, IconeSol } from './Icones'

const OPCOES: { valor: Tema; rotulo: string; icone: (props: { size?: number }) => React.ReactElement }[] = [
  { valor: 'claro', rotulo: 'Claro', icone: IconeSol },
  { valor: 'escuro', rotulo: 'Escuro', icone: IconeLua },
  { valor: 'auto', rotulo: 'Automático', icone: IconeMonitor },
]

/** Seletor de tema do cabeçalho do ERP (2026-08-14) — três estados (Claro / Escuro /
 *  Automático), guardados em `localStorage` por navegador. Menu em vez de botão que só alterna:
 *  com três estados, um botão cíclico esconde o "Automático" (o usuário não descobre que existe)
 *  e não mostra qual está ativo. O ícone do gatilho reflete o tema **em uso** — com "Automático"
 *  ele vira sol ou lua conforme o sistema operacional, e não o monitor, senão a barra não diz
 *  nada sobre o que está na tela.
 *
 *  A troca é instantânea e não recarrega nada: só reescreve `data-theme` no `<html>` e o CSS
 *  inteiro acompanha, porque toda cor do projeto sai de token (`var(--x)`), incluindo os
 *  gráficos Recharts. Ver `lib/tema.ts` para o porquê de "Automático" não escrever atributo. */
export default function SeletorTema() {
  const [tema, setTema] = useState<Tema>(temaSalvo)
  const [aberto, setAberto] = useState(false)
  const caixaRef = useRef<HTMLDivElement>(null)

  // Fecha ao clicar fora / apertar Esc — mesmo comportamento dos outros menus do shell.
  useEffect(() => {
    if (!aberto) return
    const aoClicarFora = (e: MouseEvent) => {
      if (caixaRef.current && !caixaRef.current.contains(e.target as Node)) setAberto(false)
    }
    const aoTeclar = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setAberto(false)
    }
    document.addEventListener('mousedown', aoClicarFora)
    document.addEventListener('keydown', aoTeclar)
    return () => {
      document.removeEventListener('mousedown', aoClicarFora)
      document.removeEventListener('keydown', aoTeclar)
    }
  }, [aberto])

  // Com "Automático", o tema da tela muda quando o usuário troca o do Windows com o ERP aberto —
  // o CSS acompanha sozinho, mas o ícone do gatilho não, porque é estado do React.
  useEffect(() => {
    if (tema !== 'auto') return
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const aoMudar = () => setTema('auto')
    mq.addEventListener('change', aoMudar)
    return () => mq.removeEventListener('change', aoMudar)
  }, [tema])

  const escolher = (novo: Tema) => {
    salvarTema(novo)
    setTema(novo)
    setAberto(false)
  }

  const emUso = temaEfetivo(tema)
  const IconeGatilho = emUso === 'escuro' ? IconeLua : IconeSol
  const rotuloAtual = OPCOES.find((o) => o.valor === tema)!.rotulo

  return (
    <div className="seletor-tema" ref={caixaRef}>
      <button
        type="button"
        className="seletor-tema-gatilho"
        onClick={() => setAberto((a) => !a)}
        aria-haspopup="menu"
        aria-expanded={aberto}
        aria-label={`Tema: ${rotuloAtual}. Trocar`}
        title={`Tema: ${rotuloAtual}`}
      >
        <IconeGatilho size={20} />
      </button>
      {aberto && (
        <div className="seletor-tema-menu" role="menu">
          {OPCOES.map((o) => {
            const Icone = o.icone
            return (
              <button
                key={o.valor}
                type="button"
                role="menuitemradio"
                aria-checked={tema === o.valor}
                className={`seletor-tema-opcao${tema === o.valor ? ' seletor-tema-opcao-ativa' : ''}`}
                onClick={() => escolher(o.valor)}
              >
                <Icone size={18} />
                {o.rotulo}
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
