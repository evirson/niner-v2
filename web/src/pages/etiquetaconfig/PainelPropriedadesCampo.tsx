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

/** Input de mm com máscara local (digitação natural, completa só no onBlur — mesmo padrão de
 * todo campo decimal do sistema) — buffer próprio porque o valor "comprometido" (number) só
 * atualiza o campo pai no blur/change explícito. */
function CampoMm({ rotulo, valorMm, aoMudar, permiteVazio }: {
  rotulo: string
  valorMm: number | null
  aoMudar: (mm: number | null) => void
  permiteVazio?: boolean
}) {
  const [texto, setTexto] = useState(valorMm == null ? '' : mascararEtiquetaMm(String(valorMm).replace('.', ',')))

  useEffect(() => {
    setTexto(valorMm == null ? '' : mascararEtiquetaMm(String(valorMm).replace('.', ',')))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [valorMm])

  return (
    <label className="campo-mm-label">
      {rotulo}
      <input
        value={texto}
        onChange={(e) => setTexto(mascararEtiquetaMm(e.target.value))}
        onBlur={() => {
          const completo = completarEtiquetaMm(texto)
          setTexto(completo)
          if (!completo.trim() && permiteVazio) {
            aoMudar(null)
          } else {
            aoMudar(desmascararEtiquetaMm(completo))
          }
        }}
        inputMode="decimal"
        placeholder="0,00"
      />
    </label>
  )
}

/**
 * Painel de propriedades do campo selecionado no editor (2026-08-04) — posição/tamanho
 * precisos por número (complementa o arraste, que é bom pra posicionar mas impreciso demais pra
 * medidas exatas) + estilo (fonte/tamanho/negrito/fundo/alinhamento).
 */
export default function PainelPropriedadesCampo({
  campo,
  aoMudar,
  aoRemover,
}: {
  campo: CampoEtiquetaPosicionado | null
  aoMudar: (campo: CampoEtiquetaPosicionado) => void
  aoRemover: () => void
}) {
  if (!campo) {
    return (
      <div className="card editor-etiqueta-painel">
        <p className="muted" style={{ margin: 0 }}>
          Clique num campo da etiqueta pra editar sua posição e estilo, ou arraste um campo novo da paleta.
        </p>
      </div>
    )
  }

  const ehBarra = (CAMPOS_DE_BARRAS as string[]).includes(campo.campo)

  return (
    <div className="card editor-etiqueta-painel">
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
    </div>
  )
}
