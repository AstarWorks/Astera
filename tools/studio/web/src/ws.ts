import { ref, onUnmounted, shallowRef } from 'vue'
import type { McModel, SelectedFace } from './types'
import type { CameraSnapshot } from './components/ThreeViewport.vue'

export type ReferenceImage =
  | { kind: 'path'; value: string }
  | { kind: 'url'; value: string }
  | { kind: 'base64'; value: string }

export type ServerState = {
  model: McModel | null
  slug: string | null
  scope: 'samples' | 'content' | null
  selectedFace: SelectedFace | null
  referenceImage: ReferenceImage | null
}

type ServerMessage =
  | { type: 'snapshot'; state: ServerState }
  | { type: 'model_changed'; model: McModel | null; slug: string | null; scope: 'samples' | 'content' | null }
  | { type: 'selection_changed'; selectedFace: SelectedFace | null }
  | { type: 'reference_changed'; reference: ReferenceImage | null }
  | { type: 'request_screenshot'; request_id: string; camera?: string }
  | { type: 'pong' }

type ClientMessage =
  | { type: 'face_selected'; selectedFace: SelectedFace | null }
  | {
      type: 'screenshot_result'
      request_id: string
      png_base64: string
      camera: CameraSnapshot
    }
  | { type: 'ping' }

/**
 * Connect to the Studio WebSocket bridge. The composable returns reactive
 * refs that mirror server-side state plus a sender for client-originated
 * messages. Auto-reconnects with exponential backoff up to 30s.
 */
export function useStudioWs(opts?: {
  onScreenshotRequest?: (request_id: string, camera?: string) => void
}) {
  const connected = ref(false)
  const model = shallowRef<McModel | null>(null)
  const slug = ref<string | null>(null)
  const scope = ref<'samples' | 'content' | null>(null)
  const selectedFace = ref<SelectedFace | null>(null)
  const referenceImage = ref<ReferenceImage | null>(null)

  let socket: WebSocket | null = null
  let retryDelay = 500
  let closed = false
  let reconnectHandle: ReturnType<typeof setTimeout> | null = null

  function send(msg: ClientMessage) {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(msg))
    }
  }

  function handle(msg: ServerMessage) {
    switch (msg.type) {
      case 'snapshot':
        model.value = msg.state.model
        slug.value = msg.state.slug
        scope.value = msg.state.scope
        selectedFace.value = msg.state.selectedFace
        referenceImage.value = msg.state.referenceImage
        break
      case 'model_changed':
        model.value = msg.model
        slug.value = msg.slug
        scope.value = msg.scope
        break
      case 'selection_changed':
        selectedFace.value = msg.selectedFace
        break
      case 'reference_changed':
        referenceImage.value = msg.reference
        break
      case 'request_screenshot':
        opts?.onScreenshotRequest?.(msg.request_id, msg.camera)
        break
      case 'pong':
        break
    }
  }

  function connect() {
    if (closed) return
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${proto}//${location.host}/ws`
    socket = new WebSocket(url)
    socket.addEventListener('open', () => {
      connected.value = true
      retryDelay = 500
    })
    socket.addEventListener('message', (ev) => {
      try {
        handle(JSON.parse(ev.data as string) as ServerMessage)
      } catch (e) {
        console.warn('[ws] bad message:', e)
      }
    })
    socket.addEventListener('close', () => {
      connected.value = false
      socket = null
      if (closed) return
      reconnectHandle = setTimeout(connect, retryDelay)
      retryDelay = Math.min(retryDelay * 2, 30_000)
    })
    socket.addEventListener('error', () => {
      // 'close' will fire after error; reconnect handled there.
    })
  }

  function disconnect() {
    closed = true
    if (reconnectHandle) clearTimeout(reconnectHandle)
    socket?.close()
    socket = null
  }

  onUnmounted(disconnect)

  connect()

  return {
    connected,
    model,
    slug,
    scope,
    selectedFace,
    referenceImage,
    send,
  }
}
