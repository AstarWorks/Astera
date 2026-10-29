#!/bin/sh
# Usage: bench.sh [hy-mt=http://host:18080] [translategemma=http://host:18081]
# Requires Python 3 + PyYAML. Uses the shipped chat config; no model/container mutations.
export ASTERA_CHAT_CONFIG=${ASTERA_CHAT_CONFIG:-$(dirname "$0")/../../content/chat/chat.yaml}
exec python3 - "$@" <<'PY'
import json
import os
import re
import yaml
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
samples = [(source, 'en' if source == 'ja' else 'ja', text, ()) for source, text in samples]
samples += [
    ('ja', 'en', 'おつ', ('gg', 'good game')), ('ja', 'en', '草', ('lol',)),
    ('ja', 'en', 'それな', ('same',)), ('ja', 'en', '鉄', ('iron',)),
    ('ja', 'en', 'ラグい', ('lag',)), ('ja', 'en', 'ｗｗ', ('lol',)),
    ('en', 'ja', 'gg', ('おつ',)), ('en', 'ja', 'gg wp', ('おつ',)),
    ('en', 'ja', 'brb', ('戻',)), ('en', 'ja', 'ty', ('ありがとう',)),
    ('en', 'ja', 'cya', ('また',)), ('en', 'ja', 'nice clutch', ('逆転',)),
    ('ja', 'en', '誰か鉄持ってる？', ('iron',)), ('ja', 'en', '今のラグい？', ('lag',)),
    ('ja', 'ko', '誰か鉄持ってる？', ('철',)), ('ja', 'es', '誰か鉄持ってる？', ('hierro',)),
    ('ko', 'en', '적이 오른쪽에 있어!', ('right',)), ('es', 'en', 'enemigo a la derecha!', ('right',)),
    ('ja', 'ko', '草', ('ㅋㅋ',)), ('ja', 'es', 'ラグい', ('lag',)),
]
names = {'en': 'English', 'ja': 'Japanese', 'ko': 'Korean', 'es': 'Spanish'}
with open(os.environ['ASTERA_CHAT_CONFIG']) as file:
    config = yaml.safe_load(file)['translation']
endpoints = sys.argv[1:] or ['hy-mt=http://localhost:18080', 'translategemma=http://localhost:18081']
failed = False
for endpoint in endpoints:
    profile, url = endpoint.split('=', 1)
    if profile not in ('translategemma', 'hy-mt'):
        raise SystemExit('Unknown profile: ' + profile)
    latencies = []
    model_latencies = []
    print('\nPROFILE', profile, url, flush=True)
    passed = checked = 0
    for source, target, text, expected in samples:
        glossary = config['glossary'].get(source + '-' + target, {})
        terms = sorted(glossary, key=len, reverse=True)
        pattern = re.compile(r'(?<![\w])(?:' + '|'.join(re.escape(term) for term in terms) + r')(?![\w])', re.I) if terms else None
        def substitute(match):
            return next(value for key, value in glossary.items() if key.lower() == match[0].lower())
        substituted = pattern.sub(substitute, text) if pattern else text
        remainder = pattern.sub('', text) if pattern else text
        deterministic = bool(pattern and pattern.search(text)) and not any(char.isalnum() for char in remainder)
        values = dict(source=names[source], target=names[target])
        values.update({'from': source, 'to': target, 'context': config['context'],
                       'examples': config['examples'].get(source + '-' + target, ''), 'text': substituted})
        prompt = config['prompts'][profile]
        for key, value in values.items():
            prompt = prompt.replace('{' + key + '}', value)
        messages = [{'role': 'user', 'content': prompt}]
        request = urllib.request.Request(url.rstrip('/') + '/v1/chat/completions',
            data=json.dumps({'model': profile, 'messages': messages, 'temperature': 0,
                             'max_tokens': 512, 'stream': False}).encode(),
            headers={'Content-Type': 'application/json'})
        start = time.perf_counter()
        try:
            if deterministic:
                output = substituted
                finish = 'glossary'
            else:
                with urllib.request.urlopen(request, timeout=120) as response:
                    choice = json.load(response)['choices'][0]
                output = choice['message']['content']
                finish = choice.get('finish_reason')
            elapsed = (time.perf_counter() - start) * 1000
            latencies.append(elapsed)
            if not deterministic: model_latencies.append(elapsed)
            status = '-'
            if expected:
                checked += 1
                ok = finish in ('stop', 'glossary') and any(word.casefold() in output.casefold() for word in expected)
                passed += ok
                failed |= not ok
                status = 'PASS' if ok else 'FAIL'
            print(json.dumps({'from': source, 'to': target, 'input': text,
                              'output': output, 'finish': finish, 'check': status,
                              'expected_any': expected, 'ms': round(elapsed, 1)}, ensure_ascii=False), flush=True)
        except Exception as error:
            failed = True
            print('ERROR', profile, type(error).__name__, str(error), flush=True)
            continue
    if latencies:
        ordered = sorted(latencies)
        if model_latencies:
            model_ordered = sorted(model_latencies)
            print(f'INFERENCE {profile} n={len(model_latencies)} p50={statistics.median(model_latencies):.1f}ms '
                  f'p95={model_ordered[math.ceil(len(model_ordered)*.95)-1]:.1f}ms', flush=True)
        print(f'SUMMARY {profile} meaning={passed}/{checked} n={len(latencies)} p50={statistics.median(latencies):.1f}ms '
              f'p95={ordered[math.ceil(len(ordered)*.95)-1]:.1f}ms max={max(latencies):.1f}ms '
              f'over_1500ms={sum(x > 1500 for x in latencies)}', flush=True)
sys.exit(1 if failed else 0)
PY
