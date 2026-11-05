<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import Message from 'primevue/message'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import { api, call, type Schemas } from '@/api/client'
import AccountPicker from '@/components/AccountPicker.vue'
import LineChart from '@/components/charts/LineChart.vue'
import { useAccountsStore } from '@/stores/accounts'
import { formatDate, formatDateTime, formatDelta, formatNumber, formatRate, pctChange, relativeDays } from '@/format'

const store = useAccountsStore()
const overview = ref<Schemas['Overview'] | null>(null)
const growth = ref<Schemas['FollowerGrowth'] | null>(null)
const daily = ref<Schemas['DailyMetric'][]>([])
const days = ref(30)
const error = ref<string | null>(null)

const RANGES = [
  { label: 'Last 30 days', value: 30 },
  { label: 'Last 90 days', value: 90 },
]

async function load() {
  const id = store.selectedId
  if (!id) return
  error.value = null
  try {
    const to = new Date()
    const from = new Date(Date.now() - (days.value - 1) * 86_400_000)
    const iso = (d: Date) => d.toISOString().slice(0, 10) // insights days are UTC days
    const [o, g, d] = await Promise.all([
      call(api.GET('/api/accounts/{id}/overview', { params: { path: { id } } })),
      call(api.GET('/api/accounts/{id}/analysis/growth', { params: { path: { id }, query: { period: days.value === 30 ? 'last_30_days' : 'last_90_days' } } })),
      call(api.GET('/api/accounts/{id}/insights/daily', { params: { path: { id }, query: { from: iso(from), to: iso(to) } } })),
    ])
    overview.value = o
    growth.value = g
    daily.value = d
  } catch (e) {
    error.value = String(e)
  }
}

const dayTime = (day?: string) => (day ? new Date(`${day}T12:00:00Z`).getTime() : 0)
const followerPoints = computed<[number, number][]>(() =>
  (growth.value?.daily ?? []).filter((d) => d.followers != null).map((d) => [dayTime(d.day), d.followers!]))
const reachPoints = computed<[number, number][]>(() =>
  daily.value.filter((d) => d.reach != null).map((d) => [dayTime(d.day), d.reach!]))
const viewsPoints = computed<[number, number][]>(() =>
  daily.value.filter((d) => d.views != null).map((d) => [dayTime(d.day), d.views!]))

function caption(p: Schemas['PostStat']) {
  const c = (p.caption ?? '').trim()
  return c ? (c.length > 70 ? c.slice(0, 70) + '…' : c) : '(no caption)'
}

watch([() => store.selectedId, days], load)
onMounted(async () => {
  if (store.items.length === 0) await store.load()
  load()
})
</script>

