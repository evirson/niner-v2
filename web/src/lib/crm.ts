import writeExcelFile from 'write-excel-file/browser'
import { hojeISO } from './datas'
import { api } from './api'
import { dataParaIso, formatarMoeda, isoParaData, mascararTelefone } from './masks'

export interface OpcaoCrm {
  id: number
  rotulo: string
}

export interface OpcoesCrm {
  categoriasCliente: OpcaoCrm[]
  categoriasProduto: OpcaoCrm[]
  cores: OpcaoCrm[]
  tamanhos: OpcaoCrm[]
}

export type GeneroCrm = 'MASCULINO' | 'FEMININO' | 'OUTROS'

export const ROTULO_GENERO_CRM: Record<GeneroCrm, string> = {
  MASCULINO: 'Masculino',
  FEMININO: 'Feminino',
  OUTROS: 'Outros',
}

export interface ClienteCrm {
  idCliente: number
  nome: string
  dataNascimento: string | null
  genero: GeneroCrm | null
  email: string | null
  celular: string | null
  primeiraCompra: string | null
  ultimaCompra: string | null
  numeroCompras: number
  valorTotalCompras: number
  ticketMedio: number | null
  diasSemUltimaCompra: number | null
}

/** Filtros de cliente (item 1 do pedido) — strings mascaradas como digitadas na tela; convertidas
 * pra formato de API só na hora de montar a query (`buscarClientesCrm`). */
export interface FiltrosClientesCrm {
  clienteInicial: string
  clienteFinal: string
  generos: GeneroCrm[]
  idadeDe: string
  idadeAte: string
  aniversarioDe: string
  aniversarioAte: string
  idsCategoriaCliente: number[]
  cadastroDe: string
  cadastroAte: string
  diasSemComprasMinimo: string
}

export const FILTROS_CLIENTES_CRM_VAZIO: FiltrosClientesCrm = {
  clienteInicial: '',
  clienteFinal: '',
  generos: [],
  idadeDe: '',
  idadeAte: '',
  aniversarioDe: '',
  aniversarioAte: '',
  idsCategoriaCliente: [],
  cadastroDe: '',
  cadastroAte: '',
  diasSemComprasMinimo: '',
}

/** Filtros de produtos comprados (item 2 do pedido). */
export interface FiltrosProdutosCrm {
  comprasDe: string
  comprasAte: string
  idsCategoriaProduto: number[]
  idsCor: number[]
  idsTamanho: number[]
}

export const FILTROS_PRODUTOS_CRM_VAZIO: FiltrosProdutosCrm = {
  comprasDe: '',
  comprasAte: '',
  idsCategoriaProduto: [],
  idsCor: [],
  idsTamanho: [],
}

export function contarFiltrosClientesAtivos(f: FiltrosClientesCrm): number {
  let n = 0
  if (f.clienteInicial.trim()) n++
  if (f.clienteFinal.trim()) n++
  if (f.generos.length > 0) n++
  if (f.idadeDe.trim() || f.idadeAte.trim()) n++
  if (f.aniversarioDe.trim() || f.aniversarioAte.trim()) n++
  if (f.idsCategoriaCliente.length > 0) n++
  if (f.cadastroDe.trim() || f.cadastroAte.trim()) n++
  if (f.diasSemComprasMinimo.trim()) n++
  return n
}

export function contarFiltrosProdutosAtivos(f: FiltrosProdutosCrm): number {
  let n = 0
  if (f.comprasDe.trim() || f.comprasAte.trim()) n++
  if (f.idsCategoriaProduto.length > 0) n++
  if (f.idsCor.length > 0) n++
  if (f.idsTamanho.length > 0) n++
  return n
}

export function buscarOpcoesCrm(): Promise<OpcoesCrm> {
  return api<OpcoesCrm>('/api/v1/crm/opcoes')
}

