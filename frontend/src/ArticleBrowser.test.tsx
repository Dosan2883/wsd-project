import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ArticleBrowser from './ArticleBrowser'
import App from './App'
import { readSession, rememberSession } from './session'

const game = { id: 1, name: 'Dota 2', steamAppId: 570 }
const summary = { id: 1, title: 'Dota 2 소식', excerpt: '목록 발췌', game, source: { code: 'steam', name: 'Steam' }, publishedAt: '2026-11-17T03:00:00Z', demo: false }
const article = { ...summary, body: '저장한 본문\n<script>실행하면 안 됩니다</script>', originalUrl: 'https://store.steampowered.com/app/570/', createdAt: '2026-11-17T03:00:00Z' }
const list = { items: [summary], page: 0, size: 10, totalElements: 1, totalPages: 1 }
const user = { id: 1, email: 'reader@example.com', role: 'USER', status: 'ACTIVE', createdAt: '2026-11-12T00:00:00Z' }
const session = { accessToken: 'test-token', tokenType: 'Bearer', expiresAt: new Date(Date.now() + 1800000).toISOString(), user }
const reply = (body: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response
const expired = vi.fn()

beforeEach(() => {
  sessionStorage.clear()
  window.history.replaceState(null, '', '/#/articles')
  expired.mockReset()
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    if (input === '/api/auth/login') return reply(session)
    if (input === '/api/games') return reply([game])
    if (input === '/api/articles/1') return reply(article)
    return reply(list)
  }))
})
afterEach(() => vi.unstubAllGlobals())

function show() { return render(<ArticleBrowser token="test-token" onSessionExpired={expired} />) }

