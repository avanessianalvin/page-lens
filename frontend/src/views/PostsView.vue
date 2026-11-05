<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Column from 'primevue/column'
import DataTable, { type DataTablePageEvent, type DataTableSortEvent } from 'primevue/datatable'
import DatePicker from 'primevue/datepicker'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import { useToast } from 'primevue/usetoast'
import { api, call, type Schemas } from '@/api/client'
import { useAccountsStore } from '@/stores/accounts'
import { PRODUCT_TYPES, formatDateTime, formatNumber } from '@/format'

const store = useAccountsStore()
const toast = useToast()
const router = useRouter()

const rows = ref<Schemas['Post'][]>([])
const total = ref(0)
const loading = ref(false)
const page = ref(0)
const size = ref(25)
const sort = ref('posted_at')
const dir = ref<'asc' | 'desc'>('desc')
const type = ref<string | null>(null)
const range = ref<Date[] | null>(null)

/** DataTable column field → API sort key. */
const SORT_KEYS: Record<string, string> = {
  postedAt: 'posted_at', 'latest.reach': 'reach', 'latest.views': 'views', likes: 'likes', comments: 'comments',
  'latest.shares': 'shares', 'latest.saves': 'saves', 'latest.follows': 'follows',
  'latest.totalInteractions': 'total_interactions',
}

function isoDay(d?: Date | null) {
  if (!d) return undefined
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function load() {
  if (!store.selectedId) return
  loading.value = true
  try {
    const res = await call(api.GET('/api/accounts/{id}/posts', {
      params: {
        path: { id: store.selectedId },
        query: {
          page: page.value, size: size.value, sort: sort.value, dir: dir.value, type: type.value ?? undefined,
          from: isoDay(range.value?.[0]), to: isoDay(range.value?.[1] ?? range.value?.[0]),
        },
      },
    }))
    rows.value = res.items ?? []
    total.value = res.total ?? 0
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Could not load posts', detail: String(e), life: 6000 })
  } finally {
    loading.value = false
  }
}

function onPage(e: DataTablePageEvent) {
  page.value = e.page
  size.value = e.rows
  load()
}

function onSort(e: DataTableSortEvent) {
  sort.value = SORT_KEYS[String(e.sortField)] ?? 'posted_at'
  dir.value = e.sortOrder === 1 ? 'asc' : 'desc'
  page.value = 0
  load()
}

function caption(p: Schemas['Post']) {
  const c = p.caption ?? ''
  return c.length > 90 ? c.slice(0, 90) + '…' : c
}

watch([() => store.selectedId, type, range], () => {
  page.value = 0
  load()
})
onMounted(async () => {
  if (store.items.length === 0) await store.load()
  load()
})
</script>

<template>
  <main class="pl-page">
    <h1>Posts</h1>
    <div class="pl-toolbar">
      <Select v-model="store.selectedId" :options="store.items.map((a) => a.account)" option-label="username"
        option-value="id" placeholder="Account" @update:model-value="store.select">
        <template #value="{ value }">@{{ store.items.find((a) => a.account?.id === value)?.account?.username ?? '…' }}</template>
        <template #option="{ option }">@{{ option.username }}</template>
      </Select>
      <Select v-model="type" :options="PRODUCT_TYPES" option-label="label" option-value="value" placeholder="All formats" />
      <DatePicker v-model="range" selection-mode="range" :manual-input="false" show-button-bar placeholder="Any date"
        date-format="yy-mm-dd" show-icon />
      <span class="pl-muted">{{ total }} posts</span>
    </div>

    <p v-if="store.items.length === 0" class="pl-muted">Connect an account first.</p>

    <DataTable v-else :value="rows" :loading="loading" lazy paginator :rows="size" :total-records="total"
      :rows-per-page-options="[25, 50, 100]" :first="page * size" removable-sort data-key="id"
      sort-field="postedAt" :sort-order="-1" row-hover striped-rows size="small"
      @page="onPage" @sort="onSort" @row-click="(e) => router.push(`/posts/${e.data.id}`)">
      <Column header="" style="width: 60px">
        <template #body="{ data }">
          <img v-if="data.thumbnailUrl || data.mediaUrl" :src="data.thumbnailUrl ?? data.mediaUrl" alt="" class="pl-thumb"
            loading="lazy" referrerpolicy="no-referrer" />
          <div v-else class="pl-thumb" />
        </template>
      </Column>
      <Column field="caption" header="Caption">
        <template #body="{ data }">
          <div class="pl-text" dir="auto">{{ caption(data) }}</div>
          <Tag :value="data.productType === 'FEED' && data.mediaType === 'CAROUSEL_ALBUM' ? 'CAROUSEL' : data.productType"
            severity="secondary" class="pl-type" />
        </template>
      </Column>
      <Column field="postedAt" header="Posted" sortable style="width: 150px">
        <template #body="{ data }">{{ formatDateTime(data.postedAt) }}</template>
      </Column>
      <Column field="latest.reach" header="Reach" sortable class="pl-num">
        <template #body="{ data }">{{ formatNumber(data.latest?.reach) }}</template>
      </Column>
      <Column field="latest.views" header="Views" sortable class="pl-num">
        <template #body="{ data }">{{ formatNumber(data.latest?.views) }}</template>
      </Column>
      <Column field="likes" header="Likes" sortable class="pl-num">
        <template #body="{ data }">{{ formatNumber(data.latest?.likes ?? data.likeCount) }}</template>
      </Column>
      <Column field="comments" header="Comments" sortable class="pl-num">
        <template #body="{ data }">{{ formatNumber(data.latest?.comments ?? data.commentsCount) }}</template>
      </Column>
      <Column field="latest.shares" header="Shares" sortable class="pl-num">
        <template #body="{ data }">{{ formatNumber(data.latest?.shares) }}</template>
      </Column>
      <Column field="latest.saves" header="Saves" sortable class="pl-num">
        <template #body="{ data }">{{ formatNumber(data.latest?.saves) }}</template>
      </Column>
      <template #empty><span class="pl-muted">No posts match. Run a sync from Accounts if this is new.</span></template>
    </DataTable>
  </main>
</template>

<style scoped>
.pl-type {
  margin-top: 0.25rem;
  font-size: 0.7rem;
}
:deep(.p-datatable-tbody > tr) {
  cursor: pointer;
}
</style>
