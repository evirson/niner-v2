import { useEffect, useState, type ChangeEvent, type FocusEvent, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import CategoriaClienteModal from '../../components/CategoriaClienteModal'
import { IconeEngrenagem } from '../../components/Icones'
import LinhaGrid from '../../components/LinhaGrid'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  CLIENTE_VAZIO,
  atualizarCliente,
  buscarCliente,
  cpfCnpjJaExiste,
  criarCliente,
  listarCategorias,
  paraFormulario,
  paraRequisicao,
  type ClienteFormState,
  type Genero,
} from '../../lib/clientes'
import { buscarConfiguracaoTela, paraMapa, type ConfiguracaoCampo } from '../../lib/configuracaoTela'
import { useEu } from '../../lib/eu'
import {
  ESTADOS_UF,
  celularValido,
  documentoValido,
  mascararCep,
  mascararCpfCnpj,
  mascararIdWhatsapp,
  mascararTelefone,
  somenteDigitos,
} from '../../lib/masks'
import { maiusculas } from '../../lib/texto'
import { buscarEnderecoPorCep } from '../../lib/viacep'

const CHAVE_TELA = 'cadastros.cliente.form'

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

/**
 * Campos configuráveis pela tela de configuração (docs/telas/configuracao-tela.md) — mesma
 * lista do registro `CAMPOS_POR_TELA` no backend (`ConfiguracaoTelaService`). Nome e
 * Categoria não entram: são estruturalmente obrigatórios (NOT NULL no banco).
 */
type CampoConfiguravel =
  | 'cpfCnpj'
  | 'rgIe'
  | 'email'
  | 'telefone'
  | 'whatsapp'
  | 'instagram'
  | 'facebook'
  | 'tiktok'
  | 'cep'
  | 'endereco'
  | 'numero'
  | 'complemento'
  | 'bairro'
  | 'cidade'
  | 'estado'
  | 'limiteCredito'

type CampoValidavel = 'nome' | 'idCategoriaCliente' | 'dataNascimento' | 'genero' | CampoConfiguravel
type ErrosCampo = Partial<Record<CampoValidavel, string>>

function campoVisivel(campo: CampoConfiguravel, mapa: Map<string, ConfiguracaoCampo>): boolean {
  return mapa.get(campo)?.visivel ?? true
}
function campoObrigatorio(campo: CampoConfiguravel, mapa: Map<string, ConfiguracaoCampo>): boolean {
  return mapa.get(campo)?.obrigatorio ?? false
}

/**
 * Regra de validação de cada campo, exceto `cep`/`cpfCnpj` (dependem de chamada assíncrona —
 * ver `aoSairDoCep`/`aoSairDoCpf`). Campos configuráveis checam visibilidade/obrigatoriedade
 * antes de qualquer regra de formato própria.
 */
function validarCampo(
  chave: Exclude<CampoValidavel, 'cep' | 'cpfCnpj'>,
  f: ClienteFormState,
  mapaConfig: Map<string, ConfiguracaoCampo>,
): string | undefined {
  switch (chave) {
    case 'nome':
      return f.nome.trim() ? undefined : `${f.fisicaJuridica ? 'Nome' : 'Razão social'} é obrigatório.`
    case 'idCategoriaCliente':
      return f.idCategoriaCliente ? undefined : 'Escolha uma categoria.'
    case 'dataNascimento': {
      // Sempre opcional (2026-07-21); quando preenchida, só não pode ser hoje/futuro.
      if (!f.dataNascimento) return undefined
      const hoje = new Date().toISOString().slice(0, 10)
      return f.dataNascimento < hoje ? undefined : 'Data de nascimento não pode ser hoje ou no futuro.'
    }
    case 'genero':
      return !f.fisicaJuridica || f.genero ? undefined : 'Obrigatório para pessoa física.'
    default: {
      const campo = chave as CampoConfiguravel
      if (!campoVisivel(campo, mapaConfig)) return undefined
      const valor = f[campo] as string
      if (!valor.trim()) {
        return campoObrigatorio(campo, mapaConfig) ? 'Campo obrigatório.' : undefined
      }
      if (campo === 'email') return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(valor) ? undefined : 'E-mail inválido.'
      if (campo === 'telefone') {
        return celularValido(valor) ? undefined : 'Celular deve ter 11 dígitos (DDD + 9XXXX-XXXX).'
      }
      if (campo === 'whatsapp') {
        return celularValido(valor) ? undefined : 'Id. WhatsApp deve ter 11 dígitos (DDD + 9XXXX-XXXX).'
      }
      return undefined
    }
  }
}

