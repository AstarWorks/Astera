/**
 * WebSocket handler mounted at /ws. Forwards crossws Peer events to the
 * framework-agnostic bridge in server/utils/ws-bridge.ts.
 *
 * Enable: `nitro.experimental.websocket: true` in nuxt.config.ts.
 */
import { onPeerOpen, onPeerMessage, onPeerClose } from '../utils/models/ws-bridge'

export default defineWebSocketHandler({
  open(peer) {
    onPeerOpen(peer)
  },
  message(peer, message) {
    onPeerMessage(peer, message.text())
  },
  close(peer) {
    onPeerClose(peer)
  },
  error(peer, error) {
    console.error('[ws] peer error:', error)
  },
})
