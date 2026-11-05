import createClient, { type Middleware } from 'openapi-fetch'
import type { components, paths } from './schema'

export type Schemas = components['schemas']

/** Spring Security's SPA CSRF mode: echo the XSRF-TOKEN cookie in a header on unsafe requests. */
export function xsrfToken(): string | undefined {
  const match = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))
  return match ? decodeURIComponent(match.substring('XSRF-TOKEN='.length)) : undefined
}

let onUnauthorized: () => void = () => {}

/** The router registers this so a 401 anywhere sends the user to the login page. */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

const security: Middleware = {
  onRequest({ request }) {
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method)) {
      const token = xsrfToken()
      if (token) request.headers.set('X-XSRF-TOKEN', token)
    }
    request.headers.set('X-Correlation-Id', `ui-${crypto.randomUUID()}`)
    return request
  },
  onResponse({ response }) {
    if (response.status === 401) onUnauthorized()
    return response
  },
}

/** Typed client generated from the backend's OpenAPI spec (npm run gen:api). */
export const api = createClient<paths>({ baseUrl: '', credentials: 'same-origin' })
api.use(security)

/** Problem-detail message from a failed call, or a generic one. */
export function errorMessage(error: unknown): string {
  if (error && typeof error === 'object' && 'detail' in error && typeof error.detail === 'string') {
    return error.detail
  }
  return 'Request failed'
}

/** Unwraps an openapi-fetch result, throwing a readable Error on failure. */
export async function call<T>(promise: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await promise
  if (error !== undefined || !response.ok) {
    throw new Error(errorMessage(error) + ` (${response.status})`)
  }
  return data as T
}
