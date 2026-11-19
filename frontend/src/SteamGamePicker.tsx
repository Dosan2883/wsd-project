import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiError } from './api'

export type SteamGame = { steamAppId: number; name: string }
type StorePage = { items: SteamGame[]; page: number; totalElements: number; hasNext: boolean }

export default function SteamGamePicker({ onSelect, onClose }: {
  onSelect: (game: SteamGame) => void
  onClose: () => void
}) {
  const [input, setInput] = useState('')
  const [search, setSearch] = useState({ q: '', page: 0 })
  const [result, setResult] = useState<StorePage | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    const params = new URLSearchParams({ q: search.q, page: String(search.page) })
    api<StorePage>(`/games/steam?${params}`, { signal: controller.signal }).then(page => {
      if (!controller.signal.aborted) setResult(page)
    }).catch((failure: unknown) => {
      if (controller.signal.aborted) return
      setError(failure instanceof ApiError ? failure.message : 'Steam 게임 목록을 불러오지 못했습니다.')
    })
    return () => controller.abort()
  }, [search])

  function load(q: string, page: number) {
    setResult(null)
    setError('')
    setSearch({ q, page })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    load(input.trim(), 0)
  }

  return <section className="steam-picker" id="steam-picker" aria-labelledby="steam-picker-title">
    <div className="steam-picker-heading">
      <h2 id="steam-picker-title">Steam 게임 찾기</h2>
      <button type="button" className="text-button" onClick={onClose}>닫기</button>
    </div>
    <form onSubmit={submit} role="search" aria-label="Steam 게임 검색">
      <label htmlFor="steam-game-name">게임 이름</label>
      <div className="steam-picker-search">
        <input id="steam-game-name" type="search" value={input} maxLength={100} onChange={event => setInput(event.target.value)} />
        <button type="submit" className="filter-button">게임 검색</button>
      </div>
    </form>
    {error ? <div className="steam-picker-status">
      <p className="error" role="alert">{error}</p>
      <button type="button" className="filter-button" onClick={() => load(search.q, search.page)}>게임 목록 다시 시도</button>
    </div> : !result ? <p className="steam-picker-status" role="status">Steam 게임을 불러오는 중입니다…</p> : <>
      {result.items.length > 0 ? <ul className="steam-picker-results" aria-label="Steam 게임 목록">
        {result.items.map(game => <li key={game.steamAppId}>
          <button type="button" onClick={() => onSelect(game)}>{game.name}</button>
        </li>)}
      </ul> : <p className="steam-picker-status" role="status">이 페이지에 표시할 게임이 없습니다.</p>}
      {(result.page > 0 || result.hasNext) && <nav className="pagination steam-picker-pagination" aria-label="Steam 게임 목록 페이지">
        <button type="button" className="filter-button" disabled={result.page === 0} onClick={() => load(search.q, result.page - 1)}>이전 게임</button>
        <span aria-current="page">{result.page + 1} 페이지</span>
        <button type="button" className="filter-button" disabled={!result.hasNext} onClick={() => load(search.q, result.page + 1)}>다음 게임</button>
      </nav>}
    </>}
  </section>
}