describe('로그인 후 저장한 글 열람', () => {
  it('로그인 성공 직후 서버의 글 목록을 불러오고 상세를 연다', async () => {
    window.history.replaceState(null, '', '/#/login')
    render(<App />)
    const actor = userEvent.setup()
    await actor.type(screen.getByLabelText('이메일'), 'reader@example.com')
    await actor.type(screen.getByLabelText('비밀번호'), 'valid-password')
    await actor.click(screen.getByRole('button', { name: '로그인' }))
    await actor.click(await screen.findByRole('link', { name: summary.title }))
    expect(await screen.findByText(/저장한 본문/)).toHaveTextContent("<script>실행하면 안 됩니다</script>")
    expect(screen.getByRole('heading', { name: article.title })).toBeInTheDocument()
    expect(document.querySelector('.article-body script')).toBeNull()
    expect(fetch).toHaveBeenCalledWith('/api/articles/1', expect.objectContaining({ headers: { Authorization: 'Bearer test-token' } }))
    expect(screen.getByRole('link', { name: '원문 보기 ↗' })).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('검색과게임필터를서버에전달하고페이지를초기화한다', async () => {
    window.history.replaceState(null, '', '/#/articles?page=2')
    show(); await screen.findByRole('link', { name: summary.title })
    const actor = userEvent.setup()
    await actor.type(screen.getByLabelText('제목 검색'), '패치 100%')
    await actor.click(screen.getByRole('button', { name: '검색' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.stringContaining('q=%ED%8C%A8%EC%B9%98+100%25'), expect.anything()))
    await actor.selectOptions(screen.getByLabelText('게임'), '1')
    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith('/api/games', expect.anything()))
    const articleCalls = vi.mocked(fetch).mock.calls.filter(([path]) => String(path).startsWith('/api/articles?'))
    const lastUrl = new URL(String(articleCalls.at(-1)![0]), 'http://localhost')
    expect(lastUrl.searchParams.get('page')).toBe('0')
    expect(lastUrl.searchParams.get('gameId')).toBe('1')
    expect(lastUrl.searchParams.get('q')).toBe('패치 100%')
  })

  it('상세에서돌아와도검색조건을유지한다', async () => {
    window.history.replaceState(null, '', '/#/articles?q=Dota&gameId=1')
    show(); const actor = userEvent.setup()
    await actor.click(await screen.findByRole('link', { name: summary.title }))
    await screen.findByText(/저장한 본문/)
    await actor.click(screen.getByRole('link', { name: '← 글 목록으로' }))
    expect(await screen.findByLabelText('제목 검색')).toHaveValue('Dota')
    expect(screen.getByLabelText('게임')).toHaveValue('1')
  })

  it('페이지이동을서버에전달한다', async () => {
    vi.mocked(fetch).mockImplementation(async input => {
      if (String(input) === '/api/games') return reply([game])
      const page = new URL(String(input), 'http://localhost').searchParams.get('page') === '1' ? 1 : 0
      return reply({ ...list, page, totalPages: 2, totalElements: 11, items: [{ ...summary, title: page ? '다음 페이지 글' : summary.title }] })
    })
    show(); const actor = userEvent.setup()
    await actor.click(await screen.findByRole('link', { name: '다음' }))
    expect(await screen.findByRole('link', { name: '다음 페이지 글' })).toBeInTheDocument()
    expect(screen.getByText('2 / 2')).toBeInTheDocument()
  })

  it('빈목록과통신실패후재시도를안내한다', async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('연결 실패'))
    show(); const actor = userEvent.setup()
    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결할 수 없습니다')
    vi.mocked(fetch).mockImplementation(async input => reply(String(input) === '/api/games' ? [game] : { ...list, items: [], totalElements: 0, totalPages: 0 }))
    await actor.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('heading', { name: '아직 공개된 글이 없습니다' })).toBeInTheDocument()
  })

  it('숨김또는없는글의상세주소에오류와목록링크를보여준다', async () => {
    window.history.replaceState(null, '', '/#/articles/9?gameId=1&page=2&q=Dota')
    vi.mocked(fetch).mockResolvedValueOnce(reply({ code: 'ARTICLE_NOT_FOUND', message: '글을 찾을 수 없거나 공개되지 않은 글입니다.' }, 404))
    show()
    expect(await screen.findByRole('alert')).toHaveTextContent('공개되지 않은 글')
    expect(screen.getByRole('heading', { name: '글을 볼 수 없습니다' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '목록으로 돌아가기' })).toHaveAttribute('href', '#/articles?page=2&q=Dota&gameId=1')
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })

  it('만료된인증이면다시로그인하도록요청한다', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(reply({ code: 'UNAUTHENTICATED', message: '다시 로그인해 주세요.' }, 401))
    show()
    await waitFor(() => expect(expired).toHaveBeenCalledWith(expect.objectContaining({ message: '다시 로그인해 주세요.', status: 401 })))
  })

  it('상세의위험한외부주소를링크로만들지않는다', async () => {
    window.history.replaceState(null, '', '/#/articles/1')
    vi.mocked(fetch).mockResolvedValueOnce(reply({ ...article, originalUrl: 'javascript:alert(1)' }))
    show(); await screen.findByRole('heading', { name: article.title })
    expect(screen.queryByRole('link', { name: /원문 보기/ })).not.toBeInTheDocument()
  })

  it('화면을나간뒤도착한글응답을반영하지않는다', async () => {
    let resolve!: (value: Response) => void
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    window.history.replaceState(null, '', '/#/articles/1')
    const view = show(); view.unmount()
    await act(async () => resolve(reply(article)))
    expect(screen.queryByText(article.title)).not.toBeInTheDocument()
    expect(expired).not.toHaveBeenCalled()
  })

  it('검증된Steam썸네일을표시하고실패한이미지는숨긴다', async () => {
    vi.mocked(fetch).mockImplementation(async input => reply(String(input) === '/api/games' ? [game] : { ...list, items: [{ ...summary, thumbnailUrl: 'https://clan.fastly.steamstatic.com/images/1/test.jpg' }] }))
    const view = show(); await screen.findByRole('link', { name: summary.title })
    const image = view.container.querySelector('img')!
    expect(image).toHaveAttribute('referrerpolicy', 'no-referrer')
    fireEvent.error(image)
    expect(image).toHaveAttribute('hidden')
  })
})

