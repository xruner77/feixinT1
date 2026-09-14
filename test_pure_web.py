import urllib.request
import time
import json

base = "http://192.168.123.98:8989"

def call(url):
    t0 = time.time()
    with urllib.request.urlopen(base + url, timeout=5) as r:
        txt = r.read().decode('utf-8')
        dt = int((time.time() - t0) * 1000)
        print(f"[{dt}ms] {url} -> {txt}")
        return json.loads(txt)

print("--- Testing rapid web API calls ---")
call("/api/status")
call("/api/pq?type=brightness&val=10")
call("/api/pq?type=contrast&val=15")
call("/api/pq?type=saturation&val=20")
call("/api/pq?type=dnlp&val=1")
call("/api/status")
call("/api/pq?type=reset&item=brightness")
call("/api/pq?type=reset&item=contrast")
call("/api/pq?type=reset&item=saturation")
call("/api/pq?type=dnlp&val=0")
call("/api/status")
