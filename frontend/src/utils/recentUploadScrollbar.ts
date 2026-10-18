/** Reveal the recent-upload scrollbar only while the list is moving. */
export function installRecentUploadScrollbar() {
  const timers = new WeakMap<HTMLElement, ReturnType<typeof setTimeout>>()
  document.addEventListener('scroll', event => {
    const list = event.target
    if (!(list instanceof HTMLElement) || !list.matches('.web-home .recent-list')) return
    clearTimeout(timers.get(list))
    list.classList.add('is-scrolling')
    timers.set(list, setTimeout(() => {
      list.classList.remove('is-scrolling')
      timers.delete(list)
    }, 900))
  }, { capture: true, passive: true })
}
