#!/usr/bin/env bash
# synth.sh — synthesize every Astera SFX from scratch with ffmpeg (ADR-0267: ffmpeg synthesis + CC0 only).
#
# No samples, no network, no paid service: each sound is a formula (sine / bell partials / saw-ish brass /
# filtered noise, envelopes, sweeps) mixed and then loudness-normalized (EBU R128, two-pass linear) and
# encoded as mono 44.1 kHz OGG Vorbis under content/sounds/<name>.ogg. The encode is bitexact and the noise
# is seeded, so re-running reproduces the committed files on the same ffmpeg build.
#
# Usage:
#   synth.sh              build every sound
#   synth.sh star/pickup  build only the named ones (path under content/sounds/, no extension)
#   ASTERA_SOUNDS_DIR=/tmp/out synth.sh   write somewhere else (to audition without touching content/)
#
# Needs ffmpeg >= 4.2 with libvorbis, loudnorm, aevalsrc, anoisesrc; and jq.
# Boundary: designer-side tool only; never invoked by the Astera build or runtime.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT_ROOT="${ASTERA_SOUNDS_DIR:-$ROOT/content/sounds}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SR=44100
LOUD_I=-18   # integrated LUFS target; per-cue loudness is then set by default_volume in the cue yaml
LOUD_TP=-1.5

for bin in ffmpeg ffprobe jq; do
    command -v "$bin" >/dev/null || { echo "error: $bin not on PATH" >&2; exit 1; }
done

FF=(ffmpeg -hide_banner -loglevel error -y -fflags +bitexact)

# ---- expression building blocks (aevalsrc; t = seconds) -----------------------------------------------
# bell F K : inharmonic bell, K = decay rate (1/s). Higher partials die faster.
bell() { echo "(sin(2*PI*$1*t)+0.5*sin(2*PI*2.76*$1*t)*exp(-$2*0.5*t)+0.25*sin(2*PI*5.4*$1*t)*exp(-$2*t)+0.12*sin(2*PI*8.93*$1*t)*exp(-$2*1.5*t))*exp(-$2*t)*min(t/0.002,1)*0.5"; }
# pluck F K : harmonic plucked tone.
pluck() { echo "(sin(2*PI*$1*t)+0.45*sin(4*PI*$1*t)*exp(-$2*0.6*t)+0.2*sin(6*PI*$1*t)*exp(-$2*t))*exp(-$2*t)*min(t/0.003,1)*0.55"; }
# brass F A R : saw-ish partials (1..5), attack A seconds, release rate R (1/s), gentle vibrato.
brass() { echo "(sin(2*PI*$1*t+0.02*sin(2*PI*5.5*t))+0.6*sin(4*PI*$1*t)+0.4*sin(6*PI*$1*t)+0.25*sin(8*PI*$1*t)+0.15*sin(10*PI*$1*t))*min(t/$2,1)*exp(-$3*t)*0.28"; }
# sq F K : soft square (odd harmonics), decaying.
sq() { echo "(sin(2*PI*$1*t)+0.33*sin(6*PI*$1*t)+0.2*sin(10*PI*$1*t))*exp(-$2*t)*min(t/0.002,1)*0.5"; }
# sweep F0 F1 D K : sine whose frequency glides F0->F1 over D seconds, then decays at rate K.
sweep() { echo "sin(2*PI*($1*t+($2-$1)*t*t/(2*$3)))*exp(-$4*t)*min(t/0.004,1)*0.6"; }

# gen NAME DUR EXPR : aevalsrc -> $TMP/NAME.wav
gen() { "${FF[@]}" -f lavfi -i "aevalsrc=exprs='$3':s=$SR:d=$2" -ac 1 "$TMP/$1.wav"; }
# noise NAME DUR FILTERCHAIN SEED : seeded white noise through a filter chain (envelope etc.)
noise() { "${FF[@]}" -f lavfi -i "anoisesrc=d=$2:c=white:r=$SR:seed=${4:-7}:a=0.8" -af "$3" -ac 1 "$TMP/$1.wav"; }

