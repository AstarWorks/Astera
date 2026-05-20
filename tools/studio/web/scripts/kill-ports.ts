// Free the dev port before `nuxt dev` boots Nitro + Vite.
// Runs as the `predev` npm lifecycle hook.
import { $ } from 'bun'

const port = Number(process.env.ASTERA_STUDIO_PORT ?? '3007')

const { stdout } = await $`lsof -ti:${port}`.nothrow().quiet()
const pids = stdout.toString().trim().split('\n').filter(Boolean)
if (pids.length > 0) {
  await $`kill -9 ${pids}`.nothrow().quiet()
  console.log(`[kill-ports] freed :${port} (pid ${pids.join(', ')})`)
}
