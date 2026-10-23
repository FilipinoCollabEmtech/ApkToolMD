#!/usr/bin/env python3
"""
Relay for the colab CLI: the CLI reads the OAuth authorisation code from
stdin, so we spawn it with a pipe and feed it whatever arrives on the FIFO
(and flush it - `tail -f` block buffers and never gets the code through).
"""
import os
import subprocess
import sys
import time

FIFO = "/tmp/colab_stdin"
LOG = "/tmp/colab_new.log"
COLAB = "/workspace/colabctl-env/bin/colab"


def main():
    if not os.path.exists(FIFO):
        os.mkfifo(FIFO)
    log = open(LOG, "w")
    p = subprocess.Popen([COLAB, "new"], stdin=subprocess.PIPE,
                         stdout=log, stderr=subprocess.STDOUT,
                         cwd="/workspace", bufsize=0)
    with open(FIFO, "r") as f:
        while True:
            line = f.readline()
            if not line:
                if p.poll() is not None:
                    break
                time.sleep(0.1)
                continue
            line = line.strip()
            if not line:
                continue
            sys.stderr.write("relaying: %s\n" % line[:40])
            p.stdin.write((line + "\n").encode())
            p.stdin.flush()
    p.wait()


if __name__ == "__main__":
    main()
