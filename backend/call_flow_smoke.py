import sys, io
buf = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
sys.stdout = buf

import urllib.request, json, time

BASE = "http://127.0.0.1:8000"
EMAIL = f"flow{int(time.time())}@t.com"
PASS = "p123"

def req(method, path, body=None, token=None):
    url = f"{BASE}{path}"
    data = json.dumps(body).encode() if body else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    r = urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method))
    return json.loads(r.read())

# 1. Register + login
print("== Register ==")
req("POST", "/api/v1/auth/register", {"email": EMAIL, "password": PASS})
print("Register OK")
print("== Login ==")
tok = req("POST", "/api/v1/auth/token", {"email": EMAIL, "password": PASS})["access_token"]
print(f"Token: {tok[:20]}...")
print("== Start call ==")
cs = req("POST", "/api/v1/call/start", {"persona_id": "girlfriend"}, tok)
print(json.dumps(cs, indent=2))
call_id = cs["call_id"]
print(f"Call ID: {call_id}")
print("== End call ==")
ce = req("POST", "/api/v1/call/end", {"call_id": call_id}, tok)
print(json.dumps(ce, indent=2))
print("== History ==")
hist = req("GET", "/api/v1/call/history", token=tok)
print(json.dumps(hist, indent=2))
print("\n✅ FULL CALL FLOW PASSED")
