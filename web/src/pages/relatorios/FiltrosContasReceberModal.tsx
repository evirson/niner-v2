import type { Empresa } from '../../lib/empresas'
import CabecalhoModal from '../../components/CabecalhoModal'
import EmpresaMultiSelect from '../../components/EmpresaMultiSelect'
import { mascararData } from '../../lib/masks'
import type { CategoriaParcela, StatusParcela } from '../../lib/relatorioContasReceber'
import { fecharAoClicarNoFundo } from '../../lib/modais'

export interface FiltrosTexto {
  dataVendaInicial: string
  dataVendaFinal: string
  dataVencimentoInicial: string
  dataVencimentoFinal: string
  dataRecebimentoInicial: string
  dataRecebimentoFinal: string
  idsEmpresa: number[]
  status: StatusParcela | ''
  categoria: CategoriaParcela | ''
}

export const FILTROS_VAZIOS: FiltrosTexto = {
  dataVendaInicial: '',
  dataVendaFinal: '',
  dataVencimentoInicial: '',
  dataVencimentoFinal: '',
  dataRecebimentoInicial: '',
  dataRecebimentoFinal: '',
  idsEmpresa: [],
  status: '',
  categoria: '',
}

export const OPCOES_STATUS: Array<{ chave: StatusParcela | ''; rotulo: string }> = [
  { chave: '', rotulo: 'Todos' },
  { chave: 'ABERTO', rotulo: 'Parcelas Em Aberto' },
  { chave: 'RECEBIDA', rotulo: 'Parcelas Recebidas' },
]

export const OPCOES_CATEGORIA: Array<{ chave: CategoriaParcela | ''; rotulo: string }> = [
  { chave: '', rotulo: 'Todos' },
  { chave: 'CREDIARIO', rotulo: 'Crediário' },
  { chave: 'CARTAO_DEBITO', rotulo: 'Cartão Débito' },
  { chave: 'CARTAO_CREDITO', rotulo: 'Cartão Crédito' },
]

/** Um par de campos de data (dd/mm/aaaa), controlado como texto — mesmo padrão do resto do
 *  sistema, nunca `<input type="date">`. */
function CampoPeriodo({
  rotulo,
  inicial,
  final,
  aoAlterarInicial,
  aoAlterarFinal,
  autoFocarInicial,
}: {
  rotulo: string
  inicial: string
  final: string
  aoAlterarInicial: (v: string) => void
  aoAlterarFinal: (v: string) => void
  autoFocarInicial?: boolean
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <label className="muted" style={{ fontSize: 12 }}>
        {rotulo}
      </label>
      <div style={{ display: 'flex', gap: 6 }}>
        <input
          className="mono"
          placeholder="dd/mm/aaaa"
          value={inicial}
          onChange={(e) => aoAlterarInicial(mascararData(e.target.value))}
          onFocus={(e) => e.target.select()}
          autoFocus={autoFocarInicial}
          style={{ maxWidth: 140 }}
        />
        <input
          className="mono"
          placeholder="dd/mm/aaaa"
          value={final}
          onChange={(e) => aoAlterarFinal(mascararData(e.target.value))}
          onFocus={(e) => e.target.select()}
          style={{ maxWidth: 140 }}
        />
      </div>
    </div>
  )
}

/**
 * Popup de filtros do Relatório de Contas a Receber / Recebidas (2026-08-03) — abre sozinho
 * quando a tela é aberta (nada é buscado antes de o usuário escolher os filtros); o botão
 * "Filtros" da tela reabre este popup pra gerar outro relatório. Na 1ª vez (nenhum relatório
 * gerado ainda) a única saída é "Voltar" (sai da tela) — sem X nem clique no overlay, mesmo
 * padrão do popup obrigatório de Abertura de Caixa. Depois de gerar ao menos uma vez, "Cancelar"
 * fecha o popup sem alterar o relatório já exibido.
 */
export default function FiltrosContasReceberModal({
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
        <CabecalhoModal titulo="Filtros do Relatório" aoFechar={aoFechar} />
        <p className="muted" style={{ marginTop: -4 }}>
          Informe ao menos um período completo (início e fim) — Venda, Vencimento ou Recebimento.
        </p>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <CampoPeriodo
            rotulo="Período de Venda"
            inicial={valores.dataVendaInicial}
            final={valores.dataVendaFinal}
            aoAlterarInicial={(v) => aoMudar({ dataVendaInicial: v })}
            aoAlterarFinal={(v) => aoMudar({ dataVendaFinal: v })}
            autoFocarInicial
          />
          <CampoPeriodo
            rotulo="Período de Vencimento"
            inicial={valores.dataVencimentoInicial}
            final={valores.dataVencimentoFinal}
            aoAlterarInicial={(v) => aoMudar({ dataVencimentoInicial: v })}
            aoAlterarFinal={(v) => aoMudar({ dataVencimentoFinal: v })}
          />
          <CampoPeriodo
            rotulo="Período de Recebimento"
            inicial={valores.dataRecebimentoInicial}
            final={valores.dataRecebimentoFinal}
            aoAlterarInicial={(v) => aoMudar({ dataRecebimentoInicial: v })}
            aoAlterarFinal={(v) => aoMudar({ dataRecebimentoFinal: v })}
          />

          {ehAdmin && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label className="muted" style={{ fontSize: 12 }}>
                Empresas
              </label>
              <EmpresaMultiSelect empresas={empresas} selecionadas={valores.idsEmpresa} aoAlterar={(ids) => aoMudar({ idsEmpresa: ids })} />
            </div>
          )}

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Status da Parcela
            </label>
            <select
              value={valores.status}
              onChange={(e) => aoMudar({ status: e.target.value as StatusParcela | '' })}
              aria-label="Status da parcela"
            >
              {OPCOES_STATUS.map((o) => (
                <option key={o.chave} value={o.chave}>
                  {o.rotulo}
                </option>
              ))}
            </select>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Forma de Pagamento
            </label>
            <select
              value={valores.categoria}
              onChange={(e) => aoMudar({ categoria: e.target.value as CategoriaParcela | '' })}
              aria-label="Forma de pagamento"
            >
              {OPCOES_CATEGORIA.map((o) => (
                <option key={o.chave} value={o.chave}>
                  {o.rotulo}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn" disabled={!podeGerar} onClick={aoGerar}>
            Gerar Relatório
          </button>
        </div>
      </div>
    </div>
  )
}
