import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../lib/api'
import {
  FORNECEDOR_VAZIO,
  criarFornecedor,
  paraRequisicao,
  type Fornecedor,
  type FornecedorFormState,
} from '../lib/fornecedores'
import { listarPlanosContas } from '../lib/planoContas'
import { ESTADOS_UF, mascararCep, mascararCpfCnpj, mascararTelefone } from '../lib/masks'
import { maiusculas } from '../lib/texto'
import PlanoContasModal from './PlanoContasModal'
import Toast from './Toast'

/**
 * Criação rápida de fornecedor embutida (Entrada de Produtos por Compra) — o operador não
 * precisa sair da conferência da entrada pra cadastrar um fornecedor novo. Traz TODOS os campos
 * do cadastro completo (2026-08-12, pedido do dono do produto — a versão anterior só tinha
 * razão social/CNPJ/plano de contas), reaproveitando `paraRequisicao`/`FORNECEDOR_VAZIO` de
 * `lib/fornecedores.ts` — o mesmo contrato usado por `FornecedorForm.tsx`. Fica de fora, de
 * propósito, só o que é específico da tela completa: campos obrigatórios configuráveis por
 * tenant (`cfg_tela_campo`), verificação de CNPJ duplicado e autopreenchimento por CEP — nada
 * disso impede o cadastro rápido, só vive na tela própria `/fornecedores`.
 */
export default function FornecedorQuickCreateModal({
  aoFechar,
  aoCriar,
}: {
  aoFechar: () => void
  aoCriar?: (fornecedor: Fornecedor) => void
}) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<FornecedorFormState>(FORNECEDOR_VAZIO)
  const [modalPlanoAberto, setModalPlanoAberto] = useState(false)
  const [toast, setToast] = useState('')

  const campo = (chave: keyof FornecedorFormState) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  const { data: planos } = useQuery({
    queryKey: ['planos-contas', 'entrada-mercadoria'],
    queryFn: () => listarPlanosContas({ tamanho: 100 }),
  })

  const criar = useMutation({
    mutationFn: () => criarFornecedor(paraRequisicao(form)),
    onSuccess: (fornecedor) => {
      queryClient.invalidateQueries({ queryKey: ['fornecedores'] })
      aoCriar?.(fornecedor)
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível criar o fornecedor.'),
  })

  const valido = form.razaoSocial.trim() && form.idPlanoContas.trim()

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Novo fornecedor" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Novo fornecedor</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Cadastro rápido, sem sair da entrada — a gestão completa fica na tela Fornecedores.
        </p>

        <div className="form-grid">
          <div className="col-8">
            <label htmlFor="modal-fornecedor-razao">Razão Social *</label>
            <input id="modal-fornecedor-razao" autoFocus value={form.razaoSocial} onChange={campo('razaoSocial')} />
          </div>
          <div className="col-4">
            <label htmlFor="modal-fornecedor-plano">Plano de Contas *</label>
            <div className="linha-com-botao">
              <select
                id="modal-fornecedor-plano"
                value={form.idPlanoContas}
                onChange={(e) => setForm((f) => ({ ...f, idPlanoContas: e.target.value }))}
              >
                <option value="">Selecione…</option>
                {planos?.itens.map((p) => (
                  <option key={p.idPlanoContas} value={p.idPlanoContas}>
                    {p.idPlanoContas} — {p.descricao}
                  </option>
                ))}
              </select>
              <button type="button" tabIndex={-1} className="btn ghost" onClick={() => setModalPlanoAberto(true)}>
                ＋ Novo
              </button>
            </div>
          </div>

          <div className="col-4">
            <label htmlFor="modal-fornecedor-cnpj">CNPJ</label>
            <input
              id="modal-fornecedor-cnpj"
              value={form.cnpj}
              onChange={(e) => setForm((f) => ({ ...f, cnpj: mascararCpfCnpj(e.target.value, false) }))}
              placeholder="Opcional"
            />
          </div>
          <div className="col-4">
            <label htmlFor="modal-fornecedor-ie">Inscrição Estadual</label>
            <input id="modal-fornecedor-ie" value={form.inscricaoEstadual} onChange={campo('inscricaoEstadual')} placeholder="Opcional" />
          </div>
          <div className="col-4">
            <label htmlFor="modal-fornecedor-fantasia">Nome Fantasia</label>
            <input id="modal-fornecedor-fantasia" value={form.nomeFantasia} onChange={campo('nomeFantasia')} placeholder="Opcional" />
          </div>

          <div className="col-8">
            <label htmlFor="modal-fornecedor-email">E-mail</label>
            <input
              id="modal-fornecedor-email"
              type="email"
              value={form.email}
              onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
              placeholder="Opcional"
            />
          </div>
          <div className="col-4">
            <label htmlFor="modal-fornecedor-telefone">Telefone</label>
            <input
              id="modal-fornecedor-telefone"
              value={form.telefone}
              onChange={(e) => setForm((f) => ({ ...f, telefone: mascararTelefone(e.target.value) }))}
              placeholder="Opcional"
            />
          </div>

          <div className="col-3">
            <label htmlFor="modal-fornecedor-cep">CEP</label>
            <input
              id="modal-fornecedor-cep"
              value={form.cep}
              onChange={(e) => setForm((f) => ({ ...f, cep: mascararCep(e.target.value) }))}
              placeholder="Opcional"
            />
          </div>
          <div className="col-9">
            <label htmlFor="modal-fornecedor-endereco">Endereço</label>
            <input id="modal-fornecedor-endereco" value={form.endereco} onChange={campo('endereco')} placeholder="Opcional" />
          </div>
          <div className="col-3">
            <label htmlFor="modal-fornecedor-numero">Número</label>
            <input id="modal-fornecedor-numero" value={form.numero} onChange={campo('numero')} placeholder="Opcional" />
          </div>
          <div className="col-9">
            <label htmlFor="modal-fornecedor-bairro">Bairro</label>
            <input id="modal-fornecedor-bairro" value={form.bairro} onChange={campo('bairro')} placeholder="Opcional" />
          </div>
          <div className="col-9">
            <label htmlFor="modal-fornecedor-cidade">Cidade</label>
            <input id="modal-fornecedor-cidade" value={form.cidade} onChange={campo('cidade')} placeholder="Opcional" />
          </div>
          <div className="col-3">
            <label htmlFor="modal-fornecedor-uf">UF</label>
            <select id="modal-fornecedor-uf" value={form.estado} onChange={(e) => setForm((f) => ({ ...f, estado: e.target.value }))}>
              <option value="">Selecione…</option>
              {ESTADOS_UF.map((uf) => (
                <option key={uf} value={uf}>
                  {uf}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Cancelar
          </button>
          <button type="button" className="btn" disabled={!valido || criar.isPending} onClick={() => criar.mutate()}>
            {criar.isPending ? 'Criando…' : 'Criar'}
          </button>
        </div>
      </div>

      {modalPlanoAberto && (
        <PlanoContasModal
          aoFechar={() => setModalPlanoAberto(false)}
          aoCriar={(idPlano) => {
            setForm((f) => ({ ...f, idPlanoContas: idPlano }))
            setModalPlanoAberto(false)
          }}
        />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
