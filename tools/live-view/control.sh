#!/usr/bin/env bash
# Called only by the serialized browser API; the parent owns the stack lock.
set -uo pipefail
LIVE_HERE="$(cd "$(dirname "$0")" && pwd -P)"
ACTION="$1"; HUD_ACTION="${2:-show}"
set --
export VFX_SHOT_SOURCED=1
. "$LIVE_HERE/../vfx-shot/vfx-shot.sh"
load_subnet || die "live stack is gone"
export STACK_WAS_COLD=0
VIEWER="$([ "$VIEW" = caster ] && echo Caster || echo Watcher)"
case "$ACTION" in
  view)
    prepare_view "$VIEW"
    hud "$VIEWER" "$([ "$HIDE_HUD" = 1 ] && echo hide || echo show)"
    jq -n --arg skill "$SKILL" --arg view "$VIEW" --arg viewer "$VIEWER" --arg display ":${DISP[$VIEWER]}" \
      --arg res "$RES_FILM" --arg fov "$FOV" --arg camera "$CAMERA" --arg caseId "$CASE" \
      '{skill:$skill,view:$view,viewer:$viewer,display:$display,resolution:$res,fov:$fov,camera:$camera,caseId:$caseId}' > "$STATE/live.json"
    ;;
  cast)
    grep -qw -- "$SKILL" <<<"$(rcon 'astera skill list')" || die "skill is not registered: $SKILL (Reload content first)"
    reset_arena
    give_item Caster
    if [ "$VIEW" = compare ]; then
      eval "$(python3 -I "$HERE/compare_plan.py" "$INTENT" "$CAMERA" "$CX" "$Y" "$CZ" "$STATE/camera-plan.tsv" "${FOV:--}")"
    fi
    place_scene "$VIEW" "$(target_z)"
    sleep 0.3
    if [ "$VIEW" = compare ]; then
      python3 -I "$HERE/director.py" "$SERVER_IP" "$RCON_PORT" "$RCON_PASS" "$STATE/camera-plan.tsv" "astera skill cast $SKILL Caster --force"
    else
      rcon --stamp "astera skill cast $SKILL Caster --force"
    fi
    ;;
  hud) load_client "$VIEWER" || die "viewer is gone"; hud "$VIEWER" "$HUD_ACTION" ;;
  reload)
    # The shared batch helper only warns on a rejected reload; make the interactive action fail visibly.
    reload_content() {
      local out
      out="$(rcon 'astera admin reload all')" || die "RCON reload failed"
      printf '%s\n' "$out"
      case "$out" in *ailed*|*rror*) die "content reload was rejected" ;; esac
      rcon 'astera items refresh'
    }
    read_inputs; sync_content ;;
  *) die "unknown action" ;;
esac
