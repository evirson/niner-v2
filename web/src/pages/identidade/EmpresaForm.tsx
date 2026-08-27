import { useEffect, useState, type ChangeEvent, type FormEvent, type KeyboardEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import ConfirmarSalvarModal from '../../components/ConfirmarSalvarModal'
import { IconeEmpresa } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  atualizarEmpresa,
  buscarEmpresa,
  paraFormularioEmpresa,
  paraRequisicaoEmpresa,
  type EmpresaFormState,
} from '../../lib/empresas'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { ESTADOS_UF, mascararCep, mascararCpfCnpj, mascararTelefone, somenteDigitos } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'
import { consultarCnpj, listarRamos } from '../../lib/ramos'
import { buscarEnderecoPorCep } from '../../lib/viacep'

const EMPRESA_VAZIA: EmpresaFormState = {
  nomeFantasia: '',
  cnpj: '',
  inscricaoEstadual: '',
  inscricaoMunicipal: '',
  codigoMunicipioIbge: '',
  cnae: '',
  endereco: '',
  numero: '',
  complemento: '',
  bairro: '',
  cidade: '',
  estado: '',
  cep: '',
  telefone: '',
  email: '',
  idRamo: '',
}

/**
 * Dados da Empresa (2026-08-19) — edita identificação, endereço e os dados fiscais do emitente
 * (CNPJ/Inscrição Estadual/Inscrição Municipal/código de município IBGE/CNAE) que a Conformidade
 * Fiscal cobra antes de emitir NFC-e/NF-e. Até esta tela nascer, {@code empresa} era só leitura —
 * os 4 links "Corrigir" de "Empresa sem CNPJ"/"sem Inscrição Estadual"/"sem código de município
 * IBGE"/"sem CNAE" no painel de Conformidade Fiscal não levavam a lugar nenhum (achado real,
 * reportado pelo dono do produto: "quando mando corrigir não me aparece pra cadastrar o CNPJ da
 * empresa, onde a gente coloca isso??"). Sem criação/exclusão — a empresa já existe (criada no
 * signup ou via SQL direto); esta tela só edita. ADMIN-only (checado no backend).
 *
 * <p>Razão Social, Código e Matriz/Filial ficam de fora do formulário de propósito — são
 * estruturais, não fazem parte do que a Conformidade Fiscal cobra.
 */
