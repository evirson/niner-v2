import { useRef, useState } from 'react'
import CampoEtiquetaVisual from './CampoEtiquetaVisual'
import PainelPropriedadesCampo from './PainelPropriedadesCampo'
import {
  CAMPOS_DE_BARRAS,
  ROTULO_CAMPO_ETIQUETA,
  TODOS_OS_CAMPOS,
  type CampoEtiqueta,
  type CampoEtiquetaPosicionado,
  type ColunaEtiqueta,
  type ProdutoExemplo,
} from '../../lib/etiquetaConfig'

const PX_POR_MM_BASE = 6
const ZOOM_MIN = 0.5
const ZOOM_MAX = 3
const ZOOM_PASSO = 0.25
const SNAP_MM = 0.5
const NUDGE_MM = 0.5
const NUDGE_MM_SHIFT = 5

function arredondarParaSnap(mm: number): number {
  return Math.round(mm / SNAP_MM) * SNAP_MM
}

function clamp(valor: number, minimo: number, maximo: number): number {
  return Math.min(Math.max(valor, minimo), Math.max(minimo, maximo))
}

/** Marcas de régua a cada 5mm, número só a cada 10mm — mesmo padrão nas duas réguas. */
function marcasDeRegua(comprimentoMm: number): number[] {
  const marcas: number[] = []
  for (let mm = 0; mm <= comprimentoMm; mm += 5) marcas.push(mm)
  return marcas
}

function ReguaHorizontal({ comprimentoMm, escala }: { comprimentoMm: number; escala: number }) {
  return (
    <div className="editor-etiqueta-regua-h" style={{ width: comprimentoMm * escala }}>
      {marcasDeRegua(comprimentoMm).map((mm) => (
        <span
          key={mm}
          className={`editor-etiqueta-marca-h ${mm % 10 === 0 ? 'marca-maior' : ''}`}
          style={{ left: mm * escala }}
        >
          {mm % 10 === 0 ? mm : ''}
        </span>
      ))}
    </div>
  )
}

function ReguaVertical({ comprimentoMm, escala }: { comprimentoMm: number; escala: number }) {
  return (
    <div className="editor-etiqueta-regua-v" style={{ height: comprimentoMm * escala }}>
      {marcasDeRegua(comprimentoMm).map((mm) => (
        <span
          key={mm}
          className={`editor-etiqueta-marca-v ${mm % 10 === 0 ? 'marca-maior' : ''}`}
          style={{ top: mm * escala }}
        >
          {mm % 10 === 0 ? mm : ''}
        </span>
      ))}
    </div>
  )
}

function tamanhoPadrao(campo: CampoEtiqueta): { larguraMm: number; alturaMm: number } {
  return (CAMPOS_DE_BARRAS as string[]).includes(campo) ? { larguraMm: 25, alturaMm: 10 } : { larguraMm: 20, alturaMm: 5 }
}

/**
 * Editor visual do layout da etiqueta (2026-08-04, docs/telas/configuracao-etiqueta.md) — a
 * peça central da tela de Configuração de Etiqueta de Produtos, sem precedente de
 * drag-and-drop no projeto. Interação por Pointer Events nativos (sem biblioteca de DnD — o
 * problema é posicionamento livre 2D com régua/snap/zoom, não reordenar lista, que é o que
 * dnd-kit/react-dnd resolvem). Fundo da etiqueta é sempre branco/preto (papel/tinta reais),
 * independente do tema claro/escuro do app — mesmo raciocínio já usado no PDF do Relatório de
 * Vendas.
 */
