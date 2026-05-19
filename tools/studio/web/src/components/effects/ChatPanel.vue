<script setup lang="ts">
/**
 * Claude chat panel for the fxgraph.
 *
 *   ┌────────────────────────────────────┐
 *   │ [Sonnet ▾]  $0.04 / $1.00          │
 *   ├────────────────────────────────────┤
 *   │ user: より速く回転させて              │
 *   │ claude: speed_deg_per_sec を         │
 *   │   180 にします。                     │
 *   │   [Show patch ▾] [✓ Apply] [✗ Reject]│
 *   ├────────────────────────────────────┤
 *   │ [textarea]                          │
 *   │                       [送信] [⏹]    │
 *   └────────────────────────────────────┘
 */
import { ref, computed } from 'vue'
import { api } from '../../api'
import type { ChatTurn, JsonPatchOp } from '../../api'
import type { FxGraph } from '../../effects/types'
import { applyJsonPatch } from '../../effects/patch'

type Turn = ChatTurn & {
  patch?: JsonPatchOp[] | null
  cost_usd?: number
  applied?: boolean
  showPatch?: boolean
  error?: string
}

const props = defineProps<{
  graph: FxGraph
}>()
const emit = defineEmits<{
  (e: 'update:graph', g: FxGraph): void
}>()

const model = ref<'sonnet' | 'opus'>('sonnet')
const userInput = ref('')
const sending = ref(false)
const error = ref<string | null>(null)

const turns = ref<Turn[]>([])

const COST_CAP = 1.0
const totalCost = computed(() =>
  turns.value.reduce((s, t) => s + (t.cost_usd ?? 0), 0),
)
const capExceeded = computed(() => totalCost.value >= COST_CAP)

const canSend = computed(
  () => !sending.value && userInput.value.trim().length > 0 && !capExceeded.value,
)

async function send() {
  const text = userInput.value.trim()
  if (!canSend.value) return
  const history: ChatTurn[] = turns.value.map((t) => ({
    role: t.role,
    content: t.content,
  }))
  turns.value.push({ role: 'user', content: text })
  userInput.value = ''
  sending.value = true
  error.value = null
  try {
    const res = await api.effectChat({
      user_input: text,
      graph: props.graph,
      history,
      model: model.value,
    })
    turns.value.push({
      role: 'assistant',
      content: res.explanation || res.question || res.raw || '(空の応答)',
      patch: res.patch,
      cost_usd: res.cost_usd,
    })
  } catch (e) {
    error.value = String(e)
  } finally {
    sending.value = false
  }
}

function applyAt(idx: number) {
  const t = turns.value[idx]
  if (!t.patch) return
  try {
    const next = applyJsonPatch(props.graph, t.patch)
    emit('update:graph', next)
    t.applied = true
  } catch (e) {
    t.error = `Patch apply 失敗: ${String(e)}`
  }
}

function rejectAt(idx: number) {
  turns.value[idx].applied = false
  turns.value[idx].patch = null
}

function togglePatch(idx: number) {
  turns.value[idx].showPatch = !turns.value[idx].showPatch
}

function onKeydown(e: KeyboardEvent) {
  // Cmd/Ctrl+Enter to send
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
    e.preventDefault()
    if (canSend.value) void send()
  }
}
</script>

