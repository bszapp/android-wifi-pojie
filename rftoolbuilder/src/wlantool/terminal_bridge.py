#!/usr/bin/env python3
"""Run Python tools requested over JSON Lines FIFOs and stream their output."""

import argparse
import io
import json
import os
import shlex
import subprocess
import sys
from pathlib import PurePosixPath


TOOL_ROOT = PurePosixPath("/wlantool")


def send_event(pipe, event):
    pipe.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
    pipe.flush()


def validate_script(value):
    script = PurePosixPath(value)
    if not script.is_absolute() or script.suffix != ".py":
        raise ValueError("script 必须是 /wlantool 下的绝对 .py 路径")
    try:
        script.relative_to(TOOL_ROOT)
    except ValueError as error:
        raise ValueError("script 必须位于 /wlantool") from error
    if ".." in script.parts:
        raise ValueError("script 路径不能包含 ..")
    if not os.path.isfile(str(script)):
        raise ValueError(f"script 不存在: {script}")
    return str(script)


def run_python(request, event_pipe):
    request_id = str(request.get("requestId", ""))
    if not request_id:
        raise ValueError("缺少 requestId")
    script = validate_script(str(request.get("script", "")))
    raw_args = request.get("args", [])
    if not isinstance(raw_args, list) or not all(isinstance(item, str) for item in raw_args):
        raise ValueError("args 必须是字符串数组")

    command = [sys.executable, "-u", script, *raw_args]
    display_command = shlex.join(command)
    print(f"$ {display_command}", flush=True)
    send_event(
        event_pipe,
        {
            "type": "started",
            "requestId": request_id,
            "command": command,
            "displayCommand": display_command,
        },
    )

    environment = os.environ.copy()
    environment["PYTHONUNBUFFERED"] = "1"
    process = subprocess.Popen(
        command,
        cwd=str(TOOL_ROOT),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env=environment,
        bufsize=0,
    )
    assert process.stdout is not None
    with io.TextIOWrapper(
        process.stdout,
        encoding="utf-8",
        errors="replace",
        newline="",
    ) as output:
        for line in output:
            sys.stdout.write(line)
            sys.stdout.flush()
            send_event(
                event_pipe,
                {
                    "type": "output",
                    "requestId": request_id,
                    "text": line,
                },
            )

    exit_code = process.wait()
    send_event(
        event_pipe,
        {
            "type": "finished",
            "requestId": request_id,
            "exitCode": exit_code,
        },
    )


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--command-pipe", required=True)
    parser.add_argument("--event-pipe", required=True)
    return parser.parse_args()


def main():
    args = parse_args()
    with open(args.event_pipe, "w", encoding="utf-8", buffering=1) as event_pipe:
        with open(args.command_pipe, "r", encoding="utf-8", buffering=1) as command_pipe:
            send_event(event_pipe, {"type": "ready", "protocol": 1})
            for raw_line in command_pipe:
                if not raw_line.strip():
                    continue
                request_id = ""
                try:
                    request = json.loads(raw_line)
                    if not isinstance(request, dict):
                        raise ValueError("请求必须是 JSON 对象")
                    request_id = str(request.get("requestId", ""))
                    request_type = request.get("type")
                    if request_type == "shutdown":
                        send_event(event_pipe, {"type": "stopped"})
                        return 0
                    if request_type != "run":
                        raise ValueError(f"不支持的请求类型: {request_type}")
                    run_python(request, event_pipe)
                except Exception as error:
                    message = f"{type(error).__name__}: {error}"
                    print(message, file=sys.stderr, flush=True)
                    send_event(
                        event_pipe,
                        {
                            "type": "error",
                            "requestId": request_id,
                            "message": message,
                        },
                    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
