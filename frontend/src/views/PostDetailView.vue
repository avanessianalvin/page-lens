<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Message from 'primevue/message'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import { api, call, type Schemas } from '@/api/client'
import SnapshotChart from '@/components/SnapshotChart.vue'
import { formatDateTime, formatDuration, formatNumber } from '@/format'

const props = defineProps<{ id: string }>()

const detail = ref<Schemas['PostDetail'] | null>(null)
const comments = ref<Schemas['Comment'][]>([])
const error = ref<string | null>(null)
const showTable = ref(false)

type MetricKey = 'reach' | 'views' | 'likes' | 'comments' | 'shares' | 'saves' | 'follows' | 'totalInteractions' | 'profileVisits'
const METRICS: { key: MetricKey; label: string }[] = [
  { key: 'reach', label: 'Reach' },
  { key: 'views', label: 'Views' },
  { key: 'likes', label: 'Likes' },
  { key: 'comments', label: 'Comments' },
  { key: 'shares', label: 'Shares' },
  { key: 'saves', label: 'Saves' },
  { key: 'follows', label: 'Follows' },
  { key: 'totalInteractions', label: 'Interactions' },
  { key: 'profileVisits', label: 'Profile visits' },
]
const chartMetric = ref<MetricKey>('reach')
const chartLabel = computed(() => METRICS.find((m) => m.key === chartMetric.value)!.label)

const post = computed(() => detail.value?.post)
const latest = computed(() => post.value?.latest)
const extra = computed<Record<string, unknown>>(() => {
  try {
    return latest.value?.extraMetrics ? JSON.parse(latest.value.extraMetrics) : {}
  } catch {
    return {}
  }
})

onMounted(async () => {
  try {
    detail.value = await call(api.GET('/api/posts/{id}', { params: { path: { id: Number(props.id) } } }))
    comments.value = await call(api.GET('/api/posts/{id}/comments', { params: { path: { id: Number(props.id) } } }))
  } catch (e) {
    error.value = String(e)
  }
})
</script>

<template>
  <main class="pl-page">
    <Button label="Back to posts" icon="pi pi-arrow-left" text size="small" @click="$router.push('/posts')" />
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <template v-if="post">
      <section class="pl-card pl-head">
        <img v-if="post.thumbnailUrl || post.mediaUrl" :src="post.thumbnailUrl ?? post.mediaUrl" alt="" class="pl-cover"
          referrerpolicy="no-referrer" />
        <div class="pl-head-main">
          <div class="pl-head-meta">
            <Tag :value="post.productType" severity="secondary" />
            <span class="pl-muted">@{{ detail?.accountUsername }} · {{ formatDateTime(post.postedAt) }}</span>
            <a v-if="post.permalink" :href="post.permalink" target="_blank" rel="noopener noreferrer">
              Open on Instagram <i class="pi pi-external-link" />
            </a>
          </div>
          <p class="pl-text" dir="auto">{{ post.caption || '(no caption)' }}</p>
          <span class="pl-muted">Insights updated {{ formatDateTime(post.lastInsightsAt) }}</span>
        </div>
      </section>

      <section class="pl-card">
        <h2>Latest insights</h2>
        <div class="pl-grid">
          <div v-for="m in METRICS" :key="m.key" class="pl-stat">
            <div class="label">{{ m.label }}</div>
            <div class="value">{{ formatNumber(latest?.[m.key]) }}</div>
          </div>
          <div v-if="latest?.avgWatchTimeMs != null" class="pl-stat">
            <div class="label">Avg watch time</div>
            <div class="value">{{ formatDuration(latest.avgWatchTimeMs) }}</div>
          </div>
        </div>
        <p v-if="Array.isArray(extra._unsupported)" class="pl-muted">
          Not returned by Instagram for this post: {{ (extra._unsupported as string[]).join(', ') }} (shown as —, not 0).
        </p>
      </section>

      <section class="pl-card">
        <div class="pl-section-head">
          <h2>{{ chartLabel }} over time</h2>
          <Select v-model="chartMetric" :options="METRICS" option-label="label" option-value="key" size="small" />
          <Button :label="showTable ? 'Show chart' : 'Show table'" text size="small" @click="showTable = !showTable" />
        </div>
        <SnapshotChart v-if="!showTable" :snapshots="detail?.history ?? []" :metric="chartMetric" :label="chartLabel" />
        <DataTable v-else :value="detail?.history ?? []" size="small" striped-rows>
          <Column header="Captured"><template #body="{ data }">{{ formatDateTime(data.capturedAt) }}</template></Column>
          <Column v-for="m in METRICS" :key="m.key" :header="m.label" class="pl-num">
            <template #body="{ data }">{{ formatNumber(data[m.key]) }}</template>
          </Column>
        </DataTable>
      </section>

      <section class="pl-card">
        <h2>Comments <span class="pl-muted">({{ comments.length }} threads)</span></h2>
        <p v-if="comments.length === 0" class="pl-muted">No comments synced. Comments are pulled for posts from the last 14 days.</p>
        <ul class="pl-comments">
          <li v-for="c in comments" :key="c.id">
            <div class="pl-comment">
              <strong>@{{ c.username ?? 'unknown' }}</strong>
              <span class="pl-muted">{{ formatDateTime(c.postedAt) }} · {{ formatNumber(c.likeCount) }} likes</span>
              <Tag v-if="c.hidden" value="hidden" severity="warn" />
              <div class="pl-text" dir="auto">{{ c.text }}</div>
            </div>
            <ul v-if="c.replies?.length" class="pl-replies">
              <li v-for="r in c.replies" :key="r.id" class="pl-comment">
                <strong>@{{ r.username ?? 'unknown' }}</strong>
                <span class="pl-muted">{{ formatDateTime(r.postedAt) }}</span>
                <div class="pl-text" dir="auto">{{ r.text }}</div>
              </li>
            </ul>
          </li>
        </ul>
        <p class="pl-muted">Replying to and hiding comments arrives in Phase 3.</p>
      </section>
    </template>
  </main>
</template>

<style scoped>
.pl-card {
  margin-bottom: 1rem;
}
h2 {
  font-size: 1.05rem;
  margin: 0 0 0.75rem;
}
.pl-head {
  display: flex;
  gap: 1.25rem;
}
.pl-cover {
  width: 160px;
  height: 160px;
  object-fit: cover;
  border-radius: 8px;
}
.pl-head-main {
  flex: 1;
}
.pl-head-meta {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
}
.pl-section-head {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-bottom: 0.5rem;
}
.pl-section-head h2 {
  margin: 0;
  flex: 1;
}
.pl-comments,
.pl-replies {
  list-style: none;
  padding: 0;
  margin: 0;
}
.pl-comments > li {
  border-top: 1px solid var(--p-content-border-color);
  padding: 0.6rem 0;
}
.pl-replies {
  padding-inline-start: 1.5rem;
  margin-top: 0.4rem;
}
.pl-comment {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.6rem;
  align-items: baseline;
}
.pl-comment .pl-text {
  flex-basis: 100%;
}
</style>
