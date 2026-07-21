/** Máscaras e validação de documentos para o cadastro de cliente (docs/telas/cliente.md). */

export function somenteDigitos(valor: string): string {
  return valor.replace(/\D/g, '')
}

/**
 * Mantém dígitos e letras (maiúsculas), removendo máscara/pontuação — usado pelo CNPJ
 * alfanumérico (Receita Federal, IN RFB 2.229/2024, vigente a partir de julho/2026): as 12
 * primeiras posições (raiz+ordem) podem ser letras A-Z ou dígitos, só os 2 dígitos
 * verificadores finais continuam numéricos. CPF não entra nessa mudança — continua só dígitos.
 */
export function somenteAlfanumerico(valor: string): string {
  return valor.toUpperCase().replace(/[^0-9A-Z]/g, '')
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

/**
 * CPF continua só dígitos. CNPJ é alfanumérico (posições 1-12 aceitam A-Z além de 0-9; os 2
 * dígitos verificadores finais continuam numéricos) — ver {@link somenteAlfanumerico}.
 */
export function mascararCpfCnpj(valor: string, fisicaJuridica: boolean): string {
  if (fisicaJuridica) {
    const digitos = somenteDigitos(valor).slice(0, 11)
    return aplicarMascara(digitos, '000.000.000-00')
  }
  const alfanumerico = somenteAlfanumerico(valor).slice(0, 14)
  return aplicarMascara(alfanumerico, '00.000.000/0000-00')
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

/** Formata um número (ex.: vindo da API) como moeda BR: "1234.5" -> "1.234,50". */
export function formatarMoeda(valor: number): string {
  return valor.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/**
 * Máscara de campo monetário: os dígitos digitados são sempre lidos da direita para a
 * esquerda como centavos (mesma convenção de caixas eletrônicos/apps de banco) — evita
 * ambiguidade de separador decimal/milhar. Ex.: digitar "150000" vira "1.500,00".
 */
export function mascararMoeda(valor: string): string {
  const digitos = somenteDigitos(valor)
  if (!digitos) return ''
  return formatarMoeda(Number(digitos) / 100)
}

/** Desfaz {@link mascararMoeda}, devolvendo o número para enviar à API. */
export function desmascararMoeda(valor: string): number {
  const digitos = somenteDigitos(valor)
  return digitos ? Number(digitos) / 100 : 0
}

/**
 * "Id. WhatsApp" — mesma convenção visual de Instagram/Facebook/TikTok (prefixo `@`), mas
 * só dígitos depois do `@` (é o número do celular). Validado por {@link celularValido}.
 */
export function mascararIdWhatsapp(valor: string): string {
  const digitos = somenteDigitos(valor).slice(0, 11)
  return digitos ? `@${digitos}` : ''
}

/**
 * {@code true} se vazio (telefone/WhatsApp são opcionais) ou se for um celular válido:
 * 11 dígitos (DDD + 9 dígitos) com o terceiro dígito igual a 9 (docs/telas/cliente.md).
 */
export function celularValido(valor: string): boolean {
  const d = somenteDigitos(valor)
  if (!d) return true
  return d.length === 11 && d[2] === '9'
}

/**
 * {@code true} se vazio (opcional) ou se o dígito verificador de CPF/CNPJ confere. CPF: 11
 * dígitos. CNPJ: 14 caracteres, alfanumérico nas 12 primeiras posições (ver {@link cnpjValido}).
 */
export function documentoValido(valor: string): boolean {
  const limpo = somenteAlfanumerico(valor)
  if (!limpo) return true
  if (/^[0-9]{11}$/.test(limpo)) return cpfValido(limpo)
  if (limpo.length === 14) return cnpjValido(limpo)
  return false
}

function todosIguais(d: string): boolean {
  return d.split('').every((c) => c === d[0])
}

function digitoVerificador(somaPonderada: number): number {
  const resto = somaPonderada % 11
  return resto < 2 ? 0 : 11 - resto
}

/**
 * Valor de um caractere do CNPJ alfanumérico: código ASCII menos 48 — dígitos '0'-'9' viram
 * 0-9 (o próprio valor, já que '0' é ASCII 48) e letras 'A'-'Z' viram 17-42. Fórmula da
 * Receita Federal (IN RFB 2.229/2024); confirmada com o exemplo oficial
 * "12.ABC.345/01DE-35" (soma ponderada bate com DV 35).
 */
function valorCaractereCnpj(c: string): number {
  return c.charCodeAt(0) - 48
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

/**
 * CNPJ alfanumérico (Receita Federal, a partir de julho/2026): as 12 primeiras posições
 * (raiz+ordem) podem ser 0-9 ou A-Z; os 2 dígitos verificadores finais (posições 13-14)
 * continuam sempre numéricos. CNPJs só-numéricos (formato antigo) continuam válidos — o
 * cálculo é o mesmo de sempre, só a tabela de valor por caractere ficou mais ampla.
 */
function cnpjValido(cnpj: string): boolean {
  if (!/^[0-9]{2}$/.test(cnpj.slice(12))) return false
  if (todosIguais(cnpj)) return false
  const n = cnpj.split('').map(valorCaractereCnpj)
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
