import { api } from './api'
import { mascararCep, mascararCpfCnpj, mascararTelefone, somenteAlfanumerico, somenteDigitos } from './masks'
import { maiusculas } from './texto'

export interface Empresa {
  idEmpresa: number
  codigoEmpresa: number
  razaoSocial: string
  nomeFantasia: string | null
  ativo: boolean
}

export function listarEmpresas(): Promise<Empresa[]> {
  return api<Empresa[]>('/api/v1/empresas')
}

/** Empresas que o usuário logado pode operar (ADMIN: todas do tenant; OPERADOR: só as
 *  liberadas pra ele) — usado pela Entrada de Produtos por Compra pra escolher em qual empresa
 *  dar entrada. */
export function listarEmpresasPermitidas(): Promise<Empresa[]> {
  return api<Empresa[]>('/api/v1/empresas/permitidas')
}

/**
 * Ficha completa de empresa (2026-08-19) — identificação/endereço/dados fiscais do emitente
 * (CNPJ/Inscrição Estadual/Inscrição Municipal/código de município IBGE/CNAE), que até então não
 * tinham tela nenhuma pra editar (só a Conformidade Fiscal apontava a falta, sem link que
 * funcionasse). ADMIN-only.
 */
export interface EmpresaDetalhe {
  idEmpresa: number
  codigoEmpresa: number
  razaoSocial: string
  nomeFantasia: string | null
  matriz: boolean
  cnpj: string | null
  inscricaoEstadual: string | null
  inscricaoMunicipal: string | null
  codigoMunicipioIbge: number | null
  cnae: string | null
  endereco: string | null
  numero: string | null
  complemento: string | null
  bairro: string | null
  cidade: string | null
  estado: string | null
  cep: string | null
  telefone: string | null
  email: string | null
  ativo: boolean
  criadoEm: string
  atualizadoEm: string
}

export interface EmpresaFormState {
  nomeFantasia: string
  cnpj: string
  inscricaoEstadual: string
  inscricaoMunicipal: string
  codigoMunicipioIbge: string
  cnae: string
  endereco: string
  numero: string
  complemento: string
  bairro: string
  cidade: string
  estado: string
  cep: string
  telefone: string
  email: string
}

export function paraFormularioEmpresa(e: EmpresaDetalhe): EmpresaFormState {
  return {
    nomeFantasia: e.nomeFantasia ?? '',
    cnpj: e.cnpj ? mascararCpfCnpj(e.cnpj, false) : '',
    inscricaoEstadual: e.inscricaoEstadual ?? '',
    inscricaoMunicipal: e.inscricaoMunicipal ?? '',
    codigoMunicipioIbge: e.codigoMunicipioIbge == null ? '' : String(e.codigoMunicipioIbge),
    cnae: e.cnae ?? '',
    endereco: e.endereco ?? '',
    numero: e.numero ?? '',
    complemento: e.complemento ?? '',
    bairro: e.bairro ?? '',
    cidade: e.cidade ?? '',
    estado: e.estado ?? '',
    cep: e.cep ? mascararCep(e.cep) : '',
    telefone: e.telefone ? mascararTelefone(e.telefone) : '',
    email: e.email ?? '',
  }
}

/** Campo em branco vira `null` — a Conformidade Fiscal é quem cobra o preenchimento, não este
 *  formulário (mesmo princípio de `TipoCarteiraForm`/`FornecedorForm`). */
function nuloSeVazio(valor: string): string | null {
  return valor.trim() ? valor.trim() : null
}

export function paraRequisicaoEmpresa(f: EmpresaFormState) {
  return {
    nomeFantasia: nuloSeVazio(maiusculas(f.nomeFantasia)),
    cnpj: f.cnpj.trim() ? somenteAlfanumerico(f.cnpj) : null,
    inscricaoEstadual: nuloSeVazio(maiusculas(f.inscricaoEstadual)),
    inscricaoMunicipal: nuloSeVazio(maiusculas(f.inscricaoMunicipal)),
    codigoMunicipioIbge: f.codigoMunicipioIbge.trim() ? Number(f.codigoMunicipioIbge) : null,
    cnae: nuloSeVazio(f.cnae),
    endereco: nuloSeVazio(maiusculas(f.endereco)),
    numero: nuloSeVazio(maiusculas(f.numero)),
    complemento: nuloSeVazio(maiusculas(f.complemento)),
    bairro: nuloSeVazio(maiusculas(f.bairro)),
    cidade: nuloSeVazio(maiusculas(f.cidade)),
    estado: nuloSeVazio(maiusculas(f.estado)),
    cep: f.cep.trim() ? somenteDigitos(f.cep) : null,
    telefone: f.telefone.trim() ? somenteDigitos(f.telefone) : null,
    email: nuloSeVazio(f.email),
  }
}

export function buscarEmpresa(id: number): Promise<EmpresaDetalhe> {
  return api<EmpresaDetalhe>(`/api/v1/empresas/${id}`)
}

export function atualizarEmpresa(
  id: number,
  payload: ReturnType<typeof paraRequisicaoEmpresa>,
): Promise<EmpresaDetalhe> {
  return api<EmpresaDetalhe>(`/api/v1/empresas/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
}
