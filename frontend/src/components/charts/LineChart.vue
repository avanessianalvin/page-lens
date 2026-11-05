<script setup lang="ts">
// One series over time on one axis: 2px line, 8px markers with a 2px surface ring, hairline solid grid,
// crosshair tooltip, value labelled at the line end only.
import { computed } from 'vue'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import { AXIS, GRID, INK, MUTED, SERIES, SURFACE, compact, esc, tooltipBase, tooltipRow } from './theme'

use([LineChart, GridComponent, TooltipComponent, SVGRenderer])

const props = withDefaults(defineProps<{
  points: [number, number][] // [epoch ms, value]
  label: string
  format?: (v: number) => string
  formatTime?: (t: number) => string
  height?: number
  beginAtZero?: boolean
}>(), {
  format: (v: number) => new Intl.NumberFormat().format(v),
  formatTime: (t: number) => new Date(t).toLocaleString(),
  height: 240,
  beginAtZero: true,
})

const option = computed(() => ({
  animation: false,
  grid: { left: 56, right: 72, top: 16, bottom: 32 },
  tooltip: {
    ...tooltipBase,
    trigger: 'axis',
    axisPointer: { type: 'line', lineStyle: { color: AXIS, width: 1 } },
    formatter: (params: { value: [number, number] }[]) => {
      const [t, v] = params[0]!.value
      return `<div style="color:${MUTED}">${esc(props.formatTime(t))}</div>${tooltipRow(SERIES, props.format(v), esc(props.label))}`
    },
  },
  xAxis: {
    type: 'time',
    axisLine: { lineStyle: { color: AXIS } },
    axisTick: { show: false },
    axisLabel: { color: MUTED, hideOverlap: true },
    splitLine: { show: false },
  },
  yAxis: {
    type: 'value',
    min: props.beginAtZero ? 0 : 'dataMin',
    scale: !props.beginAtZero,
    axisLine: { show: false },
    axisLabel: { color: MUTED, formatter: compact },
    splitLine: { lineStyle: { color: GRID, width: 1, type: 'solid' } },
  },
  series: [{
    type: 'line',
    data: props.points,
    lineStyle: { width: 2, color: SERIES, cap: 'round', join: 'round' },
    itemStyle: { color: SERIES, borderColor: SURFACE, borderWidth: 2 },
    symbol: 'circle',
    symbolSize: 8,
    showSymbol: props.points.length <= 45,
    label: {
      show: true,
      position: 'right',
      color: INK,
      formatter: (p: { dataIndex: number; value: [number, number] }) =>
        p.dataIndex === props.points.length - 1 ? props.format(p.value[1]) : '',
    },
  }],
}))
</script>

<template>
  <VChart v-if="points.length > 1" :option="option" autoresize :style="{ height: `${height}px`, width: '100%' }"
    :aria-label="`${label} over time`" />
  <p v-else class="pl-muted">
    {{ points.length === 1 ? 'Only one data point so far; the trend appears after more syncs.' : `No ${label.toLowerCase()} data yet.` }}
  </p>
</template>
