import sys, io
buf = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
sys.stdout = buf

import urllib.request, json, time
