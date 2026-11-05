<script setup lang="ts">
// Weekday × hour heatmap: sequential single-hue blue ramp (light = low, dark = high), per-cell tooltip with the
// post count. Empty cells (no posts) stay blank; single-post cells carry a dot so "unreliable" isn't color alone.
import { computed } from 'vue'
import { use } from 'echarts/core'
import { HeatmapChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, VisualMapComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import { GRID, INK, INK_2, MUTED, SEQUENTIAL, SERIES, SURFACE, tooltipBase, tooltipRow } from './theme'

use([HeatmapChart, GridComponent, TooltipComponent, VisualMapComponent, SVGRenderer])

export interface Cell {
  weekday: number // 1 = Monday … 7 = Sunday
  hour: number // 0-23
  posts: number
  value: number
  smallSample: boolean
}

const props = withDefaults(defineProps<{
  cells: Cell[]
  label: string
  format?: (v: number) => string
}>(), { format: (v: number) => new Intl.NumberFormat().format(v) })

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
const HOURS = Array.from({ length: 24 }, (_, h) => String(h).padStart(2, '0'))

const max = computed(() => Math.max(...props.cells.map((c) => c.value), 0))

const option = computed(() => ({
  animation: false,
  grid: { left: 44, right: 16, top: 8, bottom: 64 },
  tooltip: {
    ...tooltipBase,
    trigger: 'item',
    formatter: (p: { data: [number, number, number, number, boolean] }) => {
      const [h, d, v, n, small] = p.data
      return `<div style="color:${INK_2}">${DAYS[d]} ${HOURS[h]}:00–${HOURS[h]}:59</div>`
        + tooltipRow(SERIES, props.format(v), props.label)
        + `<div style="color:${MUTED}">${n} post${n === 1 ? '' : 's'}${small ? ' · small sample' : ''}</div>`
    },
  },
  xAxis: {
    type: 'category',
    data: HOURS,
    axisTick: { show: false },
    axisLine: { lineStyle: { color: GRID } },
    axisLabel: { color: MUTED, interval: 1 },
    splitArea: { show: false },
  },
  yAxis: {
    type: 'category',
    data: DAYS,
    inverse: true,
    axisTick: { show: false },
    axisLine: { show: false },
    axisLabel: { color: INK },
  },
  visualMap: {
    min: 0,
    max: max.value || 1,
    calculable: false,
    orient: 'horizontal',
    left: 'center',
    bottom: 0,
    itemWidth: 12,
    itemHeight: 160,
    text: ['high', 'low'],
    textStyle: { color: MUTED },
    inRange: { color: SEQUENTIAL },
    formatter: (v: number) => props.format(v),
  },
  series: [{
    type: 'heatmap',
    data: props.cells.map((c) => [c.hour, c.weekday - 1, c.value, c.posts, c.smallSample]),
    itemStyle: { borderColor: SURFACE, borderWidth: 2, borderRadius: 3 },
    label: {
      show: true,
      color: INK,
      fontSize: 10,
      formatter: (p: { data: [number, number, number, number, boolean] }) => (p.data[4] ? '•' : ''),
    },
    emphasis: { itemStyle: { borderColor: INK, borderWidth: 1 } },
  }],
}))
</script>

<template>
  <VChart v-if="cells.length" :option="option" autoresize style="height: 300px; width: 100%" :aria-label="label" />
  <p v-else class="pl-muted">No posts with this metric in the period.</p>
</template>