<template>
  <main class="pl-page">
    <h1>Overview</h1>
    <div class="pl-toolbar">
      <AccountPicker />
      <Select v-model="days" :options="RANGES" option-label="label" option-value="value" aria-label="Trend range" />
    </div>
    <p v-if="store.items.length === 0" class="pl-muted">Connect an account on the <RouterLink to="/accounts">Accounts</RouterLink> page first.</p>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <template v-if="overview">
      <Message v-if="overview.account?.status !== 'ACTIVE'" severity="error" :closable="false">
        {{ overview.account?.statusMessage ?? 'This account needs reconnecting.' }}
      </Message>

      <section class="pl-grid pl-kpis">
        <div class="pl-card pl-stat">
          <div class="label">Followers</div>
          <div class="value">{{ formatNumber(overview.account?.followersCount) }}</div>
          <div class="pl-muted">{{ formatDelta(overview.followersChange7d) }} in 7 days · {{ formatDelta(overview.followersChange30d) }} in 30</div>
        </div>
        <div class="pl-card pl-stat">
          <div class="label">Reach, last 7 days</div>
          <div class="value">{{ formatNumber(overview.reachLast7d) }}</div>
          <div class="pl-muted">{{ pctChange(overview.reachLast7d, overview.reachPrevious7d) ?? '—' }} vs previous 7 days</div>
        </div>
        <div class="pl-card pl-stat">
          <div class="label">Views, last 7 days</div>
          <div class="value">{{ formatNumber(overview.viewsLast7d) }}</div>
          <div class="pl-muted">{{ pctChange(overview.viewsLast7d, overview.viewsPrevious7d) ?? '—' }} vs previous 7 days</div>
        </div>
        <div class="pl-card pl-stat">
          <div class="label">Posts, last 30 days</div>
          <div class="value">{{ overview.postsLast30d }}</div>
          <div class="pl-muted">avg engagement {{ formatRate(overview.avgEngagementRateLast30d) }}</div>
        </div>
        <div class="pl-card pl-stat">
          <div class="label">Last sync</div>
          <div class="value pl-value-sm" :title="formatDateTime(overview.account?.lastSyncedAt)">{{ relativeDays(overview.account?.lastSyncedAt) }}</div>
          <div>
            <Tag v-if="overview.lastRun" :value="overview.lastRun.status" :severity="overview.lastRun.status === 'SUCCEEDED' ? 'success' : overview.lastRun.status === 'FAILED' ? 'danger' : 'warn'" />
            <Tag v-if="overview.token?.expiringSoon" value="token expiring" severity="warn" class="pl-ml" />
          </div>
        </div>
      </section>
      <p class="pl-muted pl-note">Reach adds up daily reach, so people reached on several days are counted more than once.</p>

      <div class="pl-two">
        <section class="pl-card">
          <h2>Followers</h2>
          <LineChart :points="followerPoints" label="Followers" :begin-at-zero="false"
            :format-time="(t) => formatDate(new Date(t).toISOString())" />
          <p class="pl-muted pl-note">One point per day PageLens synced.</p>
        </section>
        <section class="pl-card">
          <h2>Daily reach</h2>
          <LineChart :points="reachPoints" label="Accounts reached" :format-time="(t) => formatDate(new Date(t).toISOString())" />
        </section>
        <section class="pl-card">
          <h2>Daily views</h2>
          <LineChart :points="viewsPoints" label="Views" :format-time="(t) => formatDate(new Date(t).toISOString())" />
        </section>
        <section class="pl-card">
          <h2>Recent posts, last 30 days</h2>
          <h3>Highest reach</h3>
          <ol class="pl-list">
            <li v-for="p in overview.bestRecent" :key="p.postId">
              <RouterLink :to="`/posts/${p.postId}`" class="pl-text" dir="auto">{{ caption(p) }}</RouterLink>
              <span class="pl-num">{{ formatNumber(p.reach) }} · {{ formatRate(p.engagementRate) }}</span>
            </li>
          </ol>
          <h3>Lowest reach</h3>
          <ol class="pl-list">
            <li v-for="p in overview.worstRecent" :key="p.postId">
              <RouterLink :to="`/posts/${p.postId}`" class="pl-text" dir="auto">{{ caption(p) }}</RouterLink>
              <span class="pl-num">{{ formatNumber(p.reach) }} · {{ formatRate(p.engagementRate) }}</span>
            </li>
          </ol>
          <p v-if="!overview.bestRecent?.length" class="pl-muted">No posts with insights in the last 30 days.</p>
        </section>
      </div>
    </template>
  </main>
</template>

<style scoped>
.pl-kpis {
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  margin-bottom: 0.25rem;
}
.pl-value-sm {
  font-size: 1.1rem;
}
.pl-ml {
  margin-left: 0.35rem;
}
.pl-note {
  font-size: 0.8rem;
  margin: 0.25rem 0 1rem;
}
.pl-two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(480px, 1fr));
  gap: 1rem;
}
h2 {
  font-size: 1.05rem;
  margin: 0 0 0.5rem;
}
h3 {
  font-size: 0.85rem;
  color: var(--p-text-muted-color);
  margin: 0.75rem 0 0.25rem;
}
.pl-list {
  margin: 0;
  padding-inline-start: 1.25rem;
}
.pl-list li {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.2rem 0;
}
</style>
