import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { SESSION_KEY } from './session'
import { StrictMode } from 'react'

const user = { id: 1, email: 'member@example.com', role: 'USER', status: 'ACTIVE', createdAt: '2026-11-12T00:00:00Z' }
const session = { accessToken: 'memory-only-token', tokenType: 'Bearer', expiresAt: new Date(Date.now() + 1800000).toISOString(), user }
const response = (body: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  vi.stubGlobal('fetch', vi.fn(async (input: string) => response(input === '/api/me' ? user : input.startsWith('/api/articles')
    ? { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 } : [])))
  localStorage.clear(); sessionStorage.clear()
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

async function fillForm() {
  const actor = userEvent.setup()
  await actor.type(screen.getByLabelText('이메일'), 'member@example.com')
  await actor.type(screen.getByLabelText('비밀번호'), 'valid-password')
  return actor
}

async function login() {
  vi.mocked(fetch).mockResolvedValueOnce(response(session))
  const actor = await fillForm()
  await actor.click(screen.getByRole('button', { name: '로그인' }))
  await screen.findByRole('heading', { name: '새로운 소식을 만나보세요' })
  await screen.findByText('아직 공개된 글이 없습니다')
  await actor.click(screen.getByRole('link', { name: '내 계정' }))
  await screen.findByRole('heading', { name: '내 계정' })
  // Flush the browser's hash navigation before another request starts.
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)) })
  return actor
}

