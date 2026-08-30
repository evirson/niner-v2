import type { Empresa } from '../../lib/empresas'
import EmpresaMultiSelect from '../../components/EmpresaMultiSelect'
import { mascararData } from '../../lib/masks'
import { fecharAoClicarNoFundo } from '../../lib/modais'

export interface FiltrosTexto {
  dataInicial: string
  dataFinal: string
  idsEmpresa: number[]
}

export const FILTROS_VAZIOS: FiltrosTexto = {
  dataInicial: '',
  dataFinal: '',
  idsEmpresa: [],
}

/**
 * Popup de filtros do Relatório de Comissões (2026-08-03) — mesmo padrão do popup de Contas a
 * Receber: abre sozinho quando a tela é aberta (nada é buscado antes de o usuário escolher os
 * filtros); o botão "Filtros" da tela reabre este popup pra gerar outro relatório. Na 1ª vez
 * (nenhum relatório gerado ainda) a única saída é "Voltar" (sai da tela); depois da 1ª geração,
 * "Cancelar" só fecha o popup sem alterar o relatório já exibido. Mais simples que o de Contas a
 * Receber — um único período (sempre obrigatório, sem "ao menos um dos três") e sem status/
 * categoria.
 */
export default function FiltrosComissoesModal({
  valores,
  aoMudar,
  ehAdmin,
  empresas,
  podeGerar,
  primeiraVez,
  aoGerar,
  aoFechar,
}: {
  valores: FiltrosTexto
  aoMudar: (parcial: Partial<FiltrosTexto>) => void
  ehAdmin: boolean
  empresas: Empresa[]
  podeGerar: boolean
  primeiraVez: boolean
  aoGerar: () => void
  aoFechar: () => void
}) {
  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(primeiraVez ? undefined : aoFechar)}>
      <div className="modal modal-medio" role="dialog" aria-label="Filtros do relatório" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Filtros do Relatório</h2>
        <p className="muted" style={{ marginTop: -4 }}>
          Informe o período (início e fim).
        </p>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Período
            </label>
            <div style={{ display: 'flex', gap: 6 }}>
              <input
                className="mono"
                placeholder="dd/mm/aaaa"
                value={valores.dataInicial}
                onChange={(e) => aoMudar({ dataInicial: mascararData(e.target.value) })}
                onFocus={(e) => e.target.select()}
                autoFocus
                style={{ maxWidth: 140 }}
              />
              <input
                className="mono"
                placeholder="dd/mm/aaaa"
                value={valores.dataFinal}
                onChange={(e) => aoMudar({ dataFinal: mascararData(e.target.value) })}
                onFocus={(e) => e.target.select()}
                style={{ maxWidth: 140 }}
              />
            </div>
          </div>

          {ehAdmin && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label className="muted" style={{ fontSize: 12 }}>
                Empresas
              </label>
              <EmpresaMultiSelect empresas={empresas} selecionadas={valores.idsEmpresa} aoAlterar={(ids) => aoMudar({ idsEmpresa: ids })} />
            </div>
          )}
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            {primeiraVez ? 'Voltar' : 'Cancelar'}
          </button>
          <button type="button" className="btn" disabled={!podeGerar} onClick={aoGerar}>
            Gerar Relatório
          </button>
        </div>
      </div>
    </div>
  )
}
