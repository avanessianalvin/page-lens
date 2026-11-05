<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Message from 'primevue/message'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import Tag from 'primevue/tag'
import { api, call, type Schemas } from '@/api/client'
import AccountPicker from '@/components/AccountPicker.vue'
import BarChart, { type Bar } from '@/components/charts/BarChart.vue'
import HeatmapChart, { type Cell } from '@/components/charts/HeatmapChart.vue'
import { useAccountsStore } from '@/stores/accounts'
import { useSessionStore } from '@/stores/session'
import { FORMAT_LABELS, PERIODS, formatDateTime, formatNumber, formatRate } from '@/format'

const store = useAccountsStore()
const session = useSessionStore()
const router = useRouter()

const period = ref('last_90_days')
const error = ref<string | null>(null)

// ---- formats
type FormatMetric = 'avgReach' | 'avgEngagementRate' | 'avgViews' | 'avgSaves' | 'avgShares'
const FORMAT_METRICS: { label: string; value: FormatMetric }[] = [
  { label: 'Avg reach', value: 'avgReach' },
  { label: 'Avg engagement', value: 'avgEngagementRate' },
  { label: 'Avg views', value: 'avgViews' },
  { label: 'Avg saves', value: 'avgSaves' },
  { label: 'Avg shares', value: 'avgShares' },
]
const formats = ref<Schemas['FormatComparison'] | null>(null)
const formatMetric = ref<FormatMetric>('avgReach')
const formatTable = ref(false)

// ---- timing
const TIMING_METRICS = [
  { label: 'Engagement rate', value: 'engagement_rate' },
  { label: 'Reach', value: 'reach' },
  { label: 'Views', value: 'views' },
]
const timing = ref<Schemas['PostingTimes'] | null>(null)
const timingMetric = ref('engagement_rate')
const timingTable = ref(false)

// ---- captions
const captions = ref<Schemas['CaptionStats'] | null>(null)
const captionMetric = ref<'avgEngagementRate' | 'avgReach'>('avgEngagementRate')

// ---- ranking
const RANK_METRICS = [
  { label: 'Reach', value: 'reach' }, { label: 'Engagement rate', value: 'engagement_rate' },
  { label: 'Views', value: 'views' }, { label: 'Saves', value: 'saves' }, { label: 'Shares', value: 'shares' },
  { label: 'Follows', value: 'follows' }, { label: 'Interactions', value: 'total_interactions' },
]
const rankMetric = ref('reach')
const rankOrder = ref<'top' | 'bottom'>('top')
const ranked = ref<Schemas['RankedPosts'] | null>(null)

const isRate = (m: string) => m === 'engagement_rate' || m === 'avgEngagementRate'
const fmt = (m: string) => (v: number) => (isRate(m) ? formatRate(v) : formatNumber(Math.round(v)))

async function loadAll() {
  const id = store.selectedId
  if (!id) return
  error.value = null
  const q = { period: period.value }
  try {
    const [f, c] = await Promise.all([
      call(api.GET('/api/accounts/{id}/analysis/formats', { params: { path: { id }, query: q } })),
      call(api.GET('/api/accounts/{id}/analysis/captions', { params: { path: { id }, query: q } })),
    ])
    formats.value = f
    captions.value = c
    await Promise.all([loadTiming(), loadRanked()])
  } catch (e) {
    error.value = String(e)
  }
}

async function loadTiming() {
  const id = store.selectedId
  if (!id) return
  timing.value = await call(api.GET('/api/accounts/{id}/analysis/timing', {
    params: { path: { id }, query: { period: period.value, metric: timingMetric.value } },
  }))
}

async function loadRanked() {
  const id = store.selectedId
  if (!id) return
  ranked.value = await call(api.GET('/api/accounts/{id}/analysis/top-posts', {
    params: { path: { id }, query: { period: period.value, metric: rankMetric.value, order: rankOrder.value, count: 10 } },
  }))
}

const formatBars = computed<Bar[]>(() => (formats.value?.formats ?? []).map((f) => ({
  label: FORMAT_LABELS[f.format!] ?? f.format!,
  value: f[formatMetric.value],
  detail: `${f.postsWithInsights} of ${f.posts} posts with insights`,
  smallSample: f.smallSample,
})))

const cells = computed<Cell[]>(() => (timing.value?.cells ?? []).map((c) => ({
  weekday: c.weekday!, hour: c.hour!, posts: c.posts!, value: c.avgValue!, smallSample: !!c.smallSample,
})))

