<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  value: unknown
  emptyMessage?: string
}>()

type Tok = { type: string; text: string }

const TOKEN_RE = new RegExp(
  [
    /("[^"\\]*(?:\\.[^"\\]*)*")(\s*:)/.source, // key + ':'
    /"[^"\\]*(?:\\.[^"\\]*)*"/.source, // string
    /-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/.source, // number
    /\b(?:true|false)\b/.source, // bool
    /\bnull\b/.source, // null
    /[{}[\],]/.source, // bracket / comma
    /[ \t\r\n]+/.source, // ws
    /[^\s{}[\]:,"\d-]+/.source, // fallback
  ].join('|'),
  'g',
)

function tokenize(src: string): Tok[] {
  const out: Tok[] = []
  let m: RegExpExecArray | null
  TOKEN_RE.lastIndex = 0
  while ((m = TOKEN_RE.exec(src)) !== null) {
    const full = m[0]
    if (m[1] !== undefined && m[2] !== undefined) {
      out.push({ type: 'key', text: m[1] })
      out.push({ type: 'punct', text: m[2] })
      continue
    }
    if (/^"/.test(full)) out.push({ type: 'string', text: full })
    else if (/^-?\d/.test(full)) out.push({ type: 'number', text: full })
    else if (full === 'true' || full === 'false')
      out.push({ type: 'bool', text: full })
    else if (full === 'null') out.push({ type: 'null', text: full })
    else if (/^[\s]+$/.test(full)) out.push({ type: 'ws', text: full })
    else if (/^[{}[\],]$/.test(full))
      out.push({ type: 'punct', text: full })
    else out.push({ type: 'other', text: full })
  }
  return out
}

const formatted = computed(() => {
  if (props.value === null || props.value === undefined) return ''
  try {
    return JSON.stringify(props.value, null, 2)
  } catch {
    return String(props.value)
  }
})

const tokens = computed(() => tokenize(formatted.value))
</script>

<template>
  <div class="inspector">
    <div v-if="!formatted" class="empty">
      {{ emptyMessage ?? '(なし)' }}
    </div>
    <pre v-else class="body"><span
      v-for="(t, i) in tokens"
      :key="i"
      :class="`tok-${t.type}`"
    >{{ t.text }}</span></pre>
  </div>
</template>

<style scoped>
.inspector {
  height: 100%;
  min-height: 0;
  overflow: auto;
  background: #0e0e10;
}
.empty {
  padding: 40px 20px;
  text-align: center;
  color: #555;
  font-style: italic;
  font-size: 12px;
}
.body {
  margin: 0;
  padding: 12px;
  font-family: ui-monospace, 'Menlo', monospace;
  font-size: 11px;
  line-height: 1.5;
  color: #ddd;
  white-space: pre-wrap;
  word-break: break-word;
}
.tok-key {
  color: #93c5fd;
}
.tok-string {
  color: #86efac;
}
.tok-number {
  color: #fcd34d;
}
.tok-bool {
  color: #c4b5fd;
}
.tok-null {
  color: #fca5a5;
}
.tok-punct {
  color: #6b7280;
}
.tok-other {
  color: #ddd;
}
.tok-ws {
  white-space: pre;
}
</style>
