<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import Textarea from 'primevue/textarea'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { api, call, xsrfToken, type Schemas } from '@/api/client'
import { useAccountsStore } from '@/stores/accounts'
import { useAlertsStore } from '@/stores/alerts'
import { formatDateTime, formatNumber, relativeDays } from '@/format'

type LoginType = 'INSTAGRAM_LOGIN' | 'FACEBOOK_LOGIN'

const SOURCE_LABELS: Record<string, string> = {
  INSTAGRAM_LOGIN: 'Instagram Login',
  FACEBOOK_LOGIN: 'Facebook Login',
  WINDSOR: 'Windsor.ai (read-only)',
  IMPORT: 'CSV import (read-only)',
}
const BUSINESS_SUITE_URL = 'https://business.facebook.com/latest/insights/content'

const store = useAccountsStore()
const alerts = useAlertsStore()
const toast = useToast()
const confirm = useConfirm()
const route = useRoute()
const router = useRouter()

const options = ref<Schemas['ConnectOptions'] | null>(null)
const runs = ref<Schemas['SyncRun'][]>([])
const loading = ref(true)
const tokenDialog = ref(false)
const tokenType = ref<LoginType>('INSTAGRAM_LOGIN')
const tokenValue = ref('')
const busy = ref(false)
const sources = ref<Schemas['SourcesStatus'] | null>(null)
const importDialog = ref(false)
const importAccountId = ref<number | null>(null)
const importing = ref(false)
const importReport = ref<Schemas['Report'] | null>(null)
let poll: number | undefined

const loginTypes = [
  { label: 'Instagram Login (no Facebook Page needed)', value: 'INSTAGRAM_LOGIN' },
  { label: 'Facebook Login (account linked to a Page)', value: 'FACEBOOK_LOGIN' },
]

async function load() {
  try {
    await store.load()
    runs.value = await call(api.GET('/api/sync-runs', { params: { query: { limit: 20 } } }))
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Could not load accounts', detail: String(e), life: 6000 })
  } finally {
    loading.value = false
  }
}

function latestRun(accountId?: number) {
  return runs.value.find((r) => r.accountId === accountId)
}

async function connect(loginType: LoginType) {
  busy.value = true
  try {
    const res = await call(api.POST('/api/accounts/connect', { body: { loginType } }))
    window.location.href = res.authorizationUrl!
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Cannot start login', detail: String(e), life: 8000 })
    busy.value = false
  }
}

async function connectWithToken() {
  busy.value = true
  try {
    const connected = await call(api.POST('/api/accounts/connect-token', {
      body: { loginType: tokenType.value, accessToken: tokenValue.value },
    }))
    toast.add({ severity: 'success', summary: `Connected ${connected.map((a) => '@' + a.account?.username).join(', ')}`, life: 4000 })
    tokenDialog.value = false
    for (const a of connected) if (a.account?.id) await syncNow(a.account.id, true)
    await load()
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Token rejected', detail: String(e), life: 8000 })
  } finally {
    tokenValue.value = ''
    busy.value = false
  }
}

async function syncAll() {
  const res = await call(api.POST('/api/accounts/sync-all'))
  toast.add({
    severity: 'info',
    summary: `Sync started for ${res.started?.length ?? 0} account(s)`,
    detail: res.skipped?.length ? `${res.skipped.length} already syncing` : undefined,
    life: 4000,
  })
  runs.value = await call(api.GET('/api/sync-runs', { params: { query: { limit: 20 } } }))
}

async function syncNow(id: number, quiet = false) {
  try {
    await call(api.POST('/api/accounts/{id}/sync', { params: { path: { id } } }))
    if (!quiet) toast.add({ severity: 'info', summary: 'Sync started', life: 3000 })
    runs.value = await call(api.GET('/api/sync-runs', { params: { query: { limit: 20 } } }))
  } catch (e) {
    toast.add({ severity: 'warn', summary: 'Sync not started', detail: String(e), life: 5000 })
  }
}

