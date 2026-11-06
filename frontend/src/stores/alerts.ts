import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, call, type Schemas } from '@/api/client'

/** Open alerts (failed syncs, expiring tokens, stale data, backups...), refreshed every minute while logged in. */
export const useAlertsStore = defineStore('alerts', () => {
  const items = ref<Schemas['Alert'][]>([])
  let timer: number | undefined

  const critical = computed(() => items.value.filter((a) => a.severity === 'CRITICAL').length)
  const actionable = computed(() => items.value.filter((a) => a.severity !== 'INFO'))

  function forAccount(id?: number) {
    return items.value.filter((a) => a.accountId === id)
  }

  async function load() {
    try {
      items.value = await call(api.GET('/api/alerts'))
    } catch {
      // keep the last known alerts; the next poll retries
    }
  }

  function start() {
    if (timer) return
    load()
    timer = window.setInterval(load, 60_000)
  }

  function stop() {
    window.clearInterval(timer)
    timer = undefined
    items.value = []
  }

  return { items, critical, actionable, forAccount, load, start, stop }
})
