#!/usr/bin/env bash
# Own the normal vfx-shot stack lock and slot until the browser service exits.
set -uo pipefail
ACTION="${1:-start}"
LIVE_HERE="$(cd "$(dirname "$0")" && pwd -P)"
SHOT_ID="$(printf '%s' "$(cd "$LIVE_HERE/../.." && pwd -P)" | sha1sum | cut -c1-8)"
export SHOT_ID
if [ "$ACTION" = down ]; then VIEW=caster; CASE=""; fi
export VIEW="${VIEW:-caster}" RES="${RES:-1280x720}"
[[ "$VIEW" != compare || "${CASE:-}" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] || { echo 'compare needs a valid CASE id' >&2; exit 2; }
export SHOT_SOUND_VOLUME=1.0 SHOT_MAX_FPS="${LIVE_FPS:-20}"
export SHOT_PULSE_PREFIX="live_$SHOT_ID"
PULSE_DIR="/tmp/claude-1000/astera-live-$SHOT_ID"
export SHOT_PULSE_SERVER="unix:$PULSE_DIR/pulse.sock"
export VFX_SHOT_SOURCED=1 SHOT_KEEP=0
set --
. "$LIVE_HERE/../vfx-shot/vfx-shot.sh" || exit 1
if [ "$ACTION" = down ]; then
  if [ -f "$STATE/live-owner" ]; then
    read -r pid start < "$STATE/live-owner"
    if [ "$(proc_starttime "$pid")" = "$start" ]; then
      kill -TERM "$pid"
      for _ in $(seq 1 90); do
        [ "$(proc_starttime "$pid")" != "$start" ] && break
        sleep 1
      done
      [ "$(proc_starttime "$pid")" != "$start" ] || die "live session did not stop; see $STATE/watchdog.log"
    fi
  fi
  reap_py --only-state "$STATE" --force --wait-lock 10 || exit 1
  rm -f "$STATE/live-owner" "$STATE/live.json"
  exit 0
fi
[ "$ACTION" = start ] || die "usage: live-view.sh [start|down]"
[[ "$SKILL" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] && [ -f "$ROOT/content/skills/$SKILL.yaml" ] || die "SKILL must name content/skills/<id>.yaml"
case "$VIEW" in caster|side|compare) ;; *) die "VIEW must be caster|side|compare" ;; esac
for tool in bun ffmpeg ffprobe pulseaudio pactl Xvfb xdotool jq; do command -v "$tool" >/dev/null || die "install $tool (see tools/live-view/README.md)"; done
[[ "$RES" =~ ^[0-9]+x[0-9]+$ ]] || die "RES must be WIDTHxHEIGHT"
width="${RES%x*}"; height="${RES#*x}"
[ "$width" -ge 400 ] && [ "$height" -ge 120 ] && [ "$((width % 2))" = 0 ] && [ "$((height % 2))" = 0 ] || die "RES must have even dimensions (at least 400x120)"
[[ "$SHOT_MAX_FPS" =~ ^[0-9]+$ ]] && [ "$SHOT_MAX_FPS" -ge 5 ] && [ "$SHOT_MAX_FPS" -le 60 ] || die "LIVE_FPS must be 5..60"
# Tag every detached process so the inherited watchdog/reaper also owns them after SIGKILL.
run_begin
printf '%s %s\n' "$$" "$(proc_starttime "$$")" > "$STATE/live-owner"
mkdir -p "$PULSE_DIR"
chmod 700 "$PULSE_DIR"
VFX_SHOT_STATE="$STATE" setsid pulseaudio -n --daemonize=no --exit-idle-time=-1 --use-pid-file=no \
  --load="module-native-protocol-unix socket=$PULSE_DIR/pulse.sock auth-anonymous=1" \
  --load="module-null-sink sink_name=${SHOT_PULSE_PREFIX}_Caster sink_properties=device.description=${SHOT_PULSE_PREFIX}_Caster rate=48000 channels=2" \
  --load="module-null-sink sink_name=${SHOT_PULSE_PREFIX}_Watcher sink_properties=device.description=${SHOT_PULSE_PREFIX}_Watcher rate=48000 channels=2" \
  > "$STATE/pulse.log" 2>&1 {STACK_LOCK_FD}>&- {SLOT_FD}>&- &
for _ in $(seq 1 100); do
  PULSE_SERVER="$SHOT_PULSE_SERVER" pactl info >/dev/null 2>&1 && break
  sleep 0.1
done
PULSE_SERVER="$SHOT_PULSE_SERVER" pactl info >/dev/null 2>&1 || die "private PulseAudio failed: $STATE/pulse.log"
ensure_client_install
boot_server
grep -qw -- "$SKILL" <<<"$(rcon 'astera skill list')" || die "skill is not registered: $SKILL"
prepare_view "$VIEW"
hud "$VIEWER" "$([ "$HIDE_HUD" = 1 ] && echo hide || echo show)"
jq -n --arg skill "$SKILL" --arg view "$VIEW" --arg viewer "$VIEWER" --arg display ":${DISP[$VIEWER]}" \
  --arg res "$RES_FILM" --arg fov "$FOV" --arg camera "$CAMERA" --arg caseId "$CASE" \
  '{skill:$skill,view:$view,viewer:$viewer,display:$display,resolution:$res,fov:$fov,camera:$camera,caseId:$caseId}' > "$STATE/live.json"
export LIVE_STATE="$STATE" LIVE_ROOT="$ROOT" LIVE_HERE SHOT_ID
VFX_SHOT_STATE="$STATE" setsid bun "$LIVE_HERE/server.mjs" {STACK_LOCK_FD}>&- {SLOT_FD}>&- &
FILM_PID=$!
wait "$FILM_PID"
