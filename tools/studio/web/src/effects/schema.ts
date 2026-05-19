/**
 * Node schema — declarative specification of every node type.
 *
 * Used by:
 *   - NodeInspector.vue (UI control selection: slider vs number vs color vs select)
 *   - NodePalette.vue (category grouping + tooltip descriptions)
 *   - FxNodeBox.vue (param label rendering)
 *   - effects/defaults.ts (per-type default params, derived from schema)
 *   - effects/validate.ts (range / required-input checks before applying a
 *     Claude-emitted JSON Patch)
 *   - server/routes/effects.ts → GET /api/effects/schema (AI cached prompt)
 *
 * Add a new node: append a NodeSpec entry; the editor + AI pick it up automatically.
 */
import type { NodeType, ParamValue, GradientStop, CurveStop } from './types'

export type NumberSpec = {
  type: 'number'
  min?: number
  max?: number
  step?: number
  default: number
  label: string
  description?: string
}

export type IntegerSpec = {
  type: 'integer'
  min?: number
  max?: number
  default: number
  label: string
  description?: string
}

export type ColorSpec = {
  type: 'color'
  default: string
  label: string
  description?: string
}

export type BooleanSpec = {
  type: 'boolean'
  default: boolean
  label: string
  description?: string
}

export type SelectSpec = {
  type: 'select'
  options: { value: string; label: string }[]
  default: string
  label: string
  description?: string
}

export type Tuple2Spec = {
  type: 'tuple2'
  default: [number, number]
  min?: number
  max?: number
  label: string
  description?: string
}

export type StringSpec = {
  type: 'string'
  default: string
  label: string
  description?: string
}

export type GradientSpec = {
  type: 'gradient'
  default: GradientStop[]
  label: string
  description?: string
}

export type CurveSpec = {
  type: 'curve'
  default: CurveStop[]
  /** Soft hint for the editor on numeric value range; not enforced. */
  vmin?: number
  vmax?: number
  label: string
  description?: string
}

export type ParamSpec =
  | NumberSpec
  | IntegerSpec
  | ColorSpec
  | BooleanSpec
  | SelectSpec
  | Tuple2Spec
  | StringSpec
  | GradientSpec
  | CurveSpec

export type NodeCategory =
  | 'generator'
  | 'transform'
  | 'filter'
  | 'composite'
  | 'animation'
  | 'values'
  | 'sink'

/** What kind of data does this port carry / this node emit? */
export type EdgeKind = 'canvas' | 'number' | 'vec2' | 'effector'

export type NodeInputSpec = {
  /** Port id used in edges (`in: 'image'`). */
  id: string
  label: string
  description?: string
  /** If true, node still runs without it (e.g. composite.over with only `a`). */
  optional?: boolean
  /** What this port accepts. Defaults to 'canvas'. Scalar/vec2 ports auto-merge
   *  into the matching-name param when an edge is connected. */
  kind?: EdgeKind
}

export type NodeSpec = {
  type: NodeType
  label: string
  category: NodeCategory
  description: string
  /** Inputs in port-binding order. Empty for pure generators. */
  inputs: NodeInputSpec[]
  /** Whether the node emits anything. False only for `output`. */
  hasOutput: boolean
  /** What the node emits. Defaults to 'canvas' for backwards compatibility. */
  outputKind?: EdgeKind
  params: Record<string, ParamSpec>
}

const C = (id: string, label = id, description?: string): NodeInputSpec => ({
  id,
  label,
  description,
})

