// Chart tokens from the dataviz reference palette (validated light mode, card surface #ffffff).
// Charts are single-hue by design: one series per chart, one axis. Text never wears the data color.

export const SERIES = '#2a78d6' // categorical slot 1
export const SURFACE = '#ffffff'
export const GRID = '#e1e0d9' // hairline gridline
export const AXIS = '#c3c2b7' // baseline / axis
export const MUTED = '#898781' // axis labels, and marks for unreliable (small-sample) values
export const INK = '#0b0b0b' // primary text
export const INK_2 = '#52514e' // secondary text

/** Sequential blue ramp 100→700 for heatmaps (lightest = near zero). */
export const SEQUENTIAL = ['#cde2fb', '#9ec5f4', '#6da7ec', '#3987e5', '#256abf', '#184f95', '#0d366b']

export function compact(v: number) {
  return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(v)
}

export function percent(v: number, digits = 1) {
  return `${(v * 100).toFixed(digits)} %`
}

export const tooltipBase = {
  backgroundColor: SURFACE,
  borderColor: GRID,
  textStyle: { color: INK },
  extraCssText: 'box-shadow: 0 2px 8px rgba(0,0,0,.08);',
}

/** Tooltip row: a short line key in the series color, then the value (strong), then the label. */
export function tooltipRow(color: string, value: string, label: string) {
  return `<span style="display:inline-block;width:10px;height:2px;background:${color};vertical-align:middle;margin-right:6px"></span>`
    + `<b>${value}</b> <span style="color:${INK_2}">${label}</span>`
}

/** Escapes text placed into tooltip HTML (captions and city names are user data). */
export function esc(s: string) {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
}
