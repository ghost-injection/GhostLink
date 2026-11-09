"""DedSec Life palette and locally persisted appearance preferences."""
import colorsys
import json
import os
from pathlib import Path

NAMES = ('NEON', 'CATHODE', 'HACKER GREEN', 'SPECTRUM RGB', 'TESLA / ELECTRIC BLUE')
PATH = Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'GhostLink' / 'appearance.json'

def read():
    try:
        value = json.loads(PATH.read_text())
        return int(value.get('theme', 2)) % len(NAMES), bool(value.get('effects', True))
    except (OSError, ValueError, TypeError):
        return 2, True

def save(theme, effects):
    PATH.parent.mkdir(parents=True, exist_ok=True)
    tmp = PATH.with_suffix('.tmp')
    tmp.write_text(json.dumps({'theme': theme, 'effects': effects}))
    tmp.replace(PATH)

def color(theme, seconds=0, saturation=.85, value=1):
    hue = (166, 35, 118, (seconds % 12) * 30, 192)[theme]
    rgb = colorsys.hsv_to_rgb(hue / 360, saturation, value)
    return '#' + ''.join(f'{round(v*255):02x}' for v in rgb)
