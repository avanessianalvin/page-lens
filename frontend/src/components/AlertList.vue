<script setup lang="ts">
// Open alerts with what to do about each. Severity is shown as an icon + label, never color alone.
import Message from 'primevue/message'
import type { Schemas } from '@/api/client'
import { formatDateTime } from '@/format'

defineProps<{ alerts: Schemas['Alert'][] }>()

const SEVERITY: Record<string, { severity: 'error' | 'warn' | 'info'; icon: string; label: string }> = {
  CRITICAL: { severity: 'error', icon: 'pi pi-times-circle', label: 'Critical' },
  WARNING: { severity: 'warn', icon: 'pi pi-exclamation-triangle', label: 'Warning' },
  INFO: { severity: 'info', icon: 'pi pi-info-circle', label: 'Info' },
}
</script>

<template>
  <div class="pl-alerts">
    <Message v-for="(a, i) in alerts" :key="i" :severity="SEVERITY[a.severity!]!.severity" :icon="SEVERITY[a.severity!]!.icon"
      :closable="false" size="small">
      <strong>{{ SEVERITY[a.severity!]!.label }}<template v-if="a.account"> · {{ a.account }}</template>:</strong>
      {{ a.message }}
      <span v-if="a.action" class="pl-action">→ {{ a.action }}</span>
      <span v-if="a.since" class="pl-since">({{ formatDateTime(a.since) }})</span>
    </Message>
  </div>
</template>

<style scoped>
.pl-alerts {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  margin-bottom: 1rem;
}
.pl-action {
  font-weight: 600;
  margin-left: 0.35rem;
}
.pl-since {
  opacity: 0.75;
  font-size: 0.85em;
  margin-left: 0.35rem;
}
</style>
