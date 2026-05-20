<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { api } from '~/utils/api'
import type { TextureEntry } from '~/utils/api'
import type { Job, JobDetail, Tile } from '#shared/types'
import JobList from '../components/textures/JobList.vue'
import TileGrid from '../components/textures/TileGrid.vue'
import TileCard from '../components/textures/TileCard.vue'

const jobs = ref<Job[]>([])
const selectedJobId = ref<string | null>(null)
const jobDetail = ref<JobDetail | null>(null)
const selectedTileName = ref<string | null>(null)
const manifestCount = ref(0)
const adopted = ref<TextureEntry[]>([])
const adoptedFilter = ref('')
const previewing = ref<TextureEntry | null>(null)
// 'adopted' = 採用済みテクスチャ一覧 (= Studio で生成・採用した本物)
// 'jobs'    = 未採用ジョブの review UI
const viewMode = ref<'adopted' | 'jobs'>('adopted')
const busy = ref(false)
const busyMsg = ref<string>('')
const error = ref<string | null>(null)

const newGenOpen = ref(false)
const showGrid = ref(false)

type Preset = {
  label: string
  category: string
  grid: string
  prompt: string
}
const PRESETS: Preset[] = [
  {
    label: '武器 (swords)',
    category: 'items/swords',
    grid: '4x4',
    prompt: `A 4x4 grid of 16 distinct fantasy weapons in pixel-art style.

Cells (row-major, top-left first):
  r1: longsword, scimitar, dagger, greatsword (flame-pattern)
  r2: katana, rapier, falchion, claymore
  r3: shortsword, broadsword, sabre, kris (wavy blade)
  r4: gladius, estoc, kopis, zweihander

Each blade diagonally oriented, hilt at bottom-left, tip toward upper-right.
Consistent gold/brown hilt language across all 16. Each subject the same
relative size within its cell.`,
  },
  {
    label: 'ポーション (potions)',
    category: 'items/potions',
    grid: '4x4',
    prompt: `A 4x4 grid of 16 distinct fantasy glass potion bottles in pixel-art style.

Each bottle has the same silhouette (round flask with cork stopper) but
different liquid colors and effects:
  r1: red (healing), green (poison), blue (mana), yellow (energy)
  r2: purple (magic), orange (fire), cyan (water), white (light)
  r3: black (void), pink (love), brown (mud), gold (luck)
  r4: silver (mercury), gray (smoke), rainbow (chaos), clear (water)

Subtle bubbles or sparkles inside. Cork visible at top. Same bottle shape
across all 16 — only liquid color and tiny effect vary.`,
  },
  {
    label: '防具 (armor)',
    category: 'items/armor',
    grid: '4x4',
    prompt: `A 4x4 grid of 16 distinct fantasy armor pieces in pixel-art style.

Four material tiers × four slots:
  r1 (leather, brown):  helmet, chestplate, leggings, boots
  r2 (chainmail, gray): helmet, chestplate, leggings, boots
  r3 (iron, silver):    helmet, chestplate, leggings, boots
  r4 (gold, yellow):    helmet, chestplate, leggings, boots

Item silhouettes consistent within each slot column (all helmets same shape,
all boots same shape, etc.), only material/color varies row to row.`,
  },
  {
    label: '道具 (tools)',
    category: 'items/tools',
    grid: '4x4',
    prompt: `A 4x4 grid of 16 distinct fantasy tools in pixel-art style.

Four tool types × four material tiers:
  r1 (wood handle, stone head):    pickaxe, shovel, axe, hoe
  r2 (wood handle, iron head):     pickaxe, shovel, axe, hoe
  r3 (wood handle, gold head):     pickaxe, shovel, axe, hoe
  r4 (wood handle, diamond head):  pickaxe, shovel, axe, hoe

Each tool oriented diagonally, head at upper-right, handle at lower-left.
Wooden handle color consistent across all 16.`,
  },
  {
    label: '石ブロック (blocks)',
    category: 'blocks/stone-family',
    grid: '3x3',
    prompt: `A 3x3 grid of 9 distinct stone-family Minecraft block textures in pixel-art style.

Cells (row-major):
  r1: smooth stone, cobblestone, mossy cobblestone
  r2: cracked stone bricks, polished stone, stone bricks
  r3: andesite, diorite, granite

Each subject fills its cell as a seamless tile texture (no border framing).
Color palette consistent across all 9 (varying grays + a hint of color for
moss/andesite/diorite/granite).`,
  },
  {
    label: 'UI アイコン (icons)',
    category: 'items/icons',
    grid: '4x4',
    prompt: `A 4x4 grid of 16 distinct fantasy RPG UI icons in pixel-art style.

Cells (row-major):
  r1: heart (HP), mana droplet, gold coin, gem
  r2: key, scroll, map, compass
  r3: sword (attack), shield (defense), boot (speed), bow (range)
  r4: star, skull, eye, hourglass

Each icon centered in its cell with a 1-pixel dark outline. Consistent
chunky pixel style. Bright saturated colors.`,
  },
  {
    label: 'Astera 武器 matrix (archetype × element, MC 風)',
    category: 'items/weapons',
    grid: '10x10',
    prompt: `A 10x10 grid of 100 distinct ORIGINAL fantasy weapon items
for the Astera Minecraft server (custom server content, NOT vanilla MC
items), rendered in a Minecraft-native pixel-art style so they slot
cleanly into a Minecraft resource pack.

Style — Minecraft-native item icon aesthetic (NOT generic RPG):
- Reference vanilla MC item icons (iron sword, wooden pickaxe, bow) for
  scale, palette range, and silhouette discipline. Match THAT visual
  vocabulary, not ornate JRPG / Diablo icons.
- Muted, slightly desaturated palette: <=16 colors total across the
  whole image. Earth-tones + a single element-accent color per cell.
- Flat shading with dithered noise; NO smooth gradients, NO bright
  metallic highlights, NO gem-encrusted ornamentation.
- Thin 1-pixel dark outline only; vertical canonical pose:
  - swords:  hilt at bottom, blade pointing up
  - guns:    barrel pointing up-right, grip at bottom-left
  - wands:   crystal/orb at top, handle at bottom
- Brutally simple silhouettes readable at 16x16 thumbnail scale.
- Each item fits its cell with intent, ~10% pure-black gutter on all
  sides — silhouette must NOT cross the cell boundary.
- ELEMENT shows through an accent color on otherwise-neutral metal,
  NOT through ornate effects:
    physical = unaccented dull steel
    fire     = warm red/orange tint at the blade tip
    electric = pale yellow with a thin cyan spark
    wind     = pale green
    water    = teal
    earth    = brown/ochre
    ice      = pale cyan
    light    = bone-white with faint warm rim
    shadow   = deep purple
    void     = near-black blade with a thin red edge
- RARITY scales ornament + glow subtly, not loudly:
    common    = plain functional weapon
    uncommon  = simple etching on guard or grip
    rare      = inlaid trim, slightly thicker pommel
    epic      = visible elemental glow on blade/barrel/crystal
    legendary = unique silhouette per element (still cell-fitting)

Grid (row-major, top-left first) — Astera archetype × element matrix.
KEEP COLUMN-ELEMENT ALIGNMENT: column c always = same element:
  c1=physical c2=fire c3=electric c4=wind c5=water
  c6=earth    c7=ice  c8=light    c9=shadow c10=void

  r1: sword common      × (10 elements)
  r2: sword uncommon    × (10 elements)
  r3: sword rare        × (10 elements)
  r4: sword epic        × (10 elements)
  r5: sword legendary   × (10 elements)
  r6: gun common        × (10 elements)
  r7: gun rare          × (10 elements)
  r8: gun legendary     × (10 elements)
  r9: wand common       × (10 elements)
  r10: wand legendary   × (10 elements)

The grid reads as: row block = archetype + rarity; column = element.
Across r1->r5 each column is "the same sword tier-evolving"; reader
should see clear visual lineage within a column.`,
  },
]

