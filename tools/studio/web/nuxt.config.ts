// Astera Studio — Nuxt 4 SPA + Nitro on Bun.
// Dev-only authoring IDE for Minecraft content (textures / 3D models / pixel FX).
// Per ADR-0018 / ADR-0066 / ADR-00XX: never depended on by Astera runtime or Gradle build.

export default defineNuxtConfig({
  compatibilityDate: '2026-05-20',

  // SPA mode — Studio is a single-user dev tool. SEO N/A. Three.js / WebGL /
  // WebSocket are awkward under SSR. Skipping SSR also removes a class of
  // hydration mismatch bugs from vue-flow / CodeMirror.
  ssr: false,

  devtools: { enabled: true },

  devServer: {
    port: Number(process.env.ASTERA_STUDIO_PORT ?? '3007'),
  },

  nitro: {
    preset: 'bun',
    experimental: { websocket: true },
    // Native module — let Nitro treat it as external so the Bun runtime
    // resolves it from node_modules at runtime instead of trying to bundle it.
    externals: {
      external: ['@napi-rs/canvas'],
    },
    // Don't auto-import from `utils/asdsl/` — the ASDSL submodule exports
    // type names (Vec3, Hint, …) that collide with sibling utils. Inside
    // server/ everything still uses explicit imports.
    imports: {
      dirs: ['utils', 'utils/composite'],
    },
  },

  css: [
    '@vue-flow/core/dist/style.css',
    '@vue-flow/core/dist/theme-default.css',
    '@vue-flow/controls/dist/style.css',
    '@vue-flow/minimap/dist/style.css',
  ],

  runtimeConfig: {
    // Server-only env passthrough. Read inside server/ via useRuntimeConfig().
    anthropicApiKey: process.env.ANTHROPIC_API_KEY ?? '',
    repoRoot: process.env.STUDIO_REPO_ROOT ?? '',
    jobRoot: process.env.STUDIO_JOB_ROOT ?? '',
    texturesDir: process.env.STUDIO_TEXTURES_DIR ?? '',
    scriptsDir: process.env.STUDIO_SCRIPTS_DIR ?? '',
    modelSamplesDir: process.env.STUDIO_MODEL_SAMPLES_DIR ?? '',
    vanillaCacheDir: process.env.STUDIO_VANILLA_CACHE_DIR ?? '',
  },

  vite: {
    // Pre-bundle the heavy client-side deps so the first cold dev request
    // doesn't stall behind a 30-module dependency resolve.
    optimizeDeps: {
      include: ['three', '@vue-flow/core', '@vue-flow/background', '@vue-flow/controls', '@vue-flow/minimap'],
    },
  },

  app: {
    head: {
      title: 'Astera Studio',
      htmlAttrs: { lang: 'ja' },
      meta: [
        { charset: 'UTF-8' },
        { name: 'viewport', content: 'width=device-width, initial-scale=1.0' },
      ],
      link: [{ rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
    },
  },
})
