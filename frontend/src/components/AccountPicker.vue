<script setup lang="ts">
import { onMounted } from 'vue'
import Select from 'primevue/select'
import { useAccountsStore } from '@/stores/accounts'

const store = useAccountsStore()
onMounted(() => {
  if (store.items.length === 0) store.load()
})
</script>

<template>
  <Select :model-value="store.selectedId" :options="store.items.map((a) => a.account)" option-label="username"
    option-value="id" placeholder="Account" aria-label="Account" @update:model-value="store.select">
    <template #value="{ value }">@{{ store.items.find((a) => a.account?.id === value)?.account?.username ?? '…' }}</template>
    <template #option="{ option }">@{{ option.username }}</template>
  </Select>
</template>
