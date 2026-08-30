import { useEffect } from 'react'
import type { Empresa } from '../../lib/empresas'
import EmpresaMultiSelect from '../../components/EmpresaMultiSelect'
import type { Funcionario } from '../../lib/funcionarios'
import { mascararData } from '../../lib/masks'
import type { TotalizarPor } from '../../lib/relatorioVendas'
import { fecharAoClicarNoFundo } from '../../lib/modais'

export type PeriodoPreset = 'PERSONALIZADO' | 'MES_ATUAL' | 'MES_ANTERIOR' | 'ULTIMOS_3_MESES' | 'ULTIMOS_6_MESES' | 'ULTIMOS_12_MESES'

export const PERIODOS: Array<{ chave: PeriodoPreset; rotulo: string }> = [
  { chave: 'PERSONALIZADO', rotulo: 'Personalizado' },
  { chave: 'MES_ATUAL', rotulo: 'Mês Atual' },
  { chave: 'MES_ANTERIOR', rotulo: 'Mês Anterior' },
  { chave: 'ULTIMOS_3_MESES', rotulo: 'Últimos 3 Meses' },
  { chave: 'ULTIMOS_6_MESES', rotulo: 'Últimos 6 Meses' },
  { chave: 'ULTIMOS_12_MESES', rotulo: 'Últimos 12 Meses' },
]

export const TOTALIZADORES: Array<{ chave: TotalizarPor; rotulo: string }> = [
  { chave: 'NAO_TOTALIZAR', rotulo: 'Não Totalizar' },
  { chave: 'DATA_VENDA', rotulo: 'Data da Venda' },
  { chave: 'CLIENTE', rotulo: 'Cliente' },
  { chave: 'VENDEDOR', rotulo: 'Vendedor' },
  { chave: 'OPERADOR_CAIXA', rotulo: 'Operador de Caixa' },
  { chave: 'EMPRESA', rotulo: 'Empresa' },
]

function isoDeData(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function primeiroDiaDoMes(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1)
}

function ultimoDiaDoMes(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth() + 1, 0)
}

function hojeISO(): string {
  return isoDeData(new Date())
}

/** Presets calculam a data em cima do relógio local — mesmo espírito de `hojeISO()`, nunca UTC. */
export function calcularPeriodo(preset: PeriodoPreset): { inicio: string; fim: string } {
  const hoje = new Date()
  if (preset === 'MES_ANTERIOR') {
    const mesAnterior = new Date(hoje.getFullYear(), hoje.getMonth() - 1, 1)
    return { inicio: isoDeData(primeiroDiaDoMes(mesAnterior)), fim: isoDeData(ultimoDiaDoMes(mesAnterior)) }
  }
  if (preset === 'ULTIMOS_3_MESES') return { inicio: isoDeData(new Date(hoje.getFullYear(), hoje.getMonth() - 3, hoje.getDate())), fim: hojeISO() }
  if (preset === 'ULTIMOS_6_MESES') return { inicio: isoDeData(new Date(hoje.getFullYear(), hoje.getMonth() - 6, hoje.getDate())), fim: hojeISO() }
  if (preset === 'ULTIMOS_12_MESES') return { inicio: isoDeData(new Date(hoje.getFullYear(), hoje.getMonth() - 12, hoje.getDate())), fim: hojeISO() }
  // MES_ATUAL e fallback
  return { inicio: isoDeData(primeiroDiaDoMes(hoje)), fim: hojeISO() }
}

export interface FiltrosTexto {
  periodoPreset: PeriodoPreset
  dataInicial: string
  dataFinal: string
  idsEmpresa: number[]
  vendedor: Funcionario | null
  totalizarPor: TotalizarPor
}

export function filtrosIniciais(isoParaData: (iso: string) => string): FiltrosTexto {
  const periodoInicial = calcularPeriodo('MES_ATUAL')
  return {
    periodoPreset: 'MES_ATUAL',
    dataInicial: isoParaData(periodoInicial.inicio),
    dataFinal: isoParaData(periodoInicial.fim),
    idsEmpresa: [],
    vendedor: null,
    totalizarPor: 'NAO_TOTALIZAR',
  }
}

