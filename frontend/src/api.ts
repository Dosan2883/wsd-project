export type User = { id: number; email: string; role: 'USER' | 'ADMIN'; status: 'ACTIVE' | 'SUSPENDED'; createdAt: string }
export type Session = { accessToken: string; tokenType: 'Bearer'; expiresAt: string; user: User }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public fields: Record<string, string> = {}) {
    super(message)
  }
}

export async function api<T>(path: string, options: { method?: string; body?: unknown; token?: string; signal?: AbortSignal } = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api${path}`, {
      method: options.method ?? 'GET',
      headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}) },
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
      credentials: 'omit',
      cache: 'no-store',
    })
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error
    throw new ApiError(0, 'NETWORK_ERROR', '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.')
  }
  const body = await response.json().catch(() => null)
  if (!response.ok) throw new ApiError(response.status, body?.code ?? 'REQUEST_FAILED', body?.message ?? '요청을 처리하지 못했습니다.', body?.fields ?? {})
  return body as T
}
