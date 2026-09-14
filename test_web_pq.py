import urllib.request
import json

base_url = "http://192.168.123.98:8989"

def req(path):
    url = base_url + path
    try:
        with urllib.request.urlopen(url, timeout=3) as resp:
            content = resp.read().decode('utf-8')
            return resp.status, content
    except Exception as e:
        return 500, str(e)

print("1. Testing /api/status...")
st, txt = req("/api/status")
print(f"Status: {st}, Body: {txt}")

print("\n2. Testing /api/pq?type=brightness&val=12...")
st, txt = req("/api/pq?type=brightness&val=12")
print(f"Brightness set: {st}, Body: {txt}")

print("\n3. Testing /api/pq?type=contrast&val=8...")
st, txt = req("/api/pq?type=contrast&val=8")
print(f"Contrast set: {st}, Body: {txt}")

print("\n4. Testing /api/pq?type=saturation&val=15...")
st, txt = req("/api/pq?type=saturation&val=15")
print(f"Saturation set: {st}, Body: {txt}")

print("\n5. Checking /api/status after changes...")
st, txt = req("/api/status")
print(f"Status: {st}, Body: {txt}")

print("\n6. Resetting each one individually...")
print("Reset brightness:", req("/api/pq?type=reset&item=brightness"))
print("Reset contrast:", req("/api/pq?type=reset&item=contrast"))
print("Reset saturation:", req("/api/pq?type=reset&item=saturation"))

print("\n7. Checking /api/status after resets...")
st, txt = req("/api/status")
print(f"Final status: {st}, Body: {txt}")

print("\n8. Checking root HTML...")
st, html = req("/")
print(f"HTML len: {len(html)}, has range-brightness: {'range-brightness' in html}, has btn-rst: {'btn-rst' in html}")
