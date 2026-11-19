import type { Session } from './api'

export const SESSION_KEY = 'gamelog.session'
export type StoredSession = Pick<Session, 'accessToken' | 'expiresAt'>

export function forgetSession() {
  try { sessionStorage.removeItem(SESSION_KEY) } catch { /* 저장소가 차단돼도 메모리의 로그아웃은 진행한다. */ }
}

export function rememberSession(session: StoredSession) {
  try {
    // 회원 정보·역할은 캐시하지 않고 복원할 때 서버에서 다시 읽는다.
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ accessToken: session.accessToken, expiresAt: session.expiresAt }))
  } catch { /* 저장소가 차단된 환경에서는 현재 화면의 로그인만 유지한다. */ }
}

export function readSession(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY)
    if (!raw) return null
    const stored = JSON.parse(raw)
    if (typeof stored?.accessToken === 'string' && stored.accessToken.length > 0
      && typeof stored.expiresAt === 'string' && Number.isFinite(Date.parse(stored.expiresAt))
      && Date.parse(stored.expiresAt) > Date.now()) {
      return { accessToken: stored.accessToken, expiresAt: stored.expiresAt }
    }
  } catch { /* 손상된 저장 값으로 로그인된 화면을 열지 않는다. */ }
  forgetSession()
  return null
}
