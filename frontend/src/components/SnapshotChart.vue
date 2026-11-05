<script setup lang="ts">
// One metric over time for one post. A single series on one axis (metrics differ in scale by orders of
// magnitude, so they are never overlaid); pick the metric above the chart. Specs: 2px line, 8px markers with a
// 2px surface ring, hairline solid grid, axis-crosshair tooltip, value labelled at the line end only.
import { computed } from 'vue'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import type { Schemas } from '@/api/client'
import { formatDateTime, formatNumber } from '@/format'

use([LineChart, GridComponent, TooltipComponent, SVGRenderer])

const props = defineProps<{ snapshots: Schemas['Snapshot'][]; metric: keyof Schemas['Snapshot']; label: string }>()

const SERIES = '#2a78d6' // categorical slot 1, validated vs the white card surface
const SURFACE = '#ffffff'
const GRID = '#e1e0d9'
const AXIS = '#c3c2b7'
const MUTED = '#898781'
const INK = '#0b0b0b'

const points = computed(() =>
  props.snapshots
    .filter((s) => s.capturedAt && s[props.metric] != null)
    .map((s) => [new Date(s.capturedAt!).getTime(), s[props.metric] as number]),
)

const option = computed(() => ({
  animation: false,
  grid: { left: 56, right: 72, top: 16, bottom: 32 },
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'line', lineStyle: { color: AXIS, width: 1 } },
    backgroundColor: SURFACE,
    borderColor: GRID,
    textStyle: { color: INK },
    formatter: (params: { value: [number, number] }[]) => {
      const [t, v] = params[0]!.value
      return `<div style="color:${MUTED}">${formatDateTime(new Date(t).toISOString())}</div>`
        + `<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${SERIES};margin-right:6px"></span>`
        + `${props.label}: <b>${formatNumber(v)}</b>`
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
    min: 0,
    axisLine: { show: false },
    axisLabel: { color: MUTED, formatter: (v: number) => new Intl.NumberFormat(undefined, { notation: 'compact' }).format(v) },
    splitLine: { lineStyle: { color: GRID, width: 1, type: 'solid' } },
  },
  series: [{
    type: 'line',
    data: points.value,
    lineStyle: { width: 2, color: SERIES, cap: 'round', join: 'round' },
    itemStyle: { color: SERIES, borderColor: SURFACE, borderWidth: 2 },
    symbol: 'circle',
    symbolSize: 8,
    showSymbol: true,
    emphasis: { scale: 1.4 },
    // Direct label at the line end only, in text ink (never the series color).
    label: {
      show: true,
      position: 'right',
      color: INK,
      formatter: (p: { dataIndex: number; value: [number, number] }) =>
        p.dataIndex === points.value.length - 1 ? formatNumber(p.value[1]) : '',
    },
  }],
}))
</script>

<template>
  <div>
    <VChart v-if="points.length > 1" :option="option" autoresize class="pl-chart" :aria-label="`${label} over time`" />
    <p v-else class="pl-muted">
      {{ points.length === 1 ? 'Only one snapshot so far; the trend appears after the next sync.' : `No ${label.toLowerCase()} data returned for this post.` }}
    </p>
  </div>
</template>

<style scoped>
.pl-chart {
  height: 260px;
  width: 100%;
}
</style>
