import MultiSelectGenerico from '../../components/MultiSelectGenerico'
import {
  FILTROS_CLIENTES_CRM_VAZIO,
  ROTULO_GENERO_CRM,
  type FiltrosClientesCrm,
  type GeneroCrm,
  type OpcaoCrm,
} from '../../lib/crm'
import { mascararData, mascararDiaMes } from '../../lib/masks'

const OPCOES_GENERO: Array<{ chave: GeneroCrm; rotulo: string }> = [
  { chave: 'MASCULINO', rotulo: ROTULO_GENERO_CRM.MASCULINO },
  { chave: 'FEMININO', rotulo: ROTULO_GENERO_CRM.FEMININO },
  { chave: 'OUTROS', rotulo: ROTULO_GENERO_CRM.OUTROS },
]

function paraItens(opcoes: OpcaoCrm[]): Array<{ chave: string; rotulo: string }> {
  return opcoes.map((o) => ({ chave: String(o.id), rotulo: o.rotulo }))
}

/**
 * Filtros de Cliente (item 1 do pedido, 2026-08-05) — popup pra não competir por espaço com o
 * resto da tela do CRM (que não pode rolar). Todos os campos são opcionais e combináveis; o
 * estado vive no `CrmForm` (controlado), sem "aplicar/cancelar" — como nada acontece até "Gerar
 * Planilha Excel", editar ao vivo é seguro e mais simples que um rascunho separado.
 */
export default function FiltrosClientesModal({
  categoriasCliente,
  valor,
  aoMudar,
  aoFechar,
}: {
  categoriasCliente: OpcaoCrm[]
  valor: FiltrosClientesCrm
  aoMudar: (f: FiltrosClientesCrm) => void
  aoFechar: () => void
}) {
  const campo = <K extends keyof FiltrosClientesCrm>(chave: K, v: FiltrosClientesCrm[K]) =>
    aoMudar({ ...valor, [chave]: v })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Filtros de Clientes" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Filtros de Clientes</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Todos os campos são opcionais — combine quantos quiser.
        </p>

        <div className="form-grid">
          <div className="col-3">
            <label htmlFor="crm-cliente-inicial">Cliente Inicial</label>
            <input
              id="crm-cliente-inicial"
              placeholder="A"
              maxLength={40}
              value={valor.clienteInicial}
              onChange={(e) => campo('clienteInicial', e.target.value.toUpperCase())}
            />
          </div>
          <div className="col-3">
            <label htmlFor="crm-cliente-final">Cliente Final</label>
            <input
              id="crm-cliente-final"
              placeholder="Z"
              maxLength={40}
              value={valor.clienteFinal}
              onChange={(e) => campo('clienteFinal', e.target.value.toUpperCase())}
            />
          </div>
          <div className="col-6">
            <label>Gênero</label>
            <div>
              <MultiSelectGenerico
                itens={OPCOES_GENERO.map((o) => ({ chave: o.chave, rotulo: o.rotulo }))}
                selecionadas={valor.generos}
                aoAlterar={(chaves) => campo('generos', chaves as GeneroCrm[])}
                rotuloTodos="Todos"
              />
            </div>
          </div>

          <div className="col-3">
            <label htmlFor="crm-idade-de">Idade De</label>
            <input
              id="crm-idade-de"
              inputMode="numeric"
              placeholder="0"
              value={valor.idadeDe}
              onChange={(e) => campo('idadeDe', e.target.value.replace(/\D/g, ''))}
            />
          </div>
          <div className="col-3">
            <label htmlFor="crm-idade-ate">Idade Até</label>
            <input
              id="crm-idade-ate"
              inputMode="numeric"
              placeholder="0"
              value={valor.idadeAte}
              onChange={(e) => campo('idadeAte', e.target.value.replace(/\D/g, ''))}
            />
          </div>
          <div className="col-3">
            <label htmlFor="crm-aniversario-de">Aniversário De (dd-mm)</label>
            <input
              id="crm-aniversario-de"
              placeholder="dd-mm"
              value={valor.aniversarioDe}
              onChange={(e) => campo('aniversarioDe', mascararDiaMes(e.target.value))}
            />
          </div>
          <div className="col-3">
            <label htmlFor="crm-aniversario-ate">Aniversário Até (dd-mm)</label>
            <input
              id="crm-aniversario-ate"
              placeholder="dd-mm"
              value={valor.aniversarioAte}
              onChange={(e) => campo('aniversarioAte', mascararDiaMes(e.target.value))}
            />
          </div>

          <div className="col-6">
            <label>Categoria do Cliente</label>
            <div>
              <MultiSelectGenerico
                itens={paraItens(categoriasCliente)}
                selecionadas={valor.idsCategoriaCliente.map(String)}
                aoAlterar={(chaves) => campo('idsCategoriaCliente', chaves.map(Number))}
                rotuloTodos="Todas"
              />
            </div>
          </div>
          <div className="col-3">
            <label htmlFor="crm-cadastro-de">Cadastro De</label>
            <input
              id="crm-cadastro-de"
              placeholder="dd/mm/aaaa"
              value={valor.cadastroDe}
              onChange={(e) => campo('cadastroDe', mascararData(e.target.value))}
              onFocus={(e) => e.target.select()}
            />
          </div>
          <div className="col-3">
            <label htmlFor="crm-cadastro-ate">Cadastro Até</label>
            <input
              id="crm-cadastro-ate"
              placeholder="dd/mm/aaaa"
              value={valor.cadastroAte}
              onChange={(e) => campo('cadastroAte', mascararData(e.target.value))}
              onFocus={(e) => e.target.select()}
            />
          </div>

          <div className="col-6">
            <label htmlFor="crm-dias-sem-compras">Nº de Dias sem Compras (mínimo)</label>
            <input
              id="crm-dias-sem-compras"
              inputMode="numeric"
              placeholder="0"
              value={valor.diasSemComprasMinimo}
              onChange={(e) => campo('diasSemComprasMinimo', e.target.value.replace(/\D/g, ''))}
            />
          </div>
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={() => aoMudar(FILTROS_CLIENTES_CRM_VAZIO)}>
            Limpar
          </button>
          <button type="button" className="btn" onClick={aoFechar}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  )
}