export default function EmpresaForm() {
  const { id } = useParams()
  const idEmpresa = Number(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<EmpresaFormState>(EMPRESA_VAZIA)
  const [erroCep, setErroCep] = useState('')
  const [avisoCnpj, setAvisoCnpj] = useState('')
  const { data: ramos = [] } = useQuery({ queryKey: ['ramos'], queryFn: listarRamos, staleTime: Infinity })
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)

  const { data: empresa } = useQuery({
    queryKey: ['empresa', idEmpresa],
    queryFn: () => buscarEmpresa(idEmpresa),
  })

  useEffect(() => {
    if (empresa) setForm(paraFormularioEmpresa(empresa))
  }, [empresa])

  const salvar = useMutation({
    mutationFn: () => atualizarEmpresa(idEmpresa, paraRequisicaoEmpresa(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['empresas'] })
      queryClient.invalidateQueries({ queryKey: ['empresa', idEmpresa] })
      queryClient.invalidateQueries({ queryKey: ['fiscal-conformidade'] })
      navigate('/empresas', { state: { toast: { texto: 'Dados da empresa atualizados com sucesso.', tipo: 'sucesso' } } })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar a empresa.'),
  })

  const campo = (chave: keyof EmpresaFormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: e.target.value }))

  const campoMaiusculo = (chave: keyof EmpresaFormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  /**
   * Ao completar os 14 dígitos do CNPJ, consulta os dados públicos e usa o CNAE para SUGERIR o
   * ramo de atividade — decisão do dono do produto (2026-08-27): dar a sugestão, mas quem
   * define é o usuário. Por isso a sugestão só entra em campo VAZIO: quem já escolheu um ramo
   * não o vê ser trocado por um palpite, e o CNAE digitado à mão não é sobrescrito.
   *
   * Consulta que falha não vira erro — o cadastro segue manual, como sempre foi.
   */
  const aoMudarCnpj = async (e: ChangeEvent<HTMLInputElement>) => {
    const mascarado = mascararCpfCnpj(e.target.value, false)
    setForm((f) => ({ ...f, cnpj: mascarado }))
    setAvisoCnpj('')
    if (somenteDigitos(mascarado).length !== 14) return
    const dados = await consultarCnpj(mascarado)
    if (!dados) return
    setForm((f) => ({
      ...f,
      cnae: f.cnae.trim() ? f.cnae : (dados.cnae ?? ''),
      nomeFantasia: f.nomeFantasia.trim() ? f.nomeFantasia : maiusculas(dados.nomeFantasia ?? ''),
      idRamo: f.idRamo.trim() ? f.idRamo : (dados.ramoSugerido ? String(dados.ramoSugerido.idRamo) : ''),
    }))
    if (dados.ramoSugerido) {
      setAvisoCnpj(`Ramo sugerido pelo CNAE deste CNPJ: ${dados.ramoSugerido.nome}. Troque se não for o caso.`)
    } else if (dados.cnae) {
      setAvisoCnpj('O CNAE deste CNPJ não identifica um ramo específico — escolha o ramo abaixo.')
    }
  }

  const aoMudarCep = async (e: ChangeEvent<HTMLInputElement>) => {
    const mascarado = mascararCep(e.target.value)
    setForm((f) => ({ ...f, cep: mascarado }))
    setErroCep('')
    const digitos = somenteDigitos(mascarado)
    if (digitos.length !== 8) return
    const endereco = await buscarEnderecoPorCep(digitos)
    if (endereco) {
      setForm((f) => ({
        ...f,
        endereco: endereco.logradouro ? maiusculas(endereco.logradouro) : f.endereco,
        bairro: endereco.bairro ? maiusculas(endereco.bairro) : f.bairro,
        cidade: endereco.localidade ? maiusculas(endereco.localidade) : f.cidade,
        estado: endereco.uf || f.estado,
      }))
    } else {
      setErroCep('CEP não encontrado — endereço não preenchido automaticamente.')
    }
  }

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    salvar.mutate()
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEmpresa size={34} />
            <h1>Dados da Empresa</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="identidade.empresa.form" />
            <BotaoFecharTela />
            <button type="submit" form="form-empresa" className="btn" disabled={salvar.isPending}>
              {salvar.isPending ? 'Salvando…' : 'Salvar'}
            </button>
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <form
          id="form-empresa"
          className="card form-secoes form-secoes-larga"
          onSubmit={submeter}
          onKeyDown={(e: KeyboardEvent<HTMLFormElement>) => aoTeclarEnterNoFormulario(e, () => setConfirmarSalvarAberto(true))}
          noValidate
        >
          <section className="section">
            <p className="section-label">Identificação</p>
            <div className="form-grid">
              <div className="col-6">
                <label>Razão Social</label>
                <div className="pdv-selecao-valor">
                  <span>{empresa?.razaoSocial ?? '—'}</span>
                </div>
              </div>
              <div className="col-3">
                <label>Código</label>
                <div className="pdv-selecao-valor">
                  <span>{empresa?.codigoEmpresa ?? '—'}</span>
                </div>
              </div>
              <div className="col-3">
                <label>Tipo</label>
                <div className="pdv-selecao-valor">
                  <span>{empresa == null ? '—' : empresa.matriz ? 'Matriz' : 'Filial'}</span>
                </div>
              </div>
              <div className="col-6">
                <label htmlFor="nomeFantasia">Nome Fantasia</label>
                <input id="nomeFantasia" autoFocus value={form.nomeFantasia} onChange={campoMaiusculo('nomeFantasia')} />
              </div>
            </div>
          </section>

          <section className="section">
            <p className="section-label">Dados Fiscais</p>
            <p className="muted" style={{ marginTop: -4 }}>
              Cobrados pela Conformidade Fiscal antes de emitir NFC-e/NF-e. Opcionais aqui — quem
              aponta o que falta é aquela tela, não este formulário.
            </p>
            <div className="form-grid">
              <div className="col-4">
                <label htmlFor="cnpj">CNPJ</label>
                <input
                  id="cnpj"
                  placeholder="00.000.000/0000-00"
                  value={form.cnpj}
                  onChange={aoMudarCnpj}
                />
              </div>
              <div className="col-4">
                <label htmlFor="inscricaoEstadual">Inscrição Estadual</label>
                <input id="inscricaoEstadual" value={form.inscricaoEstadual} onChange={campoMaiusculo('inscricaoEstadual')} />
              </div>
              <div className="col-4">
                <label htmlFor="inscricaoMunicipal">Inscrição Municipal</label>
                <input id="inscricaoMunicipal" value={form.inscricaoMunicipal} onChange={campoMaiusculo('inscricaoMunicipal')} />
              </div>
              <div className="col-4">
                <label htmlFor="codigoMunicipioIbge">Código de Município (IBGE)</label>
                <input
                  id="codigoMunicipioIbge"
                  inputMode="numeric"
                  placeholder="7 dígitos"
                  value={form.codigoMunicipioIbge}
                  onChange={(e) => setForm((f) => ({ ...f, codigoMunicipioIbge: e.target.value.replace(/\D/g, '').slice(0, 7) }))}
                />
                <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                  Código de 7 dígitos do IBGE para o município da empresa — consulte em{' '}
                  <a href="https://www.ibge.gov.br/explica/codigos-dos-municipios.php" target="_blank" rel="noreferrer">
                    ibge.gov.br
                  </a>
                  .
                </p>
              </div>
              <div className="col-4">
                <label htmlFor="cnae">CNAE</label>
                <input id="cnae" placeholder="0000-0/00" value={form.cnae} onChange={campo('cnae')} />
              </div>
              <div className="col-4">
                <label htmlFor="idRamo">Ramo de Atividade</label>
                <select id="idRamo" value={form.idRamo} onChange={(e) => setForm((f) => ({ ...f, idRamo: e.target.value }))}>
                  <option value="">Não informado</option>
                  {ramos.map((r) => (
                    <option key={r.idRamo} value={r.idRamo}>
                      {r.nome}
                    </option>
                  ))}
                </select>
                {avisoCnpj && (
                  <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                    {avisoCnpj}
                  </p>
                )}
              </div>
            </div>
          </section>

          <section className="section">
            <p className="section-label">Endereço</p>
            <div className="form-grid">
              <div className="col-3">
                <label htmlFor="cep">CEP</label>
                <input id="cep" placeholder="00000-000" value={form.cep} onChange={aoMudarCep} />
                {erroCep && <p className="erro-campo">{erroCep}</p>}
              </div>
              <div className="col-6">
                <label htmlFor="endereco">Endereço</label>
                <input id="endereco" value={form.endereco} onChange={campoMaiusculo('endereco')} />
              </div>
              <div className="col-3">
                <label htmlFor="numero">Número</label>
                <input id="numero" value={form.numero} onChange={campoMaiusculo('numero')} />
              </div>
              <div className="col-4">
                <label htmlFor="complemento">Complemento</label>
                <input id="complemento" value={form.complemento} onChange={campoMaiusculo('complemento')} />
              </div>
              <div className="col-4">
                <label htmlFor="bairro">Bairro</label>
                <input id="bairro" value={form.bairro} onChange={campoMaiusculo('bairro')} />
              </div>
              <div className="col-2">
                <label htmlFor="cidade">Cidade</label>
                <input id="cidade" value={form.cidade} onChange={campoMaiusculo('cidade')} />
              </div>
              <div className="col-2">
                <label htmlFor="estado">UF</label>
                {/* Select, não texto livre: desde 2026-08-20 a UF decide o FUSO da loja e para qual
                    SEFAZ a nota vai — sigla digitada errada só apareceria na primeira venda. */}
                <select
                  id="estado"
                  value={form.estado}
                  onChange={(e) => setForm((f) => ({ ...f, estado: e.target.value }))}
                >
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
            <p className="section-label">Contato</p>
            <div className="form-grid">
              <div className="col-4">
                <label htmlFor="telefone">Telefone</label>
                <input
                  id="telefone"
                  placeholder="(00) 00000-0000"
                  value={form.telefone}
                  onChange={(e) => setForm((f) => ({ ...f, telefone: mascararTelefone(e.target.value) }))}
                />
              </div>
              <div className="col-4">
                <label htmlFor="email">E-mail</label>
                <input id="email" type="email" value={form.email} onChange={campo('email')} />
              </div>
            </div>
          </section>

          <InfoRegistro codigo={empresa?.idEmpresa} criadoEm={empresa?.criadoEm} atualizadoEm={empresa?.atualizadoEm} />
        </form>
      </div>

      {confirmarSalvarAberto && (
        <ConfirmarSalvarModal
          aoConfirmar={() => {
            setConfirmarSalvarAberto(false)
            salvar.mutate()
          }}
          aoCancelar={() => setConfirmarSalvarAberto(false)}
        />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