# mix OUT DUR "file:delay_ms:gain" ... : sum of delayed, scaled layers, hard-trimmed to DUR.
mix() {
    local out="$1" dur="$2"; shift 2
    local inputs=() graph="" labels="" i=0 spec file delay gain
    for spec in "$@"; do
        IFS=: read -r file delay gain <<<"$spec"
        inputs+=(-i "$TMP/$file.wav")
        graph+="[$i:a]adelay=${delay}|${delay},volume=${gain}[m$i];"
        labels+="[m$i]"
        i=$((i + 1))
    done
    graph+="${labels}amix=inputs=$i:normalize=0:duration=longest,atrim=0:$dur,asetpts=N/SR/TB[o]"
    "${FF[@]}" "${inputs[@]}" -filter_complex "$graph" -map "[o]" -ac 1 -ar $SR "$TMP/$out.wav"
}

# echo_tail IN OUT AFILTER : post-process a mixed wav (reverb-ish echo / lowpass).
post() { "${FF[@]}" -i "$TMP/$1.wav" -af "$3" -ac 1 "$TMP/$2.wav"; }

# finish NAME [WAV] : EBU R128 two-pass (linear) normalize, mono 44.1 kHz Vorbis -> content/sounds/NAME.ogg.
# Clips >= 0.6 s: loudnorm to $LOUD_I LUFS. Shorter clips (clicks, ticks): peak-normalized to -3 dBFS.
finish() {
    local name="$1" wav="$TMP/$2.wav" dst="$OUT_ROOT/$1.ogg" len stats
    len=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$wav")
    local chain fade
    fade="afade=t=out:st=$(awk "BEGIN{print $len-0.01}"):d=0.01"
    if awk "BEGIN{exit !($len < 0.6)}"; then
        # Too short for BS.1770 gating (no complete 400 ms block): normalize the sample peak to -3 dBFS instead.
        local peak
        peak=$(ffmpeg -hide_banner -nostats -loglevel info -i "$wav" -af volumedetect -f null - 2>&1 | sed -n 's/.*max_volume: \(-\?[0-9.]*\) dB.*/\1/p')
        chain="volume=$(awk "BEGIN{print -3 - ($peak)}")dB,$fade"
    else
        stats=$(ffmpeg -hide_banner -nostats -loglevel info -i "$wav" -af "loudnorm=I=$LOUD_I:TP=$LOUD_TP:LRA=11:print_format=json" -f null - 2>&1 | sed -n '/^{/,/^}/p')
        local mi mtp mlra mthr off
        mi=$(jq -r .input_i <<<"$stats"); mtp=$(jq -r .input_tp <<<"$stats")
        mlra=$(jq -r .input_lra <<<"$stats"); mthr=$(jq -r .input_thresh <<<"$stats"); off=$(jq -r .target_offset <<<"$stats")
        chain="loudnorm=I=$LOUD_I:TP=$LOUD_TP:LRA=11:measured_I=$mi:measured_TP=$mtp:measured_LRA=$mlra:measured_thresh=$mthr:offset=$off:linear=true,atrim=0:$len,$fade"
    fi
    mkdir -p "$(dirname "$dst")"
    "${FF[@]}" -i "$wav" -af "$chain" -ac 1 -ar $SR -c:a libvorbis -q:a 5 -flags:a +bitexact "$dst"
    local ch rate
    ch=$(ffprobe -v error -select_streams a:0 -show_entries stream=channels -of csv=p=0 "$dst")
    rate=$(ffprobe -v error -select_streams a:0 -show_entries stream=sample_rate -of csv=p=0 "$dst")
    [[ "$ch" == 1 && "$rate" == $SR ]] || { echo "error: $dst is not mono/${SR}Hz" >&2; exit 1; }
    echo "ok: $name ($(stat -c %s "$dst") bytes, ${len}s)"
}

# ---- the sounds ----------------------------------------------------------------------------------------
# Each function builds its layers with gen/noise, mixes them into $TMP/<leaf>.wav, then finish "<cat>/<leaf>" <leaf>.

