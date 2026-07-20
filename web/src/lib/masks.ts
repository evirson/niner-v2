/** Máscaras e validação de documentos para o cadastro de cliente (docs/telas/cliente.md). */

export function somenteDigitos(valor: string): string {
  return valor.replace(/\D/g, '')
}

/** Aplica um padrão tipo "000.000.000-00" sobre os dígitos, parando quando eles acabam. */
function aplicarMascara(digitos: string, padrao: string): string {
  let saida = ''
  let i = 0
  for (const c of padrao) {
    if (i >= digitos.length) break
    if (c === '0') {
      saida += digitos[i++]
    } else {
      saida += c
    }
  }
  return saida
}

export function mascararCpfCnpj(valor: string, fisicaJuridica: boolean): string {
  const digitos = somenteDigitos(valor).slice(0, fisicaJuridica ? 11 : 14)
  return aplicarMascara(digitos, fisicaJuridica ? '000.000.000-00' : '00.000.000/0000-00')
}

/** Aceita fixo (10 dígitos) e celular (11 dígitos), com DDD. */
export function mascararTelefone(valor: string): string {
  const digitos = somenteDigitos(valor).slice(0, 11)
  return aplicarMascara(digitos, digitos.length > 10 ? '(00) 00000-0000' : '(00) 0000-0000')
}

export function mascararCep(valor: string): string {
  const digitos = somenteDigitos(valor).slice(0, 8)
  return aplicarMascara(digitos, '00000-000')
}

/** {@code true} se vazio (opcional) ou se o dígito verificador de CPF/CNPJ confere. */
export function documentoValido(valor: string): boolean {
  const d = somenteDigitos(valor)
  if (!d) return true
  if (d.length === 11) return cpfValido(d)
  if (d.length === 14) return cnpjValido(d)
  return false
}

function todosIguais(d: string): boolean {
  return d.split('').every((c) => c === d[0])
}

function digitoVerificador(somaPonderada: number): number {
  const resto = somaPonderada % 11
  return resto < 2 ? 0 : 11 - resto
}

function cpfValido(cpf: string): boolean {
  if (todosIguais(cpf)) return false
  const n = cpf.split('').map(Number)
  let soma = 0
  for (let i = 0; i < 9; i++) soma += n[i] * (10 - i)
  if (n[9] !== digitoVerificador(soma)) return false
  soma = 0
  for (let i = 0; i < 10; i++) soma += n[i] * (11 - i)
  return n[10] === digitoVerificador(soma)
}

function cnpjValido(cnpj: string): boolean {
  if (todosIguais(cnpj)) return false
  const n = cnpj.split('').map(Number)
  const pesos1 = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
  let soma = 0
  for (let i = 0; i < 12; i++) soma += n[i] * pesos1[i]
  if (n[12] !== digitoVerificador(soma)) return false
  const pesos2 = [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
  soma = 0
  for (let i = 0; i < 13; i++) soma += n[i] * pesos2[i]
  return n[13] === digitoVerificador(soma)
}

export const ESTADOS_UF = [
  'AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA', 'PB',
  'PR', 'PE', 'PI', 'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO',
]
