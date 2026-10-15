"""Quick end-to-end smoke test for the call feature against a live backend."""
import json
import time
import urllib.request

BASE = "http://127.0.0.1:8000/api/v1"
EMAIL = "calltest-%d@test.com" % int(time.time())


def post(path, body=None, token=""):
    data = json.dumps(body).encode() if body is not None else b""
    req = urllib.request.Request(
        BASE + path,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + token,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def get(path, token=""):
    req = urllib.request.Request(
        BASE + path,
        headers={"Authorization": "Bearer " + token},
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def main() -> None:
    print("=== 1. Register user ===")
    status, body = post(
        "/auth/register",
        {"email": EMAIL, "password": "password123", "display_name": "Call Tester"},
    )
    print(status, body.get("user", body) if isinstance(body, dict) else body)
    token = body.get("access_token", "") if isinstance(body, dict) else ""

    print("\n=== 2. Call /start without auth (expect 401) ===")
    status, body = post("/call/start", {"persona": "girlfriend"})
    print(status, body)

    print("\n=== 3. Call /history without auth (expect 401) ===")
    status, body = get("/call/history")
    print(status, body)

    print("\n=== 4. Create chat session ===")
    status, body = post("/chat/sessions/", {"title": "Call test", "persona": "girlfriend"}, token)
    print(status, body.get("id", body) if isinstance(body, dict) else body)
    session_id = body.get("id", "") if isinstance(body, dict) else ""

    print("\n=== 5. Start call (expect 201 connected) ===")
    status, body = post("/call/start", {"session_id": session_id, "persona": "girlfriend"}, token)
    print(status, body)

    print("\n=== 6. Start call without session (expect 201) ===")
    status, body = post("/call/start", {"persona": "boyfriend"}, token)
    print(status, body)

    print("\n=== 7. End call (expect 200 ended) ===")
    status, body = post("/call/end", {}, token)
    print(status, body)

    print("\n=== 8. Call history (expect ended + connected records) ===")
    status, body = get("/call/history", token)
    print(status, body)

    print("\n=== 9. Send a chat message (sanity: chat still works) ===")
    status, body = post(
        "/chat/sessions/" + session_id + "/messages",
        {"content": "Hello VibeMate"},
        token,
    )
    print(status, body.get("assistant_message", {}).get("content", body) if isinstance(body, dict) else body)

    print("\nALL CHECKS DONE")


if __name__ == "__main__":
    main()
