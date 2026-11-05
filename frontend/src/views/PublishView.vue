<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import InputNumber from 'primevue/inputnumber'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import Tag from 'primevue/tag'
import Textarea from 'primevue/textarea'
import ToggleSwitch from 'primevue/toggleswitch'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { api, call, xsrfToken, type Schemas } from '@/api/client'
import AccountPicker from '@/components/AccountPicker.vue'
import { useAccountsStore } from '@/stores/accounts'
import { useSessionStore } from '@/stores/session'
import { formatDateTime } from '@/format'

type Kind = 'IMAGE' | 'REEL' | 'STORY' | 'CAROUSEL'
type ItemType = 'IMAGE' | 'VIDEO'
interface FormItem { type: ItemType; url: string; uploadId: string | null; uploadName: string | null; uploading: boolean }

const store = useAccountsStore()
const session = useSessionStore()
const confirm = useConfirm()
const toast = useToast()

const IMAGE_HINT = 'Public JPEG URL (https)'
const VIDEO_HINT = 'Public MP4/MOV URL (https)'

const KINDS = [
  { label: 'Photo', value: 'IMAGE' }, { label: 'Reel', value: 'REEL' },
  { label: 'Story', value: 'STORY' }, { label: 'Carousel', value: 'CAROUSEL' },
]
const kind = ref<Kind>('IMAGE')
const caption = ref('')
const items = ref<FormItem[]>([])
const altText = ref('')
const shareToFeed = ref(true)
const coverUrl = ref('')
const thumbOffset = ref<number | null>(null)
const idempotencyKey = ref(crypto.randomUUID())

const report = ref<Schemas['WriteResult'] | null>(null)
const busy = ref(false)
const jobs = ref<Schemas['PublishJob'][]>([])
let poll: number | undefined

const account = computed(() => store.selected)
const canUpload = computed(() => account.value?.loginType === 'FACEBOOK_LOGIN')

function blankItem(type: ItemType): FormItem {
  return { type, url: '', uploadId: null, uploadName: null, uploading: false }
}

function resetItems() {
  items.value = kind.value === 'CAROUSEL' ? [blankItem('IMAGE'), blankItem('IMAGE')]
    : [blankItem(kind.value === 'REEL' ? 'VIDEO' : 'IMAGE')]
  report.value = null
}

