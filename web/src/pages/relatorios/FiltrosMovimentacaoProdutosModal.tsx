import type { CategoriaProduto } from '../../lib/categoriasProduto'
import EmpresaMultiSelect from '../../components/EmpresaMultiSelect'
import MultiSelectGenerico from '../../components/MultiSelectGenerico'
import type { Empresa } from '../../lib/empresas'
import { mascararData } from '../../lib/masks'
import type { ModeloRelatorioMovimentacao, TipoMovimentoProduto, VariacaoEncontrada } from '../../lib/relatorioMovimentacaoProdutos'

export interface FiltrosMovimentacaoTexto {
  modelo: ModeloRelatorioMovimentacao
  dataInicial: string
  dataFinal: string
  idsEmpresa: number[]
  tipos: TipoMovimentoProduto[]
  marcas: string[]
  idsCategoria: number[]
  idEmpresaKardex: number | null
  variacaoKardex: VariacaoEncontrada | null
}

export const FILTROS_MOVIMENTACAO_VAZIOS: FiltrosMovimentacaoTexto = {
  modelo: 'ANALITICO',
  dataInicial: '',
  dataFinal: '',
  idsEmpresa: [],
  tipos: [],
  marcas: [],
  idsCategoria: [],
  idEmpresaKardex: null,
  variacaoKardex: null,
}

export const OPCOES_MODELO: Array<{ chave: ModeloRelatorioMovimentacao; rotulo: string }> = [
  { chave: 'ANALITICO', rotulo: 'Analítico' },
  { chave: 'KARDEX', rotulo: 'Kardex por Produto' },
  { chave: 'SINTETICO', rotulo: 'Sintético' },
]

/** Rótulo amigável do tipo de movimento — usado aqui (checkbox) e na tela principal (coluna/
 *  gráficos). Backend só devolve o valor cru do ENUM (P4 — front decide a apresentação). */
export const ROTULO_TIPO_MOVIMENTO: Record<TipoMovimentoProduto, string> = {
  COMPRA: 'Compra',
  TRANSFERENCIA: 'Transferência',
  DEVOLUCAO: 'Devolução',
  AJUSTE: 'Ajuste',
  VENDA: 'Venda',
  RESERVA: 'Reserva',
  LIBERACAO_RESERVA: 'Liberação de Reserva',
  CANCELAMENTO: 'Cancelamento',
}

const TODOS_OS_TIPOS: TipoMovimentoProduto[] = [
  'COMPRA', 'TRANSFERENCIA', 'DEVOLUCAO', 'AJUSTE', 'VENDA', 'RESERVA', 'LIBERACAO_RESERVA', 'CANCELAMENTO',
]

function rotuloVariacaoSelecionada(v: VariacaoEncontrada): string {
  const variacao = [v.variacaoCor, v.variacaoTamanho].filter(Boolean).join(' · ')
  return `${v.sku} — ${v.descricaoProduto}${variacao ? ` (${variacao})` : ''}`
}

/**
 * Popup de filtros do Relatório de Movimentação de Produtos / Kardex (2026-08-04) — mesmo padrão
 * dos demais relatórios (abre sozinho, só sai por "Voltar"/"Cancelar" antes/depois da 1ª
 * geração). O seletor de Modelo troca os campos visíveis: Analítico/Sintético mostram Empresas
 * (multi)/Tipo de Movimento/Marca/Categorias; Kardex troca por Empresa única + busca de produto
 * (`PesquisaVariacaoModal`, aberta pelo pai via `aoAbrirBuscaVariacao`) — estoque é sempre por
 * empresa, não faz sentido "todas as empresas" numa ficha de UM produto.
 */
export default function FiltrosMovimentacaoProdutosModal({
  valores,
  aoMudar,
  ehAdmin,
  empresas,
  marcas,
  categorias,
  podeGerar,
  primeiraVez,
  aoGerar,
  aoFechar,
  aoAbrirBuscaVariacao,
}: {
  valores: FiltrosMovimentacaoTexto
  aoMudar: (parcial: Partial<FiltrosMovimentacaoTexto>) => void
  ehAdmin: boolean
  empresas: Empresa[]
  marcas: string[]
  categorias: CategoriaProduto[]
  podeGerar: boolean
  primeiraVez: boolean
  aoGerar: () => void
  aoFechar: () => void
  aoAbrirBuscaVariacao: () => void
}) {
  const ehKardex = valores.modelo === 'KARDEX'

  return (
    <div className="modal-overlay" onClick={primeiraVez ? undefined : aoFechar}>
      <div className="modal modal-medio" role="dialog" aria-label="Filtros do relatório" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Filtros do Relatório</h2>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Modelo
            </label>
            <select
              value={valores.modelo}
              onChange={(e) => aoMudar({ modelo: e.target.value as ModeloRelatorioMovimentacao })}
              aria-label="Modelo do relatório"
            >
              {OPCOES_MODELO.map((o) => (
                <option key={o.chave} value={o.chave}>
                  {o.rotulo}
                </option>
              ))}
            </select>
          </div>

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

          {ehKardex ? (
            <>
              {ehAdmin && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  <label className="muted" style={{ fontSize: 12 }}>
                    Empresa
                  </label>
                  <select
                    value={valores.idEmpresaKardex ?? ''}
                    onChange={(e) => aoMudar({ idEmpresaKardex: e.target.value ? Number(e.target.value) : null })}
                    aria-label="Empresa do Kardex"
                  >
                    <option value="">Selecione…</option>
                    {empresas.map((e) => (
                      <option key={e.idEmpresa} value={e.idEmpresa}>
                        {e.nomeFantasia ?? e.razaoSocial}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <label className="muted" style={{ fontSize: 12 }}>
                  Produto
                </label>
                {valores.variacaoKardex ? (
                  <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                    <span style={{ flex: 1 }}>{rotuloVariacaoSelecionada(valores.variacaoKardex)}</span>
                    <button type="button" className="btn ghost" onClick={aoAbrirBuscaVariacao}>
                      Trocar
                    </button>
                  </div>
                ) : (
                  <button type="button" className="btn ghost" onClick={aoAbrirBuscaVariacao} style={{ alignSelf: 'flex-start' }}>
                    Buscar produto…
                  </button>
                )}
              </div>
            </>
          ) : (
            <>
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
                  Tipo de Movimento
                </label>
                <MultiSelectGenerico
                  itens={TODOS_OS_TIPOS.map((t) => ({ chave: t, rotulo: ROTULO_TIPO_MOVIMENTO[t] }))}
                  selecionadas={valores.tipos}
                  aoAlterar={(tipos) => aoMudar({ tipos: tipos as TipoMovimentoProduto[] })}
                  rotuloTodos="Todos os tipos"
                />
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <label className="muted" style={{ fontSize: 12 }}>
                  Marca
                </label>
                <MultiSelectGenerico
                  itens={marcas.map((m) => ({ chave: m, rotulo: m }))}
                  selecionadas={valores.marcas}
                  aoAlterar={(marcasSelecionadas) => aoMudar({ marcas: marcasSelecionadas })}
                  rotuloTodos="Todas as marcas"
                />
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <label className="muted" style={{ fontSize: 12 }}>
                  Categorias
                </label>
                <MultiSelectGenerico
                  itens={categorias.map((c) => ({ chave: String(c.idCategoria), rotulo: c.nomeCategoria }))}
                  selecionadas={valores.idsCategoria.map(String)}
                  aoAlterar={(chaves) => aoMudar({ idsCategoria: chaves.map(Number) })}
                  rotuloTodos="Todas as categorias"
                />
              </div>
            </>
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
