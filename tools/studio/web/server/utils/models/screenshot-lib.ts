import type { CacheEntry } from './image-cache'

export const VALID_CAMERAS = [
  'iso',
  'front',
  'back',
  'top',
  'bottom',
  'side',
  'left',
  'item_gui',
] as const

export type RenderedView = {
  view_id: string
  image_id: string
  width: number
  height: number
  bytes_size: number
}

export function toRenderedView(entry: CacheEntry, view_id: string): RenderedView {
  return {
    view_id,
    image_id: entry.image_id,
    width: entry.width,
    height: entry.height,
    bytes_size: entry.bytes.length,
  }
}
