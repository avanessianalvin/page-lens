import { createRouter, createWebHistory } from 'vue-router'
import { setUnauthorizedHandler } from '@/api/client'
import { useSessionStore } from '@/stores/session'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/overview' },
    { path: '/overview', name: 'overview', component: () => import('@/views/OverviewView.vue') },
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/accounts', name: 'accounts', component: () => import('@/views/AccountsView.vue') },
    { path: '/posts', name: 'posts', component: () => import('@/views/PostsView.vue') },
    { path: '/posts/:id', name: 'post', component: () => import('@/views/PostDetailView.vue'), props: true },
    { path: '/audience', name: 'audience', component: () => import('@/views/AudienceView.vue') },
    { path: '/analysis', name: 'analysis', component: () => import('@/views/AnalysisView.vue') },
    { path: '/publish', name: 'publish', component: () => import('@/views/PublishView.vue') },
    { path: '/logs', name: 'logs', component: () => import('@/views/LogsView.vue') },
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/overview' },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  if (!session.checked) {
    try {
      await session.refresh()
    } catch {
      // server unreachable: let the login page show the error
    }
  }
  if (!to.meta.public && !session.authenticated) {
    return { name: 'login', query: { next: to.fullPath } }
  }
  if (to.name === 'login' && session.authenticated) {
    return { name: 'overview' }
  }
})

setUnauthorizedHandler(() => {
  const session = useSessionStore()
  session.authenticated = false
  if (router.currentRoute.value.name !== 'login') {
    router.push({ name: 'login', query: { next: router.currentRoute.value.fullPath } })
  }
})

export default router