ui_click() {      # dry tick: soft square blip + a puff of high noise
    gen a 0.09 "$(sq 1900 70)"
    noise b 0.09 "highpass=f=5000,volume='exp(-t*90)':eval=frame" 11
    mix click 0.09 a:0:0.9 b:0:0.25; finish ui/click click
}
ui_confirm() {    # two rising plucks, a fifth apart
    gen a 0.3 "$(pluck 880 11)"; gen b 0.3 "$(pluck 1320 9)"
    mix confirm 0.34 a:0:0.8 b:90:1; finish ui/confirm confirm
}
ui_deny() {       # two falling low buzzes
    gen a 0.18 "$(sq 196 14)"; gen b 0.28 "$(sq 147 9)"
    mix deny 0.4 a:0:1 b:110:1; finish ui/deny deny
}
ui_purchase() {   # coin: two bright bells, shimmer
    gen a 0.55 "$(bell 1568 9)"; gen b 0.55 "$(bell 2093 8)"
    noise c 0.2 "highpass=f=7000,volume='exp(-t*22)':eval=frame" 21
    mix purchase 0.55 a:0:0.8 b:75:1 c:60:0.18; finish ui/purchase purchase
}
star_pickup() {   # bright rising chime: a quick upward blip into two high bells
    gen a 0.55 "$(sweep 1900 2637 0.06 12)"
    gen b 0.55 "$(bell 2637 8)"; gen c 0.55 "$(bell 3951 10)"
    mix pickup 0.55 a:0:0.6 b:0:0.9 c:45:0.7; finish star/pickup pickup
}
star_burst() {    # shatter + rising sparkle arpeggio
    noise a 0.5 "highpass=f=3000,volume='exp(-t*14)':eval=frame" 31
    local i=0 f
    for f in 2093 2637 3136 3951 5274 6272; do gen "s$i" 0.7 "$(bell $f 7)"; i=$((i + 1)); done
    mix burst 0.8 a:0:0.55 s0:0:0.6 s1:35:0.6 s2:70:0.6 s3:105:0.6 s4:140:0.55 s5:175:0.5; finish star/burst burst
}
norma_level_up() {  # C5 E5 G5 C6 arpeggio
    local i=0 f; for f in 523 659 784 1047; do gen "n$i" 0.6 "$(pluck $f 7)"; i=$((i + 1)); done
    gen top 0.6 "$(bell 2093 10)"
    mix level-up 0.75 n0:0:0.8 n1:85:0.85 n2:170:0.9 n3:255:1 top:255:0.35; finish norma/level-up level-up
}
norma_level_max() {  # triumphant fanfare: climbing triplet then a held major chord with a bell crown
    gen a 0.4 "$(brass 523 0.015 4)"; gen b 0.4 "$(brass 659 0.015 4)"; gen c 0.5 "$(brass 784 0.015 4)"
    gen d 1.5 "$(brass 523 0.03 1.6)"; gen e 1.5 "$(brass 659 0.03 1.6)"; gen f 1.5 "$(brass 784 0.03 1.6)"; gen g 1.5 "$(brass 1047 0.03 1.6)"
    gen h 1.5 "$(bell 2093 3)"; gen k 1.5 "$(bell 3136 3.5)"
    mix fl 2.1 a:0:0.8 b:130:0.85 c:260:0.9 d:400:0.7 e:400:0.7 f:400:0.7 g:400:0.7 h:400:0.35 k:420:0.25
    post fl level-max "aecho=0.7:0.5:70|140:0.3|0.15"; finish norma/level-max level-max
}
match_countdown_tick() {  # woodblock tock with a faint bell
    gen a 0.2 "$(sq 1100 45)"; gen b 0.2 "$(bell 1760 26)"
    mix tick 0.2 a:0:0.9 b:0:0.3; finish match/countdown-tick tick
}
match_go() {      # riser, whoosh, then a bright bell hit
    gen a 0.3 "sin(2*PI*(380*t+(1500-380)*t*t/(2*0.3)))*min(t/0.05,1)*0.45"
    noise b 0.3 "bandpass=f=2500:w=2,volume='min(t/0.28,1)':eval=frame" 41
    gen c 0.6 "$(bell 1568 7)"; gen d 0.6 "$(bell 2349 7)"
    mix go 0.9 a:0:0.7 b:0:0.5 c:300:1 d:300:0.7; finish match/go go
}
combat_kill() {   # punchy confirm: low thump + sharp bell ping
    gen a 0.25 "$(sweep 240 70 0.12 14)"; gen b 0.5 "$(bell 1319 12)"
    noise c 0.1 "highpass=f=3500,volume='exp(-t*60)':eval=frame" 51
    mix kill 0.5 a:0:0.9 b:0:0.8 c:0:0.3; finish combat/kill kill
}
match_death() {   # falling groan + low rumble
    gen a 1.1 "sin(2*PI*(330*t+(45-330)*t*t/(2*1.1)))*exp(-1.8*t)*min(t/0.01,1)*0.6"
    noise b 1.2 "lowpass=f=300,volume='exp(-t*2.5)':eval=frame" 61
    mix death 1.2 a:0:1 b:0:0.6; finish match/death death
}
match_win() {     # bright victory sting: rising brass triad then a held major 9th with bells
    gen a 0.35 "$(brass 523 0.01 5)"; gen b 0.35 "$(brass 659 0.01 5)"; gen c 0.35 "$(brass 784 0.01 5)"
    gen d 1.8 "$(brass 523 0.02 1.4)"; gen e 1.8 "$(brass 659 0.02 1.4)"; gen f 1.8 "$(brass 784 0.02 1.4)"; gen g 1.8 "$(brass 1175 0.02 1.4)"
    gen h 1.8 "$(bell 2093 2.6)"; gen k 1.8 "$(bell 2637 3)"; gen m 1.8 "$(bell 3136 3.4)"
    mix w 2.2 a:0:0.8 b:100:0.85 c:200:0.9 d:320:0.7 e:320:0.7 f:320:0.7 g:320:0.55 h:320:0.35 k:380:0.3 m:440:0.25
    post w win "aecho=0.7:0.5:60|120:0.25|0.12"; finish match/win win
}
match_lose() {    # slow descending minor line, dark and muted
    gen a 1.0 "$(pluck 440 3.5)"; gen b 1.0 "$(pluck 349 3.2)"; gen c 1.4 "$(pluck 294 2.2)"; gen d 1.4 "$(pluck 147 2)"
    mix l 1.8 a:0:0.8 b:380:0.85 c:760:1 d:760:0.7
    post l lose "lowpass=f=2200,aecho=0.7:0.5:90:0.25"; finish match/lose lose
}
# Extras for the three weapon families (not wired into skills yet; ready for a play_sound to point at).
weapons_gun_shot() {   # crack + body thump + short tail
    noise a 0.35 "highpass=f=900,volume='exp(-t*28)':eval=frame" 71
    gen b 0.3 "$(sweep 180 45 0.1 16)"
    noise c 0.4 "lowpass=f=1400,volume='exp(-t*9)':eval=frame" 72
    mix shot 0.45 a:0:0.9 b:0:1 c:10:0.3; finish weapons/gun-shot shot
}
weapons_sword_swing() {  # airy whoosh: band-passed noise with a bell-shaped envelope
    noise a 0.45 "bandpass=f=1800:w=1.6,volume='pow(sin(PI*min(t/0.4,1)),2)':eval=frame" 81
    gen b 0.45 "sin(2*PI*(500*t+(1400-500)*t*t/(2*0.4)))*sin(PI*min(t/0.4,1))*0.12"
    mix swing 0.45 a:0:1 b:0:0.5; finish weapons/sword-swing swing
}
skills_wand_cast() {   # rising shimmer and a bell crown
    gen a 0.7 "sin(2*PI*(500*t+(1800-500)*t*t/(2*0.5)))*min(t/0.05,1)*exp(-3*max(t-0.4,0))*0.3"
    local i=0 f; for f in 1568 1976 2349 3136; do gen "s$i" 0.7 "$(bell $f 6)"; i=$((i + 1)); done
    mix wand 0.8 a:0:0.7 s0:150:0.6 s1:220:0.6 s2:290:0.6 s3:360:0.6; finish skills/wand-cast wand
}

ALL=(ui/click ui/confirm ui/deny ui/purchase star/pickup star/burst norma/level-up norma/level-max
     match/countdown-tick match/go combat/kill match/death match/win match/lose
     weapons/gun-shot weapons/sword-swing skills/wand-cast)

targets=("$@"); [[ ${#targets[@]} -gt 0 ]] || targets=("${ALL[@]}")
for name in "${targets[@]}"; do
    fn="${name//\//_}"; fn="${fn//-/_}"
    declare -F "$fn" >/dev/null || { echo "error: unknown sound '$name'" >&2; exit 2; }
    "$fn"
done
