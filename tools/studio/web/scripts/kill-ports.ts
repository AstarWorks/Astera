// Free dev ports before `bun run dev` boots vite + api.
// Runs as the `predev` npm lifecycle hook.
import { $ } from 'bun'

const ports = [
  Number(process.env.ASTERA_STUDIO_PORT ?? '3007'),
  Number(process.env.ASTERA_STUDIO_API_PORT ?? '3008'),
]

for (const port of ports) {
  const { stdout } = await $`lsof -ti:${port}`.nothrow().quiet()
  const pids = stdout.toString().trim().split('\n').filter(Boolean)
  if (pids.length === 0) continue
  await $`kill -9 ${pids}`.nothrow().quiet()
  console.log(`[kill-ports] freed :${port} (pid ${pids.join(', ')})`)
}
