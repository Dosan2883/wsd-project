import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiError } from './api'
import type { Session, Suspension, User } from './api'
import ArticleBrowser from './ArticleBrowser'
import { forgetSession, readSession, rememberSession } from './session'
import { koreanDate } from './articles'

type Page = 'login' | 'signup' | 'account' | 'admin' | 'articles'
function initialPage(): Page {
  const path = window.location.hash.slice(2).split('?')[0]
  if (path === 'articles' || path.startsWith('articles/')) return 'articles'
  return ['signup', 'account', 'admin'].includes(path) ? path as Page : 'login'
}

export default function App() {
  const [page, setPage] = useState<Page>(initialPage)
  const [session, setSession] = useState<Session | null>(null)
  const [storedSession] = useState(readSession)
  const [restoring, setRestoring] = useState(Boolean(storedSession))
  const [suspension, setSuspension] = useState<Suspension | null>(null)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [pending, setPending] = useState(false)
  const [adminMessage, setAdminMessage] = useState('')
  const requestRef = useRef<AbortController | null>(null)

  const navigate = useCallback((next: Page) => {
    requestRef.current?.abort()
    setPending(false)
    setPage(next)
    window.location.hash = `/${next}`
    setPassword('')
    setError('')
    setFields({})
    setAdminMessage('')
  }, [])

  const clearSession = useCallback((message: string) => {
    forgetSession()
    setSession(null)
    setSuspension(null)
    navigate('login')
    setNotice(message)
  }, [navigate])

  const handleAuthFailure = useCallback((failure: ApiError) => {
    clearSession(failure.message)
    if (failure.code === 'ACCOUNT_SUSPENDED') {
      setSuspension(failure.suspension ?? { reason: '자세한 정지 사유는 관리자에게 문의해 주세요.', suspendedAt: null })
    }
  }, [clearSession])

  useEffect(() => {
    if (!storedSession) return
    const controller = new AbortController()
    async function restoreSession() {
      try {
        const user = await api<User>('/me', { token: storedSession!.accessToken, signal: controller.signal })
        if (controller.signal.aborted) return
        if (Date.parse(storedSession!.expiresAt) <= Date.now()) {
          clearSession('로그인이 만료되었습니다. 다시 로그인해 주세요.')
          return
        }
        setSession({ ...storedSession!, tokenType: 'Bearer', user })
        const currentPage = initialPage()
        if (currentPage === 'login' || currentPage === 'signup') navigate('articles')
      } catch (caught) {
        if (!controller.signal.aborted) handleAuthFailure(caught as ApiError)
      } finally { if (!controller.signal.aborted) setRestoring(false) }
    }
    void restoreSession()
    return () => controller.abort()
  }, [storedSession, clearSession, handleAuthFailure, navigate])

  useEffect(() => {
    const onHashChange = () => {
      const next = initialPage()
      requestRef.current?.abort()
      setPending(false)
      setPassword('')
      setError('')
      setFields({})
      setPage(next)
    }
    window.addEventListener('hashchange', onHashChange)
    return () => { window.removeEventListener('hashchange', onHashChange); requestRef.current?.abort() }
  }, [])

  useEffect(() => {
    if (!session) return
    const timeout = window.setTimeout(() => clearSession('로그인이 만료되었습니다. 다시 로그인해 주세요.'), Math.max(0, Date.parse(session.expiresAt) - Date.now()))
    return () => window.clearTimeout(timeout)
  }, [session, clearSession])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (pending) return
    setError(''); setNotice(''); setFields({})
    if (new TextEncoder().encode(password).length > 72) { setFields({ password: '비밀번호는 UTF-8 기준 72바이트 이하로 입력해 주세요.' }); return }
    setPending(true)
    const controller = new AbortController()
    requestRef.current = controller
    try {
      if (page === 'signup') {
        await api<User>('/auth/signup', { method: 'POST', body: { email, password }, signal: controller.signal })
        if (controller.signal.aborted) return
        navigate('login')
        setNotice('회원가입이 완료되었습니다. 새 계정으로 로그인해 주세요.')
      } else {
        const result = await api<Session>('/auth/login', { method: 'POST', body: { email, password }, signal: controller.signal })
        if (controller.signal.aborted) return
        setSession(result)
        rememberSession(result)
        setSuspension(null)
        navigate('articles')
      }
    } catch (caught) {
      if (controller.signal.aborted) return
      const failure = caught as ApiError
      if (failure.code === 'ACCOUNT_SUSPENDED') { handleAuthFailure(failure); return }
      setError(failure.message)
      setFields(failure.fields ?? {})
    } finally { if (!controller.signal.aborted) setPending(false) }
  }

  async function checkAccess(admin: boolean) {
    if (!session || pending) return
    setPending(true); setError(''); setNotice(''); setAdminMessage('')
    const controller = new AbortController()
    requestRef.current = controller
    try {
      if (admin) {
        const result = await api<{ message: string }>('/admin/access', { token: session.accessToken, signal: controller.signal })
        if (!controller.signal.aborted) setAdminMessage(result.message)
      } else {
        const user = await api<User>('/me', { token: session.accessToken, signal: controller.signal })
        if (!controller.signal.aborted) { setSession({ ...session, user }); setNotice('서버에서 현재 회원 정보를 확인했습니다.') }
      }
    } catch (caught) {
      if (controller.signal.aborted) return
      const failure = caught as ApiError
      if (failure.status === 401 || failure.code === 'ACCOUNT_SUSPENDED') handleAuthFailure(failure)
      else setError(failure.message)
    } finally { if (!controller.signal.aborted) setPending(false) }
  }

  const needsLogin = (page === 'account' || page === 'admin' || page === 'articles') && !session
  const form = page === 'login' || page === 'signup' || needsLogin
  const signup = page === 'signup'
  return <div className="shell">
    <header className="header">
      <a href={session ? '#/articles' : '#/login'} className="brand" aria-label="GAMELOG 홈"><span className="brand-icon">G</span> GAMELOG<span className="brand-dot">.</span></a>
      <span className="header-caption">게임의 다음 소식을 만나는 곳</span>
      {session && <nav className="header-nav" aria-label="회원 메뉴"><a className="text-button" href="#/articles" aria-current={page === 'articles' ? 'page' : undefined}>글 목록</a><a className="text-button" href="#/account" aria-current={page === 'account' ? 'page' : undefined}>내 계정</a><button className="text-button" onClick={() => clearSession('로그아웃되었습니다.')}>로그아웃</button></nav>}
    </header>
    {suspension && <section className="suspension-banner" role="alert" aria-label="계정 정지 안내"><h2>계정 이용이 정지되었습니다</h2><dl><div><dt>정지 사유</dt><dd>{suspension.reason}</dd></div><div><dt>정지 일시</dt><dd>{suspension.suspendedAt && Number.isFinite(Date.parse(suspension.suspendedAt)) ? `${koreanDate(suspension.suspendedAt)} (한국 시간)` : '과거 정지 일시가 등록되어 있지 않습니다.'}</dd></div></dl></section>}
    {restoring ? <main className="news-layout"><p className="news-state" role="status">서버에서 로그인 정보를 확인하는 중입니다…</p></main> : page === 'articles' && session ? <ArticleBrowser token={session.accessToken} onSessionExpired={handleAuthFailure} /> : <main className="layout">
      <aside className="intro">
        <span className="eyebrow">YOUR NEXT CHAPTER</span>
        <h1>좋아하는 게임의<br />다음 이야기를<br /><span>함께 만나세요.</span></h1>
        <p>게임 소식을 읽고, 생각을 나누고.<br />나만의 게임 기록을 시작하는 첫걸음.</p>
        <div className="orbit" aria-hidden="true"><div className="orbit-ring" /><span className="orbit-center">G</span><span className="orbit-tag one">NEWS</span><span className="orbit-tag two">COMMUNITY</span><span className="orbit-star">✦</span></div>
        <div className="intro-footer"><span className="status-dot" /> GAME NEWS & COMMUNITY</div>
      </aside>
      <section className="panel" aria-labelledby="panel-title">
        <div className="step-label">ACCOUNT / {signup ? 'JOIN' : page === 'admin' ? 'ADMIN' : session ? 'PROFILE' : 'SIGN IN'}</div>
        <h2 id="panel-title">{form ? signup ? '계정을 만들어 보세요' : '다시 만나 반가워요' : page === 'admin' ? '관리자 접근 확인' : '내 계정'}</h2>
        <p className="subtitle">{form ? signup ? '이메일로 간단하게 시작하세요.' : '로그인하고 GAMELOG를 시작하세요.' : '현재 계정의 인증 정보를 확인할 수 있어요.'}</p>
        {notice && <p className="notice" role="status">{notice}</p>}
        {needsLogin && <p className="notice">로그인이 필요합니다.</p>}
        {error && <p className="error" role="alert">{error}</p>}
        {form ? <>
          <form onSubmit={submit}>
            <div className="field"><label htmlFor="email">이메일</label><input id="email" type="email" autoComplete="email" placeholder="you@example.com" value={email} maxLength={254} required disabled={pending} onChange={e => setEmail(e.target.value)} aria-invalid={!!fields.email} aria-describedby={fields.email ? 'email-error' : undefined} />{fields.email && <span className="field-error" id="email-error">{fields.email}</span>}</div>
            <div className="field"><label htmlFor="password">비밀번호</label><input id="password" type="password" autoComplete={signup ? 'new-password' : 'current-password'} placeholder="8자 이상 입력해 주세요" value={password} minLength={8} maxLength={72} required disabled={pending} onChange={e => setPassword(e.target.value)} aria-invalid={!!fields.password} aria-describedby="password-help password-error" /><span className="field-help" id="password-help">8자 이상 · UTF-8 기준 최대 72바이트</span>{fields.password && <span className="field-error" id="password-error">{fields.password}</span>}</div>
            <button className="primary-button" disabled={pending} type="submit">{pending ? '처리 중…' : signup ? '회원가입' : '로그인'}<span aria-hidden="true">↗</span></button>
          </form>
          <p className="switch-form">{signup ? '이미 계정이 있으신가요?' : '아직 계정이 없으신가요?'} <button className="text-button" onClick={() => { navigate(signup ? 'login' : 'signup'); setNotice('') }}>{signup ? '로그인' : '회원가입'}</button></p>
        </> : session && <>
          <dl className="profile"><div><dt>이메일</dt><dd>{session.user.email}</dd></div><div><dt>권한</dt><dd><span className="badge">{session.user.role === 'ADMIN' ? '관리자' : '일반 회원'}</span></dd></div><div><dt>계정 상태</dt><dd>{session.user.status === 'ACTIVE' ? '활성' : '정지'}</dd></div><div><dt>가입일</dt><dd>{new Date(session.user.createdAt).toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })}</dd></div></dl>
          {page === 'admin' ? <><p className="field-help">서버에서 현재 관리자 권한을 검사합니다.</p><button className="primary-button" disabled={pending} onClick={() => checkAccess(true)}>{pending ? '확인 중…' : '관리자 권한 확인'}<span aria-hidden="true">↗</span></button>{adminMessage && <p className="notice" role="status">{adminMessage}</p>}<button className="text-button back" onClick={() => navigate('account')}>내 계정으로 돌아가기</button></> : <><button className="primary-button" disabled={pending} onClick={() => checkAccess(false)}>{pending ? '확인 중…' : '내 정보 새로 확인'}<span aria-hidden="true">↻</span></button><button className="text-button back" onClick={() => { navigate('admin'); setNotice('') }}>관리자 페이지</button></>}
        </>}
        <div className="session-note"><span aria-hidden="true">◈</span><p>로그인은 현재 탭에서 유지되며, 새로고침 시 서버에서 다시 확인합니다.<br />인증이 만료되면 다시 로그인해 주세요.</p></div>
      </section>
    </main>}
    <footer className="footer"><span>© {new Date().getFullYear()} GAMELOG</span><span>게임을 좋아하는 우리를 위한 공간</span></footer>
  </div>
}
