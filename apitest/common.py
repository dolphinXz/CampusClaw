# -*- coding: utf-8 -*-
"""
apitest 公共助手：HTTP / SSE / 登录 token / MySQL 一致性校验。

连接参数取环境变量（带本地默认值），不存放任何密钥：
  APITEST_BASE_URL  默认 http://localhost:8080
  DB_HOST/DB_PORT/DB_USER/DB_PASSWORD/DB_NAME  默认 localhost:3306 / campusclaw / change-me / campusclaw
"""
import http.client
import json
import os
import time
import urllib.error
import urllib.request

import pymysql

BASE_URL = os.environ.get("APITEST_BASE_URL", "http://localhost:8080").rstrip("/")

DB_CONFIG = {
    "host": os.environ.get("DB_HOST", "localhost"),
    "port": int(os.environ.get("DB_PORT", "3306")),
    "user": os.environ.get("DB_USER", "campusclaw"),
    "password": os.environ.get("DB_PASSWORD", "change-me"),
    "database": os.environ.get("DB_NAME", "campusclaw"),
    "charset": "utf8mb4",
}

# 预置账号（与 SeedDataService 一致）
TEACHER_A = ("teacher-a", "Teacher@123")
TEACHER_B = ("teacher-b", "TeacherB@123")
STUDENT_A = ("student-a1", "Student@123")
STUDENT_B = ("student-b1", "StudentB@123")

_token_cache = {}


class CaseFailure(AssertionError):
    """用例断言失败。"""


def request(method, path, token=None, json_body=None, timeout=30):
    """发起 HTTP 请求，返回 (status, data)；data 为解析后的 JSON 或原始文本。"""
    url = BASE_URL + path
    headers = {"Accept": "application/json"}
    data = None
    if json_body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
    if token:
        headers["Authorization"] = "Bearer " + token

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            status = resp.status
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        status = e.code

    try:
        parsed = json.loads(body) if body else None
    except (ValueError, TypeError):
        parsed = body
    return status, parsed


def login(creds, use_cache=True):
    """登录并返回 token（同一进程内缓存）。"""
    key = creds[0]
    if use_cache and key in _token_cache:
        return _token_cache[key]
    status, data = request("POST", "/api/auth/login",
                           json_body={"username": creds[0], "password": creds[1]})
    if status != 200 or not isinstance(data, dict) or not data.get("token"):
        raise CaseFailure("登录失败：status=%s body=%s" % (status, data))
    token = data["token"]
    _token_cache[key] = token
    return token


def db_query(sql, args=None):
    """执行查询，返回 (columns, rows)。"""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            cursor.execute(sql, args)
            rows = cursor.fetchall()
            columns = [d[0] for d in cursor.description] if cursor.description else []
            return columns, rows
    finally:
        conn.close()


def db_execute(sql, args=None):
    """执行写语句，返回受影响行数。"""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            affected = cursor.execute(sql, args)
        conn.commit()
        return affected
    finally:
        conn.close()


def db_one(sql, args=None):
    """返回单行（dict）或 None。"""
    columns, rows = db_query(sql, args)
    if not rows:
        return None
    return dict(zip(columns, rows[0]))


def sse_request(path, token=None, json_body=None, timeout=180):
    """
    发起 SSE 请求，返回 (http_status, events)。
    events 为 [{"event": "delta", "data": {...}}, ...]。
    非 200 时 events 为空列表（响应体为普通 JSON 错误）。
    """
    url = BASE_URL + path
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(json_body or {}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")

    events = []
    status = None
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = resp.status
            if status != 200:
                body = resp.read().decode("utf-8", errors="replace")
                try:
                    return status, json.loads(body)
                except ValueError:
                    return status, body
            _parse_sse(resp, events)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, body
    return status, events


def _parse_sse(resp, events):
    """逐块读取响应流，解析 event/data 帧。
    urllib 在 chunked 流收尾时可能抛 IncompleteRead：把已读部分当最后一块处理。"""
    buffer = b""
    while True:
        try:
            chunk = resp.read(256)
        except http.client.IncompleteRead as e:
            chunk = e.partial
        if not chunk:
            break
        buffer += chunk
        while True:
            sep = _find_blank(buffer)
            if sep is None:
                break
            raw, buffer = buffer[:sep], buffer[sep + 2:].lstrip(b"\r\n")
            frame = raw.decode("utf-8", errors="replace")
            event, data_lines = "message", []
            for line in frame.splitlines():
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].strip())
            if not data_lines:
                continue
            try:
                payload = json.loads("\n".join(data_lines))
            except ValueError:
                payload = {"raw": "\n".join(data_lines)}
            events.append({"event": event, "data": payload})


def _find_blank(buf):
    for sep in (b"\n\n", b"\r\n\r\n"):
        idx = buf.find(sep)
        if idx != -1:
            return idx
    return None


def assert_eq(actual, expected, message=""):
    if actual != expected:
        raise CaseFailure("%s：期望 %r，实际 %r" % (message, expected, actual))


def assert_true(cond, message="断言失败"):
    if not cond:
        raise CaseFailure(message)


def wait_until(fn, timeout=15, interval=0.3):
    """轮询 fn 直到返回真值或超时。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = fn()
        if result:
            return result
        time.sleep(interval)
    return None
