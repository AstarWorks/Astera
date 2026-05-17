<script setup lang="ts">
import type { Job } from '../types'

defineProps<{ jobs: Job[]; selectedId: string | null }>()
defineEmits<{ select: [id: string] }>()

function relTime(iso: string): string {
  const d = (Date.now() - new Date(iso).getTime()) / 1000
  if (d < 60) return `${Math.floor(d)}秒前`
  if (d < 3600) return `${Math.floor(d / 60)}分前`
  if (d < 86400) return `${Math.floor(d / 3600)}時間前`
  return `${Math.floor(d / 86400)}日前`
}
</script>

<template>
  <ul class="job-list">
    <li v-if="jobs.length === 0" class="empty">/tmp にジョブなし</li>
    <li
      v-for="job in jobs"
      :key="job.id"
      :class="{ selected: job.id === selectedId }"
      @click="$emit('select', job.id)"
    >
      <div class="id">{{ job.id }}</div>
      <div class="meta">{{ job.tileCount }} タイル · {{ relTime(job.mtime) }}</div>
    </li>
  </ul>
</template>

<style scoped>
.job-list {
  list-style: none;
  padding: 0;
  margin: 0 0 16px;
}
.job-list li {
  padding: 6px 8px;
  cursor: pointer;
  border-radius: 4px;
  margin-bottom: 2px;
}
.job-list li:hover {
  background: #2a2a2f;
}
.job-list li.selected {
  background: #3b82f6;
}
.job-list li.selected .meta {
  color: #cce0ff;
}
.empty {
  color: #555;
  font-size: 11px;
  font-style: italic;
  cursor: default !important;
}
.empty:hover {
  background: transparent !important;
}
.id {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
.meta {
  font-size: 10px;
  color: #999;
}
</style>
