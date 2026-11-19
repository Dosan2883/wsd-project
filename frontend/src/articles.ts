export type Game = { id: number; name: string; steamAppId: number }
export type ArticleSummary = {
  id: number; title: string; excerpt: string; game: Game | null
  source: { code: string; name: string }; publishedAt: string; demo: boolean
}
export type ArticleDetail = Omit<ArticleSummary, 'excerpt'> & { body: string; originalUrl: string; createdAt: string }
export type ArticlePage = { items: ArticleSummary[]; page: number; size: number; totalElements: number; totalPages: number }

export function koreanDate(value: string) {
  return new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' })
}
