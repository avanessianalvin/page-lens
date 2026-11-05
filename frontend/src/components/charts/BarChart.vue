<script setup lang="ts">
// Ranked horizontal bars, one hue, one baseline: bars <= 24px thick with a 4px rounded data end, the value at
// the bar tip in text ink, a per-bar hover tooltip. Bars for unreliable (small-sample) values are drawn in the
// muted gray and say so in their label and tooltip, so the flag never relies on color alone.
import { computed } from 'vue'
import { use } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import { GRID, INK, INK_2, MUTED, SERIES, esc, tooltipBase, tooltipRow } from './theme'

use([BarChart, GridComponent, TooltipComponent, SVGRenderer])

export interface Bar {
  label: string
  value: number | null | undefined
  /** Shown under the label, e.g. "12 posts". */
  detail?: string
  smallSample?: boolean
}

const props = withDefaults(defineProps<{
  bars: Bar[]
  label: string // what the value is, for tooltips and the accessible name
  format?: (v: number) => string
  /** Keep the given order (ordinal categories such as age) instead of ranking largest first. */
  keepOrder?: boolean
}>(), {
  format: (v: number) => new Intl.NumberFormat().format(v),
  keepOrder: false,
})

const rows = computed(() => {
  const withValues = props.bars.filter((b) => b.value != null) as (Bar & { value: number })[]
  const sorted = props.keepOrder ? withValues : [...withValues].sort((a, b) => b.value - a.value)
  return sorted.reverse() // ECharts draws category axis bottom-up
})

const height = computed(() => Math.max(80, rows.value.length * 34 + 24))

const option = computed(() => ({
  animation: false,
  grid: { left: 8, right: 64, top: 4, bottom: 4, containLabel: true },
  tooltip: {
    ...tooltipBase,
    trigger: 'item',
    formatter: (p: { dataIndex: number }) => {
      const r = rows.value[p.dataIndex]!
      return `<div style="color:${INK_2}">${esc(r.label)}${r.detail ? ` · ${esc(r.detail)}` : ''}</div>`
        + tooltipRow(r.smallSample ? MUTED : SERIES, props.format(r.value), esc(props.label))
        + (r.smallSample ? `<div style="color:${MUTED}">Small sample: unreliable</div>` : '')
    },
  },
  xAxis: {
    type: 'value',
    min: 0,
    axisLabel: { show: false },
    splitLine: { lineStyle: { color: GRID, width: 1, type: 'solid' } },
  },
  yAxis: {
    type: 'category',
    data: rows.value.map((r) => r.label),
    axisTick: { show: false },
    axisLine: { lineStyle: { color: GRID } },
    axisLabel: {
      color: INK,
      width: 160,
      overflow: 'truncate',
      formatter: (v: string, i: number) => {
        const r = rows.value[i]
        return r?.detail ? `${v}\n{d|${r.detail}${r.smallSample ? ' · small sample' : ''}}` : v
      },
      rich: { d: { color: MUTED, fontSize: 11 } },
    },
  },
  series: [{
    type: 'bar',
    barMaxWidth: 24,
    data: rows.value.map((r) => ({
      value: r.value,
      itemStyle: { color: r.smallSample ? MUTED : SERIES, borderRadius: [0, 4, 4, 0] },
    })),
    label: { show: true, position: 'right', color: INK, formatter: (p: { value: number }) => props.format(p.value) },
  }],
}))
</script>

<template>
  <VChart v-if="rows.length" :option="option" autoresize :style="{ height: `${height}px`, width: '100%' }"
    :aria-label="label" />
  <p v-else class="pl-muted">No data.</p>
</template>
