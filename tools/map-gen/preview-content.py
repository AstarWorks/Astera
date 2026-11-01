#!/usr/bin/env python3
"""Preview the shipped content directly; generator coordinates are integrated."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
subprocess.run(['./gradlew', '-q', ':tools:arena-preview:run'], cwd=ROOT, check=True)
