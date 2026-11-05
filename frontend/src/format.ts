import { useSessionStore } from '@/stores/session'

/** Timestamps are stored in UTC and shown in the owner's time zone (from /api/settings). */
export function formatDateTime(iso?: string | null): string {
  if (!iso) return '—'
  const tz = useSessionStore().timeZone
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: tz }).format(new Date(iso))
}

export function formatDate(iso?: string | null): string {
  if (!iso) return '—'
  const tz = useSessionStore().timeZone
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: tz }).format(new Date(iso))
}

/** NULL means "not returned by Instagram": shown as a dash, never as 0. */
export function formatNumber(n?: number | null): string {
  return n == null ? '—' : new Intl.NumberFormat().format(n)
}

export function formatDuration(ms?: number | null): string {
  if (ms == null) return '—'
  return ms < 60_000 ? `${(ms / 1000).toFixed(1)} s` : `${Math.floor(ms / 60_000)} m ${Math.round((ms % 60_000) / 1000)} s`
}

export function relativeDays(iso?: string | null): string {
  if (!iso) return 'never'
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000)
  if (days <= 0) return 'today'
  return days === 1 ? 'yesterday' : `${days} days ago`
}

/** Engagement rate is a 0..1 ratio from the API. */
export function formatRate(r?: number | null): string {
  return r == null ? '—' : `${(r * 100).toFixed(1)} %`
}

/** "+12" / "−3" / "0"; dash when unknown. */
export function formatDelta(n?: number | null): string {
  if (n == null) return '—'
  return n > 0 ? `+${new Intl.NumberFormat().format(n)}` : n < 0 ? `−${new Intl.NumberFormat().format(-n)}` : '0'
}

/** Change of `now` vs `before` as a signed percentage, or null when not computable. */
export function pctChange(now?: number | null, before?: number | null): string | null {
  if (now == null || before == null || before === 0) return null
  const pct = ((now - before) / before) * 100
  return `${pct >= 0 ? '+' : '−'}${Math.abs(pct).toFixed(0)} %`
}

export const PERIODS = [
  { label: 'Last 7 days', value: 'last_7_days' },
  { label: 'Last 30 days', value: 'last_30_days' },
  { label: 'Last 90 days', value: 'last_90_days' },
  { label: 'Last 365 days', value: 'last_365_days' },
  { label: 'All time', value: 'all' },
]

export const FORMAT_LABELS: Record<string, string> = {
  REELS: 'Reels', CAROUSEL: 'Carousels', IMAGE: 'Photos', VIDEO: 'Feed videos',
}

export const PRODUCT_TYPES = [
  { label: 'All formats', value: null },
  { label: 'Reels', value: 'REELS' },
  { label: 'Feed posts', value: 'FEED' },
  { label: 'Carousels', value: 'CAROUSEL_ALBUM' },
  { label: 'Photos', value: 'IMAGE' },
  { label: 'Stories', value: 'STORY' },
]

export const SORT_FIELDS = [
  { label: 'Posted', value: 'posted_at' },
  { label: 'Reach', value: 'reach' },
  { label: 'Views', value: 'views' },
  { label: 'Likes', value: 'likes' },
  { label: 'Comments', value: 'comments' },
  { label: 'Shares', value: 'shares' },
  { label: 'Saves', value: 'saves' },
  { label: 'Follows', value: 'follows' },
  { label: 'Interactions', value: 'total_interactions' },
]
