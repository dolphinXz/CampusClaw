# -*- coding: utf-8 -*-
"""
apitest 一键执行：发现 cases/ 下全部接口用例，运行并生成 HTML 报告。

用法：
    cd apitest
    python run_all.py            # 或 py run_all.py

用例组织：cases/api/<URL路径>/<HTTP方法>.py，文件内定义 CASES = [(名称, 函数), ...]。
报告输出到 reports/report.html（运行产物，已加入 .gitignore）。
退出码：全部通过为 0，有失败为 1。
"""
import html
import importlib.util
import inspect
import os
import sys
import time
import traceback
from datetime import datetime

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)

import common  # noqa: E402

CASES_DIR = os.path.join(BASE_DIR, "cases")
REPORTS_DIR = os.path.join(BASE_DIR, "reports")
HTTP_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"}


def discover():
    """按 URL 路径组织的用例文件：文件名（去 .py）为 HTTP 方法。"""
    found = []
    for root, _dirs, files in os.walk(CASES_DIR):
        for name in files:
            stem, ext = os.path.splitext(name)
            if ext != ".py" or stem.upper() not in HTTP_METHODS:
                continue
            rel = os.path.relpath(os.path.join(root, name), CASES_DIR)
            found.append(os.path.join(root, name))
    return sorted(found)


def load_module(path):
    mod_name = "apicase_" + str(abs(hash(path)))
    spec = importlib.util.spec_from_file_location(mod_name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_file(path, ctx):
    """运行单个用例文件，返回 [(名称, 通过?, 详情, 耗时s)]。"""
    rel = os.path.relpath(path, CASES_DIR).replace(os.sep, "/")
    results = []
    try:
        module = load_module(path)
        cases = getattr(module, "CASES", None)
        if not cases:
            results.append((rel + " :: (空)", False, "文件未定义 CASES", 0.0))
            return results
        for name, fn in cases:
            label = "%s :: %s" % (rel, name)
            start = time.time()
            try:
                fn(ctx)
                results.append((label, True, "", time.time() - start))
            except Exception as e:  # noqa: BLE001 单条失败不阻断其余
                detail = str(e)
                if not isinstance(e, common.CaseFailure):
                    detail += "\n" + traceback.format_exc(limit=3)
                results.append((label, False, detail, time.time() - start))
    except Exception as e:  # noqa: BLE001 文件级错误（导入失败等）
        results.append((rel + " :: (导入)", False,
                        "%s\n%s" % (e, traceback.format_exc(limit=3)), 0.0))
    return results


def write_report(results, elapsed):
    os.makedirs(REPORTS_DIR, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    path = os.path.join(REPORTS_DIR, "report.html")

    passed = sum(1 for r in results if r[1])
    failed = len(results) - passed
    rows = []
    for label, ok, detail, cost in results:
        badge = '<span class="ok">PASS</span>' if ok else '<span class="bad">FAIL</span>'
        detail_html = ("<pre>%s</pre>" % html.escape(detail)) if detail else ""
        rows.append(
            "<tr><td>%s</td><td>%s</td><td>%.2fs</td><td class='detail'>%s</td></tr>"
            % (badge, html.escape(label), cost, detail_html)
        )

    page = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>CampusClaw 接口测试报告 %(stamp)s</title>
<style>
body{font-family:system-ui,sans-serif;margin:24px;background:#faf7f2;color:#222}
h1{font-size:20px}.meta{color:#666;margin-bottom:16px}
table{border-collapse:collapse;width:100%%}
th,td{border:1px solid #e2dcd2;padding:8px 10px;text-align:left;font-size:13px;vertical-align:top}
th{background:#f1ebe2}
.ok{color:#1a7f4b;font-weight:600}.bad{color:#b3261e;font-weight:600}
.detail pre{white-space:pre-wrap;margin:0;font-size:12px;color:#8a2f2f}
.summary{font-size:15px;margin:8px 0 16px}
</style></head><body>
<h1>CampusClaw 接口测试报告</h1>
<div class="meta">生成时间 %(stamp)s · 基地址 %(base)s · 总耗时 %(elapsed).1fs</div>
<div class="summary">%(passed)d 通过 / %(failed)d 失败 / %(total)d 总计</div>
<table><tr><th>结果</th><th>用例</th><th>耗时</th><th>失败详情</th></tr>
%(rows)s
</table></body></html>
""" % {
        "stamp": stamp,
        "base": html.escape(common.BASE_URL),
        "elapsed": elapsed,
        "passed": passed,
        "failed": failed,
        "total": len(results),
        "rows": "\n".join(rows),
    }
    with open(path, "w", encoding="utf-8") as f:
        f.write(page)
    return path, passed, failed


def main():
    files = discover()
    if not files:
        print("未发现用例文件（cases/）")
        return 1

    print("发现 %d 个用例文件，开始执行（基地址 %s）…" % (len(files), common.BASE_URL))
    ctx = {}
    results = []
    start = time.time()
    for path in files:
        results.extend(run_file(path, ctx))
    elapsed = time.time() - start

    report_path, passed, failed = write_report(results, elapsed)

    for label, ok, detail, cost in results:
        mark = "PASS" if ok else "FAIL"
        line = "%-4s %.2fs  %s" % (mark, cost, label)
        print(line)
        if detail:
            for dl in detail.splitlines():
                print("       " + dl)

    print("\n%d/%d passed · 报告：%s" % (passed, len(results), report_path))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
