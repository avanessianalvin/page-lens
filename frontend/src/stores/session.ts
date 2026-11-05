import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, call, xsrfToken } from '@/api/client'

/** Login state plus the server settings the UI needs (display time zone). */
export const useSessionStore = defineStore('session', () => {
  const authenticated = ref(false)
  const username = ref<string | null>(null)
  const timeZone = ref<string>(Intl.DateTimeFormat().resolvedOptions().timeZone)
  const checked = ref(false)
  /** Server-side master switch for replying, hiding, deleting comments and publishing. */
  const writeActionsEnabled = ref(false)
  const writeActionsHint = ref<string | null>(null)

  async function refresh() {
    const s = await call(api.GET('/api/auth/session'))
    authenticated.value = !!s.authenticated
    username.value = s.username ?? null
    checked.value = true
    if (authenticated.value) {
      const [settings, writes] = await Promise.all([
        call(api.GET('/api/settings')),
        call(api.GET('/api/write-status')),
      ])
      if (settings.timeZone) timeZone.value = settings.timeZone
      writeActionsEnabled.value = !!writes.writeActionsEnabled
      writeActionsHint.value = writes.howToEnable ?? null
    }
  }

  /** Spring form login (form-encoded, not part of the OpenAPI spec). */
  async function login(user: string, password: string): Promise<string | null> {
    if (!xsrfToken()) await refresh() // make sure the XSRF cookie exists
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': xsrfToken() ?? '' },
      body: new URLSearchParams({ username: user, password }),
    })
    if (res.ok) {
      await refresh()
      return null
    }
    const body = await res.json().catch(() => ({}))
    return body.error ?? `Login failed (${res.status})`
  }

  async function logout() {
    await fetch('/api/auth/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'X-XSRF-TOKEN': xsrfToken() ?? '' },
    })
    authenticated.value = false
    username.value = null
  }

  return { authenticated, username, timeZone, checked, writeActionsEnabled, writeActionsHint, refresh, login, logout }
})