describe('비회원 뉴스 열람', () => {
  it('Steam 상점에서 게임을 선택하면 먼저 기사를 수집하고 새 게임으로 드롭다운을 갱신한다', async () => {
    const terraria = { id: 2, name: 'Terraria', steamAppId: 105600 }
    const fresh = { ...summary, game: terraria, title: 'Terraria 업데이트' }
    let registered = false
    vi.mocked(fetch).mockImplementation(async input => {
      if (input === '/api/games') return reply(registered ? [game, terraria] : [game])
      if (String(input).startsWith('/api/games/steam?')) {
        const params = new URL(String(input), 'http://localhost').searchParams
        const page = Number(params.get('page'))
        return reply({
          items: [{ name: page ? 'Terraria' : '다른 게임', steamAppId: page ? 105600 : 12345 }],
          page, hasNext: page === 0, totalElements: 26,
        })
      }
      if (String(input).includes('steamAppId=105600')) {
        registered = true
        return reply({ ...list, items: [fresh] })
      }
      return reply(String(input).includes('gameId=2') ? { ...list, items: [fresh] } : list)
    })
    const view = render(<App />)
    const actor = userEvent.setup()
    await actor.click(screen.getByRole('button', { name: 'Steam 전체 게임 검색' }))
    await screen.findByRole('button', { name: '다른 게임' })
    await actor.type(screen.getByLabelText('게임 이름'), 'Terraria')
    await actor.click(screen.getByRole('button', { name: '게임 검색' }))
    await actor.click(await screen.findByRole('button', { name: '다음 게임' }))
    expect(fetch).toHaveBeenCalledWith('/api/games/steam?q=Terraria&page=1', expect.objectContaining({ headers: {} }))
    await actor.click(await screen.findByRole('button', { name: 'Terraria' }))
    expect(await screen.findByRole('link', { name: fresh.title })).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/api/articles?page=0&size=10&q=&steamAppId=105600&gameName=Terraria', expect.anything())
    expect(screen.getByLabelText('게임')).toHaveValue('2')
    await actor.selectOptions(screen.getByLabelText('게임'), '1')
    await screen.findByRole('link', { name: summary.title })
    await actor.selectOptions(screen.getByLabelText('게임'), '2')
    await screen.findByRole('link', { name: fresh.title })
    expect(fetch).toHaveBeenCalledWith('/api/articles?page=0&size=10&q=&gameId=2', expect.anything())
    view.unmount()
    render(<App />)
    expect(await screen.findByRole('link', { name: fresh.title })).toBeInTheDocument()
    expect(screen.getByLabelText('게임')).toHaveDisplayValue('Terraria')
  })

  it('첫 화면에서 인증 없이 목록과 상세를 조회한다', async () => {
    window.history.replaceState(null, '', '/')
    render(<App />)
    expect(screen.getByRole('status')).toHaveTextContent('글을 불러오는 중')
    expect(screen.getByRole('link', { name: '로그인' })).toHaveAttribute('href', '#/login')
    await userEvent.click(await screen.findByRole('link', { name: summary.title }))
    expect(await screen.findByRole('heading', { name: article.title, level: 2 })).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/api/articles?page=0&size=10&q=', expect.objectContaining({ headers: {} }))
    expect(fetch).toHaveBeenCalledWith('/api/games', expect.objectContaining({ headers: {} }))
    expect(fetch).toHaveBeenCalledWith('/api/articles/1', expect.objectContaining({ headers: {} }))
  })

  it('검색·게임·페이지를 주소에서 복원하고 만료된 인증으로 상세 주소를 잃지 않는다', async () => {
    window.history.replaceState(null, '', '/#/articles?page=1&q=Dota&gameId=1')
    vi.mocked(fetch).mockImplementation(async input => {
      if (input === '/api/games') return reply([game])
      if (input === '/api/me') return reply({ code: 'UNAUTHENTICATED', message: '다시 로그인해 주세요.' }, 401)
      if (input === '/api/articles/1') return reply(article)
      return reply({ ...list, page: 1, totalPages: 2 })
    })
    const view = render(<App />)
    expect(await screen.findByText('2 / 2')).toBeInTheDocument()
    expect(screen.getByLabelText('제목 검색')).toHaveValue('Dota')
    expect(screen.getByLabelText('게임')).toHaveValue('1')
    expect(fetch).toHaveBeenCalledWith('/api/articles?page=1&size=10&q=Dota&gameId=1', expect.objectContaining({ headers: {} }))
    await userEvent.click(screen.getByRole('link', { name: summary.title }))
    await screen.findByRole('heading', { name: article.title, level: 2 })
    const detailUrl = window.location.hash
    view.unmount()
    rememberSession(session)
    render(<App />)
    expect(await screen.findByRole('heading', { name: article.title, level: 2 })).toBeInTheDocument()
    expect(window.location.hash).toBe(detailUrl)
    expect(readSession()).toBeNull()
  })
})