function disconnect(a: Schemas['Account']) {
  confirm.require({
    header: `Disconnect @${a.username}?`,
    message: 'This deletes the account and ALL of its synced data from PageLens (posts, insights, comments, '
      + 'audience). Nothing is changed on Instagram.',
    acceptProps: { label: 'Disconnect and delete', severity: 'danger' },
    rejectProps: { label: 'Cancel', severity: 'secondary', outlined: true },
    accept: async () => {
      await call(api.DELETE('/api/accounts/{id}', { params: { path: { id: a.id! } } }))
      toast.add({ severity: 'success', summary: `@${a.username} disconnected`, life: 3000 })
      await load()
    },
  })
}

function isDirect(a?: Schemas['Account']) {
  return a?.loginType === 'INSTAGRAM_LOGIN' || a?.loginType === 'FACEBOOK_LOGIN'
}

async function addFromWindsor() {
  busy.value = true
  try {
    const found = await call(api.POST('/api/sources/windsor/accounts'))
    const added = found.filter((f) => f.added)
    toast.add({
      severity: found.length ? 'success' : 'warn',
      summary: found.length ? `Windsor.ai:  account(s),  new` : 'Windsor.ai returned no Instagram accounts',
      detail: found.length ? 'First sync started for the new ones.' : 'Connect the account in your Windsor.ai workspace first.',
      life: 6000,
    })
    for (const f of added) if (f.loginType === 'WINDSOR' && f.accountId) await syncNow(f.accountId, true)
    await load()
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Windsor.ai', detail: String(e), life: 10000 })
  } finally {
    busy.value = false
  }
}

async function importCsv(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importing.value = true
  importReport.value = null
  try {
    const form = new FormData()
    form.append('file', file)
    const query = importAccountId.value ? `?accountId=${importAccountId.value}` : ''
    const res = await fetch(`/api/sources/import/business-suite${query}`, {
      method: 'POST', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': xsrfToken() ?? '' }, body: form,
    })
    const json = await res.json()
    if (!res.ok) throw new Error(json.detail ?? `Import failed (${res.status})`)
    importReport.value = json as Schemas['Report']
    await load()
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Import failed', detail: String(e), life: 10000 })
  } finally {
    importing.value = false
    input.value = ''
  }
}

function tokenSeverity(t?: Schemas['TokenHealth']) {
  if (!t || t.status === 'INVALID' || t.status === 'MISSING') return 'danger'
  return t.expiringSoon || t.status === 'EXPIRING' ? 'warn' : 'success'
}

function runSeverity(status?: string) {
  return status === 'SUCCEEDED' ? 'success' : status === 'RUNNING' ? 'info' : status === 'PARTIAL' || status === 'SKIPPED' ? 'warn' : 'danger'
}

onMounted(async () => {
  options.value = await call(api.GET('/api/accounts/connect-options')).catch(() => null)
  sources.value = await call(api.GET('/api/sources')).catch(() => null)
  if (route.query.connected) {
    toast.add({ severity: 'success', summary: `Connected ${route.query.connected} account(s); first sync started`, life: 5000 })
  }
  if (route.query.error) {
    toast.add({ severity: 'error', summary: 'Connection failed', detail: String(route.query.error), life: 10000 })
  }
  if (route.query.connected || route.query.error) router.replace({ query: {} })
  await load()
  // Refresh while a sync is running.
  poll = window.setInterval(async () => {
    if (runs.value.some((r) => r.status === 'RUNNING')) await load()
  }, 4000)
})
onUnmounted(() => window.clearInterval(poll))
</script>

