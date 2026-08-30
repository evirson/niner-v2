import CabecalhoModal from '../../components/CabecalhoModal'
import MultiSelectGenerico from '../../components/MultiSelectGenerico'
import { FILTROS_PRODUTOS_CRM_VAZIO, type FiltrosProdutosCrm, type OpcaoCrm } from '../../lib/crm'
import { mascararData } from '../../lib/masks'
import { fecharAoClicarNoFundo } from '../../lib/modais'

function paraItens(opcoes: OpcaoCrm[]): Array<{ chave: string; rotulo: string }> {
  return opcoes.map((o) => ({ chave: String(o.id), rotulo: o.rotulo }))
}

/**
 * Filtros de Produtos Comprados (item 2 do pedido, 2026-08-05) — popup separado dos Filtros de
 * Cliente (item 1): quando qualquer campo aqui está preenchido, só entram clientes que compraram
 * um produto batendo em TODOS eles ao mesmo tempo (mesma linha de venda — ver `docs/telas/crm.md`
 * e o comentário de `CrmService.buscarClientes`, seção EXISTS).
 */
export default function FiltrosProdutosModal({
  categoriasProduto,
  cores,
  tamanhos,
  valor,
  aoMudar,
  aoFechar,
}: {
  categoriasProduto: OpcaoCrm[]
  cores: OpcaoCrm[]
  tamanhos: OpcaoCrm[]
  valor: FiltrosProdutosCrm
  aoMudar: (f: FiltrosProdutosCrm) => void
  aoFechar: () => void
}) {
  const campo = <K extends keyof FiltrosProdutosCrm>(chave: K, v: FiltrosProdutosCrm[K]) =>
    aoMudar({ ...valor, [chave]: v })

  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
      <div className="modal modal-largo" role="dialog" aria-label="Filtros de Produtos Comprados" onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo="Filtros de Produtos Comprados" aoFechar={aoFechar} />
        <p className="muted" style={{ marginTop: 4 }}>
          Todos os campos são opcionais. Preenchendo mais de um, só entram clientes que compraram um produto que bate
          em todos ao mesmo tempo (a mesma compra).
        </p>

        <div className="form-grid">
          <div className="col-3">
            <label htmlFor="crm-compras-de">Período de Compras De</label>
            <input
              autoFocus
              id="crm-compras-de"
              placeholder="dd/mm/aaaa"
              value={valor.comprasDe}
              onChange={(e) => campo('comprasDe', mascararData(e.target.value))}
              onFocus={(e) => e.target.select()}
            />
          </div>
          <div className="col-3">
            <label htmlFor="crm-compras-ate">Período de Compras Até</label>
            <input
              id="crm-compras-ate"
              placeholder="dd/mm/aaaa"
              value={valor.comprasAte}
              onChange={(e) => campo('comprasAte', mascararData(e.target.value))}
              onFocus={(e) => e.target.select()}
            />
          </div>
          <div className="col-6">
            <label>Categoria de Produtos Comprados</label>
            <div>
              <MultiSelectGenerico
                itens={paraItens(categoriasProduto)}
                selecionadas={valor.idsCategoriaProduto.map(String)}
                aoAlterar={(chaves) => campo('idsCategoriaProduto', chaves.map(Number))}
                rotuloTodos="Todas"
              />
            </div>
          </div>

          <div className="col-6">
            <label>Cor</label>
            <div>
              <MultiSelectGenerico
                itens={paraItens(cores)}
                selecionadas={valor.idsCor.map(String)}
                aoAlterar={(chaves) => campo('idsCor', chaves.map(Number))}
                rotuloTodos="Todas"
              />
            </div>
          </div>
          <div className="col-6">
            <label>Tamanho</label>
            <div>
              <MultiSelectGenerico
                itens={paraItens(tamanhos)}
                selecionadas={valor.idsTamanho.map(String)}
                aoAlterar={(chaves) => campo('idsTamanho', chaves.map(Number))}
                rotuloTodos="Todas"
              />
            </div>
          </div>
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={() => aoMudar(FILTROS_PRODUTOS_CRM_VAZIO)}>
            Limpar
          </button>

        </div>
      </div>
    </div>
  )
}
