import { useRef, useState } from 'react'
import { useEu } from '../../lib/eu'
import CampoEtiquetaVisual from './CampoEtiquetaVisual'
import PainelPropriedadesCampo from './PainelPropriedadesCampo'
import {
  CAMPOS_DE_BARRAS,
  ROTULO_CAMPO_ETIQUETA,
  TODOS_OS_CAMPOS,
  type CampoEtiqueta,
  type CampoEtiquetaPosicionado,
  type ProdutoExemplo,
} from '../../lib/etiquetaConfig'

const PX_POR_MM_BASE = 6

/** Escala da prévia do rolo. Metade da base — o rolo inteiro precisa caber na largura da tela.
 *  ⚠️ O texto acompanha esta escala desde 2026-08-20 (`CampoEtiquetaVisual`): antes a fonte saía
 *  em `pt` fixo e a prévia mostrava letra 26% maior que a real, cortando o que na verdade cabia. */
const ESCALA_PREVIA = PX_POR_MM_BASE * 0.5
/** Qual dimensão a alça arrastada altera: só largura, só altura, ou as duas (alça de canto). */
type EixoRedimensionamento = 'horizontal' | 'vertical' | 'ambos'

const ZOOM_MIN = 0.5
const ZOOM_MAX = 3
const ZOOM_PASSO = 0.25
const SNAP_MM = 0.5
const NUDGE_MM = 0.5
const NUDGE_MM_SHIFT = 5
const TAMANHO_MINIMO_MM = 2

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
  espacamentoVerticalMm,

  larguraRoloMm,
  posicoesColunas,
  campos,
  aoMudarCampos,
  produtoExemplo,
}: {
  larguraEtiquetaMm: number
  alturaEtiquetaMm: number
  espacamentoVerticalMm: number

  larguraRoloMm: number
  /** Onde cada coluna comeca no rolo, em mm — DERIVADO da geometria (V057), nao guardado. */
  posicoesColunas: number[]
  campos: CampoEtiquetaPosicionado[]
  /** Recebe uma função atualizadora (não o array pronto) — evita perder atualizações quando
   * duas mutações acontecem no mesmo ciclo do React sem re-render entre elas (ex.: pointermove
   * disparando mais rápido que o commit, em arrastes rápidos): cada chamada sempre parte do
   * estado mais recente de verdade, nunca de um `campos` (prop) capturado no closure. */
  aoMudarCampos: (atualizar: (campos: CampoEtiquetaPosicionado[]) => CampoEtiquetaPosicionado[]) => void
  produtoExemplo: ProdutoExemplo | null
}) {
  const [zoom, setZoom] = useState(1)

  /**
   * Campos cujo conteúdo não cabe na própria caixa — medido de verdade pelo
   * `CampoEtiquetaVisual`, não estimado. É o aviso mais importante desta tela: com
   * `overflow: hidden`, o texto que sobra some em silêncio na tela e reaparece bagunçado no
   * papel (foi assim que a descrição de 3 linhas invadiu o preço em 2026-08-20).
   */
  const [camposQueTransbordam, setCamposQueTransbordam] = useState<Set<string>>(new Set())
  const marcarTransbordo = (campo: string, transborda: boolean) =>
    setCamposQueTransbordam((atual) => {
      if (atual.has(campo) === transborda) return atual
      const novo = new Set(atual)
      if (transborda) novo.add(campo)
      else novo.delete(campo)
      return novo
    })
  const [campoSelecionado, setCampoSelecionado] = useState<CampoEtiqueta | null>(null)
  const arrastoRef = useRef<{ campo: CampoEtiqueta; inicioPxX: number; inicioPxY: number; inicioMmX: number; inicioMmY: number } | null>(null)
  const redimensionoRef = useRef<{ campo: CampoEtiqueta; eixo: EixoRedimensionamento; inicioPxX: number; inicioPxY: number; inicioLarguraMm: number; inicioAlturaMm: number } | null>(null)

  const escala = PX_POR_MM_BASE * zoom
  // O nome que a etiqueta imprime de verdade: empresa.cfg_nome_etiqueta (2026-08-21). Antes
  // era o literal 'NOME DA LOJA' aqui e em mais tres lugares — inclusive na Emissao, ou seja,
  // toda etiqueta ja impressa saiu com esse texto. O fallback so cobre o /eu ainda carregando.
  const { data: eu } = useEu()
  const nomeEmpresaExemplo = eu?.empresa.nomeEtiqueta || 'NOME DA EMPRESA'
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

  /** Tamanho ABSOLUTO (redimensionamento pela alça — mesmo raciocínio de `atualizarPosicao`:
   * cada pointermove calcula a partir do tamanho no início do gesto, não incrementalmente).
   * Não deixa passar da borda direita/inferior da etiqueta (a posição X/Y do campo não muda ao
   * redimensionar, só cresce/encolhe pra direita/baixo) nem ficar menor que `TAMANHO_MINIMO_MM`. */
  function atualizarTamanho(chave: CampoEtiqueta, larguraMmNovo: number, alturaMmNovo: number) {
    const atual = campos.find((c) => c.campo === chave)
    if (!atual) return
    const larguraMaxima = Math.max(TAMANHO_MINIMO_MM, largura - atual.posicaoXMm)
    const alturaMaxima = Math.max(TAMANHO_MINIMO_MM, altura - atual.posicaoYMm)
    const larguraClamp = clamp(arredondarParaSnap(larguraMmNovo), TAMANHO_MINIMO_MM, larguraMaxima)
    const alturaClamp = clamp(arredondarParaSnap(alturaMmNovo), TAMANHO_MINIMO_MM, alturaMaxima)
    atualizarCampo(chave, { larguraMm: larguraClamp, alturaMm: alturaClamp })
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

  /**
   * Alças de redimensionamento do campo selecionado — `stopPropagation` no pointerdown pra não
   * disparar `aoIniciarArraste` do campo por baixo (moveria em vez de redimensionar).
   *
   * <p>São **três** desde 2026-08-21 (pedido do dono do produto: *"mudar largura, mudar altura"*):
   * a do canto muda as duas juntas, a da borda direita só a largura e a da borda inferior só a
   * altura. Com só a do canto, ajustar uma dimensão sem bagunçar a outra era impossível no mouse —
   * e num campo de código de barras a altura é justamente o que não se quer mexer sem querer.
   */
  function aoIniciarRedimensionar(
    e: React.PointerEvent<HTMLDivElement>,
    campo: CampoEtiquetaPosicionado,
    eixo: EixoRedimensionamento,
  ) {
    e.stopPropagation()
    e.currentTarget.setPointerCapture(e.pointerId)
    setCampoSelecionado(campo.campo)
    redimensionoRef.current = {
      campo: campo.campo,
      eixo,
      inicioPxX: e.clientX,
      inicioPxY: e.clientY,
      inicioLarguraMm: campo.larguraMm ?? 0,
      inicioAlturaMm: campo.alturaMm ?? 0,
    }
  }

  function aoMoverRedimensionar(e: React.PointerEvent<HTMLDivElement>) {
    const redimensiono = redimensionoRef.current
    if (!redimensiono) return
    // O eixo não usado recebe delta zero — mantém a medida intacta em vez de recalculá-la.
    const deltaMmX = redimensiono.eixo === 'vertical' ? 0 : (e.clientX - redimensiono.inicioPxX) / escala
    const deltaMmY = redimensiono.eixo === 'horizontal' ? 0 : (e.clientY - redimensiono.inicioPxY) / escala
    atualizarTamanho(redimensiono.campo, redimensiono.inicioLarguraMm + deltaMmX, redimensiono.inicioAlturaMm + deltaMmY)
  }

  function aoSoltarRedimensionar() {
    redimensionoRef.current = null
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

  /**
   * Campo saindo da etiqueta.
   *
   * <p>⚠️ Media contra as 4 "bordas" configuraveis ate 2026-08-20; elas sairam por decisao do dono
   * do produto ("vamos esquecer as bordas") e nunca afetaram a impressao — eram so a area
   * tracejada de aviso. O aviso continua, agora contra a borda **real** do adesivo, que e o que de
   * fato corta.
   */
  function ultrapassaBorda(c: CampoEtiquetaPosicionado): boolean {
    const larguraCampo = c.larguraMm ?? 0
    const alturaCampo = c.alturaMm ?? 0
    return (
      c.posicaoXMm < 0 ||
      c.posicaoYMm < 0 ||
      c.posicaoXMm + larguraCampo > largura + 0.001 ||
      c.posicaoYMm + alturaCampo > altura + 0.001
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
          Arraste os campos pra posicionar · arraste a alça no canto pra redimensionar · setas do teclado
          ajustam fino (Shift = 5mm) · Delete remove
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
              onPointerMove={(e) => {
                aoMoverArraste(e)
                aoMoverRedimensionar(e)
              }}
              onPointerUp={() => {
                aoSoltarArraste()
                aoSoltarRedimensionar()
              }}
              onClick={() => setCampoSelecionado(null)}
            >
              {campos.map((c) => (
                <CampoEtiquetaVisual
                  key={c.campo}
                  campo={c}
                  escalaPxPorMm={escala}
                  produtoExemplo={produtoExemplo}
                  nomeEmpresaExemplo={nomeEmpresaExemplo}
                  className="editor-etiqueta-campo"
                  aoMedirTransbordo={(transborda) => marcarTransbordo(c.campo, transborda)}
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
              {campoSelecionadoObj && (() => {
                const x = campoSelecionadoObj.posicaoXMm * escala
                const y = campoSelecionadoObj.posicaoYMm * escala
                const larg = (campoSelecionadoObj.larguraMm ?? 10) * escala
                const alt = (campoSelecionadoObj.alturaMm ?? 6) * escala
                return (
                  <>
                    {/* Borda direita: só LARGURA. */}
                    <div
                      className="editor-etiqueta-redimensionar redimensionar-largura"
                      title="Arraste pra mudar a largura"
                      style={{ left: x + larg - 4, top: y + alt / 2 - 12 }}
                      onPointerDown={(e) => aoIniciarRedimensionar(e, campoSelecionadoObj, 'horizontal')}
                      onClick={(e) => e.stopPropagation()}
                    />
                    {/* Borda inferior: só ALTURA. */}
                    <div
                      className="editor-etiqueta-redimensionar redimensionar-altura"
                      title="Arraste pra mudar a altura"
                      style={{ left: x + larg / 2 - 12, top: y + alt - 4 }}
                      onPointerDown={(e) => aoIniciarRedimensionar(e, campoSelecionadoObj, 'vertical')}
                      onClick={(e) => e.stopPropagation()}
                    />
                    {/* Canto: as duas ao mesmo tempo. */}
                    <div
                      className="editor-etiqueta-redimensionar redimensionar-canto"
                      title="Arraste pra redimensionar"
                      style={{ left: x + larg - 5, top: y + alt - 5 }}
                      onPointerDown={(e) => aoIniciarRedimensionar(e, campoSelecionadoObj, 'ambos')}
                      onClick={(e) => e.stopPropagation()}
                    />
                  </>
                )
              })()}
            </div>
          </div>
        </div>
      </div>

      {campoSelecionadoObj && (
        <PainelPropriedadesCampo
          campo={campoSelecionadoObj}
          aoMudar={(c) => atualizarCampo(c.campo, c)}
          aoRemover={() => campoSelecionado && removerCampo(campoSelecionado)}
          aoFechar={() => setCampoSelecionado(null)}
        />
      )}

      {camposQueTransbordam.size > 0 && (
        <p className="erro-campo" style={{ marginTop: 8 }}>
          O conteúdo não cabe em{' '}
          {[...camposQueTransbordam].map((c) => ROTULO_CAMPO_ETIQUETA[c as CampoEtiqueta]).join(', ')} — o que
          sobra é <strong>cortado na impressão</strong>. Aumente a caixa, diminua a fonte ou encurte o texto.
        </p>
      )}

      {posicoesColunas.length > 0 && (
        <div>
          <strong className="muted">
            Prévia do rolo ({posicoesColunas.length} coluna{posicoesColunas.length === 1 ? '' : 's'} × 2 fileiras)
          </strong>
          {/* ⚠️ DUAS fileiras de propósito (2026-08-20). Com uma só, o "Espaço entre fileiras" não
              tinha como ser conferido antes de imprimir — e é justamente o valor cujo erro se
              acumula: a primeira etiqueta sai certa e a quarta sai fora do adesivo. Aqui o
              operador vê o passo vertical desenhado. */}
          <div className="editor-etiqueta-rolo-preview" style={{ width: '100%' }}>
            <div
              style={{
                position: 'relative',
                width: larguraRoloMm * ESCALA_PREVIA,
                height: (altura * 2 + espacamentoVerticalMm) * ESCALA_PREVIA,
              }}
            >
              {[0, 1].map((fileira) =>
                posicoesColunas.map((x, indiceColuna) => (
                  <div
                    key={`${fileira}-${indiceColuna}`}
                    className="editor-etiqueta-rolo-etiqueta"
                    style={{
                      position: 'absolute',
                      left: x * ESCALA_PREVIA,
                      top: fileira * (altura + espacamentoVerticalMm) * ESCALA_PREVIA,
                      width: largura * ESCALA_PREVIA,
                      height: altura * ESCALA_PREVIA,
                    }}
                  >
                    {campos.map((c) => (
                      <CampoEtiquetaVisual
                        key={c.campo}
                        campo={c}
                        escalaPxPorMm={ESCALA_PREVIA}
                        produtoExemplo={produtoExemplo}
                        nomeEmpresaExemplo={nomeEmpresaExemplo}
                        style={{ pointerEvents: 'none' }}
                      />
                    ))}
                  </div>
                )),
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
