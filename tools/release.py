#!/usr/bin/env python3
"""Build the APK with the GitHub CI, publish it and put it on the phone.

    python3 tools/release.py              # push, build, publish, copy, ping
    python3 tools/release.py --no-push    # build what is already pushed
    python3 tools/release.py --no-ping

Why GitHub and not Colab: a Colab session can be reclaimed in the middle
of a build (it just happened twice), GitHub always finishes the job.  The
CI signs the APK with the release key and publishes it to the 'latest'
release, so the link never changes.
"""

import argparse
import json
import os
import shutil
import subprocess
import time

REPO = "/workspace/ApkToolMD"
GH = "FilipinoCollabEmtech/ApkToolMD"
APK_LINK = "https://github.com/FilipinoCollabEmtech/ApkToolMD/releases/download/latest/app-debug.apk"
LOCAL = "/tmp/opencode/colab/out"


def run(cmd, timeout=300, cwd=None):
    return subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True,
                          text=True, timeout=timeout, cwd=cwd)


def step(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def head_sha():
    return run(["git", "rev-parse", "HEAD"], cwd=REPO).stdout.strip()


def push():
    step("pushing to main")
    r = run(["git", "push", "origin", "main"], timeout=900, cwd=REPO)
    if r.returncode != 0:
        print(r.stderr[-600:])
        raise SystemExit("the push failed")


def find_run(sha):
    """the CI run of this commit (it can take a minute to show up)"""
    for _ in range(90):
        r = run(["gh", "run", "list", "--limit", "12", "--json",
                 "databaseId,headSha,status,conclusion", "-R", GH], timeout=120)
        try:
            runs = json.loads(r.stdout)
        except Exception:
            runs = []
        for item in runs:
            if item.get("headSha", "").startswith(sha[:7]):
                return item["databaseId"]
        time.sleep(10)
    raise SystemExit("no CI run showed up for this commit")


def wait_run(run_id, timeout=1800):
    t0 = time.time()
    last = ""
    while time.time() - t0 < timeout:
        r = run(["gh", "run", "view", str(run_id), "--json", "status,conclusion,jobs",
                 "-R", GH], timeout=120)
        try:
            d = json.loads(r.stdout)
        except Exception:
            d = {}
        jobs = [j.get("name", "") for j in d.get("jobs", []) if j.get("status") != "completed"]
        state = "%s/%s" % (d.get("status", "?"), d.get("conclusion") or "running")
        if state != last:
            last = state
            step("CI %s%s" % (state, (" - " + ", ".join(jobs)) if jobs else ""))
        if d.get("status") == "completed":
            if d.get("conclusion") == "success":
                return
            log = run(["gh", "run", "view", str(run_id), "--log-failed", "-R", GH], timeout=300)
            print(log.stdout[-4000:])
            raise SystemExit("the CI build failed")
        time.sleep(20)
    raise SystemExit("the CI build timed out")


def download():
    os.makedirs(LOCAL, exist_ok=True)
    target = os.path.join(LOCAL, "app-debug.apk")
    r = run(["gh", "release", "download", "latest", "--pattern", "app-debug.apk",
             "--dir", LOCAL, "--clobber", "-R", GH], timeout=900)
    if not os.path.exists(target):
        print(r.stdout[-300:], r.stderr[-300:])
        raise SystemExit("the APK is not on the release")
    step("downloaded the APK (%d bytes)" % os.path.getsize(target))
    return target


def to_device(apk):
    for d in ["/sdcard/ApkToolMD", "/sdcard/Download"]:
        if not os.path.isdir(d):
            continue
        try:
            shutil.copyfile(apk, os.path.join(d, "app-debug.apk"))
            step("copied to %s/app-debug.apk" % d)
        except Exception as e:
            print("[device] %s" % e)


def ping(message):
    r = run(["python3", os.path.join(REPO, "ping.py"), message], timeout=180, cwd=REPO)
    step("ping sent")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-push", action="store_true")
    ap.add_argument("--no-ping", action="store_true")
    ap.add_argument("--no-copy", action="store_true")
    args = ap.parse_args()

    if not args.no_push:
        push()
    sha = head_sha()
    step("commit %s" % sha[:7])
    run_id = find_run(sha)
    step("CI run %s" % run_id)
    wait_run(run_id)
    step("the CI build is green")
    apk = download()
    if not args.no_copy:
        to_device(apk)
    if not args.no_ping:
        ping("ApkToolMD build OK - the burger menu with the engine profiles, the "
             "surgical APK patcher and the Cocos2d runtime dumper. APK: " + APK_LINK)
    print("\nAPK: " + APK_LINK, flush=True)


if __name__ == "__main__":
    main()
