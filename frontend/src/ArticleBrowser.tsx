import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiError } from './api'
import type { ArticleDetail, ArticlePage, Game } from './articles'
import { koreanDate, trustedThumbnail } from './articles'
import SteamGamePicker from './SteamGamePicker'
import type { SteamGame } from './SteamGamePicker'

function readRoute() {
  const url = new URL(window.location.hash.slice(1) || '/articles', window.location.origin)
  const match = url.pathname.match(/^\/articles\/([1-9]\d*)$/)
  const pageText = url.searchParams.get('page') ?? '0'
  const appId = url.searchParams.get('steamAppId') ?? ''
  const steamAppId = /^[1-9]\d{0,9}$/.test(appId) && Number(appId) <= 4294967295 ? appId : ''
  return {
    id: match?.[1] ?? null,
    invalid: !['/', '/articles'].includes(url.pathname) && !match,
    page: /^\d{1,6}$/.test(pageText) ? Number(pageText) : 0,
    gameId: steamAppId ? '' : (url.searchParams.get('gameId') ?? ''),
    steamAppId,
    gameName: (url.searchParams.get('gameName') ?? '').slice(0, 150).trim(),
    q: (url.searchParams.get('q') ?? '').slice(0, 100),
    search: url.search,
  }
}

function originalLink(value: string) {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : null }
  catch { return null }
}