const selectedPreset = ref(0)
const newGenCategory = ref(PRESETS[0]!.category)
const newGenGrid = ref(PRESETS[0]!.grid)
const newGenPrompt = ref(PRESETS[0]!.prompt)

function applyPreset(idx: number) {
  const p = PRESETS[idx]
  if (!p) return
  selectedPreset.value = idx
  newGenCategory.value = p.category
  newGenGrid.value = p.grid
  newGenPrompt.value = p.prompt
}

const selectedTile = computed<Tile | null>(() => {
  if (!jobDetail.value || !selectedTileName.value) return null
  return jobDetail.value.tiles.find((t) => t.name === selectedTileName.value) ?? null
})

async function loadJobs() {
  try {
    jobs.value = await api.jobs()
    const params = new URLSearchParams(location.search)
    const fromUrl = params.get('job')
    if (fromUrl && jobs.value.some((j) => j.id === fromUrl)) {
      await selectJob(fromUrl)
    }
  } catch (e) {
    error.value = String(e)
  }
}

async function loadAdopted() {
  try {
    const res = await api.textures({ scope: 'astera' })
    adopted.value = res.textures
    manifestCount.value = res.total
  } catch (e) {
    error.value = String(e)
  }
}

const filteredAdopted = computed(() => {
  const q = adoptedFilter.value.trim().toLowerCase()
  if (!q) return adopted.value
  return adopted.value.filter(
    (t) => t.path.toLowerCase().includes(q) || t.slug.toLowerCase().includes(q),
  )
})

