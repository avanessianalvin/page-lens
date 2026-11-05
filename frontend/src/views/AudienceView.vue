<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Message from 'primevue/message'
import { api, call, type Schemas } from '@/api/client'
import AccountPicker from '@/components/AccountPicker.vue'
import BarChart, { type Bar } from '@/components/charts/BarChart.vue'
import { useAccountsStore } from '@/stores/accounts'
import { formatDateTime, formatNumber } from '@/format'

const store = useAccountsStore()
const breakdowns = ref<Schemas['AudienceBreakdown'][]>([])
const loaded = ref(false)
const error = ref<string | null>(null)
const showTable = ref(false)

const TITLES: Record<string, string> = { country: 'Countries', city: 'Cities', age: 'Age', gender: 'Gender' }
const GENDERS: Record<string, string> = { F: 'Female', M: 'Male', U: 'Unknown' }
const countryNames = new Intl.DisplayNames(undefined, { type: 'region' })

function label(dimension: string, key: string) {
  if (dimension === 'country') {
    try {
      return countryNames.of(key) ?? key
    } catch {
      return key
    }
  }
  return dimension === 'gender' ? (GENDERS[key] ?? key) : key
}

function bars(b: Schemas['AudienceBreakdown']): Bar[] {
  const entries = b.entries ?? []
  const list = b.dimension === 'age'
    ? [...entries].sort((x, y) => (x.key ?? '').localeCompare(y.key ?? '')) // age bands in natural order
    : entries.slice(0, 15)
  return list.map((e) => ({ label: label(b.dimension!, e.key!), value: e.value, smallSample: e.smallSample }))
}

const anySmall = computed(() => breakdowns.value.some((b) => b.entries?.some((e) => e.smallSample)))

async function load() {
  const id = store.selectedId
  if (!id) return
  error.value = null
  try {
    breakdowns.value = await call(api.GET('/api/accounts/{id}/audience', { params: { path: { id } } }))
  } catch (e) {
    error.value = String(e)
  } finally {
    loaded.value = true
  }
}

watch(() => store.selectedId, load)
onMounted(async () => {
  if (store.items.length === 0) await store.load()
  load()
})
</script>

<template>
  <main class="pl-page">
    <h1>Audience</h1>
    <div class="pl-toolbar">
      <AccountPicker />
      <Button :label="showTable ? 'Show charts' : 'Show tables'" text size="small" @click="showTable = !showTable" />
    </div>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <Message v-if="loaded && !error && breakdowns.length === 0" severity="info" :closable="false">
      No audience data yet. Instagram only provides demographics for accounts with at least 100 followers, and
      PageLens captures them once a day during sync.
    </Message>
    <p v-if="breakdowns.length" class="pl-muted">
      Follower demographics over the last ~30 days, top groups only (Instagram returns at most 45 per dimension,
      and only for followers with demographic data).
      <template v-if="anySmall">Gray bars are groups under 30 people: too small to trust.</template>
    </p>

    <div class="pl-two">
      <section v-for="b in breakdowns" :key="b.dimension" class="pl-card">
        <h2>{{ TITLES[b.dimension!] ?? b.dimension }}</h2>
        <BarChart v-if="!showTable" :bars="bars(b)" label="Followers" :keep-order="b.dimension === 'age'"
          :format="(v) => formatNumber(v)" />
        <DataTable v-else :value="b.entries" size="small" striped-rows>
          <Column header="Group"><template #body="{ data }">{{ label(b.dimension!, data.key) }}</template></Column>
          <Column header="Followers" class="pl-num"><template #body="{ data }">{{ formatNumber(data.value) }}</template></Column>
          <Column header=""><template #body="{ data }"><span v-if="data.smallSample" class="pl-muted">small sample</span></template></Column>
        </DataTable>
        <p class="pl-muted pl-small">Captured {{ formatDateTime(b.capturedAt) }}</p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.pl-two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
  gap: 1rem;
}
h2 {
  font-size: 1.05rem;
  margin: 0 0 0.5rem;
}
.pl-small {
  font-size: 0.8rem;
  margin: 0.5rem 0 0;
}
</style>
