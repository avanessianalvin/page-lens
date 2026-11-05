<script setup lang="ts">
// One metric over time for one post (metrics differ in scale by orders of magnitude, so they are never overlaid).
import { computed } from 'vue'
import type { Schemas } from '@/api/client'
import LineChart from '@/components/charts/LineChart.vue'
import { formatDateTime, formatNumber } from '@/format'

const props = defineProps<{ snapshots: Schemas['Snapshot'][]; metric: keyof Schemas['Snapshot']; label: string }>()

const points = computed<[number, number][]>(() =>
  props.snapshots
    .filter((s) => s.capturedAt && s[props.metric] != null)
    .map((s) => [new Date(s.capturedAt!).getTime(), s[props.metric] as number]),
)
</script>

<template>
  <LineChart :points="points" :label="label" :format="(v) => formatNumber(v)"
    :format-time="(t) => formatDateTime(new Date(t).toISOString())" :height="260" />
</template>
