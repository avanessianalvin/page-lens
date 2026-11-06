<script setup lang="ts">
import { watch } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import Badge from 'primevue/badge'
import Button from 'primevue/button'
import Toast from 'primevue/toast'
import ConfirmDialog from 'primevue/confirmdialog'
import { useAlertsStore } from '@/stores/alerts'
import { useSessionStore } from '@/stores/session'
import { useThemeStore } from '@/stores/theme'

const session = useSessionStore()
const alerts = useAlertsStore()
useThemeStore() // applies the saved light/dark choice on load
const router = useRouter()

watch(() => session.authenticated, (on) => (on ? alerts.start() : alerts.stop()), { immediate: true })

async function logout() {
  await session.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <Toast />
  <ConfirmDialog />
  <header v-if="session.authenticated" class="pl-header">
    <div class="pl-header-inner">
      <RouterLink to="/overview" class="pl-brand"><i class="pi pi-chart-bar" /> PageLens</RouterLink>
      <nav>
        <RouterLink to="/overview">Overview</RouterLink>
        <RouterLink to="/posts">Posts</RouterLink>
        <RouterLink to="/audience">Audience</RouterLink>
        <RouterLink to="/analysis">Analysis</RouterLink>
        <RouterLink to="/publish">Publish</RouterLink>
        <RouterLink to="/accounts">Accounts</RouterLink>
        <RouterLink to="/logs">Logs</RouterLink>
        <RouterLink to="/settings">Settings</RouterLink>
      </nav>
      <span class="pl-spacer" />
      <RouterLink v-if="alerts.actionable.length" to="/overview" class="pl-bell"
        :title="`${alerts.actionable.length} alert(s) need attention`"
        :aria-label="`${alerts.actionable.length} alerts`">
        <i class="pi pi-bell" />
        <Badge :value="alerts.actionable.length" :severity="alerts.critical ? 'danger' : 'warn'" />
      </RouterLink>
      <span class="pl-muted">{{ session.username }} · {{ session.timeZone }}</span>
      <Button label="Log out" icon="pi pi-sign-out" text size="small" @click="logout" />
    </div>
  </header>
  <RouterView />
</template>

<style scoped>
.pl-header {
  background: var(--p-content-background);
  border-bottom: 1px solid var(--p-content-border-color);
}
.pl-header-inner {
  max-width: 1280px;
  margin: 0 auto;
  padding: 0.5rem 1.5rem;
  display: flex;
  align-items: center;
  gap: 1.5rem;
}
.pl-brand {
  font-weight: 700;
  font-size: 1.1rem;
  color: var(--p-primary-color);
  text-decoration: none;
}
nav {
  display: flex;
  gap: 1rem;
}
nav a {
  color: var(--p-text-muted-color);
  text-decoration: none;
  padding: 0.35rem 0;
  border-bottom: 2px solid transparent;
}
nav a.router-link-active {
  color: var(--p-text-color);
  border-bottom-color: var(--p-primary-color);
}
.pl-bell {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  color: var(--p-text-color);
  text-decoration: none;
}
.pl-spacer {
  flex: 1;
}
</style>