// Caption counters mirror the server's rules (2200 chars, 30 hashtags, 20 mentions).
const counts = computed(() => ({
  chars: [...caption.value].length,
  hashtags: (caption.value.match(/(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+/gu) ?? []).length,
  mentions: (caption.value.match(/(?<![\p{L}\p{N}_.])@[A-Za-z0-9._]+/gu) ?? []).length,
}))

function body(dryRun: boolean) {
  return {
    accountId: store.selectedId!,
    kind: kind.value,
    caption: kind.value === 'STORY' ? undefined : caption.value || undefined,
    items: items.value.map((i) => ({ type: i.type, url: i.uploadId ? undefined : i.url.trim(), uploadId: i.uploadId ?? undefined })),
    options: {
      shareToFeed: kind.value === 'REEL' ? shareToFeed.value : undefined,
      coverUrl: kind.value === 'REEL' && coverUrl.value ? coverUrl.value.trim() : undefined,
      thumbOffsetMs: kind.value === 'REEL' && thumbOffset.value != null ? thumbOffset.value : undefined,
      altText: kind.value === 'IMAGE' && altText.value ? altText.value : undefined,
    },
    dryRun,
    idempotencyKey: idempotencyKey.value,
  }
}

async function check() {
  busy.value = true
  try {
    const { data, error } = await api.POST('/api/publish', { body: body(true) })
    report.value = (data ?? error) as Schemas['WriteResult']
  } finally {
    busy.value = false
  }
}

function askPublish() {
  const n = items.value.length
  confirm.require({
    header: `Publish to @${account.value?.username}?`,
    message: `This posts a ${KINDS.find((k) => k.value === kind.value)!.label.toLowerCase()}${n > 1 ? ` with ${n} items` : ''} `
      + 'to Instagram right away. It can\'t be edited or deleted from PageLens afterwards.',
    acceptProps: { label: 'Publish now', severity: 'primary' },
    rejectProps: { label: 'Cancel', severity: 'secondary', outlined: true },
    accept: publish,
  })
}

async function publish() {
  busy.value = true
  try {
    const { data, error, response } = await api.POST('/api/publish', { body: body(false) })
    const result = (data ?? error) as Schemas['WriteResult'] & { detail?: string }
    if (response.status === 403) {
      toast.add({ severity: 'error', summary: 'Write actions are disabled', detail: result.detail, life: 8000 })
      return
    }
    report.value = result
    if (response.status === 202) {
      toast.add({ severity: 'info', summary: 'Publishing started', detail: 'Status updates below.', life: 4000 })
      idempotencyKey.value = crypto.randomUUID() // the next post gets a new key
      await loadJobs()
    }
  } finally {
    busy.value = false
  }
}

async function upload(item: FormItem, ev: Event) {
  const file = (ev.target as HTMLInputElement).files?.[0]
  if (!file) return
  item.uploading = true
  try {
    const form = new FormData()
    form.append('file', file)
    const res = await fetch('/api/publish/uploads', {
      method: 'POST', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': xsrfToken() ?? '' }, body: form,
    })
    const json = await res.json()
    if (!res.ok) throw new Error(json.detail ?? `Upload failed (${res.status})`)
    item.uploadId = json.id
    item.uploadName = file.name
    item.url = ''
  } catch (e) {
    toast.add({ severity: 'error', summary: 'Upload failed', detail: String(e), life: 8000 })
  } finally {
    item.uploading = false
  }
}

function clearUpload(item: FormItem) {
  item.uploadId = null
  item.uploadName = null
}

async function retry(job: Schemas['PublishJob']) {
  const { response, error, data } = await api.POST('/api/publish/{id}/retry', { params: { path: { id: job.id! } } })
  if (!response.ok) {
    const r = (data ?? error) as unknown as { errors?: string[]; detail?: string } | undefined
    toast.add({ severity: 'error', summary: 'Retry refused', detail: (r?.errors ?? [r?.detail ?? 'Request failed']).join(' '), life: 8000 })
  }
  await loadJobs()
}

async function loadJobs() {
  if (!store.selectedId) return
  jobs.value = await call(api.GET('/api/publish', { params: { query: { accountId: store.selectedId, limit: 20 } } }))
}

function statusSeverity(s?: string) {
  return s === 'PUBLISHED' ? 'success' : s === 'FAILED' ? 'danger' : 'info'
}

watch(kind, resetItems)
watch(() => store.selectedId, () => {
  report.value = null
  loadJobs()
})
onMounted(async () => {
  resetItems()
  if (store.items.length === 0) await store.load()
  await loadJobs()
  poll = window.setInterval(() => {
    if (jobs.value.some((j) => !['PUBLISHED', 'FAILED'].includes(j.status ?? ''))) loadJobs()
  }, 3000)
})
onUnmounted(() => window.clearInterval(poll))
</script>

<template>
  <main class="pl-page">
    <h1>Publish</h1>
    <Message v-if="!session.writeActionsEnabled" severity="warn" :closable="false">
      {{ session.writeActionsHint ?? 'Write actions are disabled.' }} You can still run checks.
    </Message>
    <div class="pl-toolbar">
      <AccountPicker />
      <SelectButton v-model="kind" :options="KINDS" option-label="label" option-value="value" :allow-empty="false" />
    </div>

    <section class="pl-card pl-form">
      <div v-for="(item, i) in items" :key="i" class="pl-item">
        <span class="pl-muted pl-idx">{{ items.length > 1 ? i + 1 : '' }}</span>
        <Select v-if="kind === 'CAROUSEL' || kind === 'STORY'" v-model="item.type"
          :options="[{ label: 'Image', value: 'IMAGE' }, { label: 'Video', value: 'VIDEO' }]" option-label="label"
          option-value="value" size="small" />
        <span v-else class="pl-muted">{{ item.type === 'VIDEO' ? 'Video' : 'Image' }}</span>
        <template v-if="item.uploadId">
          <Tag severity="secondary" :value="`Uploaded: ${item.uploadName}`" />
          <Button icon="pi pi-times" text size="small" aria-label="Remove upload" @click="clearUpload(item)" />
        </template>
        <InputText v-else v-model="item.url" class="pl-url" size="small"
          :placeholder="item.type === 'IMAGE' ? IMAGE_HINT : VIDEO_HINT" />
        <label v-if="item.type === 'VIDEO' && canUpload && !item.uploadId" class="p-button p-button-sm p-button-text">
          <input type="file" accept="video/mp4,video/quicktime,.mp4,.mov" hidden @change="upload(item, $event)" />
          <i class="pi pi-upload" />&nbsp;{{ item.uploading ? 'Uploading…' : 'Upload file' }}
        </label>
        <Button v-if="kind === 'CAROUSEL' && items.length > 2" icon="pi pi-trash" text size="small" severity="danger"
          aria-label="Remove item" @click="items.splice(i, 1)" />
      </div>
      <Button v-if="kind === 'CAROUSEL' && items.length < 20" label="Add item" icon="pi pi-plus" text size="small"
        @click="items.push(blankItem('IMAGE'))" />
      <p class="pl-muted pl-small">
        Instagram downloads media from a public URL: JPEG images up to 8 MB; MP4/MOV videos.
        <template v-if="canUpload">Videos can also be uploaded from this computer.</template>
        <template v-else>Uploading a local video needs an account connected with Facebook Login.</template>
      </p>

      <template v-if="kind !== 'STORY'">
        <label for="caption">Caption</label>
        <Textarea id="caption" v-model="caption" rows="5" dir="auto" class="pl-text" fluid auto-resize />
        <div class="pl-counts pl-small" :class="{ over: counts.chars > 2200 || counts.hashtags > 30 || counts.mentions > 20 }">
          {{ counts.chars }}/2200 characters · {{ counts.hashtags }}/30 hashtags · {{ counts.mentions }}/20 mentions
        </div>
      </template>
      <p v-else class="pl-muted pl-small">Stories have no caption through Instagram's API.</p>

      <template v-if="kind === 'IMAGE'">
        <label for="alt">Alt text <span class="pl-muted">(optional, for screen readers)</span></label>
        <InputText id="alt" v-model="altText" fluid maxlength="1000" />
      </template>
      <div v-if="kind === 'REEL'" class="pl-reel">
        <label class="pl-inline"><ToggleSwitch v-model="shareToFeed" /> Also show in profile grid</label>
        <InputText v-model="coverUrl" placeholder="Cover image URL (optional, JPEG)" size="small" class="pl-url" />
        <InputNumber v-model="thumbOffset" placeholder="Thumbnail at (ms)" :min="0" size="small" />
      </div>

      <div class="pl-actions">
        <Button label="Check (dry run)" icon="pi pi-check-circle" severity="secondary" :loading="busy"
          :disabled="!store.selectedId" @click="check" />
        <Button label="Publish…" icon="pi pi-send" :loading="busy"
          :disabled="!store.selectedId || !session.writeActionsEnabled" @click="askPublish" />
      </div>

      <div v-if="report" class="pl-report">
        <Message v-for="e in report.errors" :key="e" severity="error" :closable="false" size="small">{{ e }}</Message>
        <Message v-for="w in report.warnings" :key="w" severity="warn" :closable="false" size="small">{{ w }}</Message>
        <Message v-if="report.ok && report.dryRun" severity="success" :closable="false" size="small">
          Looks good. Nothing was sent to Instagram.
          <template v-if="report.details?.quotaTotal">{{ report.details.quotaUsed }} of {{ report.details.quotaTotal }} posts used in the last 24 hours.</template>
        </Message>
      </div>
    </section>

    <section class="pl-card">
      <h2>Recent publish jobs</h2>
      <DataTable :value="jobs" size="small" striped-rows>
        <Column header="Created"><template #body="{ data }">{{ formatDateTime(data.createdAt) }}</template></Column>
        <Column field="kind" header="Kind" />
        <Column header="Caption"><template #body="{ data }"><span class="pl-text" dir="auto">{{ (data.caption ?? '').slice(0, 60) }}</span></template></Column>
        <Column header="By"><template #body="{ data }">{{ data.actor }}</template></Column>
        <Column header="Status"><template #body="{ data }"><Tag :value="data.status" :severity="statusSeverity(data.status)" /></template></Column>
        <Column header="">
          <template #body="{ data }">
            <a v-if="data.permalink" :href="data.permalink" target="_blank" rel="noopener noreferrer">View <i class="pi pi-external-link" /></a>
            <Button v-else-if="data.status === 'FAILED'" label="Retry" size="small" text
              :disabled="!session.writeActionsEnabled" @click="retry(data)" />
          </template>
        </Column>
        <Column header="Error"><template #body="{ data }"><span class="pl-small">{{ data.errorMessage }}</span></template></Column>
        <template #empty><span class="pl-muted">Nothing published from PageLens yet.</span></template>
      </DataTable>
      <p class="pl-muted pl-small">
        Instagram allows 50 API-published posts per 24 hours. Posts can't be scheduled, edited or deleted from
        PageLens: Instagram's API doesn't support editing, and deleting isn't enabled here. Use the Instagram app for that.
      </p>
    </section>
  </main>
</template>

<style scoped>
.pl-card {
  margin-bottom: 1rem;
}
.pl-form {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}
.pl-item {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}
.pl-idx {
  width: 1.2rem;
  text-align: right;
}
.pl-url {
  flex: 1;
  min-width: 280px;
}
label {
  font-size: 0.85rem;
}
.pl-counts {
  color: var(--p-text-muted-color);
  text-align: right;
}
.pl-counts.over {
  color: var(--p-red-600);
  font-weight: 600;
}
.pl-reel,
.pl-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
}
.pl-inline {
  display: inline-flex;
  gap: 0.4rem;
  align-items: center;
}
.pl-report {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.pl-small {
  font-size: 0.8rem;
}
h2 {
  font-size: 1.05rem;
  margin: 0 0 0.5rem;
}
</style>
