/**
 * Cliente HTTP do backoffice. Sessão do staff é separada da do lojista: chave própria no
 * localStorage e token com `aud=plataforma` — token de tenant não abre nada aqui (o backend
 * recusa na porta, com decoder próprio por superfície).
 */
const CHAVE_TOKEN = 'niner_staff_token'

declare global {
  interface Window {
    NINER_API_BASE?: string
  }
}

export function baseApi(): string {
  return window.NINER_API_BASE ?? 'http://localhost:8080'
}

export function getToken(): string | null {
  return localStorage.getItem(CHAVE_TOKEN)
}

export function setToken(t: string): void {
  localStorage.setItem(CHAVE_TOKEN, t)
}

export function clearToken(): void {
  localStorage.removeItem(CHAVE_TOKEN)
  // Avisa o React de que a sessão morreu — sem isso, uma expiração no meio do uso deixaria a
  // tela mostrando dados velhos e falhando em silêncio a cada clique.
  window.dispatchEvent(new Event('niner:sessao-staff-expirada'))
}

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export async function api<T>(caminho: string, init: RequestInit = {}): Promise<T> {
  const token = getToken()
  const resp = await fetch(baseApi() + caminho, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {}),
    },
  })

  if (resp.status === 401) {
    // 401 com token = sessão expirada (a do staff dura 2h, curta de propósito).
    if (token) clearToken()
    throw new ApiError(401, token ? 'Sessão expirada. Entre novamente.' : 'Credenciais inválidas.')
  }
  if (resp.status === 204) return undefined as T
  if (!resp.ok) {
    const problema = await resp.json().catch(() => ({}))
    throw new ApiError(resp.status, problema.detail || problema.title || 'Não foi possível concluir a operação.')
  }
  return (await resp.json()) as T
}

export const REAL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
export const NUMERO = new Intl.NumberFormat('pt-BR')

export function dataHora(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '—'
}

export function data(iso: string | null | undefined): string {
  return iso ? new Date(iso + (iso.length === 10 ? 'T00:00:00' : '')).toLocaleDateString('pt-BR') : '—'
}
