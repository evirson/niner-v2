import type { Cliente } from '../../lib/clientes'
import type { PdvCliente } from '../../lib/pdv'
import ClienteForm from '../clientes/ClienteForm'

/**
 * Cadastro de cliente completo dentro do PDV (item 2, revisado 2026-07-31) — a mesma tela de
 * cadastro (`ClienteForm`, `/clientes/novo`), num modal que rola por dentro, sem sair da
 * venda em andamento: `aoSalvarComSucesso` seleciona o cliente recém-criado e fecha o modal
 * em vez de navegar para `/clientes`. Substituiu o formulário mínimo (`ClienteRapidoModal`),
 * que só tinha nome/categoria/gênero/CPF/celular.
 */
export default function ClienteFormModal({
  aoFechar,
  aoCriar,
}: {
  aoFechar: () => void
  aoCriar: (cliente: PdvCliente) => void
}) {
  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div
        className="modal modal-largo"
        role="dialog"
        aria-label="Novo cliente"
        onClick={(e) => e.stopPropagation()}
        style={{ height: '85vh', display: 'flex', flexDirection: 'column', overflow: 'hidden', padding: 0 }}
      >
        <ClienteForm
          aoCancelar={aoFechar}
          aoSalvarComSucesso={(cliente: Cliente) =>
            aoCriar({
              idCliente: cliente.idCliente,
              nome: cliente.nome,
              cpfCnpj: cliente.cpfCnpj,
              telefone: cliente.telefone,
            })
          }
        />
      </div>
    </div>
  )
}
