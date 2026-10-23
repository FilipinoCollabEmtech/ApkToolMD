import subprocess, sys, time

run_id = sys.argv[1] if len(sys.argv) > 1 else None
if not run_id:
    out = subprocess.check_output(["gh", "run", "list", "--limit", "1", "--json", "databaseId"], text=True)
    import json
    run_id = json.loads(out)[0]["databaseId"]
    print("latest run:", run_id)

deadline = time.time() + 300
while time.time() < deadline:
    out = subprocess.check_output(["gh", "run", "view", str(run_id), "--json", "status,conclusion,name"], text=True)
    import json
    d = json.loads(out)
    print(f"{time.strftime('%H:%M:%S')}  {d['status']}  {d.get('conclusion')}  {d['name']}", flush=True)
    if d["status"] == "completed":
        sys.exit(0 if d.get("conclusion") == "success" else 1)
    time.sleep(15)
print("timeout")
sys.exit(2)