async function selectJob(id: string) {
  selectedJobId.value = id
  selectedTileName.value = null
  viewMode.value = 'jobs'
  jobDetail.value = await api.job(id)
  const u = new URL(location.href)
  u.searchParams.set('job', id)
  history.replaceState(null, '', u.toString())
  if (jobDetail.value.tiles[0]) selectedTileName.value = jobDetail.value.tiles[0].name
}

async function withBusy<T>(label: string, fn: () => Promise<T>): Promise<T | undefined> {
  busy.value = true
  busyMsg.value = label
  error.value = null
  try {
    return await fn()
  } catch (e) {
    error.value = String(e)
    return undefined
  } finally {
    busy.value = false
    busyMsg.value = ''
  }
}

function advanceAfterRemoval() {
  if (!jobDetail.value) return
  const idx = jobDetail.value.tiles.findIndex((t) => t.name === selectedTileName.value)
  jobDetail.value.tiles = jobDetail.value.tiles.filter(
    (t) => t.name !== selectedTileName.value,
  )
  selectedTileName.value =
    jobDetail.value.tiles[idx]?.name ?? jobDetail.value.tiles[idx - 1]?.name ?? null
}

async function onAccept(slug: string, targetPath: string) {
  if (!selectedJobId.value || !selectedTileName.value) return
  await withBusy('採用中...', async () => {
    await api.accept(selectedJobId.value!, selectedTileName.value!, { slug, targetPath })
    advanceAfterRemoval()
    await loadAdopted()
  })
}

async function onReject() {
  if (!selectedJobId.value || !selectedTileName.value) return
  await withBusy('破棄中...', async () => {
    await api.reject(selectedJobId.value!, selectedTileName.value!)
    advanceAfterRemoval()
  })
}

async function onRegen(refinedPrompt: string) {
  if (!selectedJobId.value || !selectedTileName.value) return
  await withBusy('再生成中... (Codex 30-60秒)', async () => {
    const res = await api.regenerate(selectedJobId.value!, selectedTileName.value!, refinedPrompt)
    if (jobDetail.value) {
      const t = jobDetail.value.tiles.find((x) => x.name === res.tile.name)
      if (t) t.bytes = res.tile.bytes
    }
  })
}

