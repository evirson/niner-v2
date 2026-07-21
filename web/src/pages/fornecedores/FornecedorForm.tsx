import { useEffect, useState, type ChangeEvent, type FocusEvent, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { IconeEngrenagem, IconeFornecedor } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import LinhaGrid from '../../components/LinhaGrid'
import PlanoContasModal from '../../components/PlanoContasModal'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarConfiguracaoTela, paraMapa, type ConfiguracaoCampo } from '../../lib/configuracaoTela'
import { useEu } from '../../lib/eu'
import {
  FORNECEDOR_VAZIO,
  atualizarFornecedor,
  buscarFornecedor,
  cnpjJaExiste,
  criarFornecedor,
  paraFormulario,
  paraRequisicao,
  type FornecedorFormState,
} from '../../lib/fornecedores'
import { listarPlanosContas } from '../../lib/planoContas'
import {
  ESTADOS_UF,
  documentoValido,
  mascararCep,
  mascararCpfCnpj,
  mascararTelefone,
  somenteAlfanumerico,
  telefoneValido,
} from '../../lib/masks'
import { maiusculas } from '../../lib/texto'
import { buscarEnderecoPorCep } from '../../lib/viacep'

const CHAVE_TELA = 'cadastros.fornecedor.form'

/** Campos de texto livre do formulário sujeitos à convenção de maiúsculas do projeto. */
type CampoMaiusculo =
  | 'razaoSocial'
  | 'nomeFantasia'
  | 'inscricaoEstadual'
  | 'endereco'
  | 'numero'
  | 'bairro'
  | 'cidade'

/**
 * Campos configuráveis pela tela de configuração — mesma lista de `CAMPOS_POR_TELA` no
 * backend. Razão Social e Plano de Contas não entram: são NOT NULL no banco.
 */
type CampoConfiguravel =
  | 'nomeFantasia'
  | 'cnpj'
  | 'inscricaoEstadual'
  | 'email'
  | 'telefone'
  | 'cep'
  | 'endereco'
  | 'numero'
  | 'bairro'
  | 'cidade'
  | 'estado'

type CampoValidavel = 'razaoSocial' | 'idPlanoContas' | CampoConfiguravel
type ErrosCampo = Partial<Record<CampoValidavel, string>>

function campoVisivel(campo: CampoConfiguravel, mapa: Map<string, ConfiguracaoCampo>): boolean {
  return mapa.get(campo)?.visivel ?? true
}
function campoObrigatorio(campo: CampoConfiguravel, mapa: Map<string, ConfiguracaoCampo>): boolean {
  return mapa.get(campo)?.obrigatorio ?? false
}

/**
 * Regra de validação de cada campo, exceto `cep`/`cnpj` (dependem de chamada assíncrona).
 * CNPJ sempre com 14 caracteres (fornecedor é pessoa jurídica — CPF não é aceito) e
 * telefone aceita fixo ou celular (10–11 dígitos), diferente do cliente.
 */
function validarCampo(
  chave: Exclude<CampoValidavel, 'cep' | 'cnpj'>,
  f: FornecedorFormState,
  mapaConfig: Map<string, ConfiguracaoCampo>,
): string | undefined {
  if (chave === 'razaoSocial') {
    return f.razaoSocial.trim() ? undefined : 'Razão social é obrigatória.'
  }
  if (chave === 'idPlanoContas') {
    return f.idPlanoContas ? undefined : 'Escolha um plano de contas.'
  }
  const campo = chave as CampoConfiguravel
  if (!campoVisivel(campo, mapaConfig)) return undefined
  const valor = f[campo] as string
  if (!valor.trim()) {
    return campoObrigatorio(campo, mapaConfig) ? 'Campo obrigatório.' : undefined
  }
  if (campo === 'email') return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(valor) ? undefined : 'E-mail inválido.'
  if (campo === 'telefone') {
    return telefoneValido(valor) ? undefined : 'Telefone deve ter 10 ou 11 dígitos (com DDD).'
  }
  return undefined
}

