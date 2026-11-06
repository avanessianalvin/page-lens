<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import Tag from 'primevue/tag'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { api, call, type Schemas } from '@/api/client'
import AccountPicker from '@/components/AccountPicker.vue'
import { useAccountsStore } from '@/stores/accounts'
import { useAlertsStore } from '@/stores/alerts'
import { useThemeStore } from '@/stores/theme'
import { PERIODS, formatDateTime } from '@/format'

const theme = useThemeStore()
const store = useAccountsStore()
const alerts = useAlertsStore()
const toast = useToast()
const confirm = useConfirm()

const settings = ref<Schemas['Settings'] | null>(null)
const backups = ref<Schemas['Backups'] | null>(null)
const exportPeriod = ref('all')
const purgeConfirm = ref('')
let poll: number | undefined

const THEMES = [
  { label: 'System', value: 'system' },
  { label: 'Light', value: 'light' },
  { label: 'Dark', value: 'dark' },
]

const backupRunning = computed(() => backups.value?.runs?.some((r) => r.status === 'RUNNING') ?? false)

function exportUrl(kind: 'posts.csv' | 'posts.json' | 'daily.csv') {
  const q = new URLSearchParams({ accountId: String(store.selectedId ?? ''), period: exportPeriod.value })
  return `/api/export/${kind}?${q}`
}