const SPECS: NodeSpec[] = [
  // ── Generators ──────────────────────────────────────────────────────────
  {
    type: 'shape.ring',
    label: 'リング',
    category: 'generator',
    description: '中心 (cx, cy)、半径 radius、太さ thickness の円環を描く。',
    inputs: [],
    hasOutput: true,
    params: {
      cx: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: '中心 X' },
      cy: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: '中心 Y' },
      radius: { type: 'number', min: 1, max: 32, step: 0.5, default: 12, label: '半径' },
      thickness: { type: 'number', min: 0.5, max: 8, step: 0.5, default: 2, label: '太さ' },
      color: { type: 'color', default: '#ffe14a', label: '色' },
    },
  },
  {
    type: 'shape.circle',
    label: '塗りつぶし円',
    category: 'generator',
    description: '中心 (cx, cy)、半径 radius の塗りつぶし円。',
    inputs: [],
    hasOutput: true,
    params: {
      cx: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: '中心 X' },
      cy: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: '中心 Y' },
      radius: { type: 'number', min: 1, max: 32, step: 0.5, default: 8, label: '半径' },
      color: { type: 'color', default: '#ffffff', label: '色' },
    },
  },
  {
    type: 'shape.polygon',
    label: '正多角形',
    category: 'generator',
    description: '指定 sides 数の正多角形（塗りつぶし）。',
    inputs: [],
    hasOutput: true,
    params: {
      cx: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: '中心 X' },
      cy: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: '中心 Y' },
      radius: { type: 'number', min: 1, max: 32, step: 0.5, default: 10, label: '半径' },
      sides: { type: 'integer', min: 3, max: 12, default: 5, label: '辺の数' },
      rotation_deg: { type: 'number', min: -180, max: 180, step: 1, default: 0, label: '回転 (度)' },
      color: { type: 'color', default: '#ffffff', label: '色' },
    },
  },
  {
    type: 'shape.line',
    label: '直線',
    category: 'generator',
    description: '2 点 (x0, y0)–(x1, y1) を結ぶ直線。',
    inputs: [],
    hasOutput: true,
    params: {
      x0: { type: 'number', min: 0, max: 64, step: 0.5, default: 0, label: 'x0' },
      y0: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: 'y0' },
      x1: { type: 'number', min: 0, max: 64, step: 0.5, default: 32, label: 'x1' },
      y1: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: 'y1' },
      thickness: { type: 'number', min: 0.5, max: 8, step: 0.5, default: 1, label: '太さ' },
      color: { type: 'color', default: '#ffffff', label: '色' },
    },
  },
  {
    type: 'noise.perlin',
    label: 'Perlin ノイズ',
    category: 'generator',
    description: 'グラデーション Perlin ノイズ。PXC sh_perlin の WebGL2 移植。',
    inputs: [],
    hasOutput: true,
    params: {
      scale: { type: 'number', min: 1, max: 32, step: 0.5, default: 8, label: 'スケール' },
      seed: { type: 'integer', min: 0, max: 9999, default: 1, label: 'シード' },
      octaves: { type: 'integer', min: 1, max: 6, default: 3, label: 'オクターブ' },
    },
  },
  {
    type: 'noise.value',
    label: 'Value ノイズ',
    category: 'generator',
    description: 'スカラー格子バリューノイズ。Perlin より硬めの斑点状パターン。',
    inputs: [],
    hasOutput: true,
    params: {
      scale: { type: 'number', min: 1, max: 32, step: 0.5, default: 8, label: 'スケール' },
      seed: { type: 'integer', min: 0, max: 9999, default: 1, label: 'シード' },
    },
  },
  {
    type: 'image.import',
    label: '画像読込',
    category: 'generator',
    description: '静的 PNG をパスから読み込む。content/textures/ 配下の相対パスまたは絶対 URL を受け付ける。非同期ロード後にキャッシュされる。',
    inputs: [],
    hasOutput: true,
    params: {
      path: { type: 'string', default: '', label: 'パス' },
    },
  },
  {
    type: 'generate.scatter',
    label: '散布',
    category: 'generator',
    description:
      'シード付き乱数で N 個の小さなシェイプを散布。オプションで `mask` 入力のアルファ範囲内にのみ配置可能。魔法陣の rune dot / 星空 / 草スプライト などに。',
    inputs: [
      { id: 'mask', label: 'マスク (任意)', description: 'alpha>0 領域にのみ散布', optional: true },
    ],
    hasOutput: true,
    params: {
      count: { type: 'integer', min: 1, max: 200, default: 24, label: '個数' },
      shape: {
        type: 'select',
        options: [
          { value: 'dot', label: '丸' },
          { value: 'square', label: '四角' },
          { value: 'star', label: '星' },
        ],
        default: 'dot',
        label: '形状',
      },
      size_min: { type: 'number', min: 0.5, max: 8, step: 0.25, default: 1, label: '最小サイズ' },
      size_max: { type: 'number', min: 0.5, max: 8, step: 0.25, default: 2, label: '最大サイズ' },
      color: { type: 'color', default: '#ffe14a', label: '色' },
      seed: { type: 'integer', min: 0, max: 9999, default: 1, label: 'シード' },
    },
  },
  {
    type: 'generate.gradient_radial',
    label: '放射状グラデーション',
    category: 'generator',
    description:
      '中心 (cx, cy) から (inner_radius, outer_radius) 区間で内側→外側に色補間したフィールドを生成。glow ベース、particle alpha マスク、polar warp ソースに。',
    inputs: [],
    hasOutput: true,
    params: {
      cx: { type: 'number', min: 0, max: 128, step: 1, default: 32, label: '中心 X' },
      cy: { type: 'number', min: 0, max: 128, step: 1, default: 32, label: '中心 Y' },
      inner_radius: { type: 'number', min: 0, max: 128, step: 1, default: 0, label: '内半径' },
      outer_radius: { type: 'number', min: 1, max: 128, step: 1, default: 32, label: '外半径' },
      color_inner: { type: 'color', default: '#ffffff', label: '内色' },
      color_outer: { type: 'color', default: '#00000000', label: '外色 (透明可)' },
    },
  },
  {
    type: 'particle.emitter',
    label: 'パーティクル',
    category: 'generator',
    description:
      'PXC の Particle ノード相当。N 個の粒子を burst / continuous で発生させ、寿命と速度から各フレームの状態を決定論的に計算。色は粒子の生涯にわたる gradient で表現。爆発 / 火花 / 魔法弾コアの主役。`effector` ポートに particle.effector を接続すると per-particle 力が加わる。',
    inputs: [
      { id: 'effector', label: 'エフェクター', description: 'Attract / Vortex / Wind / Repel 力', optional: true },
    ],
    hasOutput: true,
    params: {
      count: { type: 'integer', min: 1, max: 200, default: 40, label: '個数' },
      spawn_origin: { type: 'tuple2', default: [16, 16], min: 0, max: 128, label: '発生位置' },
      spawn_radius: { type: 'number', min: 0, max: 32, step: 0.5, default: 0, label: '発生範囲' },
      spawn_mode: {
        type: 'select',
        options: [
          { value: 'burst', label: 'バースト (frame=0 で全数)' },
          { value: 'continuous', label: '連続 (時間で分散)' },
        ],
        default: 'burst',
        label: '発生モード',
      },
      velocity_min: { type: 'number', min: 0, max: 20, step: 0.1, default: 1, label: '最小速度 (px/frame)' },
      velocity_max: { type: 'number', min: 0, max: 20, step: 0.1, default: 4, label: '最大速度 (px/frame)' },
      velocity_direction: {
        type: 'select',
        options: [
          { value: 'radial', label: '放射状' },
          { value: 'random', label: 'ランダム' },
          { value: 'angle', label: '指定角度' },
        ],
        default: 'radial',
        label: '速度方向',
      },
      velocity_angle_deg: {
        type: 'number',
        min: -180,
        max: 180,
        step: 5,
        default: 0,
        label: '角度 (度、指定時のみ)',
      },
      lifetime_frames: { type: 'integer', min: 1, max: 64, default: 12, label: '寿命 (frames)' },
      size_start: { type: 'number', min: 0.25, max: 16, step: 0.25, default: 3, label: '開始サイズ' },
      size_end: { type: 'number', min: 0.25, max: 16, step: 0.25, default: 0.5, label: '終了サイズ' },
      shape: {
        type: 'select',
        options: [
          { value: 'dot', label: '丸' },
          { value: 'square', label: '四角' },
          { value: 'diamond', label: '菱形' },
        ],
        default: 'dot',
        label: '粒形状',
      },
      gradient: {
        type: 'gradient',
        default: [
          { t: 0, color: '#ffffff' },
          { t: 0.25, color: '#fff5b8' },
          { t: 0.55, color: '#fb923c' },
          { t: 0.8, color: '#b91c1c' },
          { t: 1, color: '#00000000' },
        ],
        label: '色 (生涯)',
        description: '0 = 発生時、1 = 死亡時 の色補間ストップ',
      },
      seed: { type: 'integer', min: 0, max: 9999, default: 1, label: 'シード' },
    },
  },
  {
    type: 'particle.effector',
    label: 'パーティクル力場',
    category: 'generator',
    description:
      '粒子に加わる力場 (attract/vortex/wind/repel)。particle.emitter の `effector` 入力に接続して、中心 (origin) からの距離に応じて falloff した加速度を与える。渦巻きオーラや吸い込みエフェクトに。',
    inputs: [],
    hasOutput: true,
    outputKind: 'effector',
    params: {
      mode: {
        type: 'select',
        options: [
          { value: 'attract', label: '吸引 (origin に引き寄せ)' },
          { value: 'repel', label: '反発 (origin から押し出す)' },
          { value: 'vortex', label: '渦 (origin を中心に旋回)' },
          { value: 'wind', label: '風 (origin の方向に直進)' },
        ],
        default: 'attract',
        label: 'モード',
      },
      origin: { type: 'tuple2', default: [16, 16], min: 0, max: 128, label: '中心 (px)' },
      strength: { type: 'number', min: -10, max: 10, step: 0.05, default: 1.0, label: '強さ (px/frame²)' },
      radius: { type: 'number', min: 1, max: 64, step: 1, default: 16, label: '影響半径' },
      falloff: {
        type: 'select',
        options: [
          { value: 'linear', label: '線形' },
          { value: 'inverse_square', label: '逆二乗' },
          { value: 'none', label: 'なし (一定)' },
        ],
        default: 'linear',
        label: '減衰',
      },
    },
  },
  {
    type: 'ai.image',
    label: 'AI 画像生成',
    category: 'generator',
    description:
      'Codex CLI 経由で 1 枚の pixel-art PNG を生成し、それ以降のレンダーに使う。生成後は `generated_path` に保存先 (content/textures/animations/ai-cache 以下) が入る。再生成は Inspector の「生成」ボタンから。',
    inputs: [],
    hasOutput: true,
    params: {
      prompt: { type: 'string', default: 'A 32x32 pixel-art rune symbol, glowing yellow on transparent.', label: 'プロンプト' },
      seed: { type: 'integer', min: 0, max: 9999, default: 1, label: 'シード' },
      generated_path: { type: 'string', default: '', label: '生成済みパス' },
    },
  },

  // ── Transforms ──────────────────────────────────────────────────────────
  {
    type: 'xform.translate',
    label: '平行移動',
    category: 'transform',
    description: '入力を (dx, dy) ピクセル平行移動。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      dx: { type: 'number', min: -32, max: 32, step: 0.5, default: 0, label: 'dx' },
      dy: { type: 'number', min: -32, max: 32, step: 0.5, default: 0, label: 'dy' },
    },
  },
  {
    type: 'xform.rotate',
    label: '回転 (静的)',
    category: 'transform',
    description: '入力を angle_deg だけ静的に回転。アニメ用途は anim.rotate を使う。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      angle_deg: { type: 'number', min: -180, max: 180, step: 1, default: 0, label: '角度 (度)' },
      ox: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: '中心 X' },
      oy: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: '中心 Y' },
    },
  },
  {
    type: 'xform.scale',
    label: '拡縮',
    category: 'transform',
    description: '入力を (sx, sy) 倍に拡縮。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      sx: { type: 'number', min: 0.1, max: 4, step: 0.05, default: 1, label: 'sx' },
      sy: { type: 'number', min: 0.1, max: 4, step: 0.05, default: 1, label: 'sy' },
      ox: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: '中心 X' },
      oy: { type: 'number', min: 0, max: 64, step: 0.5, default: 16, label: '中心 Y' },
    },
  },
  {
    type: 'xform.mirror',
    label: 'ミラー',
    category: 'transform',
    description: '入力を水平 (x) または垂直 (y) でミラー反転。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      axis: {
        type: 'select',
        options: [
          { value: 'x', label: 'X 軸 (左右反転)' },
          { value: 'y', label: 'Y 軸 (上下反転)' },
        ],
        default: 'x',
        label: '軸',
      },
    },
  },
  {
    type: 'xform.polar',
    label: '極座標変換',
    category: 'transform',
    description:
      'Cartesian ↔ 極座標を変換。to_polar: 横バンドが放射スポーク / 縦バンドが同心円に / ノイズが渦に。魔法陣のスポーク・爆風の光線に必須。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      mode: {
        type: 'select',
        options: [
          { value: 'to_polar', label: '→ 極座標 (帯→放射)' },
          { value: 'to_cartesian', label: '→ Cartesian (放射→帯)' },
        ],
        default: 'to_polar',
        label: 'モード',
      },
      origin: { type: 'tuple2', default: [16, 16], min: 0, max: 128, label: '中心' },
      twist_deg: {
        type: 'number',
        min: -180,
        max: 180,
        step: 1,
        default: 0,
        label: 'ねじり (度)',
      },
    },
  },

  // ── Filters ─────────────────────────────────────────────────────────────
  {
    type: 'filter.blur',
    label: 'ガウシアンブラー',
    category: 'filter',
    description: 'Canvas2D ベースの軽いガウシアンブラー。強い blur は filter.bloom 内で扱う。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      radius: { type: 'number', min: 0, max: 8, step: 0.5, default: 1, label: '半径' },
    },
  },
  {
    type: 'filter.bloom',
    label: 'ブルーム',
    category: 'filter',
    description:
      'PXC 由来の輝度閾値 → 分離ガウシアン → HSV サチュレーション + ティント の多パス bloom。WebGL2 で実行。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      threshold: { type: 'number', min: 0, max: 1, step: 0.05, default: 0.4, label: '輝度閾値' },
      radius: { type: 'integer', min: 1, max: 9, default: 3, label: 'ブラー半径' },
      intensity: { type: 'number', min: 0, max: 2, step: 0.05, default: 1.0, label: '強度' },
      saturation: { type: 'number', min: 0, max: 2, step: 0.05, default: 1.2, label: '彩度' },
      color: { type: 'color', default: '#fff7c2', label: 'ティント色' },
    },
  },
  {
    type: 'filter.dilate',
    label: '膨張',
    category: 'filter',
    description: 'アルファチャンネルのモルフォロジ膨張 (square kernel)。細い線を太らせる用途。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      radius: { type: 'integer', min: 1, max: 5, default: 1, label: '半径' },
    },
  },
  {
    type: 'filter.palette',
    label: 'パレット量子化',
    category: 'filter',
    description: '入力画像から N 色のパレットを median-cut 抽出し、各画素を最近傍色に置換。エクスポート前の色数制限に。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      colors: { type: 'integer', min: 2, max: 64, default: 16, label: '色数' },
    },
  },
  {
    type: 'filter.trail',
    label: 'トレイル (フレーム蓄積)',
    category: 'filter',
    description:
      'フレーム間で蓄積される画像バッファ。前フレームの結果に decay (透明度減衰) を掛けて、現フレーム入力を上から重ねる。動く粒や形状の「軌跡」を残す。初の state-bearing ノード — ctx.state にバッファを保持。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      decay: { type: 'number', min: 0, max: 1, step: 0.05, default: 0.7, label: '減衰率 (per frame)' },
      mode: {
        type: 'select',
        options: [
          { value: 'over', label: '通常 (over)' },
          { value: 'add', label: '加算 (lighter)' },
        ],
        default: 'over',
        label: '重ねモード',
      },
    },
  },
  {
    type: 'filter.dither',
    label: 'ディザリング',
    category: 'filter',
    description:
      'パレットアウェアディザ。Bayer 4×4 / 8×8 の規則ディザ、または Floyd-Steinberg 誤差拡散。グラデーションを pixel-art らしい「階段状」に。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      palette_size: { type: 'integer', min: 2, max: 16, default: 4, label: 'パレットサイズ (per ch)' },
      pattern: {
        type: 'select',
        options: [
          { value: 'bayer4', label: 'Bayer 4×4' },
          { value: 'bayer8', label: 'Bayer 8×8' },
          { value: 'floyd', label: 'Floyd-Steinberg (誤差拡散)' },
        ],
        default: 'bayer4',
        label: 'パターン',
      },
    },
  },
  {
    type: 'filter.outline',
    label: 'アウトライン',
    category: 'filter',
    description:
      '入力画像のアルファ縁を太さ thickness の指定色で描画 (dilate − original)。pixel-art のリム / 統一感ある縁取りに。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      thickness: { type: 'integer', min: 1, max: 3, default: 1, label: '太さ (px)' },
      color: { type: 'color', default: '#000000', label: '色' },
      mode: {
        type: 'select',
        options: [
          { value: 'outside', label: '外側 (画像周りに足す)' },
          { value: 'inside', label: '内側 (画像内に描く)' },
          { value: 'replace', label: '置換 (入力 → 縁のみ)' },
        ],
        default: 'outside',
        label: 'モード',
      },
    },
  },

  // ── Composites ──────────────────────────────────────────────────────────
  {
    type: 'composite.over',
    label: '前面合成 (a over b)',
    category: 'composite',
    description: 'a を b の上にアルファ合成。',
    inputs: [C('a', '上 (a)'), C('b', '下 (b)', '透過部分から透けて見える背景')],
    hasOutput: true,
    params: {},
  },
  {
    type: 'composite.add',
    label: '加算合成',
    category: 'composite',
    description: 'a と b を加算ブレンド (lighter)。エネルギー / グロー の重ね用途。',
    inputs: [C('a', '上 (a)'), C('b', '下 (b)')],
    hasOutput: true,
    params: {},
  },
  {
    type: 'composite.screen',
    label: 'スクリーン',
    category: 'composite',
    description: 'スクリーンブレンド (1 - (1-a)(1-b))。明部を強調、暗部は保持。',
    inputs: [C('a', '上 (a)'), C('b', '下 (b)')],
    hasOutput: true,
    params: {},
  },
  {
    type: 'composite.multiply',
    label: '乗算',
    category: 'composite',
    description: '乗算ブレンド (a × b)。暗部強調、シャドウや vignette 用途。',
    inputs: [C('a', '上 (a)'), C('b', '下 (b)')],
    hasOutput: true,
    params: {},
  },

  // ── Animation drivers ───────────────────────────────────────────────────
  {
    type: 'anim.time',
    label: '時間ドライバ',
    category: 'animation',
    description: '時間ドライバ (v1 はメタデータのみ、graph レベルの frames/frame_time_ticks に時間情報を集約)。v2 でスカラー出力対応予定。',
    inputs: [],
    hasOutput: true,
    params: {
      loop_seconds: { type: 'number', min: 0.1, max: 10, step: 0.1, default: 1.5, label: 'ループ秒' },
    },
  },
  {
    type: 'anim.rotate',
    label: '回転アニメ',
    category: 'animation',
    description: '入力を speed_deg_per_sec で連続回転。フレーム時間から自動位相算出。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      speed_deg_per_sec: {
        type: 'number',
        min: -360,
        max: 360,
        step: 5,
        default: 90,
        label: '速度 (度/秒)',
      },
      origin: {
        type: 'tuple2',
        default: [16, 16],
        min: 0,
        max: 64,
        label: '回転中心',
      },
    },
  },
  {
    type: 'anim.scale_pulse',
    label: '伸縮パルス',
    category: 'animation',
    description:
      '入力を [min, max] の範囲で周期スケール。カーブ形状は easing 選択 — sin (正弦)、ease_in_out (柔らかいパルス + 頂点ホールド)、bounce_out (頂点で跳ね返り)、square (ストロボ)。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      min: { type: 'number', min: 0.1, max: 2, step: 0.05, default: 0.8, label: '最小スケール' },
      max: { type: 'number', min: 0.1, max: 2, step: 0.05, default: 1.2, label: '最大スケール' },
      period_seconds: { type: 'number', min: 0.1, max: 10, step: 0.1, default: 1.0, label: '周期秒' },
      easing: {
        type: 'select',
        options: [
          { value: 'sin', label: 'Sin (正弦)' },
          { value: 'ease_in_out', label: 'Ease InOut (柔らか)' },
          { value: 'bounce_out', label: 'Bounce (跳ね)' },
          { value: 'square', label: 'Square (ストロボ)' },
        ],
        default: 'sin',
        label: 'カーブ',
      },
    },
  },
  {
    type: 'anim.scale_ramp',
    label: 'スケールランプ',
    category: 'animation',
    description:
      'from → to へ duration_frames の間で easing カーブに沿ってスケール。期間後の挙動: hold (保持) / fade (alpha フェード) / loop (繰り返し)。衝撃波 / spawn-in / impact 用途に。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      from: { type: 'number', min: 0, max: 4, step: 0.05, default: 0, label: '開始スケール' },
      to: { type: 'number', min: 0, max: 4, step: 0.05, default: 1, label: '終了スケール' },
      start_frame: { type: 'integer', min: 0, max: 64, default: 0, label: '開始フレーム' },
      duration_frames: { type: 'integer', min: 1, max: 64, default: 8, label: '持続フレーム数' },
      easing: {
        type: 'select',
        options: [
          { value: 'linear', label: 'Linear' },
          { value: 'ease_in', label: 'Ease In' },
          { value: 'ease_out', label: 'Ease Out' },
          { value: 'ease_in_out', label: 'Ease InOut' },
          { value: 'back_out', label: 'Back Out (オーバーシュート)' },
          { value: 'bounce_out', label: 'Bounce Out (跳ね)' },
          { value: 'elastic_out', label: 'Elastic Out (弾性)' },
        ],
        default: 'ease_out',
        label: 'カーブ',
      },
      after: {
        type: 'select',
        options: [
          { value: 'hold', label: 'Hold (保持)' },
          { value: 'fade', label: 'Fade (alpha フェード)' },
          { value: 'loop', label: 'Loop (繰り返し)' },
        ],
        default: 'hold',
        label: '期間後',
      },
    },
  },
  {
    type: 'anim.curve',
    label: 'カーブ (Bezier)',
    category: 'animation',
    description:
      '多キーフレーム数値カーブ。0..1 の loop 進行に沿ってキーフレーム間を線形補間し、`number` を出力。下流ノードの数値 param に bind (例: scale_pulse.max を curve で駆動)。',
    inputs: [],
    hasOutput: true,
    outputKind: 'number',
    params: {
      keyframes: {
        type: 'curve',
        default: [
          { t: 0, v: 0 },
          { t: 0.5, v: 1 },
          { t: 1, v: 0 },
        ],
        label: 'キーフレーム',
        vmin: -2,
        vmax: 2,
      },
      mode: {
        type: 'select',
        options: [
          { value: 'loop', label: 'ループ' },
          { value: 'once', label: '一度のみ (終端で保持)' },
          { value: 'pingpong', label: 'ピンポン (行って戻る)' },
        ],
        default: 'loop',
        label: 'モード',
      },
    },
  },
  {
    type: 'anim.wiggler',
    label: 'ウィグラー (Perlin)',
    category: 'animation',
    description:
      'Perlin ノイズ駆動の random walk。`center + amplitude * perlin(t * frequency)` を `number` 出力。揺らめき / 浮遊感 / 微振動に。',
    inputs: [],
    hasOutput: true,
    outputKind: 'number',
    params: {
      frequency: { type: 'number', min: 0.1, max: 10, step: 0.1, default: 1.0, label: '周波数' },
      amplitude: { type: 'number', min: 0, max: 100, step: 0.1, default: 1.0, label: '振幅' },
      center: { type: 'number', min: -100, max: 100, step: 0.1, default: 0, label: '中心値' },
      seed: { type: 'integer', min: 0, max: 9999, default: 1, label: 'シード' },
    },
  },
  {
    type: 'anim.stagger',
    label: 'スタガー (層遅延)',
    category: 'animation',
    description:
      '入力画像を N 層に重ねる (各層は前の層より delay_frames 前の状態 + alpha 減衰)。爆風の trail や階段状の reveal に。',
    inputs: [C('image', '入力')],
    hasOutput: true,
    params: {
      count: { type: 'integer', min: 1, max: 8, default: 3, label: '層数' },
      delay_frames: { type: 'integer', min: 1, max: 16, default: 2, label: '遅延フレーム数' },
      decay_alpha: { type: 'number', min: 0, max: 1, step: 0.05, default: 0.6, label: '透明度減衰 (層ごと)' },
    },
  },

  // ── Values (scalar / vector emitters) ───────────────────────────────────
  {
    type: 'values.number',
    label: '数値定数',
    category: 'values',
    description:
      '数値を出力。下流ノードの同名 input port (kind: number) に接続すると、その param を edge 駆動に置き換える。例: Number(0.8) → filter.bloom.intensity。',
    inputs: [],
    hasOutput: true,
    outputKind: 'number',
    params: {
      value: { type: 'number', min: -10, max: 10, step: 0.01, default: 1, label: '値' },
    },
  },
  {
    type: 'values.vec2',
    label: '2D ベクター定数',
    category: 'values',
    description:
      '(x, y) 2 成分ベクターを出力。座標 param (origin / spawn_origin など) を edge 駆動に。',
    inputs: [],
    hasOutput: true,
    outputKind: 'vec2',
    params: {
      x: { type: 'number', min: -128, max: 128, step: 0.5, default: 0, label: 'x' },
      y: { type: 'number', min: -128, max: 128, step: 0.5, default: 0, label: 'y' },
    },
  },

  // ── Sink ────────────────────────────────────────────────────────────────
  {
    type: 'output',
    label: '出力',
    category: 'sink',
    description: '最終フレーム出力。各 fxgraph に 1 つだけ存在。',
    inputs: [C('image', '入力')],
    hasOutput: false,
    params: {},
  },
]