export default function FornecedorForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<FornecedorFormState>(FORNECEDOR_VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [modalPlanoAberto, setModalPlanoAberto] = useState(false)

  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const { data: configuracao } = useQuery({
    queryKey: ['config-tela', CHAVE_TELA],
    queryFn: () => buscarConfiguracaoTela(CHAVE_TELA),
  })
  const mapaConfig = paraMapa(configuracao)

  const { data: planos } = useQuery({
    queryKey: ['planos-contas', 'select-fornecedor'],
    queryFn: () => listarPlanosContas({ tamanho: 100 }),
  })

  const { data: fornecedorExistente } = useQuery({
    queryKey: ['fornecedor', id],
    queryFn: () => buscarFornecedor(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (fornecedorExistente) setForm(paraFormulario(fornecedorExistente))
  }, [fornecedorExistente])

  const salvar = useMutation({
    mutationFn: () =>
      editando ? atualizarFornecedor(Number(id), paraRequisicao(form)) : criarFornecedor(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['fornecedores'] })
      navigate('/fornecedores')
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar o fornecedor.'),
  })

  /** onChange de campo de texto livre — sempre maiúsculas, não importa o teclado. */
  const campo = (chave: CampoMaiusculo) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  /** Valida um campo ao sair dele. */
  const aoSairDoCampo = (chave: Exclude<CampoValidavel, 'cep' | 'cnpj'>) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form, mapaConfig) }))

  /** CNPJ: obrigatoriedade, formato (14 caracteres alfanuméricos + DV) e duplicidade. */
  const aoSairDoCnpj = async () => {
    if (!campoVisivel('cnpj', mapaConfig)) return
    if (!form.cnpj) {
      setErros((e) => ({ ...e, cnpj: campoObrigatorio('cnpj', mapaConfig) ? 'Campo obrigatório.' : undefined }))
      return
    }
    const limpo = somenteAlfanumerico(form.cnpj)
    if (limpo.length !== 14 || !documentoValido(form.cnpj)) {
      setErros((e) => ({ ...e, cnpj: 'CNPJ inválido — confira os dígitos.' }))
      return
    }
    const jaExiste = await cnpjJaExiste(limpo, editando ? Number(id) : undefined)
    setErros((e) => ({
      ...e,
      cnpj: jaExiste ? 'CNPJ já cadastrado para outro fornecedor.' : undefined,
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
    if (somenteLeitura) return

    const cnpjErro = (() => {
      if (!campoVisivel('cnpj', mapaConfig)) return undefined
      if (!form.cnpj) return campoObrigatorio('cnpj', mapaConfig) ? 'Campo obrigatório.' : undefined
      const limpo = somenteAlfanumerico(form.cnpj)
      return limpo.length !== 14 || !documentoValido(form.cnpj)
        ? 'CNPJ inválido — confira os dígitos.'
        : erros.cnpj
    })()

    const cepErro = (() => {
      if (!campoVisivel('cep', mapaConfig)) return undefined
      const digitos = form.cep.replace(/\D/g, '')
      if (digitos.length === 0) return campoObrigatorio('cep', mapaConfig) ? 'Campo obrigatório.' : undefined
      return digitos.length !== 8 ? 'CEP inválido.' : erros.cep
    })()

    const novosErros: ErrosCampo = {
      razaoSocial: validarCampo('razaoSocial', form, mapaConfig),
      idPlanoContas: validarCampo('idPlanoContas', form, mapaConfig),
      nomeFantasia: validarCampo('nomeFantasia', form, mapaConfig),
      inscricaoEstadual: validarCampo('inscricaoEstadual', form, mapaConfig),
      email: validarCampo('email', form, mapaConfig),
      telefone: validarCampo('telefone', form, mapaConfig),
      endereco: validarCampo('endereco', form, mapaConfig),
      numero: validarCampo('numero', form, mapaConfig),
      bairro: validarCampo('bairro', form, mapaConfig),
      cidade: validarCampo('cidade', form, mapaConfig),
      estado: validarCampo('estado', form, mapaConfig),
      cnpj: cnpjErro,
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
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeFornecedor size={34} />
            <h1>Fornecedor</h1>
          </div>
          <div className="topbar-acoes">
            {ehAdmin && (
              <Link
                className="btn ghost ajuda-gatilho"
                to="/fornecedores/configuracao"
                aria-label="Configurar tela de fornecedor"
                title="Configurar campos desta tela"
              >
                <IconeEngrenagem />
              </Link>
            )}
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <button type="button" className="btn ghost" onClick={() => navigate('/fornecedores')}>
              {somenteLeitura ? 'Voltar' : 'Cancelar'}
            </button>
            {!somenteLeitura && (
              <button type="submit" form="form-fornecedor" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form id="form-fornecedor" className="card form-secoes form-secoes-larga" onSubmit={submeter} noValidate>
      <fieldset disabled={somenteLeitura} className="form-fieldset">
        <section className="section">
          <p className="section-label">Identificação</p>

          <div className="identificacao-linha">
            <label className="checkbox-linha" style={{ marginTop: 0 }}>
              <input
                type="checkbox"
                checked={form.ativo}
                onChange={(e) => setForm((f) => ({ ...f, ativo: e.target.checked }))}
              />
              Fornecedor ativo
            </label>
          </div>

          <div className="form-grid">
            <div className="col-8">
              <label htmlFor="razaoSocial">Razão Social *</label>
              <input
                id="razaoSocial"
                autoFocus
                value={form.razaoSocial}
                onChange={campo('razaoSocial')}
                onBlur={aoSairDoCampo('razaoSocial')}
              />
              {erros.razaoSocial && <p className="erro-campo">{erros.razaoSocial}</p>}
            </div>
            <div className="col-4">
              <label htmlFor="planoContas">Plano de Contas *</label>
              <div className="linha-com-botao">
                <select
                  id="planoContas"
                  value={form.idPlanoContas}
                  onChange={(e) => setForm((f) => ({ ...f, idPlanoContas: e.target.value }))}
                  onBlur={aoSairDoCampo('idPlanoContas')}
                >
                  <option value="">Selecione…</option>
                  {planos?.itens.map((p) => (
                    <option key={p.idPlanoContas} value={p.idPlanoContas}>
                      {p.idPlanoContas} — {p.descricao}
                    </option>
                  ))}
                </select>
                {/* fora da ordem de tabulação, como o "+ Nova categoria" do cliente */}
                <button
                  type="button"
                  tabIndex={-1}
                  className="btn ghost"
                  onClick={() => setModalPlanoAberto(true)}
                >
                  ＋ Novo
                </button>
              </div>
              {erros.idPlanoContas && <p className="erro-campo">{erros.idPlanoContas}</p>}
            </div>

            <LinhaGrid
              itens={[
                {
                  visivel: campoVisivel('cnpj', mapaConfig),
                  peso: 3,
                  children: (
                    <>
                      <label htmlFor="cnpj">CNPJ{campoObrigatorio('cnpj', mapaConfig) && ' *'}</label>
                      <input
                        id="cnpj"
                        value={form.cnpj}
                        onChange={(e) => setForm((f) => ({ ...f, cnpj: mascararCpfCnpj(e.target.value, false) }))}
                        onBlur={aoSairDoCnpj}
                      />
                      {erros.cnpj && <p className="erro-campo">{erros.cnpj}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('inscricaoEstadual', mapaConfig),
                  peso: 3,
                  children: (
                    <>
                      <label htmlFor="inscricaoEstadual">
                        Inscrição Estadual{campoObrigatorio('inscricaoEstadual', mapaConfig) && ' *'}
                      </label>
                      <input
                        id="inscricaoEstadual"
                        value={form.inscricaoEstadual}
                        onChange={campo('inscricaoEstadual')}
                        onBlur={aoSairDoCampo('inscricaoEstadual')}
                      />
                      {erros.inscricaoEstadual && <p className="erro-campo">{erros.inscricaoEstadual}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('nomeFantasia', mapaConfig),
                  peso: 6,
                  children: (
                    <>
                      <label htmlFor="nomeFantasia">
                        Nome Fantasia{campoObrigatorio('nomeFantasia', mapaConfig) && ' *'}
                      </label>
                      <input
                        id="nomeFantasia"
                        value={form.nomeFantasia}
                        onChange={campo('nomeFantasia')}
                        onBlur={aoSairDoCampo('nomeFantasia')}
                      />
                      {erros.nomeFantasia && <p className="erro-campo">{erros.nomeFantasia}</p>}
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
                  peso: 8,
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
                  peso: 4,
                  children: (
                    <>
                      <label htmlFor="telefone">Telefone{campoObrigatorio('telefone', mapaConfig) && ' *'}</label>
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
                  peso: 3,
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
                  visivel: campoVisivel('bairro', mapaConfig),
                  peso: 9,
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

        <InfoRegistro
          codigo={fornecedorExistente?.idFornecedor}
          criadoEm={fornecedorExistente?.criadoEm}
          atualizadoEm={fornecedorExistente?.atualizadoEm}
        />
      </fieldset>
      </form>
      </div>

      {modalPlanoAberto && (
        <PlanoContasModal
          aoFechar={() => setModalPlanoAberto(false)}
          aoCriar={(idPlanoContas) => {
            setForm((f) => ({ ...f, idPlanoContas }))
            setErros((e) => ({ ...e, idPlanoContas: undefined }))
            setModalPlanoAberto(false)
          }}
        />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
