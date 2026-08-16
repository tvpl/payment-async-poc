#!/usr/bin/env python3
"""Mint an HS256 JWT for scraping payment-sbus's /prometheus endpoint during the T57 capacity
gate. payment-sbus has no dev token issuer (unlike payment-api's /auth/token) — it validates any
HS256 token signed with SBUS_DEV_JWT_SECRET (payment-sbus/src/main/resources/application-dev.yml),
so this mints one directly with stdlib only (no dependency on a JWT library).

Usage: mint_jwt.py <secret>
"""
import base64
import hashlib
import hmac
import json
import sys
import time


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def mint(secret: str) -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    now = int(time.time())
    payload = {"sub": "capacity-gate", "roles": [], "groups": [], "iat": now, "exp": now + 3600}
    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(payload).encode())}"
    signature = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return f"{signing_input}.{b64url(signature)}"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: mint_jwt.py <secret>", file=sys.stderr)
        sys.exit(1)
    print(mint(sys.argv[1]))
