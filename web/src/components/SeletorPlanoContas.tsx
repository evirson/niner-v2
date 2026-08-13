import { useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listarPlanosContas, type PlanoContas } from '../lib/planoContas'

/**
 * Seletor de plano de contas com busca por CÓDIGO ou por NOME (2026-08-13, pedido do dono do
 * produto) — substitui os `<select>` de plano de contas em formulários (Fornecedor, Contas a
 * Pagar, Movimentação de Conta Corrente, Parâmetros do Sistema, Importação de Dados) e em
 * filtros de listagem (Fornecedores, Movimentação de Conta Corrente — migrados em 2026-08-22,
 * ver seção de "limpar" abaixo). Digitando dígitos/pontos ("401", "4.01.001") busca por prefixo
 * de código; digitando texto livre busca na descrição. Enter escolhe a linha destacada (setas
 * ↑/↓ movem o destaque) e NÃO dispara o Enter-como-Tab do formulário (stopPropagation só
 * enquanto a lista está aberta); clique escolhe qualquer uma.
 *
 * Carrega o plano inteiro numa query só (tamanho 500 — o plano padrão 9.99.999 tem ~76 contas)
 * e filtra no cliente: sem o problema de select truncado por paginação (2026-08-19).
 *
 * Suporta limpar o valor (`onChange('')`) apagando o texto e saindo do campo (blur) ou
 * apertando Enter com o campo vazio — necessário pros usos como FILTRO de listagem (Fornecedores,
 * Movimentação de Conta Corrente, 2026-08-22), onde "vazio" tem o significado válido de "todos".
 * Nos formulários onde o campo é obrigatório, a validação do próprio formulário já acusa "campo
 * vazio" no blur, então deixar limpar também ali é consistente (mesmo padrão de qualquer
 * typeahead: apagar o texto e sair some com a seleção).
 */
export default function SeletorPlanoContas({
  id,
  value,
  onChange,
  onBlur,
  apenasAnaliticas = false,
  autoFocus = false,
  placeholder = 'Código ou nome da conta…',
  ariaLabel,
}: {
  id?: string
  value: string
  onChange: (codigo: string) => void
  onBlur?: () => void
  /** Só contas analíticas ativas (ex.: Parâmetros do Sistema) — o padrão lista todas as ativas. */
  apenasAnaliticas?: boolean
  autoFocus?: boolean
  placeholder?: string
  /** Acessibilidade — usar quando o campo não tem `<label htmlFor>` visível (ex.: filtro de lista). */
  ariaLabel?: string
}) {
  const { data } = useQuery({
    queryKey: ['planos-contas', 'seletor'],
    queryFn: () => listarPlanosContas({ tamanho: 500 }),
  })

  const contas = useMemo(() => {
    const itens = (data?.itens ?? []).filter((p) => p.ativo)
    return apenasAnaliticas ? itens.filter((p) => p.natureza === 'ANALITICA') : itens
  }, [data, apenasAnaliticas])

  const [texto, setTexto] = useState('')
  const [editando, setEditando] = useState(false)
  const [destaque, setDestaque] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)

  const selecionada = contas.find((p) => p.idPlanoContas === value)
  const exibicao = editando ? texto : value ? `${value} — ${selecionada?.descricao ?? ''}` : ''

  const filtradas = useMemo(() => {
    const t = texto.trim().toUpperCase()
    if (!t) return contas
    // Só dígitos/pontos = busca por prefixo de código (pontos ignorados: "401" acha "4.01.000");
    // qualquer outra coisa = busca na descrição (e no código literal, por via das dúvidas).
    if (/^[\d.]+$/.test(t)) {
      const digitos = t.replace(/\./g, '')
      return contas.filter((p) => p.idPlanoContas.replace(/\./g, '').startsWith(digitos))
    }
    return contas.filter((p) => p.descricao.toUpperCase().includes(t) || p.idPlanoContas.includes(t))
  }, [contas, texto])

  const escolher = (p: PlanoContas) => {
    onChange(p.idPlanoContas)
    setEditando(false)
    setTexto('')
    // O foco continua no input após escolher (Enter) — seleciona o texto pra que digitar de
    // novo SUBSTITUA a exibição e recomece a busca, em vez de anexar no fim dela.
    requestAnimationFrame(() => inputRef.current?.select())
  }

  return (
    <div style={{ position: 'relative', flex: 1, minWidth: 0 }}>
      <input
        id={id}
        ref={inputRef}
        autoFocus={autoFocus}
        placeholder={placeholder}
        aria-label={ariaLabel}
        value={exibicao}
        style={{ width: '100%' }}
        onFocus={(e) => e.target.select()}
        onChange={(e) => {
          setTexto(e.target.value)
          setEditando(true)
          setDestaque(0)
        }}
        onBlur={() => {
          // Campo apagado (texto vazio) e o usuário saiu sem escolher nada = limpa o valor
          // (necessário pro uso como filtro, onde vazio = "todos"). Sem apagar nada, sem
          // escolha explícita, volta a exibir o valor atual (rows usam onMouseDown com
          // preventDefault, então clicar numa linha não passa por aqui).
          if (editando && texto.trim() === '' && value) {
            onChange('')
          }
          setEditando(false)
          setTexto('')
          onBlur?.()
        }}
        onKeyDown={(e) => {
          if (!editando) return
          if (e.key === 'ArrowDown') {
            e.preventDefault()
            setDestaque((d) => Math.min(d + 1, Math.min(filtradas.length, 50) - 1))
          } else if (e.key === 'ArrowUp') {
            e.preventDefault()
            setDestaque((d) => Math.max(d - 1, 0))
          } else if (e.key === 'Enter') {
            e.preventDefault()
            e.stopPropagation()
            if (texto.trim() === '') {
              onChange('')
              setEditando(false)
              setTexto('')
            } else if (filtradas.length > 0) {
              escolher(filtradas[Math.min(destaque, filtradas.length - 1)])
            }
          } else if (e.key === 'Escape') {
            setEditando(false)
            setTexto('')
          }
        }}
      />
      {editando && filtradas.length > 0 && (
        <div
          className="card"
          style={{
            position: 'absolute',
            top: '100%',
            left: 0,
            right: 0,
            zIndex: 30,
            maxHeight: 220,
            overflowY: 'auto',
            padding: 4,
            marginTop: 2,
          }}
        >
          {filtradas.slice(0, 50).map((p, i) => (
            <div
              key={p.idPlanoContas}
              onMouseDown={(e) => {
                e.preventDefault()
                escolher(p)
              }}
              onMouseEnter={() => setDestaque(i)}
              style={{
                padding: '6px 8px',
                cursor: 'pointer',
                borderRadius: 4,
                background: i === destaque ? 'rgba(var(--accent-rgb), 0.15)' : undefined,
                // Sintéticas (agrupadoras) em negrito, igual à convenção da tela de Plano de Contas.
                fontWeight: p.natureza === 'SINTETICA' ? 600 : 400,
              }}
            >
              <span className="mono">{p.idPlanoContas}</span> — {p.descricao}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
