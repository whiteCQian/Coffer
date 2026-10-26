import { nextTick, ref, watch } from 'vue'

/** Change the presentation of one drawer instance without remounting its editor. */
export function useDetailPresentation(identity: () => unknown) {
  const expanded = ref(false)
  const panel = ref<HTMLElement>()
  const toggleButton = ref<HTMLButtonElement>()

  async function setExpanded(value: boolean) {
    expanded.value = value
    await nextTick()
    if (value) panel.value?.focus({ preventScroll: true })
    else toggleButton.value?.focus({ preventScroll: true })
  }

  function onKeydown(event: KeyboardEvent) {
    if (!expanded.value || !panel.value) return
    // Nested rename/delete dialogs retain their own keyboard behavior.
    if (!(event.target instanceof Node) || !panel.value.contains(event.target)) return
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      void setExpanded(false)
      return
    }
    if (event.key !== 'Tab') return
    const controls = [...panel.value.querySelectorAll<HTMLElement>(
      'button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]',
    )].filter(element => element.getClientRects().length && element.tabIndex >= 0)
    const first = controls[0], last = controls.at(-1)
    if (!first || !last) { event.preventDefault(); return }
    if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) {
      event.preventDefault(); last.focus()
    } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.value)) {
      event.preventDefault(); first.focus()
    }
  }

  watch(identity, () => { expanded.value = false }, { flush: 'sync' })
  return { expanded, panel, toggleButton, setExpanded, onKeydown }
}
