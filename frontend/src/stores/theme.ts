import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

export type ThemeMode = 'system' | 'light' | 'dark'
const KEY = 'pagelens.theme'

/** Light / dark / follow-the-OS. PrimeVue switches on the `pl-dark` class on <html>. */
export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>((localStorage.getItem(KEY) as ThemeMode) || 'system')
  const media = window.matchMedia('(prefers-color-scheme: dark)')
  const systemDark = ref(media.matches)
  media.addEventListener('change', (e) => (systemDark.value = e.matches))

  const dark = computed(() => mode.value === 'dark' || (mode.value === 'system' && systemDark.value))

  function apply() {
    document.documentElement.classList.toggle('pl-dark', dark.value)
    document.documentElement.style.colorScheme = dark.value ? 'dark' : 'light'
  }

  watch(dark, apply, { immediate: true })
  watch(mode, (m) => localStorage.setItem(KEY, m))

  return { mode, dark }
})
