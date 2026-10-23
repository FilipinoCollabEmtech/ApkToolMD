import urllib.request, sys
msg = " ".join(sys.argv[1:]) if len(sys.argv) > 1 else "Hi"
data = msg.encode()
req = urllib.request.Request("https://ntfy.sh/chat_completed", data=data, method="POST")
with urllib.request.urlopen(req) as r:
    print(r.read().decode())