export default function EditorEtiquetaCanvas({
  larguraEtiquetaMm,
  alturaEtiquetaMm,
  bordaSuperiorMm,
  bordaInferiorMm,
  bordaEsquerdaMm,
  bordaDireitaMm,
  larguraRoloMm,
  colunas,
  campos,
  aoMudarCampos,
  produtoExemplo,
}: {
  larguraEtiquetaMm: number
  alturaEtiquetaMm: number
  bordaSuperiorMm: number
  bordaInferiorMm: number
  bordaEsquerdaMm: number
  bordaDireitaMm: number
  larguraRoloMm: number
  colunas: ColunaEtiqueta[]
  campos: CampoEtiquetaPosicionado[]
  /** Recebe uma função atualizadora (não o array pronto) — evita perder atualizações quando
   * duas mutações acontecem no mesmo ciclo do React sem re-render entre elas (ex.: pointermove
   * disparando mais rápido que o commit, em arrastes rápidos): cada chamada sempre parte do
   * estado mais recente de verdade, nunca de um `campos` (prop) capturado no closure. */
  aoMudarCampos: (atualizar: (campos: CampoEtiquetaPosicionado[]) => CampoEtiquetaPosicionado[]) => void
  produtoExemplo: ProdutoExemplo | null
}) {
  const [zoom, setZoom] = useState(1)
  const [campoSelecionado, setCampoSelecionado] = useState<CampoEtiqueta | null>(null)
  const arrastoRef = useRef<{ campo: CampoEtiqueta; inicioPxX: number; inicioPxY: number; inicioMmX: number; inicioMmY: number } | null>(null)

  const escala = PX_POR_MM_BASE * zoom
  const nomeEmpresaExemplo = 'NOME DA LOJA'
  const largura = Math.max(larguraEtiquetaMm, 0)
  const altura = Math.max(alturaEtiquetaMm, 0)

  const camposDisponiveis = TODOS_OS_CAMPOS.filter((c) => !campos.some((cp) => cp.campo === c))
  const campoSelecionadoObj = campos.find((c) => c.campo === campoSelecionado) ?? null

  function atualizarCampo(chave: CampoEtiqueta, alteracoes: Partial<CampoEtiquetaPosicionado>) {
    aoMudarCampos((atual) => atual.map((c) => (c.campo === chave ? { ...c, ...alteracoes } : c)))
  }

  /** Posição ABSOLUTA (arraste — cada pointermove calcula a partir do ponto de início do
   * gesto, não incrementalmente, então ler `larguraMm`/`alturaMm` da prop fechada é seguro:
   * dimensões não mudam durante o próprio arraste). */
  function atualizarPosicao(chave: CampoEtiqueta, xMm: number, yMm: number) {
    const atual = campos.find((c) => c.campo === chave)
    if (!atual) return
    const larguraCampo = atual.larguraMm ?? 0
    const alturaCampo = atual.alturaMm ?? 0
    const xClamp = clamp(arredondarParaSnap(xMm), 0, Math.max(0, largura - larguraCampo))
    const yClamp = clamp(arredondarParaSnap(yMm), 0, Math.max(0, altura - alturaCampo))
    atualizarCampo(chave, { posicaoXMm: xClamp, posicaoYMm: yClamp })
  }

  /** Delta relativo (nudge do teclado) — soma dentro do próprio updater, a partir do campo mais
   * recente, nunca da prop fechada: teclas de seta em repetição rápida (segurar a tecla) podem
   * disparar vários keydown no mesmo ciclo do React, e cada um precisa somar em cima do último,
   * não recalcular do zero a partir de um valor desatualizado (mesmo motivo de
   * `aoMudarCampos` — ver comentário no tipo da prop). */
  function moverCampoRelativo(chave: CampoEtiqueta, dxMm: number, dyMm: number) {
    aoMudarCampos((atual) =>
      atual.map((c) => {
        if (c.campo !== chave) return c
        const larguraCampo = c.larguraMm ?? 0
        const alturaCampo = c.alturaMm ?? 0
        const xClamp = clamp(arredondarParaSnap(c.posicaoXMm + dxMm), 0, Math.max(0, largura - larguraCampo))
        const yClamp = clamp(arredondarParaSnap(c.posicaoYMm + dyMm), 0, Math.max(0, altura - alturaCampo))
        return { ...c, posicaoXMm: xClamp, posicaoYMm: yClamp }
      }),
    )
  }

  function adicionarCampo(campo: CampoEtiqueta) {
    const tamanho = tamanhoPadrao(campo)
    const novo: CampoEtiquetaPosicionado = {
      campo,
      posicaoXMm: 0,
      posicaoYMm: 0,
      larguraMm: tamanho.larguraMm,
      alturaMm: tamanho.alturaMm,
      fonte: 'ARIAL',
      tamanhoFontePt: 7,
      negrito: false,
      fundoPreto: false,
      alinhamento: 'ESQUERDA',
      exibirTextoLegivel: (CAMPOS_DE_BARRAS as string[]).includes(campo) ? true : null,
    }
    aoMudarCampos((atual) => [...atual, novo])
    setCampoSelecionado(campo)
  }

  function removerCampo(campo: CampoEtiqueta) {
    aoMudarCampos((atual) => atual.filter((c) => c.campo !== campo))
    if (campoSelecionado === campo) setCampoSelecionado(null)
  }

  function aoIniciarArraste(e: React.PointerEvent<HTMLDivElement>, campo: CampoEtiquetaPosicionado) {
    e.currentTarget.setPointerCapture(e.pointerId)
    setCampoSelecionado(campo.campo)
    arrastoRef.current = {
      campo: campo.campo,
      inicioPxX: e.clientX,
      inicioPxY: e.clientY,
      inicioMmX: campo.posicaoXMm,
      inicioMmY: campo.posicaoYMm,
    }
  }

  function aoMoverArraste(e: React.PointerEvent<HTMLDivElement>) {
    const arrasto = arrastoRef.current
    if (!arrasto) return
    const deltaMmX = (e.clientX - arrasto.inicioPxX) / escala
    const deltaMmY = (e.clientY - arrasto.inicioPxY) / escala
    atualizarPosicao(arrasto.campo, arrasto.inicioMmX + deltaMmX, arrasto.inicioMmY + deltaMmY)
  }

  function aoSoltarArraste() {
    arrastoRef.current = null
  }

  function aoTeclarNoCampo(e: React.KeyboardEvent<HTMLDivElement>, campo: CampoEtiquetaPosicionado) {
    if (e.key === 'Delete' || e.key === 'Backspace') {
      e.preventDefault()
      removerCampo(campo.campo)
      return
    }
    const passo = e.shiftKey ? NUDGE_MM_SHIFT : NUDGE_MM
    let dx = 0
    let dy = 0
    if (e.key === 'ArrowLeft') dx = -passo
    else if (e.key === 'ArrowRight') dx = passo
    else if (e.key === 'ArrowUp') dy = -passo
    else if (e.key === 'ArrowDown') dy = passo
    else return
    e.preventDefault()
    moverCampoRelativo(campo.campo, dx, dy)
  }

  function ultrapassaBorda(c: CampoEtiquetaPosicionado): boolean {
    const larguraCampo = c.larguraMm ?? 0
    const alturaCampo = c.alturaMm ?? 0
    return (
      c.posicaoXMm < bordaEsquerdaMm ||
      c.posicaoYMm < bordaSuperiorMm ||
      c.posicaoXMm + larguraCampo > largura - bordaDireitaMm ||
      c.posicaoYMm + alturaCampo > altura - bordaInferiorMm
    )
  }

  return (
    <div className="editor-etiqueta">
      <div className="editor-etiqueta-toolbar">
        <span className="muted">Zoom</span>
        <button type="button" className="btn ghost" onClick={() => setZoom((z) => Math.max(ZOOM_MIN, z - ZOOM_PASSO))}>
          −
        </button>
        <span className="mono">{Math.round(zoom * 100)}%</span>
        <button type="button" className="btn ghost" onClick={() => setZoom((z) => Math.min(ZOOM_MAX, z + ZOOM_PASSO))}>
          +
        </button>
        <button type="button" className="btn ghost" onClick={() => setZoom(1)}>
          Redefinir
        </button>
        <span className="muted" style={{ marginLeft: 'auto' }}>
          Arraste os campos pra posicionar · setas do teclado ajustam fino (Shift = 5mm) · Delete remove
        </span>
      </div>

      <div className="editor-etiqueta-corpo">
        <div className="editor-etiqueta-paleta">
          <strong className="muted">Campos disponíveis</strong>
          {camposDisponiveis.length === 0 ? (
            <p className="muted" style={{ fontSize: 13 }}>
              Todos os campos já estão na etiqueta.
            </p>
          ) : (
            camposDisponiveis.map((c) => (
              <button key={c} type="button" className="btn ghost editor-etiqueta-paleta-item" onClick={() => adicionarCampo(c)}>
                ＋ {ROTULO_CAMPO_ETIQUETA[c]}
              </button>
            ))
          )}
        </div>

        <div className="editor-etiqueta-area">
          <div style={{ display: 'flex' }}>
            <div style={{ width: 24 }} />
            <ReguaHorizontal comprimentoMm={largura} escala={escala} />
          </div>
          <div style={{ display: 'flex' }}>
            <ReguaVertical comprimentoMm={altura} escala={escala} />
            <div
              className="editor-etiqueta-canvas"
              style={{ width: largura * escala, height: altura * escala }}
              onPointerMove={aoMoverArraste}
              onPointerUp={aoSoltarArraste}
              onClick={() => setCampoSelecionado(null)}
            >
              <div
                className="editor-etiqueta-area-segura"
                style={{
                  position: 'absolute',
                  top: bordaSuperiorMm * escala,
                  left: bordaEsquerdaMm * escala,
                  right: bordaDireitaMm * escala,
                  bottom: bordaInferiorMm * escala,
                }}
              />
              {campos.map((c) => (
                <CampoEtiquetaVisual
                  key={c.campo}
                  campo={c}
                  escalaPxPorMm={escala}
                  produtoExemplo={produtoExemplo}
                  nomeEmpresaExemplo={nomeEmpresaExemplo}
                  className="editor-etiqueta-campo"
                  tabIndex={0}
                  onPointerDown={(e) => aoIniciarArraste(e, c)}
                  onKeyDown={(e) => aoTeclarNoCampo(e, c)}
                  onClick={(e) => {
                    e.stopPropagation()
                    setCampoSelecionado(c.campo)
                  }}
                  style={{
                    outline:
                      campoSelecionado === c.campo
                        ? '2px solid var(--accent)'
                        : ultrapassaBorda(c)
                          ? '2px solid var(--danger)'
                          : '1px dashed rgba(0,0,0,.25)',
                    outlineOffset: 1,
                  }}
                />
              ))}
            </div>
          </div>
        </div>

        <PainelPropriedadesCampo
          campo={campoSelecionadoObj}
          aoMudar={(c) => atualizarCampo(c.campo, c)}
          aoRemover={() => campoSelecionado && removerCampo(campoSelecionado)}
        />
      </div>

      {colunas.length > 0 && (
        <div>
          <strong className="muted">Prévia do rolo completo ({colunas.length} coluna{colunas.length === 1 ? '' : 's'})</strong>
          <div className="editor-etiqueta-rolo-preview" style={{ width: '100%' }}>
            <div style={{ position: 'relative', width: larguraRoloMm * PX_POR_MM_BASE * 0.5, height: altura * PX_POR_MM_BASE * 0.5 }}>
              {colunas.map((coluna) => (
                <div
                  key={coluna.numeroColuna}
                  className="editor-etiqueta-rolo-etiqueta"
                  style={{
                    position: 'absolute',
                    left: coluna.posicaoInicialMm * PX_POR_MM_BASE * 0.5,
                    width: largura * PX_POR_MM_BASE * 0.5,
                    height: altura * PX_POR_MM_BASE * 0.5,
                  }}
                >
                  {campos.map((c) => (
                    <CampoEtiquetaVisual
                      key={c.campo}
                      campo={c}
                      escalaPxPorMm={PX_POR_MM_BASE * 0.5}
                      produtoExemplo={produtoExemplo}
                      nomeEmpresaExemplo={nomeEmpresaExemplo}
                      style={{ pointerEvents: 'none' }}
                    />
                  ))}
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
