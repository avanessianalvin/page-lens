// Chart tokens from the dataviz reference palette, one set per mode, both validated against the card surface
// they sit on (light #ffffff, dark #18181b = PrimeVue Aura's dark content background).
// Charts are single-hue by design: one series per chart, one axis. Text never wears the data color.
import { computed } from 'vue'
import { useThemeStore } from '@/stores/theme'

export interface ChartTokens {
  SERIES: string // categorical slot 1
  SURFACE: string // card surface, used for marker rings and tooltip background
  GRID: string // hairline gridline
  AXIS: string // baseline / axis
  MUTED: string // axis labels; marks for unreliable (small-sample) values
  INK: string // primary text
  INK_2: string // secondary text
  SEQUENTIAL: string[] // low → high; in dark mode the low end recedes into the dark surface
}

const LIGHT: ChartTokens = {
  SERIES: '#2a78d6',
  SURFACE: '#ffffff',
  GRID: '#e1e0d9',
  AXIS: '#c3c2b7',
  MUTED: '#898781',
  INK: '#0b0b0b',
  INK_2: '#52514e',
  SEQUENTIAL: ['#cde2fb', '#9ec5f4', '#6da7ec', '#3987e5', '#256abf', '#184f95', '#0d366b'],
}

const DARK: ChartTokens = {
  SERIES: '#3987e5',
  SURFACE: '#18181b',
  GRID: '#2c2c2a',
  AXIS: '#383835',
  MUTED: '#898781',
  INK: '#f0efec',
  INK_2: '#c3c2b7',
  SEQUENTIAL: ['#0d366b', '#184f95', '#256abf', '#3987e5', '#6da7ec', '#9ec5f4', '#cde2fb'],
}

/** Reactive tokens for the current theme. */
export function useChartTokens() {
  const theme = useThemeStore()
  return computed(() => (theme.dark ? DARK : LIGHT))
}

export function compact(v: number) {
  return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(v)
}

export function tooltipBase(t: ChartTokens) {
  return {
    backgroundColor: t.SURFACE,
    borderColor: t.GRID,
    textStyle: { color: t.INK },
    extraCssText: 'box-shadow: 0 2px 8px rgba(0,0,0,.12);',
  }
}

/** Tooltip row: a short line key in the series color, then the value (strong), then the label. */
export function tooltipRow(t: ChartTokens, color: string, value: string, label: string) {
  return `<span style="display:inline-block;width:10px;height:2px;background:${color};vertical-align:middle;margin-right:6px"></span>`
    + `<b>${value}</b> <span style="color:${t.INK_2}">${label}</span>`
}

/** Escapes text placed into tooltip HTML (captions and city names are user data). */
export function esc(s: string) {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
}