export function buscarClientesCrm(fc: FiltrosClientesCrm, fp: FiltrosProdutosCrm): Promise<ClienteCrm[]> {
  const params = new URLSearchParams()
  if (fc.clienteInicial.trim()) params.set('clienteInicial', fc.clienteInicial.trim())
  if (fc.clienteFinal.trim()) params.set('clienteFinal', fc.clienteFinal.trim())
  fc.generos.forEach((g) => params.append('generos', g))
  if (fc.idadeDe.trim()) params.set('idadeDe', fc.idadeDe.trim())
  if (fc.idadeAte.trim()) params.set('idadeAte', fc.idadeAte.trim())
  if (fc.aniversarioDe.trim()) params.set('aniversarioDe', fc.aniversarioDe.trim())
  if (fc.aniversarioAte.trim()) params.set('aniversarioAte', fc.aniversarioAte.trim())
  fc.idsCategoriaCliente.forEach((id) => params.append('idsCategoriaCliente', String(id)))
  if (fc.cadastroDe.trim()) params.set('cadastroDe', dataParaIso(fc.cadastroDe) ?? '')
  if (fc.cadastroAte.trim()) params.set('cadastroAte', dataParaIso(fc.cadastroAte) ?? '')
  if (fc.diasSemComprasMinimo.trim()) params.set('diasSemComprasMinimo', fc.diasSemComprasMinimo.trim())
  if (fp.comprasDe.trim()) params.set('comprasDe', dataParaIso(fp.comprasDe) ?? '')
  if (fp.comprasAte.trim()) params.set('comprasAte', dataParaIso(fp.comprasAte) ?? '')
  fp.idsCategoriaProduto.forEach((id) => params.append('idsCategoriaProduto', String(id)))
  fp.idsCor.forEach((id) => params.append('idsCor', String(id)))
  fp.idsTamanho.forEach((id) => params.append('idsTamanho', String(id)))

  const query = params.toString()
  return api<ClienteCrm[]>(`/api/v1/crm/clientes${query ? `?${query}` : ''}`)
}

/** As 11 colunas possíveis na planilha (item 3 do pedido + valor total/ticket médio/dias sem
 * comprar, 2026-08-05) — sempre calculadas pelo backend; esta lista só decide quais entram na
 * exportação. */
export type ColunaSaidaCrm =
  | 'nome' | 'dataNascimento' | 'genero' | 'email' | 'celular'
  | 'primeiraCompra' | 'ultimaCompra' | 'numeroCompras'
  | 'valorTotalCompras' | 'ticketMedio' | 'diasSemUltimaCompra'

export const ROTULO_COLUNA_CRM: Record<ColunaSaidaCrm, string> = {
  nome: 'Nome',
  dataNascimento: 'Data de Nascimento',
  genero: 'Gênero',
  email: 'E-mail',
  celular: 'Celular',
  primeiraCompra: 'Primeira Compra',
  ultimaCompra: 'Última Compra',
  numeroCompras: 'Nº Compras',
  valorTotalCompras: 'Valor Total Comprado',
  ticketMedio: 'Ticket Médio',
  diasSemUltimaCompra: 'Dias sem Comprar',
}

export const TODAS_AS_COLUNAS_CRM: ColunaSaidaCrm[] = [
  'nome', 'dataNascimento', 'genero', 'email', 'celular', 'primeiraCompra', 'ultimaCompra', 'numeroCompras',
  'valorTotalCompras', 'ticketMedio', 'diasSemUltimaCompra',
]

/** Colunas monetárias — ganham formato de moeda na planilha (`write-excel-file`), diferente das
 * demais colunas numéricas (Nº Compras, Dias sem Comprar), que ficam como inteiro simples. */
const COLUNAS_MONETARIAS_CRM: ColunaSaidaCrm[] = ['valorTotalCompras', 'ticketMedio']

function valorCelula(cliente: ClienteCrm, coluna: ColunaSaidaCrm): string | number {
  switch (coluna) {
    case 'nome':
      return cliente.nome
    case 'dataNascimento':
      return cliente.dataNascimento ? isoParaData(cliente.dataNascimento) : ''
    case 'genero':
      return cliente.genero ? ROTULO_GENERO_CRM[cliente.genero] : ''
    case 'email':
      return cliente.email ?? ''
    case 'celular':
      return cliente.celular ?? ''
    case 'primeiraCompra':
      return cliente.primeiraCompra ? isoParaData(cliente.primeiraCompra) : ''
    case 'ultimaCompra':
      return cliente.ultimaCompra ? isoParaData(cliente.ultimaCompra) : ''
    case 'numeroCompras':
      return cliente.numeroCompras
    case 'valorTotalCompras':
      return cliente.valorTotalCompras
    case 'ticketMedio':
      return cliente.ticketMedio ?? ''
    case 'diasSemUltimaCompra':
      return cliente.diasSemUltimaCompra ?? ''
  }
}

