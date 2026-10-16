# -*- coding: utf-8 -*-
"""POST /api/auth/login：正确凭据签发 token；错误凭据 401；后续请求按约定携带 Bearer。"""
import common


def _good_login(ctx):
    status, data = common.request(
        "POST", "/api/auth/login",
        json_body={"username": common.TEACHER_A[0], "password": common.TEACHER_A[1]})
    common.assert_eq(status, 200, "登录状态码")
    common.assert_true(isinstance(data, dict) and data.get("token"), "响应应含 token")
    common.assert_true(data.get("user", {}).get("username") == common.TEACHER_A[0], "响应含用户名")


def _bad_password_rejected(ctx):
    status, data = common.request(
        "POST", "/api/auth/login",
        json_body={"username": common.TEACHER_A[0], "password": "wrong-password"})
    common.assert_eq(status, 401, "错误密码应 401")
    common.assert_true(isinstance(data, dict) and not data.get("token"), "不应签发 token")


def _bearer_works_on_me(ctx):
    token = common.login(common.STUDENT_A)
    status, data = common.request("GET", "/api/auth/me", token=token)
    common.assert_eq(status, 200, "/me 状态码")
    common.assert_eq(data.get("username"), common.STUDENT_A[0], "/me 用户名")
    common.assert_eq(data.get("role"), "STUDENT", "/me 角色")


def _me_without_token_401(ctx):
    status, data = common.request("GET", "/api/auth/me")
    common.assert_eq(status, 401, "无 token 访问 /me 应 401")


CASES = [
    ("正确凭据登录并获取令牌", _good_login),
    ("错误凭据被拒绝", _bad_password_rejected),
    ("Bearer 令牌可访问受保护接口", _bearer_works_on_me),
    ("缺少令牌返回 401", _me_without_token_401),
]