function size(bytes?: number | null) {
  if (bytes == null) return '—'
  return bytes > 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

async function loadBackups() {
  backups.value = await call(api.GET('/api/backups'))
}

async function backupNow() {
  await call(api.POST('/api/backups'))
  toast.add({ severity: 'info', summary: 'Backup started', life: 3000 })
  window.setTimeout(loadBackups, 1500)
}

function purge() {
  const account = store.selected
  if (!account) return
  confirm.require({
    header: `Purge all data for @${account.username}?`,
    message: 'Deletes every synced post, insight snapshot, comment, daily metric and audience snapshot for this '
      + 'account from PageLens. The account stays connected and the next sync starts from scratch. Nothing changes '
      + 'on Instagram. Make sure you have a recent backup.',
    acceptProps: { label: 'Purge data', severity: 'danger' },
    rejectProps: { label: 'Cancel', severity: 'secondary', outlined: true },
    accept: async () => {
      const { data, error, response } = await api.POST('/api/accounts/{id}/purge', {
        params: { path: { id: account.id! }, query: { confirm: purgeConfirm.value } },
      })
      if (response.ok) {
        toast.add({ severity: 'success', summary: 'Data purged', detail: JSON.stringify(data), life: 6000 })
        purgeConfirm.value = ''
        alerts.load()
      } else {
        toast.add({ severity: 'error', summary: 'Not purged', detail: (error as unknown as { detail?: string } | undefined)?.detail, life: 8000 })
      }
    },
  })
}

onMounted(async () => {
  if (store.items.length === 0) await store.load()
  settings.value = await call(api.GET('/api/settings'))
  await loadBackups()
  poll = window.setInterval(() => {
    if (backupRunning.value) loadBackups()
  }, 3000)
})
onUnmounted(() => window.clearInterval(poll))
</script>

<template>
  <main class="pl-page">
    <h1>Settings</h1>

    <section class="pl-card">
      <h2>Appearance</h2>
      <SelectButton v-model="theme.mode" :options="THEMES" option-label="label" option-value="value" :allow-empty="false" />
      <p class="pl-muted pl-small">"System" follows your operating system's light/dark setting.</p>
    </section>

    <section v-if="settings" class="pl-card">
      <h2>Configuration</h2>
      <p class="pl-muted pl-small">These are read from <code>.env</code> at startup; change them there and restart PageLens (see docs/SETUP.md).</p>
      <dl class="pl-dl">
        <dt>Time zone</dt><dd>{{ settings.timeZone }} <code>PAGELENS_TIME_ZONE</code></dd>
        <dt>Sync schedule</dt><dd><code>{{ settings.syncCron }}</code> <code>PAGELENS_SYNC_CRON</code></dd>
        <dt>Stories</dt><dd><code>{{ settings.storiesCron }}</code> <code>PAGELENS_STORIES_CRON</code></dd>
        <dt>Write actions</dt>
        <dd>
          <Tag :value="settings.writeActionsEnabled ? 'Enabled' : 'Disabled'" :severity="settings.writeActionsEnabled ? 'warn' : 'secondary'" />
          <code>PAGELENS_WRITE_ACTIONS_ENABLED</code>
          <span class="pl-muted pl-small">Replying, hiding, deleting comments and publishing.</span>
        </dd>
        <dt>Version</dt><dd>{{ settings.version }}</dd>
      </dl>
    </section>

    <section v-if="settings" class="pl-card">
      <div class="pl-head">
        <h2>Backups</h2>
        <Button label="Back up now" icon="pi pi-database" size="small" :loading="backupRunning"
          :disabled="!settings.pgDumpFound" @click="backupNow" />
      </div>
      <Message v-if="!settings.pgDumpFound" severity="warn" :closable="false" size="small">
        pg_dump was not found. Install the PostgreSQL client tools or set <code>PAGELENS_PG_DUMP</code> to its full path.
      </Message>
      <p class="pl-muted pl-small">
        <template v-if="settings.backupEnabled">Nightly at <code>{{ settings.backupCron }}</code>, kept {{ settings.backupKeepDays }} days</template>
        <template v-else>Nightly backups are off (<code>PAGELENS_BACKUP_ENABLED</code>)</template>
        · folder <code>{{ backups?.dir }}</code>. The backup holds data, not the encryption key: back up
        <code>PAGELENS_ENCRYPTION_KEY</code> separately. Restoring: docs/RUNBOOK.md.
      </p>
      <DataTable :value="backups?.runs ?? []" size="small" striped-rows :rows="8" paginator :always-show-paginator="false">
        <Column header="Started"><template #body="{ data }">{{ formatDateTime(data.startedAt) }}</template></Column>
        <Column field="triggeredBy" header="By" />
        <Column header="Status">
          <template #body="{ data }">
            <Tag :value="data.status" :severity="data.status === 'SUCCEEDED' ? 'success' : data.status === 'FAILED' ? 'danger' : 'info'" />
          </template>
        </Column>
        <Column header="File"><template #body="{ data }"><code class="pl-small">{{ data.fileName }}</code></template></Column>
        <Column header="Size" class="pl-num"><template #body="{ data }">{{ size(data.sizeBytes) }}</template></Column>
        <Column header="Error"><template #body="{ data }"><span class="pl-small">{{ data.errorMessage }}</span></template></Column>
        <template #empty><span class="pl-muted">No backups yet.</span></template>
      </DataTable>
    </section>

    <section class="pl-card">
      <h2>Export</h2>
      <div class="pl-toolbar">
        <AccountPicker />
        <Select v-model="exportPeriod" :options="PERIODS" option-label="label" option-value="value" aria-label="Period" />
      </div>
      <div class="pl-toolbar">
        <a :href="exportUrl('posts.csv')" class="p-button p-button-sm p-button-outlined" :class="{ 'p-disabled': !store.selectedId }">
          <i class="pi pi-download" />&nbsp;Posts (CSV)</a>
        <a :href="exportUrl('posts.json')" class="p-button p-button-sm p-button-outlined" :class="{ 'p-disabled': !store.selectedId }">
          <i class="pi pi-download" />&nbsp;Posts (JSON)</a>
        <a :href="exportUrl('daily.csv')" class="p-button p-button-sm p-button-outlined" :class="{ 'p-disabled': !store.selectedId }">
          <i class="pi pi-download" />&nbsp;Daily metrics (CSV)</a>
      </div>
      <p class="pl-muted pl-small">CSV files open in Excel with Farsi and Armenian intact. Empty cells mean Instagram didn't return that value.</p>
    </section>

    <section class="pl-card pl-danger">
      <h2>Purge account data</h2>
      <p class="pl-muted pl-small">
        Removes everything PageLens synced for the selected account but keeps it connected. To remove the account and
        its token too, use Disconnect on the Accounts page.
      </p>
      <div class="pl-toolbar">
        <AccountPicker />
        <InputText v-model="purgeConfirm" size="small" :placeholder="`Type ${store.selected?.username ?? 'the username'} to confirm`" />
        <Button label="Purge…" icon="pi pi-eraser" severity="danger" size="small" outlined
          :disabled="!store.selected || purgeConfirm.replace(/^@/, '').toLowerCase() !== store.selected.username?.toLowerCase()"
          @click="purge" />
      </div>
    </section>
  </main>
</template>

<style scoped>
.pl-card {
  margin-bottom: 1rem;
}
h2 {
  font-size: 1.05rem;
  margin: 0 0 0.5rem;
}
.pl-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pl-small {
  font-size: 0.8rem;
}
.pl-dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.4rem 1.25rem;
  margin: 0;
}
.pl-dl dt {
  color: var(--p-text-muted-color);
}
.pl-dl dd {
  margin: 0;
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}
code {
  font-size: 0.8rem;
}
.pl-danger {
  border-color: var(--p-red-300);
}
</style>