export default function ClienteForm() {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<ClienteFormState>(CLIENTE_VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [modalCategoriaAberto, setModalCategoriaAberto] = useState(false)

  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const { data: configuracao } = useQuery({
    queryKey: ['config-tela', CHAVE_TELA],
    queryFn: () => buscarConfiguracaoTela(CHAVE_TELA),
  })
  const mapaConfig = paraMapa(configuracao)

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
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar o cliente.'),
  })

  /** onChange de campo de texto livre — sempre maiúsculas, não importa o teclado. */
  const campo = (chave: CampoMaiusculo) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  /** Valida um campo ao sair dele. */
  const aoSairDoCampo = (chave: Exclude<CampoValidavel, 'cep' | 'cpfCnpj'>) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form, mapaConfig) }))

  /** CPF/CNPJ: obrigatoriedade, depois formato e, se válido, se já existe outro cliente com ele. */
  const aoSairDoCpf = async () => {
    if (!campoVisivel('cpfCnpj', mapaConfig)) return
    if (!form.cpfCnpj) {
      setErros((e) => ({ ...e, cpfCnpj: campoObrigatorio('cpfCnpj', mapaConfig) ? 'Campo obrigatório.' : undefined }))
      return
    }
    if (!documentoValido(form.cpfCnpj)) {
      setErros((e) => ({ ...e, cpfCnpj: 'CPF/CNPJ inválido — confira os dígitos.' }))
      return
    }
    const jaExiste = await cpfCnpjJaExiste(somenteDigitos(form.cpfCnpj), editando ? Number(id) : undefined)
    setErros((e) => ({
      ...e,
      cpfCnpj: jaExiste ? `${form.fisicaJuridica ? 'CPF' : 'CNPJ'} já cadastrado para outro cliente.` : undefined,
    }))
  }

  const aoTrocarCep = async (valor: string) => {
    const mascarado = mascararCep(valor)
    setForm((f) => ({ ...f, cep: mascarado }))
    const digitos = mascarado.replace(/\D/g, '')
    if (digitos.length !== 8) {
      setErros((e) => ({ ...e, cep: undefined }))
      return
    }
    const endereco = await buscarEnderecoPorCep(digitos)
    if (endereco) {
      setErros((e) => ({ ...e, cep: undefined }))
      setForm((f) => ({
        ...f,
        endereco: endereco.logradouro ? maiusculas(endereco.logradouro) : f.endereco,
        bairro: endereco.bairro ? maiusculas(endereco.bairro) : f.bairro,
        cidade: endereco.localidade ? maiusculas(endereco.localidade) : f.cidade,
        estado: endereco.uf || f.estado,
      }))
    } else {
      setErros((e) => ({ ...e, cep: 'CEP inválido.' }))
    }
  }

  const aoSairDoCep = () => {
    if (!campoVisivel('cep', mapaConfig)) return
    const digitos = form.cep.replace(/\D/g, '')
    if (digitos.length === 0) {
      setErros((e) => ({ ...e, cep: campoObrigatorio('cep', mapaConfig) ? 'Campo obrigatório.' : undefined }))
      return
    }
    if (digitos.length !== 8) {
      setErros((e) => ({ ...e, cep: 'CEP inválido.' }))
    }
  }

  const submeter = (e: FormEvent) => {
    e.preventDefault()

    const cpfCnpjErro = (() => {
      if (!campoVisivel('cpfCnpj', mapaConfig)) return undefined
      if (!form.cpfCnpj) return campoObrigatorio('cpfCnpj', mapaConfig) ? 'Campo obrigatório.' : undefined
      return !documentoValido(form.cpfCnpj) ? 'CPF/CNPJ inválido — confira os dígitos.' : erros.cpfCnpj
    })()

    const cepErro = (() => {
      if (!campoVisivel('cep', mapaConfig)) return undefined
      const digitos = form.cep.replace(/\D/g, '')
      if (digitos.length === 0) return campoObrigatorio('cep', mapaConfig) ? 'Campo obrigatório.' : undefined
      return digitos.length !== 8 ? 'CEP inválido.' : erros.cep
    })()

    const novosErros: ErrosCampo = {
      nome: validarCampo('nome', form, mapaConfig),
      idCategoriaCliente: validarCampo('idCategoriaCliente', form, mapaConfig),
      dataNascimento: validarCampo('dataNascimento', form, mapaConfig),
      genero: validarCampo('genero', form, mapaConfig),
      email: validarCampo('email', form, mapaConfig),
      telefone: validarCampo('telefone', form, mapaConfig),
      whatsapp: validarCampo('whatsapp', form, mapaConfig),
      rgIe: validarCampo('rgIe', form, mapaConfig),
      instagram: validarCampo('instagram', form, mapaConfig),
      facebook: validarCampo('facebook', form, mapaConfig),
      tiktok: validarCampo('tiktok', form, mapaConfig),
      endereco: validarCampo('endereco', form, mapaConfig),
      numero: validarCampo('numero', form, mapaConfig),
      complemento: validarCampo('complemento', form, mapaConfig),
      bairro: validarCampo('bairro', form, mapaConfig),
      cidade: validarCampo('cidade', form, mapaConfig),
      estado: validarCampo('estado', form, mapaConfig),
      limiteCredito: validarCampo('limiteCredito', form, mapaConfig),
      cpfCnpj: cpfCnpjErro,
      cep: cepErro,
    }
    setErros(novosErros)
    if (Object.values(novosErros).some(Boolean)) {
      setToast('Corrija os campos destacados antes de salvar.')
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
        <div className="topbar-acoes">
          {ehAdmin && (
            <Link
              className="btn ghost ajuda-gatilho"
              to="/clientes/configuracao"
              aria-label="Configurar tela de cliente"
              title="Configurar campos desta tela"
            >
              <IconeEngrenagem />
            </Link>
          )}
          <AjudaDaTela chaveTela={CHAVE_TELA} />
        </div>
      </div>

      <form className="card form-secoes form-secoes-larga" onSubmit={submeter} noValidate>
        <section className="section">
          <p className="section-label">Identificação</p>

          <label className="checkbox-linha" style={{ marginTop: 0 }}>
            <input
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

          <div className="form-grid">
            <div className="col-8">
              <label htmlFor="nome">{form.fisicaJuridica ? 'Nome' : 'Razão Social'} *</label>
              <input id="nome" autoFocus value={form.nome} onChange={campo('nome')} onBlur={aoSairDoCampo('nome')} />
              {erros.nome && <p className="erro-campo">{erros.nome}</p>}
            </div>
            <div className="col-4">
              <label htmlFor="categoria">Categoria *</label>
              <div className="linha-com-botao">
                <select
                  id="categoria"
                  value={form.idCategoriaCliente ?? ''}
                  onChange={(e) =>
                    setForm((f) => ({ ...f, idCategoriaCliente: e.target.value ? Number(e.target.value) : null }))
                  }
                  onBlur={aoSairDoCampo('idCategoriaCliente')}
                >
                  <option value="">Selecione…</option>
                  {categorias?.map((c) => (
                    <option key={c.idCategoriaCliente} value={c.idCategoriaCliente}>
                      {c.nomeCategoria}
                    </option>
                  ))}
                </select>
                {/* fora da ordem de tabulação: com a categoria já escolhida, o próximo campo é o CPF */}
                <button
                  type="button"
                  tabIndex={-1}
                  className="btn ghost"
                  onClick={() => setModalCategoriaAberto(true)}
                >
                  ＋ Nova categoria
                </button>
              </div>
              {erros.idCategoriaCliente && <p className="erro-campo">{erros.idCategoriaCliente}</p>}
            </div>

            <LinhaGrid
              itens={[
                {
                  visivel: campoVisivel('cpfCnpj', mapaConfig),
                  peso: 3,
                  children: (
                    <>
                      <label htmlFor="cpfCnpj">
                        {form.fisicaJuridica ? 'CPF' : 'CNPJ'}
                        {campoObrigatorio('cpfCnpj', mapaConfig) && ' *'}
                      </label>
                      <input
                        id="cpfCnpj"
                        value={form.cpfCnpj}
                        onChange={(e) =>
                          setForm((f) => ({ ...f, cpfCnpj: mascararCpfCnpj(e.target.value, f.fisicaJuridica) }))
                        }
                        onBlur={aoSairDoCpf}
                      />
                      {erros.cpfCnpj && <p className="erro-campo">{erros.cpfCnpj}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('rgIe', mapaConfig),
                  peso: 3,
                  children: (
                    <>
                      <label htmlFor="rgIe">
                        {form.fisicaJuridica ? 'RG' : 'Inscrição Estadual'}
                        {campoObrigatorio('rgIe', mapaConfig) && ' *'}
                      </label>
                      <input id="rgIe" value={form.rgIe} onChange={campo('rgIe')} onBlur={aoSairDoCampo('rgIe')} />
                      {erros.rgIe && <p className="erro-campo">{erros.rgIe}</p>}
                    </>
                  ),
                },
                {
                  visivel: form.fisicaJuridica,
                  peso: 3,
                  children: (
                    <>
                      <label htmlFor="dataNascimento">Data de nascimento</label>
                      <input
                        id="dataNascimento"
                        type="date"
                        value={form.dataNascimento}
                        onChange={(e) => setForm((f) => ({ ...f, dataNascimento: e.target.value }))}
                        onBlur={aoSairDoCampo('dataNascimento')}
                      />
                      {erros.dataNascimento && <p className="erro-campo">{erros.dataNascimento}</p>}
                    </>
                  ),
                },
                {
                  visivel: form.fisicaJuridica,
                  peso: 3,
                  children: (
                    <>
                      <label htmlFor="genero">Gênero *</label>
                      <select
                        id="genero"
                        value={form.genero}
                        onChange={(e) => setForm((f) => ({ ...f, genero: e.target.value as Genero }))}
                        onBlur={aoSairDoCampo('genero')}
                      >
                        <option value="">Selecione…</option>
                        <option value="MASCULINO">Masculino</option>
                        <option value="FEMININO">Feminino</option>
                        <option value="OUTROS">Outros</option>
                      </select>
                      {erros.genero && <p className="erro-campo">{erros.genero}</p>}
                    </>
                  ),
                },
              ]}
            />
          </div>
        </section>

        <section className="section">
          <p className="section-label">Contato</p>

          <div className="form-grid">
            <LinhaGrid
              itens={[
                {
                  visivel: campoVisivel('email', mapaConfig),
                  peso: 6,
                  children: (
                    <>
                      <label htmlFor="email">E-mail{campoObrigatorio('email', mapaConfig) && ' *'}</label>
                      <input
                        id="email"
                        type="email"
                        value={form.email}
                        onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
                        onBlur={aoSairDoCampo('email')}
                      />
                      {erros.email && <p className="erro-campo">{erros.email}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('telefone', mapaConfig),
                  peso: 3,
                  children: (
                    <>
                      <label htmlFor="telefone">Celular{campoObrigatorio('telefone', mapaConfig) && ' *'}</label>
                      <input
                        id="telefone"
                        value={form.telefone}
                        onChange={(e) => setForm((f) => ({ ...f, telefone: mascararTelefone(e.target.value) }))}
                        onBlur={aoSairDoCampo('telefone')}
                      />
                      {erros.telefone && <p className="erro-campo">{erros.telefone}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('whatsapp', mapaConfig),
                  peso: 3,
                  children: (
                    <>
                      <label htmlFor="whatsapp">Id. WhatsApp{campoObrigatorio('whatsapp', mapaConfig) && ' *'}</label>
                      <input
                        id="whatsapp"
                        placeholder="@11999998888"
                        value={form.whatsapp}
                        onChange={(e) => setForm((f) => ({ ...f, whatsapp: mascararIdWhatsapp(e.target.value) }))}
                        onBlur={aoSairDoCampo('whatsapp')}
                      />
                      {erros.whatsapp && <p className="erro-campo">{erros.whatsapp}</p>}
                    </>
                  ),
                },
              ]}
            />

            <LinhaGrid
              itens={[
                {
                  visivel: campoVisivel('instagram', mapaConfig),
                  peso: 4,
                  children: (
                    <>
                      <label htmlFor="instagram">Instagram{campoObrigatorio('instagram', mapaConfig) && ' *'}</label>
                      <input
                        id="instagram"
                        placeholder="@usuario"
                        value={form.instagram}
                        onChange={campo('instagram')}
                        onBlur={aoSairDoCampo('instagram')}
                      />
                      {erros.instagram && <p className="erro-campo">{erros.instagram}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('facebook', mapaConfig),
                  peso: 4,
                  children: (
                    <>
                      <label htmlFor="facebook">Facebook{campoObrigatorio('facebook', mapaConfig) && ' *'}</label>
                      <input
                        id="facebook"
                        placeholder="@usuario"
                        value={form.facebook}
                        onChange={campo('facebook')}
                        onBlur={aoSairDoCampo('facebook')}
                      />
                      {erros.facebook && <p className="erro-campo">{erros.facebook}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('tiktok', mapaConfig),
                  peso: 4,
                  children: (
                    <>
                      <label htmlFor="tiktok">TikTok{campoObrigatorio('tiktok', mapaConfig) && ' *'}</label>
                      <input
                        id="tiktok"
                        placeholder="@usuario"
                        value={form.tiktok}
                        onChange={campo('tiktok')}
                        onBlur={aoSairDoCampo('tiktok')}
                      />
                      {erros.tiktok && <p className="erro-campo">{erros.tiktok}</p>}
                    </>
                  ),
                },
              ]}
            />
          </div>
        </section>

        <section className="section">
          <p className="section-label">Endereço</p>

          <div className="form-grid">
            <LinhaGrid
              itens={[
                {
                  visivel: campoVisivel('cep', mapaConfig),
                  peso: 3,
                  children: (
                    <>
                      <label htmlFor="cep">CEP{campoObrigatorio('cep', mapaConfig) && ' *'}</label>
                      <input
                        id="cep"
                        value={form.cep}
                        onChange={(e) => aoTrocarCep(e.target.value)}
                        onBlur={aoSairDoCep}
                      />
                      {erros.cep && <p className="erro-campo">{erros.cep}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('endereco', mapaConfig),
                  peso: 9,
                  children: (
                    <>
                      <label htmlFor="endereco">Endereço{campoObrigatorio('endereco', mapaConfig) && ' *'}</label>
                      <input
                        id="endereco"
                        value={form.endereco}
                        onChange={campo('endereco')}
                        onBlur={aoSairDoCampo('endereco')}
                      />
                      {erros.endereco && <p className="erro-campo">{erros.endereco}</p>}
                    </>
                  ),
                },
              ]}
            />

            <LinhaGrid
              itens={[
                {
                  visivel: campoVisivel('numero', mapaConfig),
                  peso: 2,
                  children: (
                    <>
                      <label htmlFor="numero">Número{campoObrigatorio('numero', mapaConfig) && ' *'}</label>
                      <input
                        id="numero"
                        value={form.numero}
                        onChange={campo('numero')}
                        onBlur={aoSairDoCampo('numero')}
                      />
                      {erros.numero && <p className="erro-campo">{erros.numero}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('complemento', mapaConfig),
                  peso: 4,
                  children: (
                    <>
                      <label htmlFor="complemento">
                        Complemento{campoObrigatorio('complemento', mapaConfig) && ' *'}
                      </label>
                      <input
                        id="complemento"
                        placeholder="apto, bloco, sala…"
                        value={form.complemento}
                        onChange={campo('complemento')}
                        onBlur={aoSairDoCampo('complemento')}
                      />
                      {erros.complemento && <p className="erro-campo">{erros.complemento}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('bairro', mapaConfig),
                  peso: 6,
                  children: (
                    <>
                      <label htmlFor="bairro">Bairro{campoObrigatorio('bairro', mapaConfig) && ' *'}</label>
                      <input
                        id="bairro"
                        value={form.bairro}
                        onChange={campo('bairro')}
                        onBlur={aoSairDoCampo('bairro')}
                      />
                      {erros.bairro && <p className="erro-campo">{erros.bairro}</p>}
                    </>
                  ),
                },
              ]}
            />

            <LinhaGrid
              itens={[
                {
                  visivel: campoVisivel('cidade', mapaConfig),
                  peso: 9,
                  children: (
                    <>
                      <label htmlFor="cidade">Cidade{campoObrigatorio('cidade', mapaConfig) && ' *'}</label>
                      <input
                        id="cidade"
                        value={form.cidade}
                        onChange={campo('cidade')}
                        onBlur={aoSairDoCampo('cidade')}
                      />
                      {erros.cidade && <p className="erro-campo">{erros.cidade}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('estado', mapaConfig),
                  peso: 3,
                  children: (
                    <>
                      <label htmlFor="estado">UF{campoObrigatorio('estado', mapaConfig) && ' *'}</label>
                      <select
                        id="estado"
                        value={form.estado}
                        onChange={(e) => setForm((f) => ({ ...f, estado: e.target.value }))}
                        onBlur={aoSairDoCampo('estado')}
                      >
                        <option value="">Selecione…</option>
                        {ESTADOS_UF.map((uf) => (
                          <option key={uf} value={uf}>
                            {uf}
                          </option>
                        ))}
                      </select>
                      {erros.estado && <p className="erro-campo">{erros.estado}</p>}
                    </>
                  ),
                },
              ]}
            />
          </div>
        </section>

        {campoVisivel('limiteCredito', mapaConfig) && (
          <section className="section">
            <p className="section-label">Outros</p>

            <div className="form-grid">
              <div className="col-4">
                <label htmlFor="limiteCredito">
                  Limite de crédito (R$){campoObrigatorio('limiteCredito', mapaConfig) && ' *'}
                </label>
                <input
                  id="limiteCredito"
                  inputMode="decimal"
                  placeholder="0,00"
                  value={form.limiteCredito}
                  onChange={(e) => setForm((f) => ({ ...f, limiteCredito: e.target.value }))}
                  onBlur={aoSairDoCampo('limiteCredito')}
                />
                {erros.limiteCredito && <p className="erro-campo">{erros.limiteCredito}</p>}
              </div>
            </div>
          </section>
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
            setErros((e) => ({ ...e, idCategoriaCliente: undefined }))
            setModalCategoriaAberto(false)
          }}
        />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
