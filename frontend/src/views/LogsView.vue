<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Tab from 'primevue/tab'
import TabList from 'primevue/tablist'
import Tabs from 'primevue/tabs'
import Tag from 'primevue/tag'
import ToggleSwitch from 'primevue/toggleswitch'
import { useToast } from 'primevue/usetoast'
import { api, call, type Schemas } from '@/api/client'
import { formatDateTime } from '@/format'

type TabKey = 'app' | 'api' | 'audit' | 'sync'

const toast = useToast()
const tab = ref<TabKey>('app')
const live = ref(true)
const loading = ref(false)

const level = ref<string | null>('INFO')
const query = ref('')
const correlationId = ref('')
const errorsOnly = ref(false)

const appRows = ref<Record<string, unknown>[]>([])
const apiRows = ref<Schemas['ApiCallEntry'][]>([])
const auditRows = ref<Schemas['AuditEntry'][]>([])
const syncRows = ref<Schemas['SyncRun'][]>([])
let timer: number | undefined

const LEVELS = [
  { label: 'All levels', value: null },
  { label: 'INFO+', value: 'INFO' },
  { label: 'WARN+', value: 'WARN' },
  { label: 'ERROR', value: 'ERROR' },
]

async function load() {
  loading.value = true
  try {
    const cid = correlationId.value.trim() || undefined
    if (tab.value === 'app') {
      appRows.value = await call(api.GET('/api/logs/app', {
        params: { query: { level: level.value ?? undefined, q: query.value || undefined, correlationId: cid, limit: 300 } },
      })) as Record<string, unknown>[]
    } else if (tab.value === 'api') {
      apiRows.value = await call(api.GET('/api/logs/api-calls', {
        params: { query: { errorsOnly: errorsOnly.value, endpoint: query.value || undefined, correlationId: cid, limit: 300 } },
      }))
    } else if (tab.value === 'audit') {
      auditRows.value = await call(api.GET('/api/logs/audit', { params: { query: { limit: 300 } } }))
    } else {
      syncRows.value = await call(api.GET('/api/sync-runs', { params: { query: { limit: 200 } } }))
    }
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Could not load logs', detail: String(e), life: 5000 })
  } finally {
    loading.value = false
  }
}

/** Click a correlation id anywhere to see every line from that request / sync run. */
function follow(cid?: string | null) {
  if (!cid) return
  correlationId.value = cid
  tab.value = 'app'
}

function levelSeverity(l: unknown) {
  return l === 'ERROR' ? 'danger' : l === 'WARN' ? 'warn' : l === 'DEBUG' || l === 'TRACE' ? 'secondary' : 'info'
}

function statusSeverity(s?: number | null, code?: string | null) {
  if (code || (s ?? 0) >= 400) return 'danger'
  return 'success'
}

