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
  // Avisa o AuthProvider (contexto React, em memória) de que o token morreu — sem isso,
  // RequireAuth continuava achando que o usuário estava logado (o estado em memória não
  // reagia a mudanças feitas fora do React, como esta) e não mandava de volta pro /login
  // sozinho depois de uma sessão expirar (2026-08-06, bug real encontrado em teste manual).
  window.dispatchEvent(new Event('niner:sessao-expirada'))
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

/**
 * Um 401 quer dizer coisas diferentes conforme havia ou não um token sendo usado:
 * - Com token (chamada autenticada que a API rejeitou) → a sessão morreu de verdade
 *   ("Sessão expirada.", limpa o token).
 * - Sem token (ex.: `/api/publico/login` com credenciais erradas) → não é sessão nenhuma,
 *   é a própria tentativa de login/ação pública sendo rejeitada — mostra o motivo real que
 *   o backend mandou (`detail`/`title`, ex. "Credenciais inválidas."), sem mascarar.
 * Bug real encontrado em teste manual (2026-08-06): antes disso, TODO 401 — inclusive login
 * com senha errada — virava "Sessão expirada.", escondendo o motivo verdadeiro.
 */
async function tratarNaoAutorizado(res: Response, tokenUsado: string | null): Promise<never> {
  if (tokenUsado) {
    clearToken()
    throw new ApiError(401, 'Sessão expirada.')
  }
  let msg = 'Não autorizado.'
  try {
    const p = await res.json()
    msg = p.detail || p.title || msg
  } catch {
    /* resposta sem corpo JSON */
  }
  throw new ApiError(401, msg)
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
    return tratarNaoAutorizado(res, token)
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
  // Corpo vazio não é exclusividade do 204 — um endpoint `void` sem `@ResponseStatus` explícito
  // no Spring vira 200 OK com corpo vazio, e `res.json()` nesse corpo lança `SyntaxError`
  // (`Unexpected end of JSON input`), fazendo esta função rejeitar mesmo com a chamada tendo
  // funcionado (2026-08-04, bug real: Contagem de Estoque tratava leitura bem-sucedida como
  // falha). Ler como texto primeiro e só fazer `JSON.parse` se não estiver vazio cobre 204 e
  // qualquer outro status sem corpo, sem depender de cada controller acertar a anotação.
  const texto = await res.text()
  if (!texto) return undefined as T
  return JSON.parse(texto) as T
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
    return tratarNaoAutorizado(res, token)
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