async function onNewGen() {
  if (!newGenPrompt.value.trim()) return
  await withBusy('新規グリッド生成中... (Codex 60秒)', async () => {
    const { jobId } = await api.generate({
      category: newGenCategory.value,
      prompt: newGenPrompt.value,
      grid: newGenGrid.value,
    })
    newGenOpen.value = false
    await loadJobs()
    await selectJob(jobId)
  })
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && previewing.value) previewing.value = null
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  await Promise.all([loadJobs(), loadAdopted()])
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="mode-switch">
        <button
          :class="['mode', { active: viewMode === 'adopted' }]"
          @click="viewMode = 'adopted'; selectedJobId = null; jobDetail = null"
        >
          📒 採用済み<span class="badge">{{ manifestCount }}</span>
        </button>
        <button
          :class="['mode', { active: viewMode === 'jobs' }]"
          @click="viewMode = 'jobs'"
        >
          🛠 ジョブ<span class="badge">{{ jobs.length }}</span>
        </button>
      </div>
      <button class="new-btn" @click="newGenOpen = !newGenOpen">
        {{ newGenOpen ? 'キャンセル' : '+ 新規生成' }}
      </button>
      <div v-if="newGenOpen" class="new-form">
        <label
          >プリセット
          <select
            :value="selectedPreset"
            @change="applyPreset(Number(($event.target as HTMLSelectElement).value))"
          >
            <option v-for="(p, i) in PRESETS" :key="i" :value="i">
              {{ p.label }}
            </option>
          </select>
        </label>
        <label
          >カテゴリ
          <input v-model="newGenCategory" placeholder="items/swords" />
        </label>
        <label
          >グリッド
          <input v-model="newGenGrid" placeholder="4x4" />
        </label>
        <label
          >プロンプト
          <textarea
            v-model="newGenPrompt"
            rows="10"
            placeholder="プリセット選択後に編集可能"
          ></textarea>
        </label>
        <button :disabled="busy || !newGenPrompt.trim()" @click="onNewGen">
          生成
        </button>
      </div>
      <h3>ジョブ</h3>
      <JobList :jobs="jobs" :selected-id="selectedJobId" @select="selectJob" />
    </aside>
    <main class="main">
      <div v-if="busy" class="busy">⏳ {{ busyMsg }}</div>

      <div v-if="error" class="error">⚠️ {{ error }} <button @click="error = null">×</button></div>

      <!-- 採用済みテクスチャ browse view (Studio の主役) -->
      <div v-if="viewMode === 'adopted'" class="adopted-view">
        <div class="adopted-header">
          <h2>採用済みテクスチャ <span class="meta">{{ filteredAdopted.length }} / {{ adopted.length }} 件</span></h2>
          <input
            v-model="adoptedFilter"
            class="adopted-filter"
            placeholder="パスでフィルタ (例: items/swords)"
          />
        </div>
        <div v-if="adopted.length === 0" class="empty">
          採用済みテクスチャがまだありません。「ジョブ」モードで生成 → 採用してください。
        </div>
        <div v-else class="adopted-grid">
          <button
            v-for="t in filteredAdopted"
            :key="t.slug"
            class="adopted-cell"
            :title="t.slug"
            @click="previewing = t"
          >
            <img :src="t.preview_url" :alt="t.slug" />
            <div class="adopted-path">{{ t.path }}</div>
          </button>
        </div>
      </div>

      <div
        v-if="previewing"
        class="preview-modal"
        @click.self="previewing = null"
      >
        <div class="preview-box">
          <button class="preview-close" @click="previewing = null" title="閉じる (Esc)">×</button>
          <img :src="previewing.preview_url" :alt="previewing.slug" />
          <div class="preview-meta">
            <div class="preview-slug">{{ previewing.slug }}</div>
            <div class="preview-path">{{ previewing.path }}</div>
            <div class="preview-size">{{ previewing.size }} B</div>
          </div>
        </div>
      </div>

      <div v-else-if="jobDetail" class="job-header">
        <h2>
          {{ jobDetail.id }}
          <span class="meta">残り {{ jobDetail.tiles.length }} タイル</span>
          <button
            v-if="jobDetail.hasGrid"
            class="grid-toggle"
            @click="showGrid = !showGrid"
          >
            {{ showGrid ? '元画像を隠す' : '元画像を表示' }}
          </button>
        </h2>
        <div v-if="showGrid && jobDetail.hasGrid" class="grid-source">
          <img :src="`/files/jobs/${jobDetail.id}/grid`" alt="source grid" />
          <div class="grid-source-caption">
            Codex 生成元画像 (sliced 前)
          </div>
        </div>
        <pre v-if="jobDetail.prompt" class="prompt">{{ jobDetail.prompt }}</pre>
      </div>
      <div v-if="viewMode === 'jobs' && jobDetail" class="content">
        <TileGrid
          :tiles="jobDetail.tiles"
          :selected="selectedTileName"
          @select="(n) => (selectedTileName = n)"
        />
        <TileCard
          v-if="selectedTile"
          :tile="selectedTile"
          :default-category="newGenCategory"
          :busy="busy"
          @accept="onAccept"
          @reject="onReject"
          @regen="onRegen"
        />
        <div v-else class="empty-tile">← タイルを選択してレビュー</div>
      </div>
      <div v-else-if="viewMode === 'jobs' && !jobDetail" class="empty">
        ジョブ未選択。左のリストから選ぶか、「+ 新規生成」してください。
      </div>
    </main>
  </div>
