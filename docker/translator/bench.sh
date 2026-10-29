#!/bin/sh
# Usage: bench.sh [translategemma=http://host:18080] [hy-mt=http://host:18081] [lfm2=http://host:18082]
# No dependencies beyond Python 3; no model downloads or container mutations.
exec python3 - "$@" <<'PY'
import json
import math
import statistics
import sys
import time
import urllib.request

samples = [
    ('ja', 'こんにちは'), ('ja', '助けて！右から敵！'),
    ('ja', 'コア守って、あと10秒'), ('ja', '弾ない、ちょっと待って'),
    ('ja', 'GG！楽しかったｗ'), ('ja', 'ナイス！今のうますぎ'),
    ('ja', '星を拾ってから戻るね'), ('ja', '左の橋こわれた😭'),
    ('ja', '回復お願い🙏'), ('ja', 'ラグい？自分だけ？'),
    ('ja', 'ちょｗ落ちたｗｗ'), ('ja', '一旦下がろう'),
    ('ja', 'おつ！また明日'), ('ja', '武器どこで買うの？'),
    ('ja', '初見です、よろしく！'), ('ja', 'こんにちは みんな'),  # Japanize output: konnichiha minna
    ('ja', 'ありがとう'),  # arigatou after Japanize
    ('ja', 'りょうかい！'),  # ryoukai after Japanize
    ('ja', 'Steve、右の塔にいるよ https://example.com/map'),
    ('ja', 'え、まじ？😂'),
    ('en', 'gg wp'), ('en', 'help right side!!'),
    ('en', 'defend core, 10 sec left'), ('en', 'out of ammo brb'),
    ('en', 'lol that was close 😂'), ('en', 'nice clutch!'),
    ('en', 'pick up stars before we push'), ('en', 'bridge left is down'),
    ('en', 'heal pls 🙏'), ('en', 'lag or just me?'),
    ('en', 'oops fell into void lol'), ('en', 'fall back for now'),
    ('en', 'cya tomorrow'), ('en', 'where do i buy a gun?'),
    ('en', 'first game, hi everyone'), ('en', 'one hp!!'),
    ('en', 'dont rush solo'), ('en', 'ty for the carry'),
    ('en', 'Steve check https://example.com/map'), ('en', 'wait what 😂'),
]
assert len(samples) == 40
names = {'en': 'English', 'ja': 'Japanese'}
endpoints = sys.argv[1:] or ['translategemma=http://localhost:18080', 'hy-mt=http://localhost:18081', 'lfm2=http://localhost:18082']
failed = False
for endpoint in endpoints:
    profile, url = endpoint.split('=', 1)
    if profile not in ('translategemma', 'hy-mt', 'lfm2'):
        raise SystemExit('Unknown profile: ' + profile)
    latencies = []
    print('\nPROFILE', profile, url, flush=True)
    for source, text in samples:
        target = 'en' if source == 'ja' else 'ja'
        messages = []
        if profile == 'lfm2':
            messages.append({'role': 'system', 'content': f'Translate to {names[target]}.'})
            prompt = text
        elif profile == 'hy-mt':
            prompt = f'Translate the following segment into {names[target]}, without additional explanation.\n\n{text}'
        else:
            src, dst = names[source], names[target]
            prompt = (f'You are a professional {src} ({source}) to {dst} ({target}) translator. '
                      f'Your goal is to accurately convey the meaning and nuances of the original {src} text '
                      f'while adhering to {dst} grammar, vocabulary, and cultural sensitivities.\n'
                      f'Produce only the {dst} translation, without any additional explanations or commentary. '
                      f'Please translate the following {src} text into {dst}:\n\n\n{text}')
        messages.append({'role': 'user', 'content': prompt})
        request = urllib.request.Request(url.rstrip('/') + '/v1/chat/completions',
            data=json.dumps({'model': profile, 'messages': messages, 'temperature': 0,
                             'max_tokens': 512, 'stream': False}).encode(),
            headers={'Content-Type': 'application/json'})
        start = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                choice = json.load(response)['choices'][0]
            elapsed = (time.perf_counter() - start) * 1000
            latencies.append(elapsed)
            print(json.dumps({'from': source, 'to': target, 'input': text,
                              'output': choice['message']['content'], 'finish': choice.get('finish_reason'),
                              'ms': round(elapsed, 1)}, ensure_ascii=False), flush=True)
        except Exception as error:
            failed = True
            print('ERROR', profile, type(error).__name__, str(error), flush=True)
            break
    if latencies:
        ordered = sorted(latencies)
        print(f'SUMMARY {profile} n={len(latencies)} p50={statistics.median(latencies):.1f}ms '
              f'p95={ordered[math.ceil(len(ordered)*.95)-1]:.1f}ms max={max(latencies):.1f}ms '
              f'over_1500ms={sum(x > 1500 for x in latencies)}', flush=True)
sys.exit(1 if failed else 0)
PY
