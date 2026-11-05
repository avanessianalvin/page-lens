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