/**
 * Valor "cru" de uma coluna, pra ordenação client-side na grid (2026-08-07) — diferente de
 * {@link valorCelula}, nunca formata (datas ficam em ISO, que ordena certo como string; nunca
 * `dd/mm/aaaa`, que ordenaria errado lexicograficamente).
 */
export function valorOrdenacaoCrm(cliente: ClienteCrm, coluna: ColunaSaidaCrm): string | number | null {
  switch (coluna) {
    case 'nome':
      return cliente.nome
    case 'dataNascimento':
      return cliente.dataNascimento
    case 'genero':
      return cliente.genero
    case 'email':
      return cliente.email
    case 'celular':
      return cliente.celular
    case 'primeiraCompra':
      return cliente.primeiraCompra
    case 'ultimaCompra':
      return cliente.ultimaCompra
    case 'numeroCompras':
      return cliente.numeroCompras
    case 'valorTotalCompras':
      return cliente.valorTotalCompras
    case 'ticketMedio':
      return cliente.ticketMedio
    case 'diasSemUltimaCompra':
      return cliente.diasSemUltimaCompra
  }
}

/** Valor formatado pra exibir na grid de resultado (2026-08-07) — celular mascarado, datas
 *  `dd/mm/aaaa`, valores monetários com `R$`, `—` pros campos vazios. */
export function formatarCelulaCrm(cliente: ClienteCrm, coluna: ColunaSaidaCrm): string {
  switch (coluna) {
    case 'nome':
      return cliente.nome
    case 'dataNascimento':
      return cliente.dataNascimento ? isoParaData(cliente.dataNascimento) : '—'
    case 'genero':
      return cliente.genero ? ROTULO_GENERO_CRM[cliente.genero] : '—'
    case 'email':
      return cliente.email ?? '—'
    case 'celular':
      return cliente.celular ? mascararTelefone(cliente.celular) : '—'
    case 'primeiraCompra':
      return cliente.primeiraCompra ? isoParaData(cliente.primeiraCompra) : '—'
    case 'ultimaCompra':
      return cliente.ultimaCompra ? isoParaData(cliente.ultimaCompra) : '—'
    case 'numeroCompras':
      return String(cliente.numeroCompras)
    case 'valorTotalCompras':
      return `R$ ${formatarMoeda(cliente.valorTotalCompras)}`
    case 'ticketMedio':
      return cliente.ticketMedio != null ? `R$ ${formatarMoeda(cliente.ticketMedio)}` : '—'
    case 'diasSemUltimaCompra':
      return cliente.diasSemUltimaCompra != null ? String(cliente.diasSemUltimaCompra) : '—'
  }
}

/**
 * Planilha Excel (item 4 do pedido) — gerada 100% no navegador (`write-excel-file`, sem
 * dependência nova problemática: `xlsx`/SheetJS tem vulnerabilidade alta sem correção disponível
 * via npm, ver docs/telas/crm.md), mesmo espírito client-side do PDF (jsPDF) já usado nos
 * relatórios — não passa lógica de negócio pro front, só formata o que o backend já calculou.
 */
export async function exportarClientesCrmExcel(clientes: ClienteCrm[], colunas: ColunaSaidaCrm[]): Promise<void> {
  const schemaColunas = colunas.map((chave) => ({
    header: ROTULO_COLUNA_CRM[chave],
    cell: (cliente: ClienteCrm) => {
      const valor = valorCelula(cliente, chave)
      if (typeof valor !== 'number') return { value: valor, type: String }
      return COLUNAS_MONETARIAS_CRM.includes(chave)
        ? { value: valor, type: Number, format: 'R$ #,##0.00' }
        : { value: valor, type: Number }
    },
    width: chave === 'nome' || chave === 'email' ? 28 : 16,
  }))

  // ⚠️ `toISOString()` converte para UTC: depois das 21h de Brasília o arquivo baixava
  // carimbado com o DIA SEGUINTE (auditoria 2026-08-29). `lib/datas.ts` proíbe isso por escrito.
  const carimbo = hojeISO()
  await writeExcelFile(clientes, { columns: schemaColunas, sheet: 'Clientes' }).toFile(`crm-clientes-${carimbo}.xlsx`)
}
