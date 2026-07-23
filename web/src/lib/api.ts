import { API_BASE } from './config'

const TOKEN_KEY = 'niner_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(t: string): void {
  localStorage.setItem(TOKEN_KEY, t)
}
export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * Consome o token passado pelo site na URL (#token=...) no primeiro acesso pós-signup
 * (SSO leve entre site e app). Chamado uma vez antes de renderizar.
 */
export function consumeHandoffToken(): void {
  const m = window.location.hash.match(/(?:^#|&)token=([^&]+)/)
  if (m) {
    setToken(decodeURIComponent(m[1]))
    history.replaceState(null, '', window.location.pathname + window.location.search)
  }
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/** Fetch autenticado à API. Injeta o Bearer token e trata Problem Details (RFC 9457). */
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken()
  const res = await fetch(API_BASE + path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  })
  if (res.status === 401) {
    clearToken()
    throw new ApiError(401, 'Sessão expirada.')
  }
  if (!res.ok) {
    let msg = 'Ocorreu um erro.'
    try {
      const p = await res.json()
      msg = p.detail || p.title || msg
    } catch {
      /* resposta sem corpo JSON */
    }
    throw new ApiError(res.status, msg)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

/**
 * Upload multipart (`FormData`) — nunca define `Content-Type` manualmente: o navegador
 * precisa gerar o boundary sozinho, então usar {@link api} (que força `application/json`)
 * quebraria o upload.
 */
export async function apiUpload<T>(path: string, formData: FormData, method: string = 'POST'): Promise<T> {
  const token = getToken()
  const res = await fetch(API_BASE + path, {
    method,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: formData,
  })
  if (res.status === 401) {
    clearToken()
    throw new ApiError(401, 'Sessão expirada.')
  }
  if (!res.ok) {
    let msg = 'Ocorreu um erro.'
    try {
      const p = await res.json()
      msg = p.detail || p.title || msg
    } catch {
      /* resposta sem corpo JSON */
    }
    throw new ApiError(res.status, msg)
  }
  return (await res.json()) as T
}
