<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Message from 'primevue/message'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const router = useRouter()
const route = useRoute()

const username = ref('admin')
const password = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

async function submit() {
  busy.value = true
  error.value = null
  try {
    error.value = await session.login(username.value, password.value)
    if (!error.value) {
      const next = typeof route.query.next === 'string' && route.query.next.startsWith('/') ? route.query.next : '/accounts'
      router.push(next)
    }
  } catch {
    error.value = 'Cannot reach the PageLens server. Is app-web running?'
  } finally {
    busy.value = false
    password.value = ''
  }
}
</script>

<template>
  <main class="pl-login">
    <form class="pl-card" @submit.prevent="submit">
      <h1><i class="pi pi-chart-bar" /> PageLens</h1>
      <label for="username">Username</label>
      <InputText id="username" v-model="username" autocomplete="username" fluid />
      <label for="password">Password</label>
      <Password id="password" v-model="password" :feedback="false" toggle-mask autocomplete="current-password" fluid
        input-id="password" />
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <Button type="submit" label="Log in" :loading="busy" :disabled="!password" fluid />
    </form>
  </main>
</template>

<style scoped>
.pl-login {
  min-height: 100vh;
  display: grid;
  place-items: center;
}
form {
  width: min(360px, 90vw);
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}
h1 {
  margin: 0 0 0.5rem;
  font-size: 1.4rem;
  color: var(--p-primary-color);
}
label {
  font-size: 0.85rem;
  color: var(--p-text-muted-color);
}
</style>
