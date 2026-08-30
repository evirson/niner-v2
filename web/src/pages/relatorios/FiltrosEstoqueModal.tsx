import type { CategoriaProduto } from '../../lib/categoriasProduto'
import EmpresaMultiSelect from '../../components/EmpresaMultiSelect'
import MultiSelectGenerico from '../../components/MultiSelectGenerico'
import type { Empresa } from '../../lib/empresas'
import type { ModeloRelatorioEstoque, SituacaoProduto, TipoQuantidade } from '../../lib/relatorioEstoque'
import { fecharAoClicarNoFundo } from '../../lib/modais'

export interface FiltrosEstoqueTexto {
  modelo: ModeloRelatorioEstoque
  idsEmpresa: number[]
  marcas: string[]
  idsCategoria: number[]
  tipoQuantidade: TipoQuantidade
  situacao: SituacaoProduto
}

export const FILTROS_ESTOQUE_VAZIOS: FiltrosEstoqueTexto = {
  modelo: 'INVENTARIO',
  idsEmpresa: [],
  marcas: [],
  idsCategoria: [],
  tipoQuantidade: 'TODOS',
  situacao: 'ATIVOS',
}

export const OPCOES_MODELO: Array<{ chave: ModeloRelatorioEstoque; rotulo: string }> = [
  { chave: 'INVENTARIO', rotulo: 'Inventário' },
  { chave: 'SINTETICO', rotulo: 'Sintético' },
  { chave: 'ANALITICO', rotulo: 'Analítico' },
]

export const OPCOES_TIPO_QUANTIDADE: Array<{ chave: TipoQuantidade; rotulo: string }> = [
  { chave: 'TODOS', rotulo: 'Todos' },
  { chave: 'DIFERENTE_DE_ZERO', rotulo: 'Diferente de Zero' },
  { chave: 'ZERADA', rotulo: 'Zerada' },
]

export const OPCOES_SITUACAO: Array<{ chave: SituacaoProduto; rotulo: string }> = [
  { chave: 'ATIVOS', rotulo: 'Somente Ativos' },
  { chave: 'INATIVOS', rotulo: 'Somente Inativos' },
  { chave: 'TODOS', rotulo: 'Todos' },
]

/**
 * Popup de filtros do Relatório de Estoque (2026-08-04) — mesmo padrão dos demais relatórios
 * (abre sozinho, só sai por "Voltar" antes da 1ª geração). O seletor de Modelo troca as colunas
 * da grid exibida (Inventário/Sintético/Analítico, mesmo padrão do totalizador do Relatório de
 * Vendas) — os demais filtros (Empresas/Marca/Categorias/Tipo de Quantidade/Situação) valem para
 * os 3 modelos igualmente.
 */
export default function FiltrosEstoqueModal({
  valores,
  aoMudar,
  ehAdmin,
  empresas,
  marcas,
  categorias,
  primeiraVez,
  aoGerar,
  aoFechar,
}: {
  valores: FiltrosEstoqueTexto
  aoMudar: (parcial: Partial<FiltrosEstoqueTexto>) => void
  ehAdmin: boolean
  empresas: Empresa[]
  marcas: string[]
  categorias: CategoriaProduto[]
  primeiraVez: boolean
  aoGerar: () => void
  aoFechar: () => void
}) {
  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(primeiraVez ? undefined : aoFechar)}>
      <div className="modal modal-medio" role="dialog" aria-label="Filtros do relatório" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Filtros do Relatório</h2>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Modelo
            </label>
            <select
              autoFocus
              value={valores.modelo}
              onChange={(e) => aoMudar({ modelo: e.target.value as ModeloRelatorioEstoque })}
              aria-label="Modelo do relatório"
            >
              {OPCOES_MODELO.map((o) => (
                <option key={o.chave} value={o.chave}>
                  {o.rotulo}
                </option>
              ))}
            </select>
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

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Tipo de Quantidade
            </label>
            <select
              value={valores.tipoQuantidade}
              onChange={(e) => aoMudar({ tipoQuantidade: e.target.value as TipoQuantidade })}
              aria-label="Tipo de quantidade"
            >
              {OPCOES_TIPO_QUANTIDADE.map((o) => (
                <option key={o.chave} value={o.chave}>
                  {o.rotulo}
                </option>
              ))}
            </select>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Situação do Produto
            </label>
            <select
              value={valores.situacao}
              onChange={(e) => aoMudar({ situacao: e.target.value as SituacaoProduto })}
              aria-label="Situação do produto"
            >
              {OPCOES_SITUACAO.map((o) => (
                <option key={o.chave} value={o.chave}>
                  {o.rotulo}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            {primeiraVez ? 'Voltar' : 'Cancelar'}
          </button>
          <button type="button" className="btn" onClick={aoGerar}>
            Gerar Relatório
          </button>
        </div>
      </div>
    </div>
  )
}
