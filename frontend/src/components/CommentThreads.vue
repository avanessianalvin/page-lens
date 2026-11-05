<script setup lang="ts">
// Comment threads with moderation: reply, hide/unhide, delete. Every action is confirmed and audited server-side;
// buttons are disabled while write actions are off.
import { ref } from 'vue'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import Textarea from 'primevue/textarea'
import { useConfirm } from 'primevue/useconfirm'
import { useToast } from 'primevue/usetoast'
import { api, type Schemas } from '@/api/client'
import { useSessionStore } from '@/stores/session'
import { formatDateTime, formatNumber } from '@/format'

const props = defineProps<{ comments: Schemas['Comment'][] }>()
const emit = defineEmits<{ changed: [] }>()

const session = useSessionStore()
const confirm = useConfirm()
const toast = useToast()
const replyingTo = ref<number | null>(null)
const replyText = ref('')
const busy = ref(false)

type Result = Schemas['WriteResult'] & { detail?: string }

function report(r: Result | undefined, status: number, done: string) {
  if (status === 403) {
    toast.add({ severity: 'error', summary: 'Write actions are disabled', detail: r?.detail, life: 8000 })
  } else if (r?.ok) {
    toast.add({ severity: 'success', summary: done, detail: r.warnings?.join(' '), life: 4000 })
    emit('changed')
  } else {
    toast.add({ severity: 'error', summary: 'Not done', detail: (r?.errors ?? [r?.detail ?? 'Request failed']).join(' '), life: 8000 })
  }
}

async function sendReply(c: Schemas['Comment']) {
  busy.value = true
  try {
    const { data, error, response } = await api.POST('/api/comments/{id}/reply', {
      params: { path: { id: c.id! } }, body: { message: replyText.value },
    })
    report((data ?? error) as Result, response.status, 'Reply posted')
    if (response.ok) {
      replyingTo.value = null
      replyText.value = ''
    }
  } finally {
    busy.value = false
  }
}

function confirmReply(c: Schemas['Comment']) {
  confirm.require({
    header: `Reply to @${c.username}?`,
    message: 'The reply is posted publicly on Instagram right away.',
    acceptProps: { label: 'Post reply' },
    rejectProps: { label: 'Cancel', severity: 'secondary', outlined: true },
    accept: () => sendReply(c),
  })
}

function toggleHidden(c: Schemas['Comment']) {
  const hide = !c.hidden
  confirm.require({
    header: hide ? `Hide @${c.username}'s comment?` : `Unhide @${c.username}'s comment?`,
    message: hide ? 'Hidden comments stay visible to their author but not to anyone else. You can unhide later.'
      : 'The comment becomes visible to everyone again.',
    acceptProps: { label: hide ? 'Hide' : 'Unhide' },
    rejectProps: { label: 'Cancel', severity: 'secondary', outlined: true },
    accept: async () => {
      const { data, error, response } = await api.POST('/api/comments/{id}/hide', {
        params: { path: { id: c.id! } }, body: { hidden: hide },
      })
      report((data ?? error) as Result, response.status, hide ? 'Comment hidden' : 'Comment visible again')
    },
  })
}

function remove(c: Schemas['Comment']) {
  confirm.require({
    header: `Delete @${c.username}'s comment?`,
    message: 'This permanently deletes the comment and its replies on Instagram. It cannot be undone.',
    acceptProps: { label: 'Delete permanently', severity: 'danger' },
    rejectProps: { label: 'Cancel', severity: 'secondary', outlined: true },
    accept: async () => {
      const { data, error, response } = await api.DELETE('/api/comments/{id}', { params: { path: { id: c.id! } } })
      report((data ?? error) as Result, response.status, 'Comment deleted')
    },
  })
}
</script>

<template>
  <p v-if="props.comments.length === 0" class="pl-muted">No comments synced. Comments are pulled for posts from the last 14 days.</p>
  <ul class="pl-comments">
    <li v-for="c in props.comments" :key="c.id">
      <div class="pl-comment">
        <strong>@{{ c.username ?? 'unknown' }}</strong>
        <span class="pl-muted">{{ formatDateTime(c.postedAt) }} · {{ formatNumber(c.likeCount) }} likes</span>
        <Tag v-if="c.hidden" value="hidden" severity="warn" />
        <span class="pl-spacer" />
        <span class="pl-acts">
          <Button label="Reply" icon="pi pi-reply" text size="small" :disabled="!session.writeActionsEnabled || c.hidden"
            :title="c.hidden ? 'Instagram doesn\'t allow replies to hidden comments' : ''"
            @click="replyingTo = replyingTo === c.id ? null : c.id!" />
          <Button :label="c.hidden ? 'Unhide' : 'Hide'" :icon="c.hidden ? 'pi pi-eye' : 'pi pi-eye-slash'" text size="small"
            :disabled="!session.writeActionsEnabled" @click="toggleHidden(c)" />
          <Button label="Delete" icon="pi pi-trash" text size="small" severity="danger"
            :disabled="!session.writeActionsEnabled" @click="remove(c)" />
        </span>
        <div class="pl-text" dir="auto">{{ c.text }}</div>
      </div>
      <div v-if="replyingTo === c.id" class="pl-reply">
        <Textarea v-model="replyText" rows="2" dir="auto" class="pl-text" fluid auto-resize maxlength="2200"
          :placeholder="`Reply to @${c.username}`" />
        <Button label="Send" icon="pi pi-send" size="small" :loading="busy" :disabled="!replyText.trim()"
          @click="confirmReply(c)" />
      </div>
      <ul v-if="c.replies?.length" class="pl-replies">
        <li v-for="r in c.replies" :key="r.id" class="pl-comment">
          <strong>@{{ r.username ?? 'unknown' }}</strong>
          <span class="pl-muted">{{ formatDateTime(r.postedAt) }}</span>
          <Tag v-if="r.hidden" value="hidden" severity="warn" />
          <span class="pl-spacer" />
          <span class="pl-acts">
            <Button :label="r.hidden ? 'Unhide' : 'Hide'" text size="small" :disabled="!session.writeActionsEnabled"
              @click="toggleHidden(r)" />
            <Button label="Delete" text size="small" severity="danger" :disabled="!session.writeActionsEnabled"
              @click="remove(r)" />
          </span>
          <div class="pl-text" dir="auto">{{ r.text }}</div>
        </li>
      </ul>
    </li>
  </ul>
  <p v-if="!session.writeActionsEnabled" class="pl-muted pl-small">
    Replying, hiding and deleting are off. {{ session.writeActionsHint }}
  </p>
</template>

<style scoped>
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
.pl-spacer {
  flex: 1;
}
.pl-acts {
  display: inline-flex;
  gap: 0.1rem;
}
.pl-reply {
  display: flex;
  gap: 0.5rem;
  align-items: flex-end;
  margin: 0.4rem 0 0 1.5rem;
}
.pl-small {
  font-size: 0.8rem;
}
</style>
