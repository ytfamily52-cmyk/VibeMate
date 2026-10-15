"""Check the call records created by the app on the phone for calltest@test.com."""
import json
import urllib.request

BASE = "http://127.0.0.1:8000/api/v1"


def post(path, body):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode())


def get(path, token):
    req = urllib.request.Request(
        BASE + path,
        headers={"Authorization": "Bearer " + token},
        method="GET",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode())


token = post("/auth/login", {"email": "calltest@test.com", "password": "password123"})["access_token"]
uid = post("/auth/login", {"email": "calltest@test.com", "password": "password123"})["user"]["id"]

sessions = get("/chat/sessions/", token)
print("=== Chat sessions for calltest@test.com ===")
for s in sessions:
    print(" ", s["id"], "|", s["title"], "| persona:", s["persona"])

calls = get("/call/history", token)
print("\n=== Call records (from the phone's tap) ===")
for c in calls:
    print(" ", c)

print("\nuser id:", uid)