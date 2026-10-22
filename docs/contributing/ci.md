# CI is the test cloud

Agents (and humans on small machines) verify through GitHub Actions, not locally. Do not run `./gradlew`,
`make e2e` or docker builds on the host. Push a `wp/<name>` branch and read the result.

Workflow: `.github/workflows/ci.yml`, triggered on push to `main` and `wp/**`, on pull requests and manually.
A newer push to the same ref cancels the older run.

| Job | What | Artifacts |
|---|---|---|
| `build` | `./gradlew check` (unit + Konsist), then the 7 plugin shadow jars (`make build`) | `astera-plugin-jars`, `test-reports` (on failure) |
| `e2e` | `make e2e` (docker, MCProtocolLib bots); report copied to the job summary; fails on any failed check | `e2e-report`, `e2e-backend-logs` (on failure) |
| `skill-frames` | `:tools:skill-viewer:render` (bun + Chromium + ffmpeg) for the `content/skills/*.yaml` changed in the push / PR. Manual run: `gh workflow run CI --ref wp/<name> -f skills="simple-shoot backstep"` (or `ALL`, 40+ min) | `skill-frames` (sheet.png, anim.gif per skill) |

## Reading results with gh

```
git push -u origin HEAD:wp/<name>
gh run list --branch wp/<name> --limit 5
gh run watch <run-id> --exit-status
gh run view <run-id> --log-failed          # failing steps only
gh run download <run-id> -n e2e-report     # or -n skill-frames / astera-plugin-jars / test-reports
```

If `gh` reports a repo access error, retry with `ASTERA_GH_IDENTITY=human gh ...`.
