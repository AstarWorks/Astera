/**
 * WebSocket bridge — pushes state changes to all connected browsers and
 * receives client-originated events (face selection, screenshot results).
 *
 * The transport is intentionally framework-agnostic: it speaks to anything
 * exposing `send(string)`. Under Nuxt 4, the binding lives at
 * server/routes/ws.ts and forwards crossws Peer events here. Pre-Nuxt
 * Studio bound directly to Bun.serve via the same shape.
 *
 * One singleton subscribes to state.ts on module load and broadcasts every
 * state event to every connected client.
 */
import {
  getState,
  subscribe,
  setSelectedFace,
  type StateEvent,
} from './state'
import type { SelectedFace } from '#shared/types'
import type { CameraSnapshot } from './project'

export interface WsPeer {
  readonly id?: string
  send(data: string): void
}

type ClientMessage =
  | { type: 'face_selected'; selectedFace: SelectedFace | null }
  | {
      type: 'screenshot_result'
      request_id: string
      png_base64: string
      camera: CameraSnapshot
    }
  | { type: 'ping' }

export type ScreenshotPayload = {
  png_base64: string
  camera: CameraSnapshot
}

type ServerMessage =
  | { type: 'snapshot'; state: ReturnType<typeof getState> }
  | StateEvent
  | { type: 'request_screenshot'; request_id: string; camera?: string }
  | { type: 'pong' }

const clients = new Set<WsPeer>()

// Pending screenshot requests keyed by request_id.
const pendingScreenshots = new Map<
  string,
  {
    resolve: (payload: ScreenshotPayload) => void
    reject: (err: Error) => void
    timeout: ReturnType<typeof setTimeout>
  }
>()

function send(peer: WsPeer, msg: ServerMessage) {
  try {
    peer.send(JSON.stringify(msg))
  } catch (e) {
    console.error('[ws] send failed:', e)
  }
}

function broadcast(msg: ServerMessage) {
  for (const peer of clients) send(peer, msg)
}

subscribe((ev) => broadcast(ev))

export function wsClientCount(): number {
  return clients.size
}

/**
 * Request a screenshot from any connected browser. Used by REST/CLI handlers.
 * Returns `{png_base64, camera}` — the camera snapshot is what the server-side
 * projection (B9 / ADR-0062 §5) needs to map part bboxes to pixel coords for
 * `crop_model_view` without re-rendering.
 */
export function requestScreenshot(
  camera?: string,
  timeoutMs = 8000,
): Promise<ScreenshotPayload> {
  if (clients.size === 0) {
    return Promise.reject(new Error('no browser connected — open http://localhost:3007/models'))
  }
  const request_id = crypto.randomUUID()
  return new Promise<ScreenshotPayload>((resolve, reject) => {
    const timeout = setTimeout(() => {
      pendingScreenshots.delete(request_id)
      reject(new Error(`screenshot timed out after ${timeoutMs}ms`))
    }, timeoutMs)
    pendingScreenshots.set(request_id, { resolve, reject, timeout })
    broadcast({ type: 'request_screenshot', request_id, camera })
  })
}

export function onPeerOpen(peer: WsPeer) {
  clients.add(peer)
  // Greet with current state so the client doesn't have to re-fetch.
  send(peer, { type: 'snapshot', state: getState() })
}

export function onPeerMessage(peer: WsPeer, raw: string | Uint8Array) {
  let parsed: ClientMessage
  try {
    const text = typeof raw === 'string' ? raw : new TextDecoder().decode(raw)
    parsed = JSON.parse(text) as ClientMessage
  } catch (e) {
    console.warn('[ws] bad message:', e)
    return
  }
  switch (parsed.type) {
    case 'face_selected':
      setSelectedFace(parsed.selectedFace)
      break
    case 'screenshot_result': {
      const pending = pendingScreenshots.get(parsed.request_id)
      if (pending) {
        clearTimeout(pending.timeout)
        pendingScreenshots.delete(parsed.request_id)
        pending.resolve({
          png_base64: parsed.png_base64,
          camera: parsed.camera,
        })
      }
      break
    }
    case 'ping':
      send(peer, { type: 'pong' })
      break
  }
}

export function onPeerClose(peer: WsPeer) {
  clients.delete(peer)
}
