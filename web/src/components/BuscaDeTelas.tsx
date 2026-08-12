import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useEu } from '../lib/eu'
import { buscarTelas, filtrarPorPapel, listarTelas, MENU, type TelaBuscavel } from '../lib/menu'
import { maiusculas } from '../lib/texto'
import { IconeLupa } from './Icones'

const MAX_RESULTADOS = 8

/** Busca de telas no cabeçalho (2026-08-03). Nasceu do custo da navegação por hub: com a árvore
 * fora da lateral, chegar a uma tela virou dois cliques — aqui é sempre um atalho de teclado e
 * o Enter. Ctrl+K (ou ⌘K) foca o campo de qualquer lugar do ERP. */
export default function BuscaDeTelas() {
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const isAdmin = eu?.usuario.papel === 'ADMIN'

  const [termo, setTermo] = useState('')
  const [aberto, setAberto] = useState(false)
  const [indiceAtivo, setIndiceAtivo] = useState(0)
  const campoRef = useRef<HTMLInputElement>(null)
  const caixaRef = useRef<HTMLDivElement>(null)

  const telas = useMemo(() => listarTelas(filtrarPorPapel(MENU, isAdmin)), [isAdmin])

  const resultados = useMemo(() => buscarTelas(telas, termo, MAX_RESULTADOS), [telas, termo])

  useEffect(() => setIndiceAtivo(0), [termo])

  // Ctrl+K / ⌘K de qualquer lugar do ERP.
  useEffect(() => {
    const aoTeclar = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        campoRef.current?.focus()
        campoRef.current?.select()
      }
    }
    window.addEventListener('keydown', aoTeclar)
    return () => window.removeEventListener('keydown', aoTeclar)
  }, [])

  // Clique fora fecha a lista (mas não limpa o que foi digitado).
  useEffect(() => {
    const aoClicar = (e: MouseEvent) => {
      if (caixaRef.current && e.target instanceof Node && !caixaRef.current.contains(e.target)) setAberto(false)
    }
    document.addEventListener('mousedown', aoClicar)
    return () => document.removeEventListener('mousedown', aoClicar)
  }, [])

  const abrir = (tela: TelaBuscavel) => {
    setTermo('')
    setAberto(false)
    campoRef.current?.blur()
    navigate(tela.item.to)
  }

  const aoTeclarNoCampo = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape') {
      if (termo) setTermo('')
      else campoRef.current?.blur()
      setAberto(false)
      return
    }
    if (!resultados.length) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setAberto(true)
      setIndiceAtivo((i) => (i + 1) % resultados.length)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setAberto(true)
      setIndiceAtivo((i) => (i - 1 + resultados.length) % resultados.length)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      abrir(resultados[indiceAtivo])
    }
  }

  const mostrarLista = aberto && termo.trim().length > 0

  return (
    <div className="busca-telas" ref={caixaRef}>
      <span className="busca-telas-lupa" aria-hidden="true">
        <IconeLupa size={18} />
      </span>
      <input
        ref={campoRef}
        type="text"
        className="busca-telas-campo"
        value={termo}
        placeholder="Buscar tela…"
        aria-label="Buscar tela"
        autoComplete="off"
        role="combobox"
        aria-expanded={mostrarLista}
        aria-controls="busca-telas-lista"
        onChange={(e) => {
          setTermo(maiusculas(e.target.value))
          setAberto(true)
        }}
        onFocus={() => setAberto(true)}
        onKeyDown={aoTeclarNoCampo}
      />
      {!termo && <kbd className="busca-telas-atalho">Ctrl K</kbd>}

      {mostrarLista && (
        <ul className="busca-telas-lista" id="busca-telas-lista" role="listbox">
          {resultados.length === 0 ? (
            <li className="busca-telas-vazio">Nenhuma tela encontrada para “{termo}”.</li>
          ) : (
            resultados.map((tela, i) => {
              const Icone = tela.item.icone
              return (
                <li key={tela.item.to} role="option" aria-selected={i === indiceAtivo}>
                  <button
                    type="button"
                    className={`busca-telas-item${i === indiceAtivo ? ' ativo' : ''}`}
                    // mousedown corre antes do blur do campo — sem isso o clique se perderia.
                    onMouseDown={(e) => {
                      e.preventDefault()
                      abrir(tela)
                    }}
                    onMouseEnter={() => setIndiceAtivo(i)}
                  >
                    <span className="busca-telas-item-icone">
                      <Icone size={20} />
                    </span>
                    <span className="busca-telas-item-texto">
                      <strong>{tela.item.label}</strong>
                      <span className="busca-telas-item-descricao">{tela.item.descricao}</span>
                    </span>
                    {tela.trilha.length > 0 && <span className="busca-telas-item-trilha">{tela.trilha.join(' › ')}</span>}
                  </button>
                </li>
              )
            })
          )}
        </ul>
      )}
    </div>
  )
}