describe('authentication user flows', () => {
  it('signs up then asks the user to log in', async () => {
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: '회원가입' }))
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)) })
    vi.mocked(fetch).mockResolvedValueOnce(response(user, 201))
    const actor = await fillForm()
    await actor.click(screen.getByRole('button', { name: '회원가입' }))
    expect(await screen.findByRole('status')).toHaveTextContent('회원가입이 완료되었습니다')
    expect(screen.getByLabelText('비밀번호')).toHaveValue('')
    expect(fetch).toHaveBeenCalledWith('/api/auth/signup', expect.objectContaining({ method: 'POST' }))
  })

  it('토큰만탭에보관하고서버정보확인후로그아웃한다', async () => {
    render(<App />)
    const actor = await login()
    expect(localStorage.length).toBe(0)
    expect(JSON.parse(sessionStorage.getItem(SESSION_KEY)!)).toEqual({ accessToken: session.accessToken, expiresAt: session.expiresAt })
    vi.mocked(fetch).mockResolvedValueOnce(response(user))
    await actor.click(screen.getByRole('button', { name: '내 정보 새로 확인' }))
    expect(await screen.findByRole('status')).toHaveTextContent('서버에서 현재 회원 정보')
    expect(fetch).toHaveBeenLastCalledWith('/api/me', expect.objectContaining({ headers: { Authorization: 'Bearer memory-only-token' } }))
    await actor.click(screen.getByRole('button', { name: '로그아웃' }))
    expect(await screen.findByRole('button', { name: '로그인' })).toBeInTheDocument()
    expect(screen.queryByText('member@example.com')).not.toBeInTheDocument()
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  })

  it('shows duplicate-email errors and allows retry', async () => {
    window.history.replaceState(null, '', '/#/signup'); render(<App />)
    vi.mocked(fetch).mockResolvedValueOnce(response({ code: 'EMAIL_ALREADY_EXISTS', message: '이미 가입된 이메일입니다.' }, 409))
    const actor = await fillForm()
    await actor.click(screen.getByRole('button', { name: '회원가입' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('이미 가입된 이메일')
    expect(screen.getByRole('button', { name: '회원가입' })).toBeEnabled()
  })

  it('shows the server denial on a directly visited admin page', async () => {
    render(<App />); const actor = await login()
    await actor.click(screen.getByRole('button', { name: '관리자 페이지' }))
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)) })
    vi.mocked(fetch).mockResolvedValueOnce(response({ code: 'FORBIDDEN', message: '이 페이지에 접근할 권한이 없습니다.' }, 403))
    await actor.click(screen.getByRole('button', { name: '관리자 권한 확인' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('접근할 권한이 없습니다')
  })

  it('returns to login on an expired token response', async () => {
    render(<App />); const actor = await login()
    vi.mocked(fetch).mockResolvedValueOnce(response({ code: 'UNAUTHENTICATED', message: '인증이 만료되었습니다. 다시 로그인해 주세요.' }, 401))
    await actor.click(screen.getByRole('button', { name: '내 정보 새로 확인' }))
    expect(await screen.findByRole('status')).toHaveTextContent('인증이 만료되었습니다')
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()
  })

  it('clears the session when the server suspends the account', async () => {
    render(<App />); const actor = await login()
    vi.mocked(fetch).mockResolvedValueOnce(response({ code: 'ACCOUNT_SUSPENDED', message: '정지된 계정입니다.' }, 403))
    await actor.click(screen.getByRole('button', { name: '내 정보 새로 확인' }))
    expect(await screen.findByRole('status')).toHaveTextContent('정지된 계정')
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()
  })

  it('새로고침후현재토큰으로내정보를다시검증한다', async () => {
    const view = render(<App />); await login(); view.unmount()
    vi.mocked(fetch).mockClear()
    render(<App />)
    expect(screen.getByRole('status')).toHaveTextContent('로그인 정보를 확인하는 중')
    expect(await screen.findByRole('heading', { name: '내 계정' })).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/api/me', expect.objectContaining({ headers: { Authorization: 'Bearer memory-only-token' } }))
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
  })

  it('automatically expires an idle session', async () => {
    vi.useFakeTimers()
    vi.mocked(fetch).mockResolvedValueOnce(response({ ...session, expiresAt: new Date(Date.now() + 1000).toISOString() }))
    render(<App />)
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'member@example.com' } })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'valid-password' } })
    await act(async () => { fireEvent.submit(screen.getByRole('button', { name: '로그인' }).closest('form')!) })
    expect(screen.getByRole('heading', { name: '새로운 소식을 만나보세요' })).toBeInTheDocument()
    await act(async () => { vi.advanceTimersByTime(1100) })
    expect(screen.getByRole('status')).toHaveTextContent('로그인이 만료되었습니다')
  })

  it('does not restore a session from a late response after navigating away', async () => {
    let resolve!: (value: Response) => void
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    render(<App />)
    const actor = await fillForm()
    await actor.click(screen.getByRole('button', { name: '로그인' }))
    await actor.click(screen.getByRole('button', { name: '회원가입' }))
    await act(async () => { resolve(response(session)) })
    expect(screen.getByRole('heading', { name: '계정을 만들어 보세요' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
  })

  it('shows a recoverable connection error', async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('fetch failed'))
    render(<App />); const actor = await fillForm()
    await actor.click(screen.getByRole('button', { name: '로그인' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결할 수 없습니다')
    await waitFor(() => expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled())
  })

  it('정지로그인은사유와한국시간일시를배너로표시한다', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response({ code: 'ACCOUNT_SUSPENDED', message: '정지된 계정입니다.', suspension: { reason: '반복적인 운영 정책 위반', suspendedAt: '2026-11-19T03:00:00Z' } }, 403))
    render(<App />); const actor = await fillForm()
    await actor.click(screen.getByRole('button', { name: '로그인' }))
    const banner = await screen.findByRole('alert', { name: '계정 정지 안내' })
    expect(banner).toHaveTextContent('반복적인 운영 정책 위반')
    expect(banner).toHaveTextContent(/2026\.\s*11\.\s*19/)
    expect(banner).toHaveTextContent('12:00')
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  })

  it('새로고침검증이끝나기전에는캐시된관리자권한을노출하지않는다', async () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ ...session, user: { ...user, role: 'ADMIN' } }))
    window.history.replaceState(null, '', '/#/account')
    let resolve!: (value: Response) => void
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    render(<App />)
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
    await act(async () => resolve(response(user)))
    expect(await screen.findByText('일반 회원')).toBeInTheDocument()
  })

  it('새로고침중정지확인시토큰을삭제하고사유일시를보여준다', async () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
    vi.mocked(fetch).mockResolvedValueOnce(response({ code: 'ACCOUNT_SUSPENDED', message: '정지된 계정입니다.', suspension: { reason: '신고 확인 후 이용 제한', suspendedAt: '2026-11-19T00:00:00Z' } }, 403))
    render(<App />)
    expect(await screen.findByRole('alert', { name: '계정 정지 안내' })).toHaveTextContent('신고 확인 후 이용 제한')
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
  })

  it('새로고침중만료변조토큰을거절하면저장값을제거한다', async () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
    vi.mocked(fetch).mockResolvedValueOnce(response({ code: 'UNAUTHENTICATED', message: '다시 로그인해 주세요.' }, 401))
    render(<App />)
    expect(await screen.findByRole('button', { name: '로그인' })).toBeInTheDocument()
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  })

  it.each([
    JSON.stringify({ accessToken: 'invalid', expiresAt: '잘못된 시각' }),
    JSON.stringify({ accessToken: 'expired', expiresAt: '2000-01-01T00:00:00Z' }),
    '손상된 JSON',
  ])('손상되거나만료된저장값은복원하지않는다 (%#)', async stored => {
    sessionStorage.setItem(SESSION_KEY, stored)
    render(<App />)
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('StrictMode에서도내정보확인후복원한다', async () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
    window.history.replaceState(null, '', '/#/account')
    render(<StrictMode><App /></StrictMode>)
    expect(await screen.findByRole('heading', { name: '내 계정' })).toBeInTheDocument()
    expect(screen.getByText(user.email)).toBeInTheDocument()
  })

  it('글조회에서정지응답을받아도안내배너와로그아웃을적용한다', async () => {
    vi.mocked(fetch).mockImplementation(async input => {
      if (input === '/api/auth/login') return response(session)
      if (input === '/api/games') return response([])
      return response({ code: 'ACCOUNT_SUSPENDED', message: '정지된 계정입니다.', suspension: { reason: '로그인 이후 계정 정지', suspendedAt: '2026-11-19T04:00:00Z' } }, 403)
    })
    render(<App />); const actor = await fillForm()
    await actor.click(screen.getByRole('button', { name: '로그인' }))
    expect(await screen.findByRole('alert', { name: '계정 정지 안내' })).toHaveTextContent('로그인 이후 계정 정지')
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  })

  it('새로고침검증중서버에연결하지못하면로그인을복원하지않는다', async () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('연결 실패'))
    render(<App />)
    expect(await screen.findByText(/서버에 연결할 수 없습니다/)).toBeInTheDocument()
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
  })
})