</template>

<style scoped>
h3 {
  font-size: 11px;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin: 20px 0 6px;
}
.layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  height: 100%;
  min-height: 0;
}
.sidebar {
  background: #18181b;
  border-right: 1px solid #2a2a2f;
  padding: 16px;
  overflow-y: auto;
}
.mode-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin-bottom: 12px;
}
.mode-switch .mode {
  padding: 8px 6px;
  background: #222;
  color: #ccc;
  border: 1px solid #2a2a2f;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.mode-switch .mode:hover {
  background: #2a2a30;
}
.mode-switch .mode.active {
  background: #3b82f6;
  color: white;
  border-color: #3b82f6;
}
.mode-switch .badge {
  background: rgba(0, 0, 0, 0.3);
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 10px;
  font-family: ui-monospace, monospace;
}
.new-btn {
  width: 100%;
  padding: 8px;
  background: #3b82f6;
  color: white;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  font-weight: 600;
}
.new-btn:hover {
  background: #2563eb;
}
.new-form {
  background: #222;
  padding: 10px;
  border-radius: 4px;
  margin: 8px 0 12px;
  display: grid;
  gap: 6px;
}
.new-form label {
  display: flex;
  flex-direction: column;
  font-size: 11px;
  color: #aaa;
}
.new-form input,
.new-form textarea,
.new-form select {
  background: #111;
  color: #eee;
  border: 1px solid #333;
  padding: 5px;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  border-radius: 3px;
  margin-top: 2px;
}
.new-form select {
  font-family: ui-sans-serif, system-ui, sans-serif;
}
.new-form button {
  background: #10b981;
  color: white;
  border: 0;
  padding: 6px;
  border-radius: 3px;
  cursor: pointer;
  font-weight: 600;
}
.new-form button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.adopted-view {
  display: flex;
  flex-direction: column;
}
.adopted-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  flex: 0 0 auto;
}
.adopted-header h2 {
  margin: 0;
  font-size: 18px;
}
.adopted-header .meta {
  font-size: 12px;
  color: #888;
  font-weight: normal;
  margin-left: 8px;
}
.adopted-filter {
  background: #0a0a0c;
  color: #eee;
  border: 1px solid #333;
  padding: 6px 10px;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  border-radius: 4px;
  min-width: 280px;
  margin-left: auto;
}
.adopted-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px;
  align-content: start;
  padding-bottom: 20px;
}
.adopted-cell {
  display: flex;
  flex-direction: column;
  background: #18181b;
  border: 1px solid #2a2a2f;
  border-radius: 4px;
  padding: 8px;
  text-decoration: none;
  color: inherit;
  text-align: left;
  font: inherit;
  cursor: pointer;
  transition: border-color 0.1s;
}
.adopted-cell:hover {
  border-color: #3b82f6;
}
.adopted-cell:focus-visible {
  outline: 2px solid #3b82f6;
  outline-offset: 2px;
}
.adopted-cell img {
  display: block;
  width: 100%;
  aspect-ratio: 1 / 1;
  object-fit: contain;
  image-rendering: pixelated;
  image-rendering: crisp-edges;
  background-image: linear-gradient(45deg, #1a1a1d 25%, transparent 25%),
    linear-gradient(-45deg, #1a1a1d 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #1a1a1d 75%),
    linear-gradient(-45deg, transparent 75%, #1a1a1d 75%);
  background-size: 12px 12px;
  background-position: 0 0, 0 6px, 6px -6px, -6px 0;
  background-color: #0e0e10;
}
.adopted-path {
  font-size: 10px;
  color: #888;
  font-family: ui-monospace, monospace;
  margin-top: 6px;
  word-break: break-all;
  line-height: 1.3;
}
.preview-modal {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.75);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 32px;
  backdrop-filter: blur(2px);
}
.preview-box {
  position: relative;
  background: #18181b;
  border: 1px solid #2a2a2f;
  border-radius: 8px;
  padding: 24px;
  max-width: min(90vw, 720px);
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  gap: 16px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
}
.preview-close {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #aaa;
  font-size: 22px;
  cursor: pointer;
  line-height: 1;
}
.preview-close:hover {
  background: #2a2a2f;
  color: #fff;
}
.preview-box img {
  display: block;
  width: 100%;
  max-width: 480px;
  max-height: 60vh;
  margin: 0 auto;
  object-fit: contain;
  image-rendering: pixelated;
  image-rendering: crisp-edges;
  background-image: linear-gradient(45deg, #1a1a1d 25%, transparent 25%),
    linear-gradient(-45deg, #1a1a1d 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #1a1a1d 75%),
    linear-gradient(-45deg, transparent 75%, #1a1a1d 75%);
  background-size: 16px 16px;
  background-position: 0 0, 0 8px, 8px -8px, -8px 0;
  background-color: #0e0e10;
  border: 1px solid #2a2a2f;
  border-radius: 4px;
}
.preview-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
.preview-slug {
  color: #facc15;
  font-weight: 600;
}
.preview-path {
  color: #aaa;
  word-break: break-all;
}
.preview-size {
  color: #666;
  font-size: 11px;
}
.main {
  padding: 20px;
  overflow-y: auto;
}
.busy {
  position: sticky;
  top: 0;
  margin: -20px -20px 16px;
  padding: 10px 20px;
  background: #facc15;
  color: #000;
  font-weight: 600;
  z-index: 10;
}
.error {
  margin: 0 0 12px;
  padding: 8px 12px;
  background: #7f1d1d;
  color: #fee;
  border-radius: 4px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.error button {
  background: transparent;
  color: #fee;
  border: 0;
  cursor: pointer;
  font-size: 18px;
}
.job-header h2 {
  margin: 0 0 6px;
  font-size: 18px;
}
.job-header .meta {
  font-size: 12px;
  color: #888;
  font-weight: normal;
  margin-left: 8px;
}
.prompt {
  background: #18181b;
  padding: 10px;
  border-radius: 4px;
  font-size: 11px;
  max-height: 120px;
  overflow: auto;
  margin: 0 0 16px;
  border: 1px solid #2a2a2f;
}
.grid-toggle {
  margin-left: 12px;
  padding: 4px 10px;
  background: #374151;
  color: #fff;
  border: 0;
  border-radius: 3px;
  cursor: pointer;
  font-size: 12px;
  vertical-align: middle;
}
.grid-toggle:hover {
  background: #4b5563;
}
.grid-source {
  margin: 8px 0 16px;
  padding: 10px;
  background: #18181b;
  border-radius: 4px;
  border: 1px solid #2a2a2f;
  background-image: linear-gradient(45deg, #14141a 25%, transparent 25%),
    linear-gradient(-45deg, #14141a 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #14141a 75%),
    linear-gradient(-45deg, transparent 75%, #14141a 75%);
  background-size: 16px 16px;
  background-position: 0 0, 0 8px, 8px -8px, -8px 0;
  background-color: #2a2a2f;
}
.grid-source img {
  max-width: 100%;
  max-height: 480px;
  display: block;
  margin: 0 auto;
  image-rendering: pixelated;
  image-rendering: crisp-edges;
}
.grid-source-caption {
  text-align: center;
  font-size: 10px;
  color: #888;
  margin-top: 4px;
  font-family: ui-monospace, monospace;
}
.content {
  display: flex;
  gap: 20px;
  align-items: flex-start;
  flex-wrap: wrap;
}
.content > :first-child {
  flex: 1 1 320px;
  min-width: 0;
}
.content > :last-child,
.content > .empty-tile {
  flex: 0 0 560px;
}
.empty,
.empty-tile {
  color: #666;
  padding: 40px;
  text-align: center;
  font-size: 13px;
}
</style>
