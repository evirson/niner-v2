import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { IconeExcluir } from '../../components/Icones'
import {
  CAMPOS_DE_BARRAS,
  ROTULO_CAMPO_ETIQUETA,
  ROTULO_FONTE_ETIQUETA,
  type AlinhamentoEtiquetaCampo,
  type CampoEtiquetaPosicionado,
  type FonteEtiqueta,
} from '../../lib/etiquetaConfig'
import { completarEtiquetaMm, desmascararEtiquetaMm, mascararEtiquetaMm } from '../../lib/masks'

const ALINHAMENTOS: Array<{ valor: AlinhamentoEtiquetaCampo; rotulo: string }> = [
  { valor: 'ESQUERDA', rotulo: 'Esquerda' },
  { valor: 'CENTRO', rotulo: 'Centro' },
  { valor: 'DIREITA', rotulo: 'Direita' },
]

/** Input de mm com máscara local — digitação natural, a MÁSCARA (zeros à direita) só completa
 * no onBlur, mas o VALOR já é comprometido a cada tecla (`aoMudar` no onChange também), pra
 * canvas/prévia do rolo redesenharem em tempo real enquanto o usuário digita, não só ao sair do
 * campo (2026-08-05, pedido do dono do produto — mesmo raciocínio do arraste, que já era live).
 * `focado` trava a sincronização de volta (prop `valorMm` -> `texto`) enquanto o campo tem foco,
 * senão o eco do próprio `aoMudar` reformataria o texto a cada tecla e apagaria a vírgula que o
 * usuário acabou de digitar. */