/**
 * Popup de filtros do Relatório de Vendas (2026-08-03) — mesmo padrão do popup de Contas a
 * Receber: abre sozinho quando a tela é aberta (nada é buscado antes de o usuário escolher os
 * filtros); o botão "Filtros" da tela reabre este popup pra gerar outro relatório. Na 1ª vez
 * (nenhum relatório gerado ainda) a única saída é "Voltar" (sai da tela); depois da 1ª geração,
 * "Cancelar" só fecha o popup sem alterar o relatório já exibido. "Totalizar por" também mora
 * aqui — trocar o totalizador só troca a grid depois de "Gerar Relatório", mesma regra dos
 * outros filtros (antes era imediato, na filtros-bar embutida).
 */
export default function FiltrosVendasModal({
  valores,
  aoMudar,
  ehAdmin,
  empresas,
  podeGerar,
  primeiraVez,
  aoGerar,
  aoFechar,
  isoParaData,
  aoAbrirBuscaVendedor,
}: {
  valores: FiltrosTexto
  aoMudar: (parcial: Partial<FiltrosTexto>) => void
  ehAdmin: boolean
  empresas: Empresa[]
  podeGerar: boolean
  primeiraVez: boolean
  aoGerar: () => void
  aoFechar: () => void
  isoParaData: (iso: string) => string
  aoAbrirBuscaVendedor: () => void
}) {
  useEffect(() => {
    if (valores.periodoPreset === 'PERSONALIZADO') return
    const { inicio, fim } = calcularPeriodo(valores.periodoPreset)
    aoMudar({ dataInicial: isoParaData(inicio), dataFinal: isoParaData(fim) })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [valores.periodoPreset])

  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(primeiraVez ? undefined : aoFechar)}>
      <div className="modal modal-medio" role="dialog" aria-label="Filtros do relatório" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Filtros do Relatório</h2>
        <p className="muted" style={{ marginTop: -4 }}>
          Informe um período válido.
        </p>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Período
            </label>
            <select
              value={valores.periodoPreset}
              onChange={(e) => aoMudar({ periodoPreset: e.target.value as PeriodoPreset })}
              aria-label="Período de vendas"
              autoFocus
            >
              {PERIODOS.map((p) => (
                <option key={p.chave} value={p.chave}>
                  {p.rotulo}
                </option>
              ))}
            </select>
            <div style={{ display: 'flex', gap: 6 }}>
              <input
                className="mono"
                placeholder="dd/mm/aaaa"
                value={valores.dataInicial}
                onChange={(e) => aoMudar({ dataInicial: mascararData(e.target.value) })}
                onFocus={(e) => e.target.select()}
                aria-label="Data inicial"
                disabled={valores.periodoPreset !== 'PERSONALIZADO'}
                style={{ maxWidth: 140 }}
              />
              <input
                className="mono"
                placeholder="dd/mm/aaaa"
                value={valores.dataFinal}
                onChange={(e) => aoMudar({ dataFinal: mascararData(e.target.value) })}
                onFocus={(e) => e.target.select()}
                aria-label="Data final"
                disabled={valores.periodoPreset !== 'PERSONALIZADO'}
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

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Vendedor
            </label>
            <div style={{ display: 'flex', gap: 6 }}>
              <button type="button" className="btn ghost" onClick={aoAbrirBuscaVendedor}>
                {valores.vendedor ? valores.vendedor.nome : 'Todos os vendedores…'}
              </button>
              {valores.vendedor && (
                <button type="button" className="btn ghost" onClick={() => aoMudar({ vendedor: null })} aria-label="Limpar vendedor">
                  ✕
                </button>
              )}
            </div>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Totalizar por
            </label>
            <select
              value={valores.totalizarPor}
              onChange={(e) => aoMudar({ totalizarPor: e.target.value as TotalizarPor })}
              aria-label="Totalizar por"
            >
              {TOTALIZADORES.map((t) => (
                <option key={t.chave} value={t.chave}>
                  {t.rotulo}
                </option>
              ))}
            </select>
          </div>
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
