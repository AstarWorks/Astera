<script setup lang="ts">
import type { Tile } from '../types'

defineProps<{ tiles: Tile[]; selected: string | null }>()
defineEmits<{ select: [name: string] }>()
</script>

<template>
  <div class="grid">
    <div v-if="tiles.length === 0" class="empty">タイルなし (全て採用/破棄済)</div>
    <div
      v-for="tile in tiles"
      :key="tile.name"
      :class="['cell', { selected: tile.name === selected }]"
      @click="$emit('select', tile.name)"
      :title="tile.name"
    >
      <img :src="tile.path" :alt="tile.name" />
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, 72px);
  gap: 6px;
  align-content: start;
}
.cell {
  width: 72px;
  height: 72px;
  aspect-ratio: 1 / 1;
  padding: 4px;
  border-radius: 4px;
  cursor: pointer;
  border: 2px solid transparent;
  /* checkered transparency indicator */
  background-image: linear-gradient(45deg, #1a1a1d 25%, transparent 25%),
    linear-gradient(-45deg, #1a1a1d 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #1a1a1d 75%),
    linear-gradient(-45deg, transparent 75%, #1a1a1d 75%);
  background-size: 8px 8px;
  background-position: 0 0, 0 4px, 4px -4px, -4px 0;
  background-color: #0e0e10;
}
.cell:hover {
  border-color: #555;
}
.cell.selected {
  border-color: #3b82f6;
}
.cell img {
  display: block;
  width: 100%;
  height: 100%;
  image-rendering: pixelated;
  image-rendering: crisp-edges;
  object-fit: contain;
}
.empty {
  color: #666;
  font-size: 12px;
  padding: 20px;
  grid-column: 1 / -1;
}
</style>