function CampoMm({ rotulo, valorMm, aoMudar, permiteVazio }: {
  rotulo: string
  valorMm: number | null
  aoMudar: (mm: number | null) => void
  permiteVazio?: boolean
}) {
  const [texto, setTexto] = useState(valorMm == null ? '' : mascararEtiquetaMm(String(valorMm).replace('.', ',')))
  const [focado, setFocado] = useState(false)

  useEffect(() => {
    if (focado) return
    setTexto(valorMm == null ? '' : mascararEtiquetaMm(String(valorMm).replace('.', ',')))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [valorMm])

  function comprometer(valorDigitado: string) {
    if (!valorDigitado.trim() && permiteVazio) {
      aoMudar(null)
    } else {
      aoMudar(desmascararEtiquetaMm(valorDigitado))
    }
  }

  return (
    <label className="campo-mm-label">
      {rotulo}
      <input
        value={texto}
        onFocus={() => setFocado(true)}
        onChange={(e) => {
          const novoTexto = mascararEtiquetaMm(e.target.value)
          setTexto(novoTexto)
          comprometer(novoTexto)
        }}
        onBlur={() => {
          const completo = completarEtiquetaMm(texto)
          setTexto(completo)
          setFocado(false)
          comprometer(completo)
        }}
        inputMode="decimal"
        placeholder="0,00"
      />
    </label>
  )
}

/**
 * Painel de propriedades do campo selecionado no editor (2026-08-04; virou popup em 2026-08-05).
 *
 * <p>⚠️ **Deixou de ser modal em 2026-08-21** (pedido do dono do produto: *"esta tela cobre a
 * etiqueta e não consigo mexer"*). Era `.modal-overlay`, que por definição cobre a tela inteira e
 * captura todo clique — ou seja, o painel que existe para ajustar o campo impedia arrastar o
 * campo. As duas coisas precisam funcionar ao mesmo tempo, porque servem ao mesmo ajuste por
 * caminhos diferentes: o arraste posiciona rápido, o número posiciona exato.
 *
 * <p>Agora é uma janela flutuante (`position: fixed`), **arrastável pelo cabeçalho** para sair da
 * frente da etiqueta, e sem nenhuma camada por trás. Nasce no canto inferior direito, longe do
 * canvas, que fica à esquerda.
 */
export default function PainelPropriedadesCampo({
  campo,
  ordinal,
  aoMudar,
  aoRemover,
  aoFechar,
}: {
  campo: CampoEtiquetaPosicionado
  /** Ordinal desta instância entre as do MESMO campo (1, 2, …), ou `null` quando ela é única.
   *  Existe porque desde 2026-08-24 o mesmo campo pode ser posicionado mais de uma vez (etiqueta
   *  destacável) — e sem isto o painel abriria com o título "Código de Barras (SKU)" nas duas,
   *  sem o usuário saber qual está editando. */
  ordinal: number | null
  aoMudar: (campo: CampoEtiquetaPosicionado) => void
  aoRemover: () => void
  aoFechar: () => void
}) {
  const ehBarra = (CAMPOS_DE_BARRAS as string[]).includes(campo.campo)
  /** `null` = ainda no canto padrão do CSS; assim o painel não precisa medir a janela para nascer. */
  const [posicao, setPosicao] = useState<{ x: number; y: number } | null>(null)
  const arrasteRef = useRef<{ offsetX: number; offsetY: number } | null>(null)

  function aoArrastarCabecalho(e: ReactPointerEvent<HTMLDivElement>) {
    const caixa = e.currentTarget.parentElement?.getBoundingClientRect()
    if (!caixa) return
    arrasteRef.current = { offsetX: e.clientX - caixa.left, offsetY: e.clientY - caixa.top }
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  function aoMoverCabecalho(e: ReactPointerEvent<HTMLDivElement>) {
    const arraste = arrasteRef.current
    if (!arraste) return
    // Preso à janela: um painel arrastado para fora da viewport não teria como voltar.
    const x = Math.min(Math.max(e.clientX - arraste.offsetX, 0), window.innerWidth - 120)
    const y = Math.min(Math.max(e.clientY - arraste.offsetY, 0), window.innerHeight - 60)
    setPosicao({ x, y })
  }

  return (
    <div
      className="editor-etiqueta-painel-flutuante"
      role="dialog"
      aria-label="Propriedades do campo"
      style={posicao ? { left: posicao.x, top: posicao.y, right: 'auto', bottom: 'auto' } : undefined}
    >
      <div
        className="editor-etiqueta-painel-topo editor-etiqueta-painel-pegador"
        onPointerDown={aoArrastarCabecalho}
        onPointerMove={aoMoverCabecalho}
        onPointerUp={() => { arrasteRef.current = null }}
        title="Arraste para mover o painel"
      >
        <strong>
          {ROTULO_CAMPO_ETIQUETA[campo.campo]}
          {ordinal ? ` (${ordinal}ª)` : ''}
        </strong>
        <button type="button" className="acao-icone acao-excluir" onClick={aoRemover} title="Remover campo" aria-label="Remover campo">
          <IconeExcluir />
        </button>
      </div>
      <div className="editor-etiqueta-painel-corpo">

        <div className="editor-etiqueta-painel-grade">
          <CampoMm rotulo="Posição X (mm)" valorMm={campo.posicaoXMm} aoMudar={(v) => aoMudar({ ...campo, posicaoXMm: v ?? 0 })} />
          <CampoMm rotulo="Posição Y (mm)" valorMm={campo.posicaoYMm} aoMudar={(v) => aoMudar({ ...campo, posicaoYMm: v ?? 0 })} />
          <CampoMm rotulo="Largura (mm)" valorMm={campo.larguraMm} permiteVazio aoMudar={(v) => aoMudar({ ...campo, larguraMm: v })} />
          <CampoMm rotulo="Altura (mm)" valorMm={campo.alturaMm} permiteVazio aoMudar={(v) => aoMudar({ ...campo, alturaMm: v })} />
        </div>

        <label>
          Fonte
          <select value={campo.fonte} onChange={(e) => aoMudar({ ...campo, fonte: e.target.value as FonteEtiqueta })}>
            {Object.entries(ROTULO_FONTE_ETIQUETA).map(([valor, rotulo]) => (
              <option key={valor} value={valor}>
                {rotulo}
              </option>
            ))}
          </select>
        </label>

        <label>
          Tamanho da fonte (pt)
          <input
            type="number"
            min={4}
            step={0.5}
            value={campo.tamanhoFontePt ?? ''}
            onChange={(e) => aoMudar({ ...campo, tamanhoFontePt: e.target.value === '' ? null : Number(e.target.value) })}
          />
        </label>

        <label className="campo-checkbox">
          <input type="checkbox" checked={campo.negrito} onChange={(e) => aoMudar({ ...campo, negrito: e.target.checked })} />
          Negrito
        </label>

        <label className="campo-checkbox">
          <input type="checkbox" checked={campo.fundoPreto} onChange={(e) => aoMudar({ ...campo, fundoPreto: e.target.checked })} />
          Fundo preto / letra branca
        </label>

        <label>
          Alinhamento
          <div className="editor-etiqueta-segmentado">
            {ALINHAMENTOS.map((a) => (
              <button
                key={a.valor}
                type="button"
                className={`btn ghost ${campo.alinhamento === a.valor ? 'ativa' : ''}`}
                onClick={() => aoMudar({ ...campo, alinhamento: a.valor })}
              >
                {a.rotulo}
              </button>
            ))}
          </div>
        </label>

        {ehBarra && (
          <label className="campo-checkbox">
            <input
              type="checkbox"
              checked={Boolean(campo.exibirTextoLegivel)}
              onChange={(e) => aoMudar({ ...campo, exibirTextoLegivel: e.target.checked })}
            />
            Mostrar os dígitos embaixo do código de barras
          </label>
        )}

        <div className="ajuda-rodape" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  )
}
