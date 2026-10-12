import json, ssl, threading, subprocess, os, importlib
r=[0]
def w():
    for _ in range(100000): r[0]+=1
ts=[threading.Thread(target=w) for _ in range(8)]
for t in ts: t.start()
for t in ts: t.join()
assert r[0]==800000, r[0]
print("py threads ok, counter", r[0])
print("imports ok:", json.dumps({"ssl": ssl.OPENSSL_VERSION.split()[1] if len(ssl.OPENSSL_VERSION.split())>1 else "?"}))
out=subprocess.run(["/bin/echo","sp-ok"],capture_output=True,text=True).stdout.strip()
print("subprocess:", out)
