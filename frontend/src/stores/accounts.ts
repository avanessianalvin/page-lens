import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, call, type Schemas } from '@/api/client'

const SELECTED_KEY = 'pagelens.selectedAccount'

export const useAccountsStore = defineStore('accounts', () => {
  const items = ref<Schemas['AccountView'][]>([])
  const selectedId = ref<number | null>(Number(localStorage.getItem(SELECTED_KEY)) || null)

  const selected = computed(() => items.value.find((a) => a.account?.id === selectedId.value)?.account ?? null)

  async function load() {
    items.value = await call(api.GET('/api/accounts'))
    if (!items.value.some((a) => a.account?.id === selectedId.value)) {
      select(items.value[0]?.account?.id ?? null)
    }
  }

  function select(id: number | null) {
    selectedId.value = id
    if (id == null) localStorage.removeItem(SELECTED_KEY)
    else localStorage.setItem(SELECTED_KEY, String(id))
  }

  return { items, selectedId, selected, load, select }
})
