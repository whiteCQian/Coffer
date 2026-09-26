// Reject late responses and cancel requests when the authenticated identity changes.
let epoch = 0
let controller = new AbortController()
export function currentBoundary() { return { epoch, signal: controller.signal } }
export function resetBoundary() {
  controller.abort()
  controller = new AbortController()
  epoch += 1
  window.dispatchEvent(new Event('coffer:identity-cleared'))
}