watch([tab, level, errorsOnly, correlationId], load)
watch(live, (on) => {
  window.clearInterval(timer)
  if (on) timer = window.setInterval(load, 5000)
}, { immediate: true })
onMounted(load)
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <main class="pl-page">
    <h1>Logs</h1>
    <Tabs v-model:value="tab">
      <TabList>
        <Tab value="app">Application</Tab>
        <Tab value="api">API calls</Tab>
        <Tab value="audit">Audit trail</Tab>
        <Tab value="sync">Sync runs</Tab>
      </TabList>
    </Tabs>

    <div class="pl-toolbar pl-filters">
      <Select v-if="tab === 'app'" v-model="level" :options="LEVELS" option-label="label" option-value="value" size="small" />
      <InputText v-if="tab === 'app' || tab === 'api'" v-model="query" size="small"
        :placeholder="tab === 'app' ? 'Search text' : 'Endpoint contains'" @keyup.enter="load" />
      <InputText v-if="tab === 'app' || tab === 'api'" v-model="correlationId" size="small" placeholder="Correlation id" />
      <label v-if="tab === 'api'" class="pl-inline"><ToggleSwitch v-model="errorsOnly" /> Errors only</label>
      <Button icon="pi pi-refresh" size="small" text :loading="loading" aria-label="Refresh" @click="load" />
      <label class="pl-inline"><ToggleSwitch v-model="live" /> Live (5 s)</label>
    </div>

    <DataTable v-if="tab === 'app'" :value="appRows" size="small" scrollable scroll-height="70vh">
      <Column header="Time" style="width: 170px"><template #body="{ data }">{{ formatDateTime(data['@timestamp'] as string) }}</template></Column>
      <Column header="Level" style="width: 80px"><template #body="{ data }"><Tag :value="data.level" :severity="levelSeverity(data.level)" /></template></Column>
      <Column header="Logger" style="width: 220px"><template #body="{ data }"><span class="pl-muted pl-small">{{ data.logger_name }}</span></template></Column>
      <Column header="Message"><template #body="{ data }"><span class="pl-text pl-small" dir="auto">{{ data.message }}</span></template></Column>
      <Column header="Correlation" style="width: 140px">
        <template #body="{ data }"><a v-if="data.correlationId" href="#" class="pl-small" @click.prevent="follow(data.correlationId as string)">{{ String(data.correlationId).slice(0, 13) }}…</a></template>
      </Column>
      <template #empty><span class="pl-muted">No log lines match (app.log is empty or the filters exclude everything).</span></template>
    </DataTable>

    <DataTable v-if="tab === 'api'" :value="apiRows" size="small" scrollable scroll-height="70vh">
      <Column header="Time" style="width: 170px"><template #body="{ data }">{{ formatDateTime(data.createdAt) }}</template></Column>
      <Column field="method" header="Method" style="width: 70px" />
      <Column header="Endpoint"><template #body="{ data }"><code class="pl-small">{{ data.endpoint }}</code></template></Column>
      <Column header="Status" style="width: 110px">
        <template #body="{ data }"><Tag :severity="statusSeverity(data.httpStatus, data.errorCode)" :value="data.errorCode ? `${data.httpStatus ?? ''} #${data.errorCode}` : String(data.httpStatus)" /></template>
      </Column>
      <Column header="ms" class="pl-num" style="width: 70px"><template #body="{ data }">{{ data.durationMs }}</template></Column>
      <Column header="Usage" class="pl-num" style="width: 80px"><template #body="{ data }">{{ data.usagePercent != null ? data.usagePercent + '%' : '—' }}</template></Column>
      <Column header="Correlation" style="width: 140px">
        <template #body="{ data }"><a v-if="data.correlationId" href="#" class="pl-small" @click.prevent="follow(data.correlationId)">{{ data.correlationId.slice(0, 13) }}…</a></template>
      </Column>
    </DataTable>

    <DataTable v-if="tab === 'audit'" :value="auditRows" size="small" scrollable scroll-height="70vh">
      <Column header="Time" style="width: 170px"><template #body="{ data }">{{ formatDateTime(data.createdAt) }}</template></Column>
      <Column field="actor" header="Actor" style="width: 90px" />
      <Column field="action" header="Action" />
      <Column field="target" header="Target" />
      <Column field="payloadSummary" header="Details" />
      <Column header="Outcome" style="width: 100px"><template #body="{ data }"><Tag :value="data.outcome" :severity="data.outcome === 'ok' ? 'success' : 'warn'" /></template></Column>
    </DataTable>

    <DataTable v-if="tab === 'sync'" :value="syncRows" size="small" scrollable scroll-height="70vh">
      <Column header="Started" style="width: 170px"><template #body="{ data }">{{ formatDateTime(data.startedAt) }}</template></Column>
      <Column header="Account"><template #body="{ data }">@{{ data.accountUsername }}</template></Column>
      <Column field="jobType" header="Job" style="width: 90px" />
      <Column field="triggeredBy" header="By" style="width: 90px" />
      <Column header="Status" style="width: 110px">
        <template #body="{ data }"><Tag :value="data.status" :severity="data.status === 'SUCCEEDED' ? 'success' : data.status === 'RUNNING' ? 'info' : data.status === 'FAILED' ? 'danger' : 'warn'" /></template>
      </Column>
      <Column field="itemsFetched" header="Items" class="pl-num" style="width: 70px" />
      <Column header="Errors"><template #body="{ data }"><span class="pl-text pl-small">{{ data.errorMessage }}</span></template></Column>
      <Column header="Logs" style="width: 70px">
        <template #body="{ data }"><Button v-if="data.correlationId" icon="pi pi-search" text size="small" aria-label="Show log lines" @click="follow(data.correlationId)" /></template>
      </Column>
    </DataTable>
  </main>
</template>

<style scoped>
.pl-filters {
  margin-top: 0.75rem;
}
.pl-inline {
  display: inline-flex;
  gap: 0.4rem;
  align-items: center;
  font-size: 0.85rem;
}
.pl-small {
  font-size: 0.8rem;
}
</style>
