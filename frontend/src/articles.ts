export type Game = { id: number; name: string; steamAppId: number }
export type ArticleSummary = {
  id: number; title: string; excerpt: string; game: Game | null
  source: { code: string; name: string }; publishedAt: string; demo: boolean; thumbnailUrl?: string | null
}
export type ArticleDetail = Omit<ArticleSummary, 'excerpt'> & { body: string; originalUrl: string; createdAt: string }
export type ArticlePage = { items: ArticleSummary[]; page: number; size: number; totalElements: number; totalPages: number }

export function koreanDate(value: string) {
  return new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short', hour12: false })
}

export function trustedThumbnail(value?: string | null) {
  if (!value) return null
  try {
    const url = new URL(value)
    const host = url.hostname.toLowerCase()
    const allowed = host === 'steamstatic.com' || host.endsWith('.steamstatic.com')
      || host === 'steamusercontent.com' || host.endsWith('.steamusercontent.com') || host === 'steamcdn-a.akamaihd.net'
    return allowed && url.protocol === 'https:' && !url.username && !url.password ? url.href : null
  } catch { return null }
}