export default function ArticleBrowser({ token, onSessionExpired }: { token?: string; onSessionExpired: (failure: ApiError) => void }) {
  const [route, setRoute] = useState(readRoute)
  const [query, setQuery] = useState(route.q)
  const [games, setGames] = useState<Game[]>([])
  const [showSteamGames, setShowSteamGames] = useState(false)
  const [list, setList] = useState<ArticlePage | null>(null)
  const [detail, setDetail] = useState<ArticleDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<ApiError | null>(null)
  const [retry, setRetry] = useState(0)

  useEffect(() => {
    const update = () => setRoute(readRoute())
    window.addEventListener('hashchange', update)
    return () => window.removeEventListener('hashchange', update)
  }, [])

  useEffect(() => { setQuery(route.q) }, [route.q])

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(null); setList(null); setDetail(null)
    async function load() {
      try {
        if (route.invalid) throw new ApiError(404, 'ARTICLE_NOT_FOUND', '글을 찾을 수 없습니다.')
        if (route.id) {
          const article = await api<ArticleDetail>(`/articles/${route.id}`, { token, signal: controller.signal })
          if (!controller.signal.aborted) setDetail(article)
        } else {
          const params = new URLSearchParams({ page: String(route.page), size: '10', q: route.q })
          if (route.gameId) params.set('gameId', route.gameId)
          if (route.steamAppId) {
            params.set('steamAppId', route.steamAppId)
            if (route.gameName) params.set('gameName', route.gameName)
          }
          const articles = await api<ArticlePage>(`/articles?${params}`, { token, signal: controller.signal })
          if (controller.signal.aborted) return
          const availableGames = await api<Game[]>('/games', { token, signal: controller.signal })
          if (!controller.signal.aborted) { setList(articles); setGames(availableGames) }
        }
      } catch (caught) {
        if (controller.signal.aborted) return
        const failure = caught as ApiError
        if (token && (failure.status === 401 || failure.code === 'ACCOUNT_SUSPENDED')) onSessionExpired(failure)
        else setError(failure)
      } finally { if (!controller.signal.aborted) setLoading(false) }
    }
    void load()
    return () => controller.abort()
  }, [token, route.id, route.invalid, route.page, route.gameId, route.steamAppId, route.gameName, route.q, retry, onSessionExpired])

  function listUrl(page: number, q = route.q, filter: { gameId?: string; steamAppId?: string; gameName?: string } = route) {
    const params = new URLSearchParams()
    if (page > 0) params.set('page', String(page))
    if (q.trim()) params.set('q', q.trim())
    if (filter.gameId) params.set('gameId', filter.gameId)
    if (filter.steamAppId) {
      params.set('steamAppId', filter.steamAppId)
      if (filter.gameName) params.set('gameName', filter.gameName.slice(0, 150))
    }
    return '#/articles' + (params.size ? `?${params}` : '')
  }

  function search(event: FormEvent) { event.preventDefault(); window.location.hash = listUrl(0, query) }
  function selectSteamGame(game: SteamGame) {
    setShowSteamGames(false)
    window.location.hash = listUrl(0, route.q, { steamAppId: String(game.steamAppId), gameName: game.name })
  }

  function changeGame(value: string) {
    setShowSteamGames(false)
    const filter = value.startsWith('steam-')
      ? { steamAppId: value.slice(6), gameName: route.gameName }
      : { gameId: value }
    window.location.hash = listUrl(0, route.q, filter)
  }

  const link = detail ? originalLink(detail.originalUrl) : null
  const steamGame = games.find(game => String(game.steamAppId) === route.steamAppId)
  const remoteGame = route.steamAppId && !steamGame
  const selectedGame = remoteGame ? `steam-${route.steamAppId}` : (steamGame ? String(steamGame.id) : route.gameId)
  const searched = Boolean(route.q || route.gameId || route.steamAppId)
  const pageMissing = list && list.totalPages > 0 && list.page >= list.totalPages
  return <main className="news-layout">
    <div className="news-heading"><div><span className="eyebrow">게임 뉴스</span><h1>{route.id ? '게임의 다음 이야기' : '새로운 소식을 만나보세요'}</h1><p>관심 있는 게임의 소식을 한곳에서 확인하세요.</p></div><span className="news-mark" aria-hidden="true">G.</span></div>
    {!route.id && !route.invalid && <form className="news-filters" onSubmit={search} role="search">
      <div className="news-search"><label htmlFor="article-search">제목 검색</label><div><input id="article-search" type="search" placeholder="궁금한 소식을 검색해 보세요" maxLength={100} value={query} onChange={event => setQuery(event.target.value)} /><button type="submit" className="filter-button">검색</button></div></div>
      <div className="game-filter">
        <label htmlFor="game-filter">게임</label>
        <div className="game-filter-actions">
          <select id="game-filter" value={selectedGame} onChange={event => changeGame(event.target.value)}>
            <option value="">전체 게임</option>
            {games.map(game => <option key={game.id} value={game.id}>{game.name}</option>)}
            {remoteGame && <option value={selectedGame}>{route.gameName || `Steam 게임 ${route.steamAppId}`}</option>}
          </select>
          <button type="button" className="filter-button" aria-expanded={showSteamGames} aria-controls={showSteamGames ? 'steam-picker' : undefined} onClick={() => setShowSteamGames(value => !value)}>Steam 전체 게임 검색</button>
        </div>
      </div>
    </form>}
    {showSteamGames && !route.id && !route.invalid && <SteamGamePicker onSelect={selectSteamGame} onClose={() => setShowSteamGames(false)} />}
    {loading && <p className="news-state news-loading" role="status"><span className="news-spinner" aria-hidden="true" />글을 불러오는 중입니다…</p>}
    {error && <div className="news-state">
      {error.status === 404 && <h2>글을 볼 수 없습니다</h2>}
      <p className="error" role="alert">{error.message || '글을 불러오지 못했습니다. 다시 시도해 주세요.'}</p>
      {error.status !== 404 && <button className="filter-button" onClick={() => setRetry(value => value + 1)}>다시 시도</button>}
      {(route.id || route.invalid) && <a className="text-button" href={listUrl(route.page)}>목록으로 돌아가기</a>}
    </div>}
    {!loading && list && <>
      <div className="list-summary"><h2>공개된 글 <span>{list.totalElements}</span></h2><span>최신 발행순</span></div>
      {pageMissing ? <div className="news-state"><h2>이 페이지에는 글이 없습니다</h2><a href={listUrl(0)} className="text-button">첫 페이지로</a></div> : list.items.length === 0 ? <div className="news-state"><h2>{route.steamAppId && !route.q ? '이 게임의 뉴스가 아직 없습니다' : searched ? '검색 결과가 없습니다' : '아직 공개된 글이 없습니다'}</h2><p>{searched ? '다른 검색어나 게임을 선택해 보세요.' : '새로운 소식이 준비되면 이곳에 표시됩니다.'}</p>{(searched || route.page > 0) && <a href="#/articles" className="text-button">전체 글 보기</a>}</div> : <div className="article-grid">{list.items.map(article => <article className="article-card" key={article.id}>
        {trustedThumbnail(article.thumbnailUrl) && <img key={article.thumbnailUrl} className="article-thumbnail" src={trustedThumbnail(article.thumbnailUrl)!} alt="" loading="lazy" decoding="async" referrerPolicy="no-referrer" onError={event => { event.currentTarget.hidden = true }} />}
        <div className="article-card-meta"><span className={article.game ? 'game-label' : 'game-label unclassified'}>{article.game?.name ?? '미분류'}</span></div>
        <h3><a href={`#/articles/${article.id}${route.search}`}>{article.title}</a></h3><p>{article.excerpt}</p><div className="article-card-footer"><span>{article.source.name}</span><time dateTime={article.publishedAt}>{koreanDate(article.publishedAt)}</time></div>
      </article>)}</div>}
      {!pageMissing && list.totalPages > 1 && <nav className="pagination" aria-label="글 목록 페이지">
        {list.page > 0 ? <a href={listUrl(list.page - 1)}>이전</a> : <span aria-disabled="true">이전</span>}
        <span aria-current="page">{list.page + 1} / {list.totalPages}</span>
        {list.page + 1 < list.totalPages ? <a href={listUrl(list.page + 1)}>다음</a> : <span aria-disabled="true">다음</span>}
      </nav>}
    </>}
    {!loading && detail && <>
      <a href={listUrl(route.page)} className="back-to-list">← 글 목록으로</a>
      <article className="article-detail">
        {trustedThumbnail(detail.thumbnailUrl) && <img key={detail.thumbnailUrl} className="detail-thumbnail" src={trustedThumbnail(detail.thumbnailUrl)!} alt="" loading="lazy" decoding="async" referrerPolicy="no-referrer" onError={event => { event.currentTarget.hidden = true }} />}
        <div className="article-card-meta"><span className={detail.game ? 'game-label' : 'game-label unclassified'}>{detail.game?.name ?? '미분류'}</span></div>
        <h2>{detail.title}</h2><div className="detail-meta"><span>{detail.source.name}</span><time dateTime={detail.publishedAt}>{koreanDate(detail.publishedAt)} (한국 시간)</time></div>
        <div className="article-body">{detail.body}</div>
        {link && <a href={link} target="_blank" rel="noopener noreferrer" className="original-link">원문 보기 ↗</a>}
      </article>
    </>}
  </main>
}