const DAYS = ['', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
const slotLabel = (s: Schemas['TimeSlot']) => `${DAYS[s.weekday!]} ${String(s.hour).padStart(2, '0')}:00`

const CAPTION_TITLES: Record<string, string> = {
  length: 'Caption length (characters)', script: 'Script / language', hashtags: 'Hashtags', cta: 'Call to action',
}
const SCRIPT_LABELS: Record<string, string> = {
  farsi: 'Farsi (Arabic script)', armenian: 'Armenian', latin: 'Latin', mixed: 'Mixed', none: 'No text',
}
const captionGroups = computed(() => {
  const groups: Record<string, Bar[]> = {}
  for (const b of captions.value?.buckets ?? []) {
    const name = b.dimension === 'script' ? (SCRIPT_LABELS[b.bucket!] ?? b.bucket!)
      : b.dimension === 'length' && b.bucket === 'none' ? 'No caption' : b.bucket!
    ;(groups[b.dimension!] ??= []).push({
      label: name, value: b[captionMetric.value], detail: `${b.posts} posts`, smallSample: b.smallSample,
    })
  }
  return groups
})

function caption(p: Schemas['PostStat']) {
  const c = (p.caption ?? '').trim()
  return c ? (c.length > 80 ? c.slice(0, 80) + '…' : c) : '(no caption)'
}

watch([() => store.selectedId, period], loadAll)
watch(timingMetric, loadTiming)
watch([rankMetric, rankOrder], loadRanked)
onMounted(async () => {
  if (store.items.length === 0) await store.load()
  loadAll()
})
</script>

<template>
  <main class="pl-page">
    <h1>Analysis</h1>
    <div class="pl-toolbar">
      <AccountPicker />
      <Select v-model="period" :options="PERIODS" option-label="label" option-value="value" aria-label="Period" />
      <span class="pl-muted">Based on posts published in the period, using each post's latest insights. Stories excluded.</span>
    </div>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <!-- Formats -->
    <section class="pl-card">
      <div class="pl-section-head">
        <h2>Format comparison</h2>
        <Select v-model="formatMetric" :options="FORMAT_METRICS" option-label="label" option-value="value" size="small" />
        <Button :label="formatTable ? 'Chart' : 'Table'" text size="small" @click="formatTable = !formatTable" />
      </div>
      <BarChart v-if="!formatTable" :bars="formatBars" :label="FORMAT_METRICS.find((m) => m.value === formatMetric)!.label"
        :format="fmt(formatMetric)" />
      <DataTable v-else :value="formats?.formats ?? []" size="small" striped-rows>
        <Column header="Format"><template #body="{ data }">{{ FORMAT_LABELS[data.format] ?? data.format }}</template></Column>
        <Column field="posts" header="Posts" class="pl-num" />
        <Column header="Avg reach" class="pl-num"><template #body="{ data }">{{ formatNumber(data.avgReach) }}</template></Column>
        <Column header="Median reach" class="pl-num"><template #body="{ data }">{{ formatNumber(data.medianReach) }}</template></Column>
        <Column header="Avg views" class="pl-num"><template #body="{ data }">{{ formatNumber(data.avgViews) }}</template></Column>
        <Column header="Avg engagement" class="pl-num"><template #body="{ data }">{{ formatRate(data.avgEngagementRate) }}</template></Column>
        <Column header="Avg saves" class="pl-num"><template #body="{ data }">{{ formatNumber(data.avgSaves) }}</template></Column>
        <Column header="Avg shares" class="pl-num"><template #body="{ data }">{{ formatNumber(data.avgShares) }}</template></Column>
        <Column header=""><template #body="{ data }"><span v-if="data.smallSample" class="pl-muted">small sample</span></template></Column>
      </DataTable>
      <p class="pl-muted pl-small">Engagement rate = interactions ÷ reach. Gray = fewer than 5 posts with insights.</p>
    </section>

    <!-- Timing -->
    <section class="pl-card">
      <div class="pl-section-head">
        <h2>Best posting times <span class="pl-muted pl-small">({{ session.timeZone }})</span></h2>
        <Select v-model="timingMetric" :options="TIMING_METRICS" option-label="label" option-value="value" size="small" />
        <Button :label="timingTable ? 'Chart' : 'Table'" text size="small" @click="timingTable = !timingTable" />
      </div>
      <div v-if="timing?.bestSlots?.length" class="pl-best">
        <span class="pl-muted">Best slots:</span>
        <Tag v-for="s in timing.bestSlots" :key="`${s.weekday}-${s.hour}`" severity="secondary"
          :value="`${slotLabel(s)} · ${fmt(timingMetric)(s.avgValue!)} · ${s.posts} posts`" />
      </div>
      <HeatmapChart v-if="!timingTable" :cells="cells" :label="TIMING_METRICS.find((m) => m.value === timingMetric)!.label"
        :format="fmt(timingMetric)" />
      <DataTable v-else :value="timing?.cells ?? []" size="small" striped-rows sort-field="avgValue" :sort-order="-1">
        <Column header="Slot" sortable sort-field="weekday"><template #body="{ data }">{{ slotLabel(data) }}</template></Column>
        <Column field="posts" header="Posts" sortable class="pl-num" />
        <Column field="avgValue" header="Average" sortable class="pl-num"><template #body="{ data }">{{ fmt(timingMetric)(data.avgValue) }}</template></Column>
      </DataTable>
      <p class="pl-muted pl-small">
        {{ timing?.posts ?? 0 }} posts. Darker = better on average. A dot marks a slot with a single post: too
        little to trust. This shows when past posts did well, not a guarantee.
      </p>
    </section>

    <!-- Captions -->
    <section class="pl-card">
      <div class="pl-section-head">
        <h2>Captions</h2>
        <SelectButton v-model="captionMetric" :options="[{ label: 'Avg engagement', value: 'avgEngagementRate' }, { label: 'Avg reach', value: 'avgReach' }]"
          option-label="label" option-value="value" size="small" :allow-empty="false" />
      </div>
      <div class="pl-captions">
        <div v-for="(barsFor, dim) in captionGroups" :key="dim">
          <h3>{{ CAPTION_TITLES[dim] ?? dim }}</h3>
          <BarChart :bars="barsFor" :label="captionMetric === 'avgReach' ? 'Avg reach' : 'Avg engagement'"
            :format="fmt(captionMetric)" :keep-order="dim === 'length' || dim === 'hashtags'" />
        </div>
      </div>
      <p class="pl-muted pl-small">Gray = fewer than 5 posts. Script is detected from the letters used. Call to action looks for phrases like "link in bio", "comment", "save" in English, Farsi and Armenian.</p>
    </section>

    <!-- Top / bottom posts -->
    <section class="pl-card">
      <div class="pl-section-head">
        <h2>{{ rankOrder === 'top' ? 'Top' : 'Bottom' }} posts</h2>
        <SelectButton v-model="rankOrder" :options="[{ label: 'Top', value: 'top' }, { label: 'Bottom', value: 'bottom' }]"
          option-label="label" option-value="value" size="small" :allow-empty="false" />
        <Select v-model="rankMetric" :options="RANK_METRICS" option-label="label" option-value="value" size="small" />
      </div>
      <DataTable :value="ranked?.posts ?? []" size="small" striped-rows row-hover
        @row-click="(e) => router.push(`/posts/${e.data.postId}`)">
        <Column header="Caption"><template #body="{ data }"><span class="pl-text" dir="auto">{{ caption(data) }}</span></template></Column>
        <Column header="Format"><template #body="{ data }">{{ FORMAT_LABELS[data.format] ?? data.format }}</template></Column>
        <Column header="Posted"><template #body="{ data }">{{ formatDateTime(data.postedAt) }}</template></Column>
        <Column header="Reach" class="pl-num"><template #body="{ data }">{{ formatNumber(data.reach) }}</template></Column>
        <Column header="Engagement" class="pl-num"><template #body="{ data }">{{ formatRate(data.engagementRate) }}</template></Column>
        <Column header="Saves" class="pl-num"><template #body="{ data }">{{ formatNumber(data.saves) }}</template></Column>
        <Column header="Shares" class="pl-num"><template #body="{ data }">{{ formatNumber(data.shares) }}</template></Column>
        <template #empty><span class="pl-muted">No posts with this metric in the period.</span></template>
      </DataTable>
      <p v-if="ranked?.excludedNoData" class="pl-muted pl-small">
        {{ ranked.excludedNoData }} of {{ ranked.consideredPosts }} posts have no value for this metric and are not ranked.
      </p>
    </section>
  </main>
</template>

<style scoped>
.pl-card {
  margin-bottom: 1rem;
}
.pl-section-head {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
}
.pl-section-head h2 {
  margin: 0;
  flex: 1;
  font-size: 1.05rem;
}
.pl-best {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 0.5rem;
}
.pl-captions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(380px, 1fr));
  gap: 1rem;
}
h3 {
  font-size: 0.9rem;
  margin: 0 0 0.25rem;
}
.pl-small {
  font-size: 0.8rem;
}
:deep(.p-datatable-tbody > tr) {
  cursor: pointer;
}
</style>