export const SCHEMA: Record<NodeType, NodeSpec> = Object.fromEntries(
  SPECS.map((s) => [s.type, s]),
) as Record<NodeType, NodeSpec>

/** Default param record for a given node type, derived from schema. */
export function defaultParamsFor(type: NodeType): Record<string, ParamValue> {
  const spec = SCHEMA[type]
  if (!spec) return {}
  const out: Record<string, ParamValue> = {}
  for (const [k, p] of Object.entries(spec.params)) {
    out[k] = p.default as ParamValue
  }
  return out
}

/** Group node types by category — used by NodePalette. */
export function nodesByCategory(): Record<NodeCategory, NodeSpec[]> {
  const out: Record<NodeCategory, NodeSpec[]> = {
    generator: [],
    transform: [],
    filter: [],
    composite: [],
    animation: [],
    values: [],
    sink: [],
  }
  for (const s of SPECS) out[s.category].push(s)
  return out
}

export const CATEGORY_LABELS: Record<NodeCategory, string> = {
  generator: '生成 (generator)',
  transform: '変換 (transform)',
  filter: 'フィルタ (filter)',
  composite: '合成 (composite)',
  animation: 'アニメ (animation)',
  values: '値 (values, scalar / vec2)',
  sink: '出力 (output)',
}