<template>
  <div class="chat">
    <header>
      <select v-model="model" class="model-sel" :disabled="sending">
        <option value="sonnet">Sonnet 4.6</option>
        <option value="opus">Opus 4.7</option>
      </select>
      <span class="cost" :class="{ near: totalCost > 0.5, exceeded: capExceeded }">
        ${{ totalCost.toFixed(4) }} / ${{ COST_CAP.toFixed(2) }}
      </span>
    </header>

    <ol class="turns">
      <li v-if="turns.length === 0" class="empty">
        Claude に編集を依頼します。<br />
        例: <code>もっと回転を速く</code> / <code>外側に金色のグロー追加</code>
      </li>
      <li
        v-for="(t, idx) in turns"
        :key="idx"
        class="turn"
        :class="t.role"
      >
        <div class="who">{{ t.role === 'user' ? 'You' : 'Claude' }}</div>
        <div class="body">{{ t.content }}</div>
        <div v-if="t.role === 'assistant' && t.patch && !t.applied" class="patch-actions">
          <button class="btn" @click="togglePatch(idx)">
            {{ t.showPatch ? 'パッチ ▴' : 'パッチを見る ▾' }}
          </button>
          <button class="btn approve" @click="applyAt(idx)">✓ 適用</button>
          <button class="btn" @click="rejectAt(idx)">✗ 却下</button>
        </div>
        <pre v-if="t.showPatch && t.patch" class="patch-dump">{{ JSON.stringify(t.patch, null, 2) }}</pre>
        <div v-if="t.applied" class="applied">✓ 適用済み</div>
        <div v-if="t.error" class="patch-error">⚠️ {{ t.error }}</div>
        <div v-if="t.cost_usd !== undefined" class="meta">
          ${{ t.cost_usd.toFixed(4) }}
        </div>
      </li>
    </ol>

    <div v-if="error" class="err">⚠️ {{ error }}</div>

    <footer>
      <textarea
        v-model="userInput"
        placeholder="Claude に依頼…  ⌘/Ctrl+Enter で送信"
        @keydown="onKeydown"
        :disabled="sending || capExceeded"
        rows="3"
      ></textarea>
      <button class="send" @click="send" :disabled="!canSend">
        {{ sending ? '送信中…' : '送信' }}
      </button>
    </footer>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid #2a2a2f;
  background: #18181b;
  flex: 0 0 auto;
}
.model-sel {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 4px 6px;
  border-radius: 3px;
  font-size: 11px;
}
.cost {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  color: #888;
  margin-left: auto;
}
.cost.near { color: #fbbf24; }
.cost.exceeded { color: #ef4444; font-weight: 600; }

.turns {
  flex: 1;
  list-style: none;
  margin: 0;
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  min-height: 0;
}
.empty {
  color: #666;
  font-size: 12px;
  font-style: italic;
  line-height: 1.7;
}
.empty code {
  background: #1f1f24;
  padding: 1px 6px;
  border-radius: 3px;
  font-family: ui-monospace, monospace;
  color: #ddd;
}
.turn {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}
.turn.user .who { color: #60a5fa; }
.turn.assistant .who { color: #fbbf24; }
.who {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}
.body {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  border-radius: 4px;
  padding: 6px 8px;
  color: #ddd;
  white-space: pre-wrap;
  line-height: 1.5;
}
.patch-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.btn {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 3px 8px;
  border-radius: 3px;
  cursor: pointer;
  font-size: 10px;
}
.btn:hover { background: #26262b; }
.btn.approve {
  background: #064e3b;
  border-color: #064e3b;
  color: #6ee7b7;
}
.btn.approve:hover {
  background: #065f46;
  border-color: #065f46;
}
.patch-dump {
  margin: 4px 0 0;
  padding: 6px 8px;
  background: #0e0e10;
  border: 1px solid #2a2a2f;
  border-radius: 3px;
  font-family: ui-monospace, monospace;
  font-size: 10px;
  color: #888;
  max-height: 200px;
  overflow: auto;
}
.applied {
  font-size: 10px;
  color: #6ee7b7;
}
.patch-error {
  font-size: 10px;
  color: #fee;
  background: #7f1d1d;
  padding: 4px 6px;
  border-radius: 3px;
}
.meta {
  font-family: ui-monospace, monospace;
  font-size: 9px;
  color: #555;
  text-align: right;
}
.err {
  margin: 0 12px;
  padding: 6px 10px;
  background: #7f1d1d;
  color: #fee;
  font-size: 11px;
  border-radius: 4px;
}
footer {
  display: flex;
  gap: 6px;
  padding: 8px 12px;
  border-top: 1px solid #2a2a2f;
  background: #18181b;
  flex: 0 0 auto;
}
textarea {
  flex: 1;
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 6px 8px;
  border-radius: 3px;
  font-family: inherit;
  font-size: 12px;
  resize: none;
}
textarea:focus {
  outline: none;
  border-color: #3b82f6;
}
.send {
  background: #3b82f6;
  border: 1px solid #3b82f6;
  color: white;
  padding: 4px 14px;
  border-radius: 3px;
  cursor: pointer;
  font-size: 11px;
  font-weight: 600;
  align-self: flex-end;
}
.send:hover:not(:disabled) { background: #2563eb; }
.send:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
