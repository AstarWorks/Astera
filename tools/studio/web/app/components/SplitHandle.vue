<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  /** Current width in px (the ref the parent maintains). */
  modelValue: number
  /** Min/max clamp range. */
  min: number
  max: number
  /**
   * Direction the handle pushes the value:
   *  - 'right' means dragging right increases the value (handle is on the
   *    right edge of the column it sizes, e.g. left sidebar).
   *  - 'left' means dragging right *decreases* the value (handle is on the
   *    left edge of the column it sizes, e.g. right palette / json panes).
   */
  direction: 'right' | 'left'
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number]
}>()

const dragging = ref(false)

function onPointerDown(ev: PointerEvent) {
  ev.preventDefault()
  dragging.value = true
  const startX = ev.clientX
  const startW = props.modelValue
  const sign = props.direction === 'right' ? 1 : -1

  const onMove = (e: PointerEvent) => {
    const delta = (e.clientX - startX) * sign
    const next = Math.max(props.min, Math.min(props.max, startW + delta))
    emit('update:modelValue', next)
  }
  const onUp = () => {
    dragging.value = false
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
  }
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}
</script>

<template>
  <div
    class="split-handle"
    :class="{ dragging }"
    @pointerdown="onPointerDown"
  />
</template>

<style scoped>
.split-handle {
  width: 6px;
  cursor: col-resize;
  background: #0e0e10;
  border-left: 1px solid #2a2a2f;
  border-right: 1px solid #2a2a2f;
  flex-shrink: 0;
  transition: background 0.1s;
  z-index: 5;
}
.split-handle:hover,
.split-handle.dragging {
  background: #3b82f6;
}
</style>