<template>
  <main class="pl-page">
    <h1>Accounts</h1>

    <div class="pl-toolbar">
      <Button label="Connect with Instagram" icon="pi pi-instagram" :disabled="!options?.instagramLogin || busy"
        @click="connect('INSTAGRAM_LOGIN')" />
      <Button label="Connect with Facebook" icon="pi pi-facebook" severity="secondary"
        :disabled="!options?.facebookLogin || busy" @click="connect('FACEBOOK_LOGIN')" />
      <Button label="Paste a token" icon="pi pi-key" text @click="tokenDialog = true" />
      <Button label="Add from Windsor.ai" icon="pi pi-cloud-download" severity="secondary" outlined
        :disabled="!sources?.windsorConfigured || busy" :title="sources?.windsorConfigured ? '' : 'Set WINDSOR_API_KEY in .env and restart'"
        @click="addFromWindsor" />
      <Button label="Import CSV" icon="pi pi-upload" severity="secondary" outlined @click="importDialog = true; importReport = null" />
      <span class="pl-spacer-flex" />
      <Button v-if="store.items.length > 1" label="Sync all" icon="pi pi-refresh" severity="secondary" outlined
        @click="syncAll" />
    </div>
    <Message v-if="options && !options.instagramLogin && !options.facebookLogin" severity="warn" :closable="false">
      No Meta app is configured yet. Add META_INSTAGRAM_APP_ID / META_INSTAGRAM_APP_SECRET (or META_APP_ID /
      META_APP_SECRET) to .env and restart. See docs/SETUP.md. OAuth redirect URI: <code>{{ options.redirectUri }}</code>
      If Meta's developer site isn't available to you, use <strong>Add from Windsor.ai</strong> or
      <strong>Import CSV</strong> instead (read-only; see docs/META_ACCESS.md).
    </Message>

    <p v-if="!loading && store.items.length === 0" class="pl-muted">No accounts connected yet.</p>

    <section v-for="item in store.items" :key="item.account?.id" class="pl-card pl-account">
      <img v-if="item.account?.profilePictureUrl" :src="item.account.profilePictureUrl" alt="" class="pl-avatar" />
      <div class="pl-account-main">
        <div class="pl-account-title">
          <strong>@{{ item.account?.username }}</strong>
          <span class="pl-muted" dir="auto">{{ item.account?.displayName }}</span>
          <Tag :value="SOURCE_LABELS[item.account?.loginType ?? ''] ?? item.account?.loginType" severity="secondary" />
          <Tag v-if="item.account?.status !== 'ACTIVE'" value="Needs reconnect" severity="danger" />
        </div>
        <Message v-for="(al, i) in alerts.forAccount(item.account?.id).filter((x) => !['needs_reconnect', 'token_expiring'].includes(x.kind ?? ''))" :key="i"
          :severity="al.severity === 'CRITICAL' ? 'error' : al.severity === 'WARNING' ? 'warn' : 'info'" :closable="false" size="small">
          {{ al.message }} <strong v-if="al.action">→ {{ al.action }}</strong>
        </Message>
        <Message v-if="item.account?.statusMessage" severity="error" :closable="false" size="small">
          {{ item.account.statusMessage }}
        </Message>
        <div class="pl-grid">
          <div class="pl-stat"><div class="label">Followers</div><div class="value">{{ formatNumber(item.account?.followersCount) }}</div></div>
          <div class="pl-stat"><div class="label">Posts</div><div class="value">{{ formatNumber(item.account?.mediaCount) }}</div></div>
          <div class="pl-stat">
            <div class="label">Token</div>
            <span v-if="!isDirect(item.account)" class="pl-muted">Not needed</span>
            <Tag v-else :severity="tokenSeverity(item.token)"
              :value="item.token?.daysLeft != null ? `${item.token.status} · ${item.token.daysLeft} d left` : (item.token?.status ?? 'MISSING')" />
          </div>
          <div class="pl-stat">
            <div class="label">Last sync</div>
            <div :title="formatDateTime(item.account?.lastSyncedAt)">{{ relativeDays(item.account?.lastSyncedAt) }}</div>
          </div>
          <div class="pl-stat">
            <div class="label">Latest run</div>
            <Tag v-if="latestRun(item.account?.id)" :severity="runSeverity(latestRun(item.account?.id)?.status)"
              :value="latestRun(item.account?.id)?.status" :title="latestRun(item.account?.id)?.errorMessage ?? ''" />
            <span v-else class="pl-muted">—</span>
          </div>
        </div>
        <Message v-if="item.token?.expiringSoon" severity="warn" :closable="false" size="small">
          Token expires {{ formatDateTime(item.token.expiresAt) }}.
          {{ item.account?.loginType === 'FACEBOOK_LOGIN' ? 'Reconnect with Facebook to renew it.' : 'PageLens refreshes it automatically; reconnect if this persists.' }}
        </Message>
      </div>
      <div class="pl-account-actions">
        <Button label="Sync now" icon="pi pi-refresh" size="small" :disabled="item.account?.status !== 'ACTIVE' || item.account?.loginType === 'IMPORT'"
          :title="item.account?.loginType === 'IMPORT' ? 'Imported accounts are updated by importing a new CSV' : ''"
          @click="syncNow(item.account!.id!)" />
        <Button label="Posts" icon="pi pi-images" size="small" severity="secondary" outlined
          @click="store.select(item.account!.id!); router.push('/posts')" />
        <Button label="Disconnect" icon="pi pi-trash" size="small" severity="danger" text
          @click="disconnect(item.account!)" />
      </div>
    </section>

    <Dialog v-model:visible="importDialog" header="Import a Meta Business Suite export" modal
      :style="{ width: 'min(640px, 95vw)' }">
      <ol class="pl-steps">
        <li>Open <a :href="BUSINESS_SUITE_URL" target="_blank" rel="noopener">Meta Business Suite → Insights → Content</a>
          and pick the Instagram account.</li>
        <li>Choose <strong>Export data</strong> → Instagram → <strong>Posts</strong> (or Stories), a date range, CSV.</li>
        <li>When the report is ready, download it and choose it below. Re-importing is safe: posts are matched by id
          and each import adds a dated snapshot of their numbers.</li>
      </ol>
      <label class="pl-field">
        <span class="label">Account (only if the file has no "Account ID" column)</span>
        <Select v-model="importAccountId" :options="store.items.map((i) => ({ label: '@' + i.account?.username, value: i.account?.id }))"
          option-label="label" option-value="value" placeholder="From the file" show-clear fluid />
      </label>
      <input type="file" accept=".csv,text/csv" :disabled="importing" class="pl-file" @change="importCsv" />
      <p v-if="importing" class="pl-muted"><i class="pi pi-spin pi-spinner" /> Importing…</p>
      <div v-if="importReport" class="pl-import-report">
        <Message :severity="importReport.posts ? 'success' : 'warn'" :closable="false">
          {{ importReport.posts }} post(s) and {{ importReport.snapshots }} snapshot(s) from {{ importReport.rows }} row(s)
          <template v-if="importReport.accounts?.length"> for {{ importReport.accounts.join(', ') }}</template>.
        </Message>
        <Message v-for="(w, i) in importReport.warnings" :key="'w' + i" severity="warn" :closable="false" size="small">{{ w }}</Message>
        <Message v-for="(s, i) in importReport.skipped" :key="'s' + i" severity="secondary" :closable="false" size="small">Skipped {{ s }}</Message>
        <p class="pl-muted">Columns used: {{ importReport.mappedColumns?.join(', ') }}</p>
      </div>
      <template #footer>
        <Button label="Close" severity="secondary" text @click="importDialog = false" />
      </template>
    </Dialog>

    <Dialog v-model:visible="tokenDialog" header="Connect with a token" modal :style="{ width: 'min(560px, 95vw)' }">
      <p class="pl-muted">
        Use this if the OAuth redirect doesn't work. In the Meta App Dashboard: Instagram → API setup with Instagram
        business login → Generate token. Facebook Login: a user token from Graph API Explorer works too. The token is
        encrypted before it is stored.
      </p>
      <Select v-model="tokenType" :options="loginTypes" option-label="label" option-value="value" fluid />
      <Textarea v-model="tokenValue" rows="4" fluid placeholder="Paste the access token" class="pl-token-input"
        autocomplete="off" spellcheck="false" />
      <template #footer>
        <Button label="Cancel" severity="secondary" text @click="tokenDialog = false" />
        <Button label="Connect" icon="pi pi-check" :loading="busy" :disabled="!tokenValue.trim()" @click="connectWithToken" />
      </template>
    </Dialog>
  </main>
</template>

<style scoped>
.pl-spacer-flex {
  flex: 1;
}
.pl-account {
  display: flex;
  gap: 1rem;
  align-items: flex-start;
  margin-bottom: 1rem;
}
.pl-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  object-fit: cover;
}
.pl-account-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.pl-account-title {
  display: flex;
  gap: 0.6rem;
  align-items: center;
  flex-wrap: wrap;
}
.pl-account-actions {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.pl-steps {
  margin: 0 0 1rem;
  padding-left: 1.2rem;
  line-height: 1.5;
}
.pl-field {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  margin-bottom: 0.75rem;
}
.pl-file {
  margin: 0.25rem 0 0.75rem;
}
.pl-import-report {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.pl-token-input {
  margin-top: 0.75rem;
  font-family: ui-monospace, monospace;
}
</style>
