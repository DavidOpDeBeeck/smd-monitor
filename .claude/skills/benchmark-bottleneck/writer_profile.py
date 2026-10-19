# Where one thread of a flight recording spends its samples: by method on the stack (inclusive) and by the method running (self).
# Given two recordings, compares them method by method. Streams `jfr print`, so a recording of minutes needs little memory.
#
#   py -I writer_profile.py <recording.jfr> [<after.jfr>] [--thread smd-monitor-hub] [--package app.dodb.smd.monitor] [--skip 0] [--top 20]
#
# jfr scrub cannot select execution samples by thread, as they belong to the sampler thread, so the samples are filtered here.

import argparse
import os
import re
import shutil
import subprocess
import sys
from collections import Counter
from datetime import datetime

FRAME = re.compile(r"^\s+(?P<method>[^\s(]+)\(.*\)(?: line: \d+)?$")
THREAD = re.compile(r'^\s+sampledThread = "(?P<name>[^"]*)"')
START = re.compile(r"^\s+startTime = (?P<time>\d\d:\d\d:\d\d\.\d+) \((?P<date>\d{4}-\d\d-\d\d)\)")


class Profile:
    def __init__(self, path):
        self.path = path
        self.samples = 0
        self.truncated = 0
        self.inclusive = Counter()
        self.self_time = Counter()
        self.packaged = set()
        self.first = None
        self.last = None

    def seconds(self):
        return (self.last - self.first).total_seconds() if self.first and self.last else 0.0

    def share(self, counter, method):
        return counter[method] / self.samples if self.samples else 0.0


def jfr_command():
    found = shutil.which("jfr")
    if found:
        return found
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", "jfr.exe" if os.name == "nt" else "jfr")
        if os.path.exists(candidate):
            return candidate
    sys.exit("writer_profile needs the jfr tool of a JDK on the PATH or in JAVA_HOME/bin; found neither")


def short(method):
    parts = method.split(".")
    return ".".join(parts[-2:]) if len(parts) >= 2 else method


def read(path, thread, package, skip, depth):
    profile = Profile(path)
    command = [jfr_command(), "print", "--events", "jdk.ExecutionSample", "--stack-depth", str(depth), path]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
    # The samples of all threads, kept in order, as skipping counts from the first sample of any thread.
    recording_start = None
    name, started, frames, in_stack, truncated = None, None, [], False, False
    for line in process.stdout:
        if line.startswith("jdk.ExecutionSample {"):
            name, started, frames, in_stack, truncated = None, None, [], False, False
        elif in_stack:
            stripped = line.strip()
            if stripped == "]":
                in_stack = False
            elif stripped == "...":
                truncated = True
            else:
                match = FRAME.match(line)
                if match:
                    frames.append(match.group("method"))
        elif line.startswith("  stackTrace = ["):
            in_stack = True
        elif line.startswith("}"):
            if started and recording_start is None:
                recording_start = started
            if name == thread and frames and started and (started - recording_start).total_seconds() >= skip:
                profile.samples += 1
                profile.truncated += truncated
                profile.self_time[short(frames[0])] += 1
                for method in {short(frame) for frame in frames}:
                    profile.inclusive[method] += 1
                profile.packaged.update(short(frame) for frame in frames if frame.startswith(package))
                profile.first = profile.first or started
                profile.last = started
        else:
            match = THREAD.match(line)
            if match:
                name = match.group("name")
                continue
            match = START.match(line)
            if match:
                started = datetime.fromisoformat(match.group("date") + "T" + match.group("time"))
    errors = process.stderr.read()
    if process.wait() != 0:
        sys.exit("jfr print failed on " + path + ": " + errors.strip())
    if profile.samples == 0:
        sys.exit("No execution samples of thread '" + thread + "' in " + path + (" after skipping " + str(skip) + " s" if skip else ""))
    return profile


def describe(profile):
    rate = profile.samples / profile.seconds() if profile.seconds() else 0.0
    line = "%s: %d samples of the thread over %.0f s (%.1f a second)" % (profile.path, profile.samples, profile.seconds(), rate)
    if profile.truncated / profile.samples > 0.05:
        line += "; %.0f%% of the stacks were cut off, so raise --depth" % (100 * profile.truncated / profile.samples)
    return line


def report(profile, package, top):
    print(describe(profile))
    print()
    print("On the stack, methods of " + package + " (the share of samples that include them):")
    shown = [(method, count) for method, count in profile.inclusive.most_common() if method_in(profile, method, package)][:top]
    for method, count in shown:
        print("  %5.1f%%  %7d  %s" % (100 * count / profile.samples, count, method))
    print()
    print("Running, any method (the share of samples it was the top frame of):")
    for method, count in profile.self_time.most_common(top):
        print("  %5.1f%%  %7d  %s" % (100 * count / profile.samples, count, method))


def compare(before, after, package, top):
    print("Before " + describe(before))
    print("After  " + describe(after))
    print()
    print("On the stack, methods of " + package + ": share of the thread's samples before, after, and the change in points:")
    methods = [method for method, _ in before.inclusive.most_common() if method_in(before, method, package)][:top]
    methods += [method for method, _ in after.inclusive.most_common() if method_in(after, method, package) and method not in methods][:top // 2]
    for method in methods:
        was, now = before.share(before.inclusive, method), after.share(after.inclusive, method)
        print("  %5.1f%%  %5.1f%%  %+6.1f  %s" % (100 * was, 100 * now, 100 * (now - was), method))
    print()
    print("Running, any method: share before, after, and the change in points:")
    methods = [method for method, _ in before.self_time.most_common(top)]
    methods += [method for method, _ in after.self_time.most_common(top) if method not in methods][:top // 2]
    for method in methods:
        was, now = before.share(before.self_time, method), after.share(after.self_time, method)
        print("  %5.1f%%  %5.1f%%  %+6.1f  %s" % (100 * was, 100 * now, 100 * (now - was), method))


# The short names lose the package, so a method counts as the package's when a full frame of it was seen with that prefix.
def method_in(profile, method, package):
    return method in profile.packaged


def main():
    parser = argparse.ArgumentParser(description="Where a thread of a flight recording spends its samples")
    parser.add_argument("recording")
    parser.add_argument("after", nargs="?")
    parser.add_argument("--thread", default="smd-monitor-hub", help="the thread's name (default: the hub's writer thread)")
    parser.add_argument("--package", default="app.dodb.smd.monitor", help="the package whose methods the inclusive table lists")
    parser.add_argument("--skip", type=float, default=0, help="seconds from the recording's first sample to leave out, such as the fill")
    parser.add_argument("--top", type=int, default=20)
    parser.add_argument("--depth", type=int, default=128, help="frames read of each stack")
    arguments = parser.parse_args()
    profiles = [read(path, arguments.thread, arguments.package, arguments.skip, arguments.depth) for path in [arguments.recording, arguments.after] if path]
    if len(profiles) == 1:
        report(profiles[0], arguments.package, arguments.top)
    else:
        compare(profiles[0], profiles[1], arguments.package, arguments.top)


if __name__ == "__main__":
    main()
