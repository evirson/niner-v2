import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import CategoriaClienteModal from '../../components/CategoriaClienteModal'
import { ApiError } from '../../lib/api'
import {
  CLIENTE_VAZIO,
  atualizarCliente,
  buscarCliente,
  criarCliente,
  listarCategorias,
  paraFormulario,
  paraRequisicao,
  type ClienteFormState,
  type Genero,
} from '../../lib/clientes'
import { ESTADOS_UF, documentoValido, mascararCep, mascararCpfCnpj, mascararTelefone } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'
import { buscarEnderecoPorCep } from '../../lib/viacep'

/** Campos de texto livre do formulário sujeitos à convenção de maiúsculas do projeto. */
type CampoMaiusculo =
  | 'nome'
  | 'rgIe'
  | 'instagram'
  | 'facebook'
  | 'tiktok'
  | 'endereco'
  | 'numero'
  | 'complemento'
  | 'bairro'
  | 'cidade'

export default function ClienteForm() {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<ClienteFormState>(CLIENTE_VAZIO)
  const [erro, setErro] = useState('')
  const [modalCategoriaAberto, setModalCategoriaAberto] = useState(false)

  const { data: categorias } = useQuery({ queryKey: ['categorias-cliente'], queryFn: listarCategorias })

  const { data: clienteExistente } = useQuery({
    queryKey: ['cliente', id],
    queryFn: () => buscarCliente(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (clienteExistente) setForm(paraFormulario(clienteExistente))
  }, [clienteExistente])

  const salvar = useMutation({
    mutationFn: () =>
      editando ? atualizarCliente(Number(id), paraRequisicao(form)) : criarCliente(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['clientes'] })
      navigate('/clientes')
    },
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível salvar o cliente.'),
  })

  /** onChange de campo de texto livre — sempre maiúsculas, não importa o teclado (item 2). */
  const campo = (chave: CampoMaiusculo) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  const aoTrocarCep = async (valor: string) => {
    const mascarado = mascararCep(valor)
    setForm((f) => ({ ...f, cep: mascarado }))
    const digitos = mascarado.replace(/\D/g, '')
    if (digitos.length === 8) {
      const endereco = await buscarEnderecoPorCep(digitos)
      if (endereco) {
        setForm((f) => ({
          ...f,
          endereco: endereco.logradouro ? maiusculas(endereco.logradouro) : f.endereco,
          bairro: endereco.bairro ? maiusculas(endereco.bairro) : f.bairro,
          cidade: endereco.localidade ? maiusculas(endereco.localidade) : f.cidade,
          estado: endereco.uf || f.estado,
        }))
      }
    }
  }

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    if (!form.idCategoriaCliente) {
      setErro('Escolha uma categoria.')
      return
    }
    if (form.fisicaJuridica && (!form.dataNascimento || !form.genero)) {
      setErro('Data de nascimento e gênero são obrigatórios para pessoa física.')
      return
    }
    if (form.cpfCnpj && !documentoValido(form.cpfCnpj)) {
      setErro('CPF/CNPJ inválido — confira os dígitos.')
      return
    }
    salvar.mutate()
  }

  return (
    <div>
      <div className="topbar-tela">
        <div>
          <p className="eyebrow">Cadastros</p>
          <h1 style={{ marginTop: 4 }}>{editando ? 'Editar cliente' : 'Novo cliente'}</h1>
        </div>
        <AjudaDaTela chaveTela="cadastros.cliente.form" />
      </div>

      <form className="card form-secoes" onSubmit={submeter}>
        <section className="section">
          <p className="section-label">Identificação</p>

          <label className="checkbox-linha" style={{ marginTop: 0 }}>
            <input
              autoFocus
              type="checkbox"
              checked={form.ativo}
              onChange={(e) => setForm((f) => ({ ...f, ativo: e.target.checked }))}
            />
            Cliente ativo
          </label>

          <div className="radio-linha">
            <label>
              <input
                type="radio"
                name="fisicaJuridica"
                checked={form.fisicaJuridica}
                onChange={() => setForm((f) => ({ ...f, fisicaJuridica: true }))}
              />{' '}
              Pessoa Física
            </label>
            <label>
              <input
                type="radio"
                name="fisicaJuridica"
                checked={!form.fisicaJuridica}
                onChange={() => setForm((f) => ({ ...f, fisicaJuridica: false }))}
              />{' '}
              Pessoa Jurídica
            </label>
          </div>

          <label htmlFor="nome">{form.fisicaJuridica ? 'Nome' : 'Razão Social'} *</label>
          <input id="nome" required value={form.nome} onChange={campo('nome')} />

          <label htmlFor="categoria">Categoria *</label>
          <div className="linha-com-botao">
            <select
              id="categoria"
              required
              value={form.idCategoriaCliente ?? ''}
              onChange={(e) =>
                setForm((f) => ({ ...f, idCategoriaCliente: e.target.value ? Number(e.target.value) : null }))
              }
            >
              <option value="">Selecione…</option>
              {categorias?.map((c) => (
                <option key={c.idCategoriaCliente} value={c.idCategoriaCliente}>
                  {c.nomeCategoria}
                </option>
              ))}
            </select>
            <button type="button" className="btn ghost" onClick={() => setModalCategoriaAberto(true)}>
              ＋ Nova categoria
            </button>
          </div>

          <div className="form-grid">
            <div className="col-4">
              <label htmlFor="cpfCnpj">{form.fisicaJuridica ? 'CPF' : 'CNPJ'}</label>
              <input
                id="cpfCnpj"
                value={form.cpfCnpj}
                onChange={(e) => setForm((f) => ({ ...f, cpfCnpj: mascararCpfCnpj(e.target.value, f.fisicaJuridica) }))}
              />
            </div>
            <div className="col-4">
              <label htmlFor="rgIe">{form.fisicaJuridica ? 'RG' : 'Inscrição Estadual'}</label>
              <input id="rgIe" value={form.rgIe} onChange={campo('rgIe')} />
            </div>

            {form.fisicaJuridica && (
              <>
                <div className="col-4">
                  <label htmlFor="dataNascimento">Data de nascimento *</label>
                  <input
                    id="dataNascimento"
                    type="date"
                    required
                    value={form.dataNascimento}
                    onChange={(e) => setForm((f) => ({ ...f, dataNascimento: e.target.value }))}
                  />
                </div>
                <div className="col-4">
                  <label htmlFor="genero">Gênero *</label>
                  <select
                    id="genero"
                    required
                    value={form.genero}
                    onChange={(e) => setForm((f) => ({ ...f, genero: e.target.value as Genero }))}
                  >
                    <option value="">Selecione…</option>
                    <option value="MASCULINO">Masculino</option>
                    <option value="FEMININO">Feminino</option>
                    <option value="OUTROS">Outros</option>
                  </select>
                </div>
              </>
            )}
          </div>
        </section>

        <section className="section">
          <p className="section-label">Contato</p>

          <div className="form-grid">
            <div className="col-6">
              <label htmlFor="email">E-mail</label>
              <input
                id="email"
                type="email"
                value={form.email}
                onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
              />
            </div>
            <div className="col-3">
              <label htmlFor="telefone">Telefone</label>
              <input
                id="telefone"
                value={form.telefone}
                onChange={(e) => setForm((f) => ({ ...f, telefone: mascararTelefone(e.target.value) }))}
              />
            </div>
            <div className="col-3">
              <label htmlFor="whatsapp">WhatsApp</label>
              <input
                id="whatsapp"
                value={form.whatsapp}
                onChange={(e) => setForm((f) => ({ ...f, whatsapp: mascararTelefone(e.target.value) }))}
              />
            </div>

            <div className="col-4">
              <label htmlFor="instagram">Instagram</label>
              <input id="instagram" placeholder="@usuario" value={form.instagram} onChange={campo('instagram')} />
            </div>
            <div className="col-4">
              <label htmlFor="facebook">Facebook</label>
              <input id="facebook" placeholder="@usuario" value={form.facebook} onChange={campo('facebook')} />
            </div>
            <div className="col-4">
              <label htmlFor="tiktok">TikTok</label>
              <input id="tiktok" placeholder="@usuario" value={form.tiktok} onChange={campo('tiktok')} />
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Endereço</p>

          <div className="form-grid">
            <div className="col-3">
              <label htmlFor="cep">CEP</label>
              <input id="cep" value={form.cep} onChange={(e) => aoTrocarCep(e.target.value)} />
            </div>
            <div className="col-9">
              <label htmlFor="endereco">Endereço</label>
              <input id="endereco" value={form.endereco} onChange={campo('endereco')} />
            </div>

            <div className="col-2">
              <label htmlFor="numero">Número</label>
              <input id="numero" value={form.numero} onChange={campo('numero')} />
            </div>
            <div className="col-4">
              <label htmlFor="complemento">Complemento</label>
              <input
                id="complemento"
                placeholder="apto, bloco, sala…"
                value={form.complemento}
                onChange={campo('complemento')}
              />
            </div>
            <div className="col-6">
              <label htmlFor="bairro">Bairro</label>
              <input id="bairro" value={form.bairro} onChange={campo('bairro')} />
            </div>

            <div className="col-9">
              <label htmlFor="cidade">Cidade</label>
              <input id="cidade" value={form.cidade} onChange={campo('cidade')} />
            </div>
            <div className="col-3">
              <label htmlFor="estado">UF</label>
              <select id="estado" value={form.estado} onChange={(e) => setForm((f) => ({ ...f, estado: e.target.value }))}>
                <option value="">Selecione…</option>
                {ESTADOS_UF.map((uf) => (
                  <option key={uf} value={uf}>
                    {uf}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Outros</p>

          <div className="form-grid">
            <div className="col-4">
              <label htmlFor="limiteCredito">Limite de crédito (R$)</label>
              <input
                id="limiteCredito"
                inputMode="decimal"
                placeholder="0,00"
                value={form.limiteCredito}
                onChange={(e) => setForm((f) => ({ ...f, limiteCredito: e.target.value }))}
              />
            </div>
          </div>
        </section>

        {erro && (
          <p role="alert" className="erro">
            {erro}
          </p>
        )}

        <div className="footer-bar">
          <button type="button" className="btn ghost" onClick={() => navigate('/clientes')}>
            Cancelar
          </button>
          <button type="submit" className="btn" disabled={salvar.isPending}>
            {salvar.isPending ? 'Salvando…' : 'Salvar'}
          </button>
        </div>
      </form>

      {modalCategoriaAberto && (
        <CategoriaClienteModal
          aoFechar={() => setModalCategoriaAberto(false)}
          aoCriar={(idCategoriaCliente) => {
            setForm((f) => ({ ...f, idCategoriaCliente }))
            setModalCategoriaAberto(false)
          }}
        />
      )}
    </div>
  )
}
