import { useEffect, useState } from 'react'
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
 * Popup de propriedades do campo selecionado no editor (2026-08-04; virou popup em 2026-08-05,
 * pedido do dono do produto — melhor visualização e evita o scroll que o painel fixo ao lado do
 * canvas causava) — posição/tamanho precisos por número (complementa o arraste, que é bom pra
 * posicionar mas impreciso demais pra medidas exatas) + estilo (fonte/tamanho/negrito/fundo/
 * alinhamento). Mesmo padrão `.modal-overlay`/`.modal` do resto do sistema; só renderiza quando
 * `EditorEtiquetaCanvas` tem um campo selecionado (parent controla a condicional, não este
 * componente — por isso não há mais um estado "nenhum campo selecionado" aqui dentro).
 */
export default function PainelPropriedadesCampo({
  campo,
  aoMudar,
  aoRemover,
  aoFechar,
}: {
  campo: CampoEtiquetaPosicionado
  aoMudar: (campo: CampoEtiquetaPosicionado) => void
  aoRemover: () => void
  aoFechar: () => void
}) {
  const ehBarra = (CAMPOS_DE_BARRAS as string[]).includes(campo.campo)

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal editor-etiqueta-painel" role="dialog" aria-label="Propriedades do campo" onClick={(e) => e.stopPropagation()}>
        <div className="editor-etiqueta-painel-topo">
          <strong>{ROTULO_CAMPO_ETIQUETA[campo.campo]}</strong>
          <button type="button" className="acao-icone acao-excluir" onClick={aoRemover} title="Remover campo" aria-label="Remover campo">
            <IconeExcluir />
          </button>
        </div>

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
