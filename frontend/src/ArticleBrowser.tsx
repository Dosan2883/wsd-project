import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiError } from './api'
import type { ArticleDetail, ArticlePage, Game } from './articles'
import { koreanDate } from './articles'

function readRoute() {
  const url = new URL(window.location.hash.slice(1) || '/articles', window.location.origin)
  const match = url.pathname.match(/^\/articles\/([1-9]\d*)$/)
  const pageText = url.searchParams.get('page') ?? '0'
  return {
    id: match?.[1] ?? null,
    invalid: url.pathname !== '/articles' && !match,
    page: /^\d{1,6}$/.test(pageText) ? Number(pageText) : 0,
    gameId: url.searchParams.get('gameId') ?? '',
    q: (url.searchParams.get('q') ?? '').slice(0, 100),
    search: url.search,
  }
}

function originalLink(value: string) {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : null }
  catch { return null }
}

export default function ArticleBrowser({ token, onSessionExpired }: { token: string; onSessionExpired: (failure: ApiError) => void }) {
  const [route, setRoute] = useState(readRoute)
  const [query, setQuery] = useState(route.q)
  const [games, setGames] = useState<Game[]>([])
  const [list, setList] = useState<ArticlePage | null>(null)
  const [detail, setDetail] = useState<ArticleDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)

  useEffect(() => {
    const update = () => setRoute(readRoute())
    window.addEventListener('hashchange', update)
    return () => window.removeEventListener('hashchange', update)
  }, [])

  useEffect(() => { setQuery(route.q) }, [route.q])

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setList(null); setDetail(null)
    async function load() {
      try {
        if (route.invalid) throw new ApiError(404, 'ARTICLE_NOT_FOUND', '글을 찾을 수 없습니다.')
        if (route.id) {
          const article = await api<ArticleDetail>(`/articles/${route.id}`, { token, signal: controller.signal })
          if (!controller.signal.aborted) setDetail(article)
        } else {
          const params = new URLSearchParams({ page: String(route.page), size: '10', q: route.q })
          if (route.gameId) params.set('gameId', route.gameId)
          const [articles, availableGames] = await Promise.all([
            api<ArticlePage>(`/articles?${params}`, { token, signal: controller.signal }),
            api<Game[]>('/games', { token, signal: controller.signal }),
          ])
          if (!controller.signal.aborted) { setList(articles); setGames(availableGames) }
        }
      } catch (caught) {
        if (controller.signal.aborted) return
        const failure = caught as ApiError
        if (failure.status === 401 || failure.code === 'ACCOUNT_SUSPENDED') onSessionExpired(failure)
        else setError(failure.message || '글을 불러오지 못했습니다. 다시 시도해 주세요.')
      } finally { if (!controller.signal.aborted) setLoading(false) }
    }
    void load()
    return () => controller.abort()
  }, [token, route.id, route.invalid, route.page, route.gameId, route.q, retry, onSessionExpired])

  function listUrl(page: number, q = route.q, gameId = route.gameId) {
    const params = new URLSearchParams()
    if (page > 0) params.set('page', String(page))
    if (q.trim()) params.set('q', q.trim())
    if (gameId) params.set('gameId', gameId)
    return '#/articles' + (params.size ? `?${params}` : '')
  }

  function search(event: FormEvent) { event.preventDefault(); window.location.hash = listUrl(0, query) }
  const link = detail ? originalLink(detail.originalUrl) : null
  return <main className="news-layout">
    <div className="news-heading"><div><span className="eyebrow">게임 뉴스</span><h1>{route.id ? '게임의 다음 이야기' : '새로운 소식을 만나보세요'}</h1><p>관심 있는 게임의 소식을 한곳에서 확인하세요.</p></div><span className="news-mark" aria-hidden="true">G.</span></div>
    {!route.id && !route.invalid && <form className="news-filters" onSubmit={search} role="search">
      <div className="news-search"><label htmlFor="article-search">제목 검색</label><div><input id="article-search" type="search" placeholder="궁금한 소식을 검색해 보세요" maxLength={100} value={query} onChange={event => setQuery(event.target.value)} /><button type="submit" className="filter-button">검색</button></div></div>
      <div className="game-filter"><label htmlFor="game-filter">게임</label><select id="game-filter" value={route.gameId} onChange={event => { window.location.hash = listUrl(0, route.q, event.target.value) }}><option value="">전체 게임</option>{games.map(game => <option key={game.id} value={game.id}>{game.name}</option>)}</select></div>
    </form>}
    {loading && <p className="news-state" role="status">글을 불러오는 중입니다…</p>}
    {error && <div className="news-state"><p className="error" role="alert">{error}</p><button className="filter-button" onClick={() => setRetry(value => value + 1)}>다시 시도</button>{(route.id || route.invalid) && <a className="text-button" href={listUrl(route.page)}>목록으로 돌아가기</a>}</div>}
    {!loading && list && <>
      <div className="list-summary"><h2>공개된 글 <span>{list.totalElements}</span></h2><span>최신 발행순</span></div>
      {list.items.some(article => article.demo) && <p className="demo-notice">데모 표시가 있는 글은 열람 기능을 확인하기 위한 예시이며, 실제 수집한 뉴스가 아닙니다.</p>}
      {list.items.length === 0 ? <div className="news-state"><h2>{route.q || route.gameId ? '검색 결과가 없습니다' : '아직 공개된 글이 없습니다'}</h2><p>{route.q || route.gameId ? '다른 검색어나 게임을 선택해 보세요.' : '새로운 소식이 준비되면 이곳에 표시됩니다.'}</p>{(route.q || route.gameId || route.page > 0) && <a href="#/articles" className="text-button">전체 글 보기</a>}</div> : <div className="article-grid">{list.items.map(article => <article className="article-card" key={article.id}>
        <div className="article-card-meta"><span className="game-label">{article.game?.name ?? '미분류'}</span>{article.demo && <span className="demo-badge">데모</span>}</div>
        <h3><a href={`#/articles/${article.id}${route.search}`}>{article.title}</a></h3><p>{article.excerpt}</p><div className="article-card-footer"><span>{article.source.name}</span><time dateTime={article.publishedAt}>{koreanDate(article.publishedAt)}</time></div>
      </article>)}</div>}
      {list.totalPages > 1 && <nav className="pagination" aria-label="글 목록 페이지">
        {list.page > 0 ? <a href={listUrl(list.page - 1)}>이전</a> : <span aria-disabled="true">이전</span>}
        <span aria-current="page">{list.page + 1} / {list.totalPages}</span>
        {list.page + 1 < list.totalPages ? <a href={listUrl(list.page + 1)}>다음</a> : <span aria-disabled="true">다음</span>}
      </nav>}
    </>}
    {!loading && detail && <>
      <a href={listUrl(route.page)} className="back-to-list">← 글 목록으로</a>
      <article className="article-detail">
        <div className="article-card-meta"><span className="game-label">{detail.game?.name ?? '미분류'}</span>{detail.demo && <span className="demo-badge">데모</span>}</div>
        <h2>{detail.title}</h2><div className="detail-meta"><span>{detail.source.name}</span><time dateTime={detail.publishedAt}>{koreanDate(detail.publishedAt)} (한국 시간)</time></div>
        {detail.demo && <p className="demo-notice">열람 기능 확인용 데모 콘텐츠입니다. 실제 뉴스나 실제 기사 원문이 아닙니다.</p>}
        <div className="article-body">{detail.body}</div>
        {link && <a href={link} target="_blank" rel="noopener noreferrer" className="original-link">{detail.demo ? '게임의 Steam 페이지 보기' : '원문 보기'} ↗</a>}
      </article>
    </>}
  </main>
}
