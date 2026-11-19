import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

const user = { id: 1, email: 'member@example.com', role: 'USER', status: 'ACTIVE', createdAt: '2026-11-12T00:00:00Z' }
const session = { accessToken: 'memory-only-token', tokenType: 'Bearer', expiresAt: new Date(Date.now() + 1800000).toISOString(), user }
const response = (body: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  vi.stubGlobal('fetch', vi.fn(async (input: string) => response(input.startsWith('/api/articles')
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

  it('keeps credentials in memory, authenticates /me, and logs out', async () => {
    render(<App />)
    const actor = await login()
    expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0)
    vi.mocked(fetch).mockResolvedValueOnce(response(user))
    await actor.click(screen.getByRole('button', { name: '내 정보 새로 확인' }))
    expect(await screen.findByRole('status')).toHaveTextContent('서버에서 현재 회원 정보')
    expect(fetch).toHaveBeenLastCalledWith('/api/me', expect.objectContaining({ headers: { Authorization: 'Bearer memory-only-token' } }))
    await actor.click(screen.getByRole('button', { name: '로그아웃' }))
    expect(await screen.findByRole('button', { name: '로그인' })).toBeInTheDocument()
    expect(screen.queryByText('member@example.com')).not.toBeInTheDocument()
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

  it('does not recover a token after refresh / remount', async () => {
    const view = render(<App />); await login(); view.unmount()
    render(<App />)
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
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
})
