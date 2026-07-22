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

/** NCM: sempre 8 dígitos, exibido como "9999.99.99" (docs/telas/produto.md). */
export function mascararNcm(valor: string): string {
  const digitos = somenteDigitos(valor).slice(0, 8)
  return aplicarMascara(digitos, '0000.00.00')
}

/**
 * Data como campo de texto "dd/mm/aaaa" (2026-07-22, pedido do dono do produto) — substitui
 * `<input type="date">` em todo o sistema: o nativo navega por segmentos (dia/mês/ano) e não
 * dá pra "selecionar tudo e sobrescrever ao digitar", que é o comportamento pedido. Como campo
 * de texto normal, `onFocus` com {@code .select()} já resolve.
 */
export function mascararData(valor: string): string {
  const digitos = somenteDigitos(valor).slice(0, 8)
  return aplicarMascara(digitos, '00/00/0000')
}

/** {@code true} só quando "dd/mm/aaaa" tem os 8 dígitos e é uma data de calendário real. */
export function dataValida(valor: string): boolean {
  const m = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(valor)
  if (!m) return false
  const dia = Number(m[1]);
  const mes = Number(m[2]);
  const ano = Number(m[3])
  if (mes < 1 || mes > 12) return false
  const diasNoMes = new Date(ano, mes, 0).getDate()
  return dia >= 1 && dia <= diasNoMes
}

/** "dd/mm/aaaa" -> "aaaa-mm-dd" (ISO, para comparar ou montar o payload da API). */
export function dataParaIso(valor: string): string | null {
  if (!dataValida(valor)) return null
  const [dia, mes, ano] = valor.split('/')
  return `${ano}-${mes}-${dia}`
}

/** "aaaa-mm-dd" (ISO, vindo da API) -> "dd/mm/aaaa" para exibir no campo. */
export function isoParaData(iso: string | null | undefined): string {
  if (!iso) return ''
  const [ano, mes, dia] = iso.slice(0, 10).split('-')
  return `${dia}/${mes}/${ano}`
}

/** Formata um número (ex.: vindo da API) como moeda BR: "1234.5" -> "1.234,50". */
export function formatarMoeda(valor: number): string {
  return valor.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** Formata um número como percentual BR: "5.5" -> "5,50". */
export function formatarPercentual(valor: number): string {
  return valor.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** Formata um número como peso BR, 3 casas (numeric(14,3), docs/telas/produto.md): "1.5" -> "1,500". */
export function formatarPeso(valor: number): string {
  return valor.toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 })
}

function formatarParteInteira(digitos: string): string {
  return digitos ? Number(digitos).toLocaleString('pt-BR') : ''
}

/**
 * Máscara de campo decimal (moeda/percentual/peso) — digitação natural (2026-07-22, revisão
 * pedida pelo dono do produto): o inteiro é digitado da esquerda para a direita, como um
 * número comum; a vírgula abre até {@code casas} decimais (2 para moeda/percentual, 3 para
 * peso — numeric(14,3)). Diferente da convenção anterior (dígitos sempre lidos da direita como
 * centavos, tipo caixa eletrônico) — aqui NÃO se completa o decimal a cada tecla (isso
 * impediria continuar digitando o inteiro); a finalização fica para
 * {@link completarValorDecimal}, chamada no {@code onBlur} do campo.
 */
function mascararValorDecimal(valor: string, casas: number): string {
  const limpo = valor.replace(/[^\d,]/g, '')
  const posVirgula = limpo.indexOf(',')
  if (posVirgula === -1) {
    return formatarParteInteira(limpo)
  }
  const parteInteira = limpo.slice(0, posVirgula)
  const parteDecimal = limpo.slice(posVirgula + 1).replace(/,/g, '').slice(0, casas)
  return `${formatarParteInteira(parteInteira) || '0'},${parteDecimal}`
}

/**
 * Completa o valor decimal ao sair do campo (item 1, pedido do dono do produto): sem vírgula
 * nenhuma, ganha ",0…0" ({@code casas} zeros); com menos casas do que o esperado, completa com
 * zero(s) à direita. Chamar sempre no {@code onBlur} de todo campo de moeda/percentual/peso.
 */
function completarValorDecimal(valor: string, casas: number): string {
  if (!valor.trim()) return valor
  if (!valor.includes(',')) return `${valor},${'0'.repeat(casas)}`
  const [inteiro, decimal = ''] = valor.split(',')
  return `${inteiro || '0'},${decimal.padEnd(casas, '0').slice(0, casas)}`
}

function desmascararValorDecimal(valor: string, casas: number): number {
  if (!valor.trim()) return 0
  const semMilhar = completarValorDecimal(valor, casas).replace(/\./g, '').replace(',', '.')
  const n = Number(semMilhar)
  return Number.isFinite(n) ? n : 0
}

export function mascararMoeda(valor: string): string {
  return mascararValorDecimal(valor, 2)
}

/** Completa o campo de moeda ao sair dele — ver {@link completarValorDecimal}. */
export function completarMoeda(valor: string): string {
  return completarValorDecimal(valor, 2)
}

/** Desfaz {@link mascararMoeda}/{@link completarMoeda}, devolvendo o número para enviar à API. */
export function desmascararMoeda(valor: string): number {
  return desmascararValorDecimal(valor, 2)
}

export function mascararPercentual(valor: string): string {
  return mascararValorDecimal(valor, 2)
}

/** Completa o campo de percentual ao sair dele — ver {@link completarValorDecimal}. */
export function completarPercentual(valor: string): string {
  return completarValorDecimal(valor, 2)
}

/** Desfaz {@link mascararPercentual}/{@link completarPercentual}, devolvendo o número para enviar à API. */
export function desmascararPercentual(valor: string): number {
  return desmascararValorDecimal(valor, 2)
}

/** Máscara de campo de peso (kg) — 3 casas decimais, numeric(14,3) (docs/telas/produto.md). */
export function mascararPeso(valor: string): string {
  return mascararValorDecimal(valor, 3)
}

/** Completa o campo de peso ao sair dele — ver {@link completarValorDecimal}. */
export function completarPeso(valor: string): string {
  return completarValorDecimal(valor, 3)
}

/** Desfaz {@link mascararPeso}/{@link completarPeso}, devolvendo o número para enviar à API. */
export function desmascararPeso(valor: string): number {
  return desmascararValorDecimal(valor, 3)
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
 * {@code true} se vazio ou se for um telefone com DDD, fixo OU celular (10–11 dígitos) —
 * regra mais frouxa que {@link celularValido}, usada onde linha fixa é comum (ex.:
 * fornecedor, docs/telas/fornecedor.md).
 */
export function telefoneValido(valor: string): boolean {
  const d = somenteDigitos(valor)
  if (!d) return true
  return d.length === 10 || d.length === 11
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
