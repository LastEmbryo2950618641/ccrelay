#!/usr/bin/env python3
"""ccrelay-cli: CC Relay relay command wrapper."""

from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import hmac
import json
import os
import re
import secrets
import signal
import shutil
import subprocess
import sys
import tarfile
import time
import uuid
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

import ccrelay_ssh
import ccrelay_identity
import ccrelay_center
import ccrelay_bootstrap
import ccrelay_skill


DEFAULT_CENTER_URL = "http://127.0.0.1:18191"
CENTER_STATE_FILE = "center-runtime.json"
CENTER_LOG_FILE = "center-runtime.log"
CENTER_SECRET_FILE = "center-hmac.secret"
DEFAULT_A2A_CAPABILITIES = [
    "A2A_MESSAGE_SEND",
    "A2A_TASK_CREATE",
    "A2A_TASK_GET",
    "A2A_TASK_CANCEL",
]


class CliError(Exception):
    pass


def configure_stdio() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def main(argv: Optional[List[str]] = None) -> int:
    configure_stdio()
    parser = build_parser()
    args = parser.parse_args(argv)
    persisted_center = ccrelay_center.persisted_center_url()
    args.center = args.center or os.getenv("CCRELAY_CENTER_URL") or persisted_center or DEFAULT_CENTER_URL
    args.center_configured_by = "CLI" if "--center" in (argv or sys.argv[1:]) else (
        "CCRELAY_CENTER_URL" if os.getenv("CCRELAY_CENTER_URL") else "PERSISTED" if persisted_center else "DEFAULT"
    )
    try:
        result = args.func(args)
        if result is not None:
            write_output(result, raw=args.raw)
        return 0
    except CliError as exc:
        print(f"ccrelay-cli: {exc}", file=sys.stderr)
        return 2
    except ccrelay_ssh.SshCredentialInputRequired as exc:
        write_output(exc.interaction)
        return 2
    except ccrelay_ssh.SshCredentialError as exc:
        print(f"ccrelay-cli: {exc}", file=sys.stderr)
        return 2
    except ccrelay_skill.SkillError as exc:
        print(f"ccrelay-cli: {exc}", file=sys.stderr)
        return 2
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        print(f"ccrelay-cli: HTTP {exc.code} {exc.reason}: {body}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"ccrelay-cli: request failed: {exc.reason}", file=sys.stderr)
        return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ccrelay-cli",
        description="封装 CC Relay / Relay 能力的命令行工具。",
    )
    parser.add_argument(
        "--center",
        default=None,
        help="中心运行时地址，默认读取 CCRELAY_CENTER_URL 或使用内置本地默认值。",
    )
    parser.add_argument("--timeout", type=float, default=60.0, help="HTTP 超时时间，单位秒。")
    parser.add_argument("--raw", action="store_true", help="原样输出响应文本，不格式化 JSON。")
    subparsers = parser.add_subparsers(dest="command", required=True)

    add_health(subparsers)
    add_bootstrap(subparsers)
    add_center(subparsers)
    add_session(subparsers)
    add_relay(subparsers)
    add_access(subparsers)
    add_agent(subparsers)
    add_config(subparsers)
    ccrelay_skill.add_skill(subparsers)
    add_ssh(subparsers)
    add_observation(subparsers)
    add_deploy(subparsers)
    add_task(subparsers)
    add_a2a(subparsers)
    add_remote(subparsers)
    return parser


def add_bootstrap(subparsers: argparse._SubParsersAction) -> None:
    bootstrap = subparsers.add_parser("bootstrap", help="读取和推进本地三态引导阶段")
    commands = bootstrap.add_subparsers(dest="bootstrap_command", required=True)

    status = commands.add_parser("status", help="查看 UNINITIALIZED/SSH_READY/DEPLOYED 阶段")
    status.add_argument("--cluster-id", default="default")
    status.set_defaults(func=lambda args: ccrelay_bootstrap.get_stage(args.cluster_id))

    next_step = commands.add_parser("next", help="根据本地阶段进入唯一的下一步")
    add_identity_target_options(next_step, require_center=False)
    next_step.add_argument(
        "--execution-mode",
        help="用户确认的引导模式: AUTO_EXECUTE_REMAINING 或 INSPECT_STEP_BY_STEP",
    )
    next_step.set_defaults(func=bootstrap_next)

    deployed = commands.add_parser("mark-deployed", help="完整部署验收后标记为 DEPLOYED")
    deployed.add_argument("--cluster-id", default="default")
    deployed.set_defaults(func=bootstrap_mark_deployed)

    reset = commands.add_parser("reset", help="删除阶段记录并回到 UNINITIALIZED")
    reset.add_argument("--cluster-id", default="default")
    reset.set_defaults(func=lambda args: ccrelay_bootstrap.reset(args.cluster_id))


def add_health(subparsers: argparse._SubParsersAction) -> None:
    parser = subparsers.add_parser("health", help="检查本地运行时健康状态")
    parser.set_defaults(func=lambda args: request_json(args, "GET", "/api/skill/health"))


def add_center(subparsers: argparse._SubParsersAction) -> None:
    center = subparsers.add_parser("center", help="中心发现与状态检查")
    commands = center.add_subparsers(dest="center_command", required=True)

    resolve = commands.add_parser("resolve", help="识别当前使用的 CC 中心")
    resolve.set_defaults(func=center_resolve)

    ensure = commands.add_parser("ensure", help="确保本地非 AI CC center 已启动")
    ensure.set_defaults(func=center_ensure)

    status = commands.add_parser("status", help="查看本地 CC center 进程状态")
    status.set_defaults(func=center_status)

    stop = commands.add_parser("stop", help="停止由 Skill 管理的本地 CC center")
    stop.set_defaults(func=center_stop)

    remote_stop = commands.add_parser("remote-stop", help="停止并清除 Skill 管理的已配置远端中心")
    remote_stop.add_argument("--timeout", type=int, default=20)
    remote_stop.set_defaults(func=lambda args: ccrelay_center.stop_persisted_center(args.timeout))

    remote_cleanup = commands.add_parser("remote-cleanup", help="停止并删除远端产品运行目录，保留集群身份与 SSH 互信")
    remote_cleanup.add_argument("--timeout", type=int, default=30)
    remote_cleanup.set_defaults(func=lambda args: ccrelay_center.cleanup_persisted_cluster(args.timeout))

    sync_artifacts = commands.add_parser("sync-artifacts", help="同步远端中心后续 relay 部署所需制品")
    sync_artifacts.add_argument("--timeout", type=int, default=180)
    sync_artifacts.set_defaults(func=lambda args: ccrelay_center.sync_persisted_relay_artifacts(args.timeout))

    logs = commands.add_parser("logs", help="读取已配置远端中心的诊断与日志摘要")
    logs.add_argument("--timeout", type=int, default=20)
    logs.add_argument("--lines", type=int, default=80)
    logs.add_argument("--source", choices=["AUTO", "ACTIVE", "ATTEMPT"], default="AUTO")
    logs.set_defaults(func=lambda args: ccrelay_center.diagnose_persisted_center(
        args.timeout, args.lines, args.source,
    ))

    cleanup_attempt = commands.add_parser("cleanup-attempt", help="清理最近一次未完成自举的进程和状态")
    cleanup_attempt.add_argument("--timeout", type=int, default=20)
    cleanup_attempt.set_defaults(func=lambda args: ccrelay_center.cleanup_bootstrap_attempt(args.timeout))

    for command_name, help_text, handler in [
        ("plan", "探测候选节点并规划远端中心", center_plan),
        ("bootstrap", "按规划自动部署并切换远端中心", center_bootstrap),
    ]:
        command = commands.add_parser(command_name, help=help_text)
        command.add_argument("--node", "--nodes", action="append", dest="nodes", default=[],
                             help="中心候选 SSH 节点，格式 host:port[=username]；可重复或使用逗号分隔")
        add_ssh_concurrency_option(command)
        command.add_argument("--selection", choices=["AUTO", "MANUAL"], default="AUTO")
        command.add_argument("--exclude", action="append", default=[], help="自动选择时排除 host 或 host:sshPort")
        command.add_argument("--port-start", type=int)
        command.add_argument("--port-end", type=int)
        command.add_argument("--connect-timeout", type=int, default=15)
        command.add_argument("--manual-scheme", default="http")
        command.add_argument("--manual-host")
        command.add_argument("--manual-port", type=int)
        command.add_argument("--manual-base-path", default="")
        command.add_argument("--cluster-id", default="default")
        command.add_argument("--operator-id", default=os.getenv("USERNAME") or os.getenv("USER"))
        if command_name == "bootstrap":
            command.add_argument("--bootstrap-timeout", type=int, default=900,
                                 help="完整自举预算，单位秒，默认 900。")
            command.add_argument("--force-redeploy", action="store_true",
                                 help="在当前中心节点和端口上滚动更新制品，保留远端目录与 SQLite。")
        command.set_defaults(func=handler)


def add_session(subparsers: argparse._SubParsersAction) -> None:
    session = subparsers.add_parser("session", help="会话管理")
    commands = session.add_subparsers(dest="session_command", required=True)

    open_parser = commands.add_parser("open", help="打开协同会话")
    add_json_body_options(open_parser)
    open_parser.add_argument("--initiator-type", default="CODEX")
    open_parser.add_argument("--initiator-id", default="ccrelay-cli")
    open_parser.add_argument("--source-node-id")
    open_parser.set_defaults(func=session_open)

    close_parser = commands.add_parser("close", help="关闭协同会话")
    close_parser.add_argument("session_id")
    close_parser.set_defaults(
        func=lambda args: request_json(args, "POST", f"/api/skill/session/{quote_path(args.session_id)}/close")
    )

    get_parser = commands.add_parser("get", help="查看会话协作模式、主 Agent 和参与节点")
    get_parser.add_argument("session_id")
    get_parser.set_defaults(
        func=lambda args: request_json(args, "GET", f"/api/skill/session/{quote_path(args.session_id)}/collaboration")
    )

    messages_parser = commands.add_parser("messages", help="读取会话共享上下文增量")
    messages_parser.add_argument("session_id")
    messages_parser.add_argument("--after-cursor", type=int, default=0)
    messages_parser.add_argument("--limit", type=int, default=200)
    messages_parser.set_defaults(func=lambda args: request_json(
        args,
        "GET",
        f"/api/skill/session/{quote_path(args.session_id)}/context/delta",
        query={"afterCursor": max(args.after_cursor, 0), "limit": args.limit},
    ))


def add_relay(subparsers: argparse._SubParsersAction) -> None:
    relay = subparsers.add_parser("relay", help="relay 注册、心跳与节点管理")
    commands = relay.add_subparsers(dest="relay_command", required=True)

    register = commands.add_parser("register", help="注册 relay 节点")
    add_json_body_options(register)
    register.add_argument("--node-id")
    register.add_argument("--host")
    register.add_argument("--port", type=int)
    register.add_argument("--relay-endpoint")
    register.add_argument("--version")
    register.add_argument("--protocol-version")
    register.add_argument("--workspace-root")
    register.add_argument("--capabilities", default="")
    register.add_argument("--environment-json")
    register.add_argument("--environment-file")
    register.set_defaults(func=relay_register)

    heartbeat = commands.add_parser("heartbeat", help="上报 relay 心跳")
    add_json_body_options(heartbeat)
    heartbeat.add_argument("--node-id")
    heartbeat.add_argument("--status", default="AVAILABLE")
    heartbeat.add_argument("--active-sessions", type=int)
    heartbeat.add_argument("--cpu-load", type=float)
    heartbeat.add_argument("--memory-usage", type=int)
    heartbeat.add_argument("--last-task-time")
    heartbeat.add_argument("--detail-json")
    heartbeat.add_argument("--detail-file")
    heartbeat.add_argument("--environment-json")
    heartbeat.add_argument("--environment-file")
    heartbeat.set_defaults(func=relay_heartbeat)

    scan = commands.add_parser("scan", help="扫描 relay 可用性")
    scan.set_defaults(func=lambda args: request_json(args, "GET", "/api/skill/relay/heartbeat/scan"))

    nodes = commands.add_parser("nodes", help="列出 relay 节点")
    nodes.set_defaults(func=lambda args: request_json(args, "GET", "/api/skill/relay/nodes"))

    node = commands.add_parser("node", help="查看 relay 节点详情")
    node.add_argument("node_id")
    node.set_defaults(func=lambda args: request_json(args, "GET", f"/api/skill/relay/nodes/{quote_path(args.node_id)}"))


def add_access(subparsers: argparse._SubParsersAction) -> None:
    access = subparsers.add_parser("access", help="relay 授权管理")
    commands = access.add_subparsers(dest="access_command", required=True)

    request = commands.add_parser("request", help="申请 relay 访问授权")
    add_json_body_options(request)
    request.add_argument("--session-id")
    request.add_argument("--request-id")
    request.add_argument("--source-node-id")
    request.add_argument("--target-node-id")
    request.add_argument("--reason")
    request.add_argument("--capabilities", default=",".join(DEFAULT_A2A_CAPABILITIES))
    request.add_argument("--ttl-ms", type=int)
    request.add_argument("--parent-task-id")
    request.add_argument("--trace-id")
    request.set_defaults(func=access_request)

    renew = commands.add_parser("renew", help="续期 relay 访问授权")
    add_json_body_options(renew)
    renew.add_argument("--grant-id")
    renew.add_argument("--request-id")
    renew.add_argument("--ttl-ms", type=int)
    renew.set_defaults(func=access_renew)

    revoke = commands.add_parser("revoke", help="吊销 relay 访问授权")
    add_json_body_options(revoke)
    revoke.add_argument("--grant-id")
    revoke.add_argument("--reason", default="ccrelay-cli revoke")
    revoke.set_defaults(func=access_revoke)

    validate = commands.add_parser("validate", help="校验 relay 访问授权")
    add_json_body_options(validate)
    add_grant_context(validate)
    validate.add_argument("--capabilities", default="")
    validate.add_argument("--expires-at")
    validate.add_argument("--no-sign", action="store_true", help="不自动生成 HMAC requestSignature。")
    validate.add_argument("--hmac-secret", default=_center_hmac_secret())
    validate.set_defaults(func=access_validate)

    get = commands.add_parser("get", help="查看 relay 授权详情")
    get.add_argument("grant_id")
    get.set_defaults(func=lambda args: request_json(args, "GET", f"/api/skill/relay/access/{quote_path(args.grant_id)}"))


def add_agent(subparsers: argparse._SubParsersAction) -> None:
    agent = subparsers.add_parser("agent", help="会话内远端 Agent 编排")
    commands = agent.add_subparsers(dest="agent_command", required=True)

    observe = commands.add_parser("observe", help="查看远端 Agent 任务窗口化观测")
    add_observation_options(observe)
    observe.set_defaults(func=agent_observe)

    run = commands.add_parser("run", help="向单个远端 Agent 下发工作请求")
    add_agent_work_options(run)
    run.set_defaults(func=agent_run)

    ask = commands.add_parser("ask", help="agent run 的兼容别名；是否走 AI 由远端 Agent 决定")
    add_agent_work_options(ask)
    ask.set_defaults(func=agent_run)

    fanout = commands.add_parser("fanout", help="在一个会话内向多个远端 Agent 异步下发同一工作请求")
    add_agent_work_options(fanout, target_required=False)
    fanout.add_argument("--target-node-ids", required=True, help="逗号分隔的目标节点列表")
    fanout.add_argument(
        "--poll-seconds",
        type=float,
        default=0.0,
        help="创建任务后逐节点状态兜底查询窗口；默认 0 表示只做一次即时状态兜底，不同步等待全部完成。",
    )
    fanout.set_defaults(func=agent_fanout, collaboration_mode="INDEPENDENT_FANOUT")

    create = commands.add_parser("task-create", help="向远端 Agent 创建异步工作任务")
    add_agent_work_options(create)
    create.add_argument("--task-id")
    create.add_argument("--request-id")
    create.set_defaults(func=agent_task_create)

    get = commands.add_parser("task-get", help="查询远端 Agent 异步任务状态")
    get.add_argument("task_id")
    add_agent_context_options(get)
    get.set_defaults(func=agent_task_get)

    events = commands.add_parser("task-events", help="读取远端 Agent 异步任务事件")
    events.add_argument("task_id")
    add_agent_context_options(events)
    events.set_defaults(func=agent_task_events)

    stop = commands.add_parser("stop", help="停止远端 Agent 异步任务")
    stop.add_argument("task_id")
    add_agent_context_options(stop)
    stop.set_defaults(reason="ccrelay-cli agent stop")
    stop.set_defaults(func=agent_stop)

    inject = commands.add_parser("inject", help="向运行中的远端 Agent 注入引导提示词")
    inject.add_argument("task_id")
    add_agent_context_options(inject)
    inject.add_argument("--prompt", required=True)
    inject.set_defaults(func=agent_inject)

    adjust = commands.add_parser("adjust", help="调整运行中的远端 Agent 控制参数")
    adjust.add_argument("task_id")
    add_agent_context_options(adjust)
    add_react_options(adjust)
    adjust.set_defaults(func=agent_adjust)


def add_config(subparsers: argparse._SubParsersAction) -> None:
    config = subparsers.add_parser("config", help="运行时在线配置")
    commands = config.add_subparsers(dest="config_command", required=True)

    get = commands.add_parser("get", help="读取配置")
    get.add_argument("key")
    get.set_defaults(func=config_get)

    list_parser = commands.add_parser("list", help="列出配置")
    list_parser.add_argument("--prefix")
    list_parser.set_defaults(func=config_list)

    set_parser = commands.add_parser("set", help="更新普通配置")
    set_parser.add_argument("key")
    set_parser.add_argument("--value")
    set_parser.add_argument("--value-file")
    set_parser.add_argument("--operator-id")
    set_parser.add_argument("--comment")
    set_parser.set_defaults(func=config_set)

    secret = commands.add_parser("secret", help="密钥配置")
    secret_commands = secret.add_subparsers(dest="secret_command", required=True)

    secret_get = secret_commands.add_parser("get", help="读取密钥配置元数据")
    secret_get.add_argument("key")
    secret_get.set_defaults(func=config_secret_get)

    secret_set = secret_commands.add_parser("set", help="更新密钥配置")
    secret_set.add_argument("key")
    secret_set.add_argument("--value")
    secret_set.add_argument("--value-file")
    secret_set.add_argument("--operator-id")
    secret_set.add_argument("--comment")
    secret_set.set_defaults(func=config_secret_set)

    unset = commands.add_parser("unset", help="清除普通配置覆盖")
    unset.add_argument("key")
    unset.add_argument("--operator-id")
    unset.add_argument("--comment")
    unset.set_defaults(func=config_unset)

    reload_parser = commands.add_parser("reload", help="触发配置重载")
    reload_parser.add_argument("--operator-id")
    reload_parser.set_defaults(func=config_reload)

    history = commands.add_parser("history", help="查看配置变更历史")
    history.add_argument("key")
    history.add_argument("--limit", type=int)
    history.set_defaults(func=config_history)


def add_ssh(subparsers: argparse._SubParsersAction) -> None:
    ssh = subparsers.add_parser("ssh", help="Skill 本地 SSH 凭据、预检与免密初始化")
    commands = ssh.add_subparsers(dest="ssh_command", required=True)

    config = commands.add_parser("config", help="管理本地 SSH 凭据")
    config_commands = config.add_subparsers(dest="ssh_config_command", required=True)

    show = config_commands.add_parser("show", help="查看脱敏后的 SSH 配置")
    show.set_defaults(func=lambda _args: ccrelay_ssh.masked_config_view())

    set_default = config_commands.add_parser("set-default", help="设置唯一的通用 SSH 凭据")
    add_ssh_credential_options(set_default)
    set_default.set_defaults(func=ssh_config_set_default)

    set_passwordless_default = config_commands.add_parser(
        "set-passwordless-default", help="保存已有免密 SSH 通用账号")
    set_passwordless_default.add_argument("--username", required=True)
    set_passwordless_default.add_argument("--port", type=int, default=22)
    set_passwordless_default.add_argument(
        "--private-key-file", help="可选私钥路径；为空时使用系统 SSH config、Agent 或默认密钥。")
    set_passwordless_default.add_argument("--allow-cluster-mutual", type=parse_bool, default=True)
    set_passwordless_default.add_argument("--remote-directory")
    add_ssh_parameter_options(set_passwordless_default)
    set_passwordless_default.set_defaults(func=ssh_config_set_passwordless_default)

    remove_default = config_commands.add_parser("remove-default", help="移除通用 SSH 凭据")
    remove_default.set_defaults(func=lambda _args: ccrelay_ssh.remove_default_credential())

    set_node = config_commands.add_parser("set-node", help="按 ip:port 设置节点级 SSH 凭据")
    set_node.add_argument("--host", required=True)
    add_ssh_credential_options(set_node)
    set_node.set_defaults(func=ssh_config_set_node)

    remove_node = config_commands.add_parser("remove-node", help="移除节点级 SSH 凭据")
    remove_node.add_argument("--host", required=True)
    remove_node.add_argument("--port", type=int, default=22)
    remove_node.set_defaults(func=lambda args: ccrelay_ssh.remove_node_credential(args.host, args.port))

    tools = commands.add_parser("tools", help="检查本机 SSH 工具")
    tools.set_defaults(func=lambda _args: ccrelay_ssh.check_local_tools())

    test = commands.add_parser("test", help="测试节点 SSH；需要时使用已保存密码")
    add_ssh_target_options(test)
    test.add_argument("--bootstrap-key", type=parse_bool, default=False, help="密码登录成功后是否初始化免密。")
    test.set_defaults(func=ssh_test)

    preflight = commands.add_parser("preflight", help="部署前预检；成功后保证中心部署所需免密可用")
    add_ssh_target_options(preflight)
    preflight.set_defaults(func=ssh_preflight)

    center_key = commands.add_parser("center-key", help="获取当前 CC center 的部署公钥")
    center_key.set_defaults(func=lambda args: request_json(args, "GET", "/api/skill/ssh/center-key"))

    prepare_center = commands.add_parser("prepare-center", help="用本地凭据把中心公钥写入目标节点并验证")
    add_ssh_target_options(prepare_center)
    prepare_center.set_defaults(func=ssh_prepare_center)

    identity = commands.add_parser("identity", help="管理集群 SSH 身份策略与互信状态")
    identity_commands = identity.add_subparsers(dest="ssh_identity_command", required=True)

    identity_status = identity_commands.add_parser("status", help="查看本地与中心保存的身份能力摘要")
    identity_status.add_argument("--cluster-id", default="default")
    identity_status.set_defaults(func=ssh_identity_status)

    identity_targets = identity_commands.add_parser("targets", help="查看或显式修改部署目标节点集合")
    target_commands = identity_targets.add_subparsers(dest="ssh_identity_target_command", required=True)
    target_status = target_commands.add_parser("status", help="查看部署目标、成功、失败和已排除节点")
    target_status.add_argument("--cluster-id", default="default")
    target_status.set_defaults(func=lambda args: ccrelay_identity.target_status(args.cluster_id))
    for action, help_text in [("add", "显式加入部署目标节点"), ("exclude", "显式排除部署目标节点")]:
        command = target_commands.add_parser(action, help=help_text)
        command.add_argument("--cluster-id", default="default")
        command.add_argument("--node", "--nodes", action="append", dest="nodes", required=True)
        command.add_argument("--confirm", type=parse_bool, default=False)
        command.set_defaults(func=lambda args, selected=action: ccrelay_identity.update_target_nodes(
            args.cluster_id, args.nodes, selected.upper(), args.confirm))

    for command_name, help_text, handler in [
        ("plan", "只读探测账号模式和所需权限", ssh_identity_plan),
        ("select", "保存用户确认的账号模式和专用账号详情", ssh_identity_select_mode),
        ("apply", "按已确认模式创建账号或配置中心免密", ssh_identity_apply),
        ("verify", "重新验证中心到节点和节点间互信", ssh_identity_verify),
        ("rotate-key", "轮换集群身份密钥", ssh_identity_rotate_key),
    ]:
        command = identity_commands.add_parser(command_name, help=help_text)
        add_identity_target_options(command, require_center=command_name in {"apply", "verify", "rotate-key"})
        if command_name in {"plan", "apply"}:
            command.add_argument(
                "--execution-mode",
                help="用户确认的引导模式: AUTO_EXECUTE_REMAINING 或 INSPECT_STEP_BY_STEP",
            )
        command.set_defaults(func=handler)


def add_ssh_credential_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--username", required=True)
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--password-file", help="从临时文件读取密码；读取后不会输出密码。")
    parser.add_argument("--password-env", help="从指定环境变量读取密码；不会写入命令行参数。")
    parser.add_argument("--prompt-password", action="store_true", help="在当前交互终端隐藏输入 SSH 密码。")
    parser.add_argument("--launch-secure-terminal", action="store_true", help="在独立安全终端隐藏输入 SSH 密码。")
    parser.add_argument("--enable-passwordless", type=parse_bool, default=True)
    parser.add_argument("--allow-cluster-mutual", type=parse_bool, default=True)
    parser.add_argument("--remote-directory")
    add_ssh_parameter_options(parser)


def add_ssh_parameter_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--ssh-arguments-mode",
        choices=[ccrelay_ssh.SSH_ARGUMENT_MODE_DEFAULT, ccrelay_ssh.SSH_ARGUMENT_MODE_USER_PROVIDED],
        default=ccrelay_ssh.SSH_ARGUMENT_MODE_DEFAULT,
        help="SSH 参数来源；USER_PROVIDED 时完全使用重复传入的 --ssh-argument。",
    )
    parser.add_argument(
        "--ssh-argument",
        action="append",
        default=[],
        help="用户 SSH 参数的单个 argv token；可重复传入，例如 --ssh-argument=-o --ssh-argument=StrictHostKeyChecking=no。",
    )


def add_ssh_target_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--username")
    parser.add_argument("--connect-timeout", type=int, default=15)


def add_identity_target_options(parser: argparse.ArgumentParser, require_center: bool = True) -> None:
    parser.add_argument("--cluster-id", default="default")
    parser.add_argument("--center-node", required=require_center, help="中心 SSH 节点，格式 host:port[=username]")
    parser.add_argument("--node", "--nodes", action="append", dest="nodes", default=[],
                        help="受管节点，格式 host:port[=username]；可重复传入或使用逗号分隔")
    parser.add_argument("--allow-create", type=parse_bool,
                        help="是否允许创建 Skill 专用账号；未提供时使用本地已保存策略")
    parser.add_argument("--dedicated-username", default=None)
    parser.add_argument("--remote-directory-template", default=None)
    parser.add_argument("--confirm-details", type=parse_bool, default=False,
                        help="确认专用账号名和部署目录预览后才允许继续")
    parser.add_argument("--operator-id", default=os.getenv("USERNAME") or os.getenv("USER"))
    parser.add_argument("--connect-timeout", type=int, default=15)
    add_ssh_concurrency_option(parser)


def add_ssh_concurrency_option(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--concurrency",
        type=parse_ssh_concurrency,
        default=ccrelay_identity.DEFAULT_SSH_CONCURRENCY,
        help="SSH 多节点操作的最大并发数，范围 1-32，默认 4",
    )


def parse_ssh_concurrency(value: str) -> int:
    try:
        return ccrelay_identity.normalize_concurrency(value)
    except ccrelay_ssh.SshCredentialError as exc:
        raise argparse.ArgumentTypeError(str(exc)) from exc


def add_observation(subparsers: argparse._SubParsersAction) -> None:
    observe = subparsers.add_parser("observe", help="任务窗口化观测")
    add_observation_options(observe)
    observe.set_defaults(func=task_observe)


def add_deploy(subparsers: argparse._SubParsersAction) -> None:
    deploy = subparsers.add_parser("deploy", help="部署回报管理")
    commands = deploy.add_subparsers(dest="deploy_command", required=True)

    report = commands.add_parser("report", help="回报 relay 部署结果")
    add_json_body_options(report)
    report.add_argument("--task-id")
    report.add_argument("--session-id")
    report.add_argument("--target-node-id")
    report.add_argument("--deploy-mode")
    report.add_argument("--status")
    report.add_argument("--relay-endpoint")
    report.add_argument("--version")
    report.add_argument("--health-passed", type=parse_bool)
    report.add_argument("--registered", type=parse_bool)
    report.add_argument("--stdout-summary")
    report.add_argument("--stderr-summary")
    report.add_argument("--exit-code", type=int)
    report.add_argument("--retryable", type=parse_bool)
    report.set_defaults(func=deploy_report)

    resume = commands.add_parser("resume", help="SSH 预检成功后恢复等待中的中心部署")
    resume.add_argument("task_id")
    resume.add_argument("--host", required=True)
    resume.add_argument("--port", type=int, default=22)
    resume.add_argument("--username")
    resume.add_argument("--connect-timeout", type=int, default=15)
    resume.set_defaults(func=deploy_resume)


def add_task(subparsers: argparse._SubParsersAction) -> None:
    task = subparsers.add_parser("task", help="本地异步任务管理")
    commands = task.add_subparsers(dest="task_command", required=True)

    observe = commands.add_parser("observe", help="查看任务窗口化观测")
    add_observation_options(observe)
    observe.set_defaults(func=task_observe)

    create = commands.add_parser("create", help="创建本地异步任务")
    add_json_body_options(create)
    create.add_argument("--task-id")
    create.add_argument("--session-id")
    create.add_argument("--request-id")
    create.add_argument("--parent-task-id")
    create.add_argument("--task-type")
    create.add_argument("--source-node-id")
    create.add_argument("--target-node-id")
    create.add_argument("--payload-json")
    create.add_argument("--payload-file")
    create.add_argument("--timeout-ms", type=int)
    create.set_defaults(func=task_create)

    batch = commands.add_parser("create-batch", help="按有界并发创建多个 Relay 部署任务")
    add_json_body_options(batch)
    batch.add_argument(
        "--target-node-ids", "--target-node-id", dest="target_node_ids", action="append", required=True,
        help="部署目标 nodeId，支持重复传入或逗号分隔；每个目标创建一个独立 DEPLOY_RELAY 任务",
    )
    batch.add_argument("--session-id")
    batch.add_argument("--request-id")
    batch.add_argument("--parent-task-id")
    batch.add_argument("--task-type", default="DEPLOY_RELAY")
    batch.add_argument("--source-node-id")
    batch.add_argument("--payload-json")
    batch.add_argument("--payload-file")
    batch.add_argument("--timeout-ms", type=int)
    batch.add_argument(
        "--concurrency", type=parse_ssh_concurrency, default=ccrelay_identity.DEFAULT_SSH_CONCURRENCY,
        help="同时创建部署任务的最大并发数，范围 1-32，默认 4",
    )
    batch.set_defaults(func=task_create_batch)

    get = commands.add_parser("get", help="查看本地任务详情")
    get.add_argument("task_id")
    get.set_defaults(func=lambda args: request_json(args, "GET", f"/api/skill/tasks/{quote_path(args.task_id)}"))

    status = commands.add_parser("status", help="查看本地任务状态")
    status.add_argument("task_id")
    status.set_defaults(func=lambda args: request_json(args, "GET", f"/api/skill/tasks/{quote_path(args.task_id)}/status"))

    cancel = commands.add_parser("cancel", help="取消本地任务")
    add_json_body_options(cancel)
    cancel.add_argument("task_id")
    cancel.add_argument("--reason")
    cancel.set_defaults(func=task_cancel)

    events = commands.add_parser("events", help="查看本地任务事件")
    events.add_argument("task_id")
    events.set_defaults(func=lambda args: request_json(args, "GET", f"/api/skill/tasks/{quote_path(args.task_id)}/events"))

    stream = commands.add_parser("stream", help="流式查看本地任务事件")
    stream.add_argument("task_id")
    stream.set_defaults(func=lambda args: stream_response(args, "/api/skill/tasks/{}/events/stream".format(quote_path(args.task_id))))


def add_a2a(subparsers: argparse._SubParsersAction) -> None:
    a2a = subparsers.add_parser("a2a", help="中心代理 A2A 协同")
    commands = a2a.add_subparsers(dest="a2a_command", required=True)

    card = commands.add_parser("agent-card", help="查看 A2A 能力卡片")
    card.set_defaults(func=lambda args: request_json(args, "GET", "/api/skill/a2a/agent-card"))

    message = commands.add_parser("message-send", help="发送 A2A 消息")
    add_json_body_options(message)
    add_a2a_context(message)
    message.add_argument("--id", default=lambda_id("rpc-message"))
    message.add_argument("--prompt")
    message.add_argument("--messages-json")
    message.add_argument("--model-config-file")
    message.add_argument("--params-json")
    message.set_defaults(func=a2a_message_send)

    create = commands.add_parser("task-create", help="创建 A2A 任务")
    add_json_body_options(create)
    add_a2a_context(create)
    create.add_argument("--id", default=lambda_id("rpc-task"))
    create.add_argument("--request-id")
    create.add_argument("--task-id")
    create.add_argument("--prompt")
    create.add_argument("--input-json")
    create.add_argument("--model-config-file")
    create.add_argument("--params-json")
    create.set_defaults(func=a2a_task_create)

    get = commands.add_parser("task-get", help="查看 A2A 任务详情")
    add_a2a_context(get)
    get.add_argument("task_id")
    get.set_defaults(func=lambda args: request_json(args, "GET", f"/api/skill/a2a/tasks/{quote_path(args.task_id)}", query=a2a_query(args)))

    events = commands.add_parser("task-events", help="流式查看 A2A 任务事件")
    add_a2a_context(events)
    events.add_argument("task_id")
    events.set_defaults(func=lambda args: stream_response(args, f"/api/skill/a2a/tasks/{quote_path(args.task_id)}/events", query=a2a_query(args)))

    cancel = commands.add_parser("task-cancel", help="取消 A2A 任务")
    add_a2a_context(cancel)
    cancel.add_argument("task_id")
    cancel.set_defaults(func=lambda args: request_json(args, "POST", f"/api/skill/a2a/tasks/{quote_path(args.task_id)}/cancel", query=a2a_query(args)))


def add_remote(subparsers: argparse._SubParsersAction) -> None:
    remote = subparsers.add_parser("remote", help="显式远端 relay 诊断；默认流程不应直接使用")
    remote.add_argument("--relay", required=True, help="远端 relay 基础地址，例如 http://node:18091")
    commands = remote.add_subparsers(dest="remote_command", required=True)

    health = commands.add_parser("health", help="检查远端 relay 健康状态")
    health.set_defaults(func=lambda args: request_json(args, "GET", "/health", base=args.relay))

    chat = commands.add_parser("chat", help="直接调用远端 relay chat")
    add_json_body_options(chat)
    chat.add_argument("--prompt")
    chat.add_argument("--model-config-file")
    chat.set_defaults(func=remote_chat)

    grant_validate = commands.add_parser("grant-validate", help="直接校验远端授权")
    add_json_body_options(grant_validate)
    add_grant_context(grant_validate)
    grant_validate.add_argument("--capabilities", default="")
    grant_validate.add_argument("--expires-at")
    grant_validate.add_argument("--no-sign", action="store_true")
    grant_validate.add_argument("--hmac-secret", default=_center_hmac_secret())
    grant_validate.set_defaults(func=lambda args: request_json(args, "POST", "/internal/grant/validate", body=grant_validate_body(args), base=args.relay))

    self_replicate = commands.add_parser("self-replicate", help="直接触发远端自复制")
    add_json_body_options(self_replicate)
    self_replicate.add_argument("--task-id")
    self_replicate.add_argument("--session-id")
    self_replicate.add_argument("--source-node-id")
    self_replicate.add_argument("--target-node-id")
    self_replicate.add_argument("--host")
    self_replicate.add_argument("--port", type=int, default=22)
    self_replicate.add_argument("--username")
    self_replicate.add_argument("--relay-port", type=int)
    self_replicate.add_argument("--script-path")
    self_replicate.add_argument("--artifact-path")
    self_replicate.add_argument("--remote-directory")
    self_replicate.add_argument("--timeout-ms", type=int)
    self_replicate.add_argument("--artifact-version")
    self_replicate.add_argument("--command-arguments", default="")
    self_replicate.set_defaults(func=remote_self_replicate)

    create = commands.add_parser("task-create", help="直接创建远端 A2A 任务")
    add_json_body_options(create)
    create.set_defaults(func=lambda args: request_json(args, "POST", "/api/ai/a2a/tasks/create", body=body_from_json_options(args), base=args.relay))

    get = commands.add_parser("task-get", help="直接查看远端 A2A 任务详情")
    get.add_argument("task_id")
    get.set_defaults(func=lambda args: request_json(args, "GET", f"/api/ai/a2a/tasks/{quote_path(args.task_id)}", base=args.relay))

    events = commands.add_parser("task-events", help="直接查看远端 A2A 任务事件")
    events.add_argument("task_id")
    events.set_defaults(func=lambda args: stream_response(args, f"/api/ai/a2a/tasks/{quote_path(args.task_id)}/events", base=args.relay))

    cancel = commands.add_parser("task-cancel", help="直接取消远端 A2A 任务")
    cancel.add_argument("task_id")
    cancel.set_defaults(func=lambda args: request_json(args, "POST", f"/api/ai/a2a/tasks/{quote_path(args.task_id)}/cancel", base=args.relay))


def add_json_body_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--json", help="完整 JSON 请求体；会与显式参数合并，显式参数优先。")
    parser.add_argument("--json-file", help="从文件读取完整 JSON 请求体。")


def add_grant_context(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--grant-id")
    parser.add_argument("--session-id")
    parser.add_argument("--source-node-id")
    parser.add_argument("--target-node-id")
    parser.add_argument("--signed-token")


def add_a2a_context(parser: argparse.ArgumentParser) -> None:
    add_grant_context(parser)
    parser.add_argument("--target-relay-endpoint")
    parser.add_argument("--center-grant-validate-endpoint")
    parser.add_argument("--expires-at")
    parser.add_argument("--hmac-secret", default=_center_hmac_secret())


def add_agent_context_options(parser: argparse.ArgumentParser, target_required: bool = True) -> None:
    parser.add_argument("--session-id")
    parser.add_argument("--source-node-id")
    parser.add_argument("--target-node-id", required=target_required)
    parser.add_argument("--grant-id")
    parser.add_argument("--signed-token", help="复用已有 grant 时使用的签名 token；不会写入日志。")
    parser.add_argument("--expires-at", help="已有 grant 的过期时间；与 --signed-token 一起提供。")
    parser.add_argument("--reason", default="ccrelay-cli agent work")
    parser.add_argument("--keep-session", action="store_true")
    parser.add_argument("--hmac-secret", default=_center_hmac_secret())


def add_agent_work_options(parser: argparse.ArgumentParser, target_required: bool = True) -> None:
    add_agent_context_options(parser, target_required=target_required)
    parser.add_argument("--prompt", required=True, help="下发给远端 Agent 的工作请求；远端代码决定是否调用 AI")
    parser.add_argument("--params-json", help="附加上下文 JSON")
    parser.add_argument("--model-config-file")
    parser.add_argument(
        "--collaboration-mode",
        choices=["DIRECT", "INDEPENDENT_FANOUT", "DISCUSSION", "SESSION_FOLLOW_UP"],
        default="DIRECT",
        help="当前会话协作模式；讨论场景使用 DISCUSSION。",
    )
    parser.add_argument("--collaboration-policy-json", help="会话协作策略 JSON")
    add_react_options(parser)


def add_observation_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("task_id", nargs="?", help="单任务观测时的任务 ID")
    parser.add_argument("--task-ids", help="逗号分隔的批量任务 ID 列表")
    parser.add_argument("--parent-task-id")
    parser.add_argument("--session-id")
    parser.add_argument("--target-node-id")
    parser.add_argument("--since-sequence-no", type=int)
    parser.add_argument("--since-created-time-ms", type=int)
    parser.add_argument("--last-ms", type=int)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--tail-lines", type=int)
    parser.add_argument("--max-bytes", type=int)
    parser.add_argument("--per-event-max-bytes", type=int)
    parser.add_argument("--include", default="")
    parser.add_argument("--event-types", default="")
    parser.add_argument("--authorization-scope", default="A2A_TASK_OBSERVE")


def add_react_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--mode", default="ReAct", help="远端 Agent 执行模式，默认 ReAct。")
    parser.add_argument("--max-steps", type=int, default=12, help="ReAct 最大步数。")
    parser.add_argument("--command-whitelist", default="", help="逗号分隔的远端命令白名单；为空表示使用远端默认策略。")
    parser.add_argument("--step-timeout-ms", type=int, default=60000, help="单步执行超时时间。")
    parser.add_argument("--task-timeout-ms", type=int, default=600000, help="单任务总超时时间。")
    parser.add_argument("--audit-level", default="FULL", help="审计级别，默认 FULL。")
    parser.add_argument("--allow-ai", type=parse_bool, default=True, help="是否允许远端 Agent 在需要时调用 AI。")


def center_resolve(args: argparse.Namespace) -> Any:
    parsed = urllib.parse.urlparse(args.center)
    host = (parsed.hostname or "").lower()
    mode = "LOCAL" if host in {"127.0.0.1", "localhost", "::1"} else "REMOTE"
    health = _ensure_local_center(args) if mode == "LOCAL" else request_json(args, "GET", "/api/skill/health")
    return {
        "centerUrl": args.center,
        "mode": mode,
        "configuredBy": getattr(args, "center_configured_by", "DEFAULT"),
        "status": health.get("status") if isinstance(health, dict) else None,
        "component": health.get("component") if isinstance(health, dict) else None,
        "localProcess": health.get("localProcess") if isinstance(health, dict) else None,
    }


def center_ensure(args: argparse.Namespace) -> Any:
    parsed = urllib.parse.urlparse(args.center)
    host = (parsed.hostname or "").lower()
    if host not in {"127.0.0.1", "localhost", "::1"}:
        return {"status": "REMOTE_CENTER_SELECTED", "centerUrl": args.center, "started": False}
    return _ensure_local_center(args)


def center_status(args: argparse.Namespace) -> Any:
    state_path = _center_state_path()
    state = _read_json_file(state_path) or {}
    pid = int(state.get("pid", 0) or 0)
    discovered_pids = _discover_local_center_pids()
    if not _process_alive(pid):
        pid = next((candidate for candidate in discovered_pids if _process_alive(candidate)), 0)
    alive = _process_alive(pid)
    return {
        "status": "RUNNING" if alive else "STOPPED",
        "managed": bool(state) or alive,
        "pid": pid or None,
        "pids": discovered_pids if discovered_pids else ([pid] if pid else []),
        "centerUrl": state.get("centerUrl", DEFAULT_CENTER_URL),
        "dbPath": state.get("dbPath"),
        "javaPath": state.get("javaPath"),
        "jarPath": state.get("jarPath"),
        "statePath": str(state_path),
    }


def center_stop(args: argparse.Namespace) -> Any:
    state_path = _center_state_path()
    state = _read_json_file(state_path) or {}
    pid = int(state.get("pid", 0) or 0)
    pids = []
    if pid and _process_alive(pid):
        pids.append(pid)
    pids.extend(candidate for candidate in _discover_local_center_pids() if candidate not in pids)
    pids = [candidate for candidate in pids if _process_alive(candidate)]
    if not pids:
        if state_path.is_file():
            state_path.unlink()
        return {"status": "STOPPED", "stopped": False, "pid": pid or None}
    try:
        for candidate in pids:
            _terminate_process(candidate)
    except OSError as exc:
        raise CliError(f"停止本地 CC center 失败: {exc}") from exc
    if state_path.is_file():
        state_path.unlink()
    return {"status": "STOPPED", "stopped": True, "pid": pids[0], "pids": pids}


def center_plan(args: argparse.Namespace) -> Any:
    config = ccrelay_ssh.load_config()
    if (config.get("clusterIdentity") or {}).get("targetNodes") or args.nodes:
        nodes = ccrelay_identity.resolve_target_nodes(args.cluster_id, args.nodes, initialize=True)
        args.nodes = ccrelay_identity.node_values(nodes)
    return ccrelay_center.plan(
        args.nodes,
        selection=args.selection,
        exclusions=args.exclude,
        port_start=args.port_start,
        port_end=args.port_end,
        timeout_seconds=args.connect_timeout,
        manual_host=args.manual_host,
        manual_port=args.manual_port,
        manual_scheme=args.manual_scheme,
        manual_base_path=args.manual_base_path,
        concurrency=getattr(args, "concurrency", ccrelay_identity.DEFAULT_SSH_CONCURRENCY),
    )


def center_bootstrap(args: argparse.Namespace) -> Any:
    model_gate = ccrelay_center.model_config_bootstrap_gate(
        bool(getattr(args, "force_redeploy", False))
    )
    if not model_gate.get("ready"):
        return model_gate
    if not getattr(args, "force_redeploy", False):
        config = ccrelay_ssh.load_config()
        if (config.get("clusterIdentity") or {}).get("targetNodes") or args.nodes:
            nodes = ccrelay_identity.resolve_target_nodes(args.cluster_id, args.nodes, initialize=True)
            args.nodes = ccrelay_identity.node_values(nodes)
    active = ccrelay_center.active_center_status(max(10, min(int(args.timeout), 30)))
    previous_remote_center = None
    if active is not None and getattr(args, "force_redeploy", False):
        if active.get("status") != "REMOTE_CENTER_READY":
            return active
        selected = active.get("selected") or {}
        host = selected.get("host")
        ssh_port = selected.get("port") or 22
        center_port = selected.get("availablePort") or urllib.parse.urlparse(active.get("centerUrl") or "").port
        relay_port = active.get("relayPort") or selected.get("relayPort")
        if not host or not center_port:
            raise CliError("当前远端中心缺少节点或端口信息，不能执行原位滚动更新")
        previous_remote_center = ccrelay_center.stop_persisted_center(max(10, min(int(args.timeout), 60)))
        if not previous_remote_center.get("success"):
            return previous_remote_center
        planned = ccrelay_center.plan(
            [f"{host}:{ssh_port}"],
            selection="AUTO",
            port_start=int(center_port),
            port_end=max(int(center_port), int(relay_port or center_port) + 1),
            timeout_seconds=args.connect_timeout,
            strict_explicit_nodes=True,
            concurrency=getattr(args, "concurrency", ccrelay_identity.DEFAULT_SSH_CONCURRENCY),
        )
        if planned.get("status") != "CENTER_PLAN_READY":
            planned["previousRemoteCenter"] = previous_remote_center
            return planned
        result = ccrelay_center.bootstrap(
            planned,
            _center_hmac_secret(create=True),
            timeout_seconds=max(60, int(args.bootstrap_timeout)),
        )
    elif active is not None:
        if active.get("status") != "REMOTE_CENTER_READY":
            return active
        planned = {"selected": active.get("selected") or {}}
        result = active
    else:
        planned = ccrelay_center.recovery_plan(args.nodes) or center_plan(args)
        if planned.get("status") != "CENTER_PLAN_READY":
            return planned
        result = ccrelay_center.bootstrap(
            planned,
            _center_hmac_secret(create=True),
            timeout_seconds=max(60, int(args.bootstrap_timeout)),
        )
    if result.get("status") != "REMOTE_CENTER_READY":
        if previous_remote_center is not None:
            result["previousRemoteCenter"] = previous_remote_center
        return result
    previous_center = args.center
    args.center = result["centerUrl"]
    try:
        result["identitySync"] = sync_identity_to_selected_center(args, planned)
    except Exception as exc:
        result["rollback"] = ccrelay_center.rollback_bootstrap(result)
        args.center = previous_center
        result["success"] = False
        result["status"] = "CENTER_STATE_SYNC_FAILED"
        result["failureType"] = "CENTER_STATE_SYNC_FAILED"
        result["summary"] = str(exc)
        result["interaction"] = ccrelay_center.recovery_interaction()
        return result
    parsed_previous = urllib.parse.urlparse(previous_center)
    if (parsed_previous.hostname or "").lower() in {"127.0.0.1", "localhost", "::1"}:
        result["previousLocalCenter"] = center_stop(args)
    result["configuredBy"] = "PERSISTED"
    if previous_remote_center is not None:
        result["previousRemoteCenter"] = previous_remote_center
        result["rollingRedeploy"] = True
    return result


def sync_identity_to_selected_center(args: argparse.Namespace, planned: Dict[str, Any]) -> Dict[str, Any]:
    config = ccrelay_ssh.load_config()
    identity = config.get("clusterIdentity") or {}
    managed = ccrelay_identity.active_target_nodes(config) or identity.get("managedNodes") or []
    if not managed:
        return {"status": "NOT_APPLICABLE", "reason": "NO_PERSISTED_TARGET_NODES"}
    node_values = []
    for item in managed:
        host = item.get("host")
        if not host:
            continue
        port = item.get("port") or 22
        username = item.get("bootstrapUsername")
        node_values.append(f"{host}:{port}" + (f"={username}" if username else ""))
    selected = planned.get("selected") or {}
    center_node = f"{selected.get('host')}:{selected.get('port') or 22}"
    allow_create = bool(identity.get("dedicatedAccountCreationAllowed", False))
    dedicated_username = (identity.get("dedicatedAccount") or {}).get("username") or ccrelay_identity.DEFAULT_DEDICATED_USERNAME
    state = ccrelay_identity.verify(
        getattr(args, "cluster_id", "default"),
        center_node,
        ccrelay_identity.normalize_nodes(node_values, center_node),
        allow_create,
        dedicated_username,
        max(15, int(getattr(args, "connect_timeout", 15))),
        concurrency=getattr(args, "concurrency", ccrelay_identity.DEFAULT_SSH_CONCURRENCY),
        expected_target_nodes=ccrelay_identity.normalize_nodes(node_values),
    )
    if state.get("centerToNodeStatus") != "READY":
        raise CliError("远端中心切换后 SSH 身份验证未达到 READY")
    persistence = persist_identity_state(args, state, "CENTER_SWITCH")
    return {"status": "READY", "capability": state.get("effectiveCapability"), "persistence": persistence}


def _ensure_local_center(args: argparse.Namespace) -> Dict[str, Any]:
    health = _local_center_health(args.center, args.timeout)
    if health is not None:
        return {**health, "started": False, "localProcess": center_status(args)}
    state = _read_json_file(_center_state_path()) or {}
    if _process_alive(int(state.get("pid", 0) or 0)):
        health = _wait_local_center(args.center, args.timeout)
        return {**health, "started": False, "localProcess": center_status(args)}
    java_path, jar_path = _resolve_local_center_runtime()
    parsed = urllib.parse.urlparse(args.center)
    port = parsed.port or 18191
    local_root = _center_state_path().parent
    local_root.mkdir(parents=True, exist_ok=True)
    db_path = local_root / "center-skill.db"
    log_path = local_root / CENTER_LOG_FILE
    log_handle = log_path.open("a", encoding="utf-8")
    environment = os.environ.copy()
    environment["CCRELAY_PORT"] = str(port)
    environment["CCRELAY_DB"] = str(db_path)
    environment["WDSAVS_AI_HMAC_SECRET"] = _center_hmac_secret(create=True)
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
    process = None
    try:
        process = subprocess.Popen(
            [str(java_path), "-jar", str(jar_path)],
            cwd=str(jar_path.parent),
            stdin=subprocess.DEVNULL,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            env=environment,
            close_fds=os.name != "nt",
            creationflags=flags,
            start_new_session=os.name != "nt",
        )
    except OSError as exc:
        raise CliError(f"启动本地 CC center 失败: {exc}") from exc
    finally:
        log_handle.close()
    if process is None:
        raise CliError("启动本地 CC center 失败")
    state = {
        "pid": process.pid,
        "centerUrl": args.center,
        "dbPath": str(db_path),
        "javaPath": str(java_path),
        "jarPath": str(jar_path),
        "startedAt": int(time.time() * 1000),
    }
    _write_json_file(_center_state_path(), state)
    health = _wait_local_center(args.center, args.timeout)
    return {**health, "started": True, "localProcess": center_status(args)}


def _local_center_health(center_url: str, timeout: float) -> Optional[Dict[str, Any]]:
    try:
        request = urllib.request.Request(
            urllib.parse.urljoin(center_url.rstrip("/") + "/", "api/skill/health"),
            method="GET",
        )
        with urllib.request.urlopen(request, timeout=min(float(timeout), 5.0)) as response:
            payload = response.read().decode("utf-8")
        value = json.loads(payload)
        return value if isinstance(value, dict) else {"status": "UP"}
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError):
        return None


def _wait_local_center(center_url: str, timeout: float) -> Dict[str, Any]:
    deadline = time.monotonic() + max(5.0, min(float(timeout), 45.0))
    while time.monotonic() < deadline:
        health = _local_center_health(center_url, 2.0)
        if health is not None:
            return health
        time.sleep(0.5)
    raise CliError("本地 CC center 启动超时，请查看 .local/center-runtime.log")


def _resolve_local_center_runtime() -> tuple[Path, Path]:
    bundle = _skill_root() / "assets" / "runtime-bundle" / "ccrelay"
    cached_java = None
    extraction_error = None
    try:
        cached_java = _ensure_local_jre(bundle)
    except (OSError, tarfile.TarError, zipfile.BadZipFile, ValueError) as exc:
        extraction_error = exc
    if os.name == "nt":
        java_candidates = [bundle / "runtime-windows" / "bin" / "java.exe", bundle / "runtime" / "bin" / "java.exe"]
    else:
        java_candidates = [bundle / "runtime" / "bin" / "java"]
    if cached_java:
        java_candidates.insert(0, cached_java)
    java_candidates.extend([Path(shutil.which("java"))] if shutil.which("java") else [])
    java_path = next((candidate for candidate in java_candidates if candidate and candidate.is_file()), None)
    jar_path = bundle / "app.jar"
    if not jar_path.is_file():
        raise CliError(f"Skill 内置 CC center 制品不存在: {jar_path}")
    if not java_path:
        if extraction_error:
            raise CliError(f"本机内置 JRE 解压失败: {extraction_error}")
        raise CliError("本机没有可用 Java，且 Skill 未包含当前平台的内置 JDK")
    return java_path, jar_path


def _ensure_local_jre(bundle: Path) -> Optional[Path]:
    if os.name == "nt":
        archive = bundle / "runtime-windows.zip"
        runtime = _skill_root() / ".local" / "runtime" / "jre-windows-21"
        java_name = "java.exe"
    else:
        archive = bundle / "runtime.tar.gz"
        runtime = _skill_root() / ".local" / "runtime" / "jre-linux-21"
        java_name = "java"
    java_path = runtime / "bin" / java_name
    if java_path.is_file():
        return java_path
    if not archive.is_file():
        return None

    runtime.parent.mkdir(parents=True, exist_ok=True)
    lock = runtime.with_name(runtime.name + ".lock")
    deadline = time.monotonic() + 120
    lock_owned = False
    while not lock_owned:
        try:
            lock.mkdir()
            lock_owned = True
        except FileExistsError:
            if java_path.is_file():
                return java_path
            try:
                if time.time() - lock.stat().st_mtime > 300:
                    lock.rmdir()
                    continue
            except FileNotFoundError:
                continue
            except OSError:
                pass
            if time.monotonic() >= deadline:
                raise OSError(f"等待内置 JRE 解压锁超时: {lock}")
            time.sleep(0.25)

    temporary = runtime.with_name(runtime.name + ".tmp-" + uuid.uuid4().hex)
    try:
        if java_path.is_file():
            return java_path
        if temporary.exists():
            shutil.rmtree(temporary)
        temporary.mkdir(parents=True)
        if os.name == "nt":
            with zipfile.ZipFile(archive) as package:
                _extract_archive(package, temporary)
        else:
            with tarfile.open(archive, "r:gz") as package:
                _extract_archive(package, temporary)
        extracted_java = next(
            (item for item in temporary.rglob(java_name) if item.parent.name == "bin"), None
        )
        if extracted_java is None:
            raise OSError(f"内置 JRE 归档缺少 bin/{java_name}: {archive}")
        extracted_root = extracted_java.parent.parent
        if runtime.exists():
            shutil.rmtree(runtime)
        shutil.move(str(extracted_root), str(runtime))
        if not java_path.is_file():
            raise OSError(f"内置 JRE 解压后缺少 bin/{java_name}: {runtime}")
        if os.name != "nt":
            java_path.chmod(java_path.stat().st_mode | 0o111)
        return java_path
    finally:
        if temporary.exists():
            shutil.rmtree(temporary, ignore_errors=True)
        if lock_owned:
            try:
                lock.rmdir()
            except FileNotFoundError:
                pass


def _extract_archive(package: Any, destination: Path) -> None:
    destination_root = destination.resolve()
    for member in package.infolist() if isinstance(package, zipfile.ZipFile) else package.getmembers():
        name = member.filename if isinstance(member, zipfile.ZipInfo) else member.name
        target = (destination / name).resolve()
        if os.path.commonpath((str(destination_root), str(target))) != str(destination_root):
            raise OSError(f"内置运行时归档包含非法路径: {name}")
    if isinstance(package, zipfile.ZipFile):
        package.extractall(destination)
    else:
        package.extractall(destination, filter="data")


def _center_state_path() -> Path:
    return _skill_root() / ".local" / CENTER_STATE_FILE


def _skill_root() -> Path:
    configured = os.getenv("CCRELAY_SKILL_ROOT")
    return Path(configured).expanduser().resolve() if configured else Path(__file__).resolve().parents[1]


def _discover_local_center_pids() -> List[int]:
    jar_path = (_skill_root() / "assets" / "runtime-bundle" / "ccrelay" / "app.jar").resolve()
    if os.name == "nt":
        environment = os.environ.copy()
        environment["CCRELAY_CENTER_JAR_PATH"] = str(jar_path)
        script = (
            "$needle=$env:CCRELAY_CENTER_JAR_PATH; "
            "Get-CimInstance Win32_Process -Filter \"Name = 'java.exe'\" | "
            "Where-Object { $_.CommandLine -and $_.CommandLine.Contains($needle) } | "
            "ForEach-Object { $_.ProcessId }"
        )
        try:
            completed = subprocess.run(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
                env=environment, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            return []
        return [int(line.strip()) for line in completed.stdout.splitlines() if line.strip().isdigit()]
    matches = []
    proc_root = Path("/proc")
    if proc_root.is_dir():
        for process_dir in proc_root.iterdir():
            if not process_dir.name.isdigit():
                continue
            try:
                command_line = (process_dir / "cmdline").read_bytes().replace(b"\0", b" ").decode(
                    "utf-8", errors="replace")
            except OSError:
                continue
            if str(jar_path) in command_line:
                matches.append(int(process_dir.name))
    return matches


def _terminate_process(pid: int) -> None:
    if os.name == "nt":
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        subprocess.run(["taskkill", "/PID", str(pid), "/T"], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", check=False, creationflags=flags)
        deadline = time.monotonic() + 10
        while _process_alive(pid) and time.monotonic() < deadline:
            time.sleep(0.25)
        if _process_alive(pid):
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True,
                           text=True, encoding="utf-8", errors="replace", check=False, creationflags=flags)
            deadline = time.monotonic() + 10
            while _process_alive(pid) and time.monotonic() < deadline:
                time.sleep(0.25)
        if _process_alive(pid):
            raise OSError(f"process {pid} is still running after taskkill")
        return
    os.kill(pid, signal.SIGTERM)
    deadline = time.monotonic() + 10
    while _process_alive(pid) and time.monotonic() < deadline:
        time.sleep(0.25)
    if _process_alive(pid):
        os.kill(pid, signal.SIGKILL)


def _center_hmac_secret(create: bool = False) -> Optional[str]:
    configured = os.getenv("WDSAVS_AI_HMAC_SECRET")
    if configured:
        return configured
    path = _center_state_path().parent / CENTER_SECRET_FILE
    if path.is_file():
        try:
            return ccrelay_ssh.unprotect_secret(path.read_text(encoding="utf-8").strip())
        except (OSError, ccrelay_ssh.SshCredentialError) as exc:
            raise CliError(f"读取本地 CC center HMAC secret 失败: {exc}") from exc
    if not create:
        return None
    secret = secrets.token_urlsafe(48)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(ccrelay_ssh.protect_secret(secret), encoding="utf-8")
    try:
        path.chmod(0o600)
    except OSError:
        pass
    return secret


def _read_json_file(path: Path) -> Optional[Dict[str, Any]]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else None
    except (FileNotFoundError, OSError, json.JSONDecodeError):
        return None


def _write_json_file(path: Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def _process_alive(pid: int) -> bool:
    if not pid:
        return False
    try:
        if os.name == "nt":
            result = subprocess.run(
                ["tasklist", "/FI", f"PID eq {pid}"], capture_output=True, text=True,
                encoding="utf-8", errors="replace", check=False,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            return str(pid) in (result.stdout or "")
        os.kill(pid, 0)
        return True
    except (OSError, ValueError):
        return False


def task_observe(args: argparse.Namespace) -> Any:
    query = observation_query(args)
    if query.get("taskIds") or query.get("parentTaskId"):
        result = request_json(args, "GET", "/api/skill/observations/tasks", query=query)
        return deployment_observation_decision(result)
    task_id = query.pop("taskId", None)
    if not task_id:
        raise CliError("taskId is required for single observation")
    return request_json(args, "GET", f"/api/skill/observations/tasks/{quote_path(task_id)}", query=query)


def deployment_observation_decision(result: Any) -> Any:
    if not isinstance(result, dict) or not isinstance(result.get("observations"), list):
        return result
    observations = result.get("observations") or []
    deployment_observations = [
        item for item in observations
        if isinstance(item, dict)
        and isinstance(item.get("task"), dict)
        and str(item["task"].get("taskType") or "").upper() == "DEPLOY_RELAY"
    ]
    if not deployment_observations:
        return result
    terminal_statuses = {"SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"}
    if any(
            str(item.get("status") or "").upper() not in terminal_statuses
            for item in deployment_observations):
        return result
    failures = []
    for observation in deployment_observations:
        status = str(observation.get("status") or "").upper()
        if status == "SUCCESS":
            continue
        task = observation.get("task") if isinstance(observation.get("task"), dict) else {}
        task_result = task.get("result") if isinstance(task.get("result"), dict) else {}
        failures.append({
            "nodeKey": observation.get("targetNodeId") or task.get("targetNodeId") or observation.get("taskId"),
            "taskId": observation.get("taskId"),
            "failureStage": observation.get("currentStage") or task.get("currentStage") or "RELAY_DEPLOYMENT",
            "failureType": observation.get("errorCode") or task.get("errorCode")
            or task_result.get("failureType") or task_result.get("errorCode") or status,
            "summary": observation.get("errorMessage") or task.get("errorMessage")
            or task_result.get("summary") or task_result.get("stderrSummary") or "Relay 部署未完成",
            "retryable": bool(task_result.get("retryable", status != "CANCELLED")),
        })
    if not failures:
        return result
    decision = partial_failure_interaction(
        failures,
        len(deployment_observations),
        sum(1 for item in deployment_observations
            if str(item.get("status") or "").upper() == "SUCCESS"),
        "RELAY_DEPLOYMENT",
    )
    decision["observation"] = result
    return decision


def agent_observe(args: argparse.Namespace) -> Any:
    return task_observe(args)


def session_open(args: argparse.Namespace) -> Any:
    body = merge_body(
        args,
        {
            "initiatorType": args.initiator_type,
            "initiatorId": args.initiator_id,
            "sourceNodeId": args.source_node_id,
        },
    )
    return request_json(args, "POST", "/api/skill/session/open", body=body)


def relay_register(args: argparse.Namespace) -> Any:
    body = merge_body(
        args,
        {
            "nodeId": args.node_id,
            "host": args.host,
            "port": args.port,
            "relayEndpoint": args.relay_endpoint,
            "version": args.version,
            "protocolVersion": args.protocol_version,
            "workspaceRoot": args.workspace_root,
            "capabilities": parse_csv(args.capabilities),
            "environmentSummary": json_source(args.environment_json, args.environment_file),
        },
    )
    return request_json(args, "POST", "/api/skill/relay/register", body=body)


def relay_heartbeat(args: argparse.Namespace) -> Any:
    body = merge_body(
        args,
        {
            "nodeId": args.node_id,
            "status": args.status,
            "activeSessions": args.active_sessions,
            "cpuLoad": args.cpu_load,
            "memoryUsage": args.memory_usage,
            "lastTaskTime": args.last_task_time,
            "detail": json_source(args.detail_json, args.detail_file),
            "environmentSummary": json_source(args.environment_json, args.environment_file),
        },
    )
    return request_json(args, "POST", "/api/skill/relay/heartbeat", body=body)


def access_request(args: argparse.Namespace) -> Any:
    body = merge_body(
        args,
        {
            "sessionId": args.session_id,
            "requestId": args.request_id,
            "sourceNodeId": args.source_node_id,
            "targetNodeId": args.target_node_id,
            "reason": args.reason,
            "requiredCapabilities": parse_csv(args.capabilities),
            "ttlMs": args.ttl_ms,
            "parentTaskId": args.parent_task_id,
            "traceId": args.trace_id,
        },
    )
    return request_json(args, "POST", "/api/skill/relay/access/request", body=body)


def access_renew(args: argparse.Namespace) -> Any:
    body = merge_body(args, {"grantId": args.grant_id, "requestId": args.request_id, "ttlMs": args.ttl_ms})
    return request_json(args, "POST", "/api/skill/relay/access/renew", body=body)


def access_revoke(args: argparse.Namespace) -> Any:
    body = merge_body(args, {"grantId": args.grant_id, "reason": args.reason})
    return request_json(args, "POST", "/api/skill/relay/access/revoke", body=body)


def access_validate(args: argparse.Namespace) -> Any:
    return request_json(args, "POST", "/api/skill/relay/access/validate", body=grant_validate_body(args))


def config_get(args: argparse.Namespace) -> Any:
    return sanitize_runtime_config_response(
        request_json(args, "GET", f"/api/skill/config/{quote_path(args.key)}")
    )


def config_list(args: argparse.Namespace) -> Any:
    return sanitize_runtime_config_response(
        request_json(args, "GET", "/api/skill/config", query=compact_dict({"prefix": args.prefix}))
    )


def config_set(args: argparse.Namespace) -> Any:
    body = {
        "key": args.key,
        "value": read_text_arg(args.value, args.value_file),
        "operatorId": args.operator_id,
        "comment": args.comment,
    }
    return sanitize_runtime_config_response(request_json(args, "POST", "/api/skill/config", body=body))


def config_secret_get(args: argparse.Namespace) -> Any:
    return sanitize_runtime_config_response(
        request_json(args, "GET", f"/api/skill/config/{quote_path(args.key)}")
    )


def config_secret_set(args: argparse.Namespace) -> Any:
    body = {
        "key": args.key,
        "value": read_text_arg(args.value, args.value_file),
        "operatorId": args.operator_id,
        "comment": args.comment,
    }
    return sanitize_runtime_config_response(request_json(args, "POST", "/api/skill/config/secret", body=body))


def config_unset(args: argparse.Namespace) -> Any:
    body = {
        "key": args.key,
        "operatorId": args.operator_id,
        "comment": args.comment,
    }
    return sanitize_runtime_config_response(request_json(args, "POST", "/api/skill/config/unset", body=body))


def config_reload(args: argparse.Namespace) -> Any:
    return sanitize_runtime_config_response(
        request_json(args, "POST", "/api/skill/config/reload", query=compact_dict({"operatorId": args.operator_id}))
    )


def config_history(args: argparse.Namespace) -> Any:
    return sanitize_runtime_config_response(
        request_json(
            args,
            "GET",
            f"/api/skill/config/history/{quote_path(args.key)}",
            query=compact_dict({"limit": args.limit}),
        )
    )


def ssh_config_set_default(args: argparse.Namespace) -> Any:
    command_args = ssh_config_command_args(
        args,
        ["ssh", "config", "set-default", "--username", args.username, "--port", str(args.port)],
    )
    if args.launch_secure_terminal:
        return ccrelay_ssh.launch_secure_terminal(command_args)
    password = read_ssh_password(
        args,
        command_args,
        "DEFAULT",
    )
    return ccrelay_ssh.set_default_credential(
        args.username,
        password,
        args.port,
        args.enable_passwordless,
        args.allow_cluster_mutual,
        args.remote_directory,
        args.ssh_arguments_mode,
        args.ssh_argument,
    )


def ssh_config_set_passwordless_default(args: argparse.Namespace) -> Any:
    return ccrelay_ssh.set_passwordless_default(
        args.username,
        args.port,
        args.private_key_file,
        args.allow_cluster_mutual,
        args.remote_directory,
        args.ssh_arguments_mode,
        args.ssh_argument,
    )


def ssh_config_set_node(args: argparse.Namespace) -> Any:
    command_args = ssh_config_command_args(
        args,
        ["ssh", "config", "set-node", "--host", args.host, "--username", args.username, "--port", str(args.port)],
    )
    if args.launch_secure_terminal:
        return ccrelay_ssh.launch_secure_terminal(command_args)
    password = read_ssh_password(
        args,
        command_args,
        "NODE",
        args.host,
        args.port,
    )
    return ccrelay_ssh.set_node_credential(
        args.host,
        args.username,
        password,
        args.port,
        args.enable_passwordless,
        args.allow_cluster_mutual,
        args.remote_directory,
        args.ssh_arguments_mode,
        args.ssh_argument,
    )


def ssh_config_command_args(args: argparse.Namespace, command: List[str]) -> List[str]:
    result = list(command)
    if getattr(args, "ssh_arguments_mode", ccrelay_ssh.SSH_ARGUMENT_MODE_DEFAULT) != ccrelay_ssh.SSH_ARGUMENT_MODE_DEFAULT:
        result.extend(["--ssh-arguments-mode", args.ssh_arguments_mode])
    for value in getattr(args, "ssh_argument", []) or []:
        result.append("--ssh-argument=" + str(value))
    return result


def read_ssh_password(
    args: argparse.Namespace,
    command_args: List[str],
    scope: str,
    host: Optional[str] = None,
    port: Optional[int] = None,
) -> str:
    try:
        prompt = f"{host}:{port} SSH 密码: " if host and port else "通用 SSH 密码: "
        return ccrelay_ssh.read_password_input(
            args.password_file,
            args.password_env,
            prompt,
            prompt_password=args.prompt_password,
        )
    except ccrelay_ssh.SshCredentialInputRequired:
        raise ccrelay_ssh.SshCredentialInputRequired(
            ccrelay_ssh.credential_input_interaction(command_args, scope, host, port)
        )


def ssh_identity_status(args: argparse.Namespace) -> Any:
    local = ccrelay_identity.local_status(args.cluster_id)
    try:
        remote = request_json(args, "GET", "/api/skill/ssh/identity",
                              query={"clusterId": args.cluster_id})
    except urllib.error.HTTPError as exc:
        remote = {"available": False, "failureType": "CENTER_IDENTITY_ENDPOINT_UNAVAILABLE",
                  "statusCode": exc.code}
    return {"local": local, "center": remote}


def bootstrap_next(args: argparse.Namespace) -> Any:
    bootstrap_state = ccrelay_bootstrap.get_stage(args.cluster_id)
    if bootstrap_state["stage"] == ccrelay_bootstrap.DEPLOYED:
        return {
            **bootstrap_state,
            "status": "READY",
            "nextAction": "USE_REGISTERED_RELAY",
            "taskCreated": False,
        }
    resolve_identity_target_args(args, initialize=True)
    result = ssh_identity_plan(args)
    if isinstance(result, dict):
        result["bootstrapStage"] = bootstrap_state["stage"]
    return result


def bootstrap_mark_deployed(args: argparse.Namespace) -> Any:
    current = ccrelay_bootstrap.get_stage(args.cluster_id)
    if current["stage"] == ccrelay_bootstrap.UNINITIALIZED:
        return {
            **current,
            "status": "NEED_ACTION",
            "failureType": "SSH_READY_REQUIRED",
            "taskCreated": False,
            "resume": "先完成 SSH 免密初始化和验证，再标记完整部署。",
        }
    if current["stage"] == ccrelay_bootstrap.DEPLOYED:
        return {**current, "status": "DEPLOYED", "changed": False}
    updated = ccrelay_bootstrap.set_stage(args.cluster_id, ccrelay_bootstrap.DEPLOYED)
    return {**updated, "status": "DEPLOYED", "changed": True}


def identity_capability_is_ready(state: Dict[str, Any]) -> bool:
    capability = state.get("effectiveCapability")
    if state.get("dedicatedAccountCreationAllowed"):
        return capability == "FULL_MESH"
    return capability in {"CENTER_ONLY", "FULL_MESH"}


def failure_governance_hint(failure_type: Any, failure_stage: Any) -> str:
    text = f"{failure_type or ''} {failure_stage or ''}".upper()
    if "AUTH" in text or "CREDENTIAL" in text:
        return "为该节点配置可用的独立 SSH 凭据后重新验证"
    if "PERMISSION" in text or "ACCOUNT" in text or "SUDO" in text:
        return "提供具备所需权限的节点级账号，或修正远端账号策略后重新执行"
    if "DISK" in text or "SPACE" in text:
        return "释放或扩容远端磁盘空间后重新部署该节点"
    if "NETWORK" in text or "TIMEOUT" in text or "UNREACHABLE" in text:
        return "恢复节点网络、SSH 端口或防火墙连通性后重新验证"
    if "TOOL" in text or "COMMAND" in text:
        return "补齐远端缺失的基础命令后重新执行"
    if "TRANSFER" in text or "ARTIFACT" in text or "PACKAGE" in text:
        return "检查远端目录权限、磁盘空间和传输链路后重新部署该节点"
    if "REGISTER" in text or "HEALTH" in text or "START" in text:
        return "检查 Relay 启动日志、端口和 Center 可达性后重新验收该节点"
    return "查看该节点错误详情，修正原因后仅重新执行失败步骤"


def partial_failure_interaction(failures: List[Dict[str, Any]], total_count: int,
                                success_count: int, scope: str) -> Dict[str, Any]:
    normalized = []
    for failure in failures:
        item = dict(failure)
        item.setdefault("nodeKey", item.get("targetNodeId") or item.get("node") or "unknown")
        item.setdefault("failureStage", "UNKNOWN")
        item.setdefault("failureType", "UNKNOWN_FAILURE")
        item.setdefault("summary", "当前步骤未完成")
        item["governanceHint"] = failure_governance_hint(
            item.get("failureType"), item.get("failureStage"))
        normalized.append(item)
    failed_nodes = list(dict.fromkeys(str(item["nodeKey"]) for item in normalized))
    return identity_interaction_payload({
        "status": "PARTIAL_FAILURE_REQUIRES_DECISION",
        "stage": "PARTIAL_FAILURE_REQUIRES_DECISION",
        "failureType": "PARTIAL_FAILURE_REQUIRES_DECISION",
        "taskCreated": False,
        "prompt": "部分节点未完成当前步骤。请选择后续处理方式：",
        "options": [
            {"id": "GOVERN_FAILED_NODES", "label": "根据失败原因处理失败节点（推荐）", "recommended": True},
            {"id": "EXCLUDE_FAILED_NODES", "label": "放弃失败节点，仅使用成功节点", "recommended": False,
             "command": "<CLI> ssh identity targets exclude --node <host:port> --confirm true"},
            {"id": "CANCEL", "label": "取消", "recommended": False},
        ],
        "fields": [],
        "scope": scope,
        "totalNodeCount": total_count,
        "successNodeCount": success_count,
        "failedNodeCount": len(failed_nodes),
        "failedNodes": failed_nodes,
        "failureReport": normalized,
        "resume": "选择治理时只处理失败节点并重新验收完整 active 节点集合；选择放弃时必须显式排除失败节点，再重新验收剩余节点。",
    })


def identity_failure_decision(state: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    failures = ccrelay_identity.identity_failure_records(state)
    if not failures and identity_capability_is_ready(state):
        return None
    total_count = int(state.get("targetNodeCount") or len(state.get("nodes") or []))
    failed_nodes = {item.get("nodeKey") for item in failures}
    success_count = max(0, total_count - len(failed_nodes))
    return partial_failure_interaction(failures, total_count, success_count, "SSH_IDENTITY")


def ssh_identity_plan(args: argparse.Namespace) -> Any:
    resolve_identity_target_args(args, initialize=True)
    config = ccrelay_ssh.load_config()
    if not config.get("default"):
        return identity_credentials_interaction(args, config)
    credential_gate = validate_identity_credentials(args, config)
    if credential_gate:
        return credential_gate
    allow_create = resolve_identity_mode(args)
    if allow_create is None:
        return identity_mode_interaction(config)
    dedicated_username, directory_template = resolve_identity_details(args, config)
    if allow_create and identity_details_confirmation_required(
            args, config, dedicated_username, directory_template):
        return identity_details_interaction(args, config, dedicated_username, directory_template)
    model_gate = ccrelay_center.model_config_bootstrap_gate()
    if not model_gate.get("ready"):
        model_gate["blockedCommand"] = "ssh identity plan"
        model_gate["resume"] = (
            "模型配置完成并通过测试后，重新执行原 ssh identity plan 命令；"
            "只有下一次调用才展示自动执行或逐步检视选项。"
        )
        return model_gate
    execution_mode = resolve_bootstrap_execution_mode(args)
    if execution_mode is None:
        return bootstrap_execution_mode_interaction()
    if not args.center_node:
        return center_selection_required_interaction(args, execution_mode)
    return ccrelay_identity.plan(
        args.cluster_id,
        args.center_node,
        args.nodes,
        allow_create,
        dedicated_username,
        args.connect_timeout,
        directory_template,
        getattr(args, "concurrency", ccrelay_identity.DEFAULT_SSH_CONCURRENCY),
    )


def ssh_identity_apply(args: argparse.Namespace) -> Any:
    resolve_identity_target_args(args, initialize=True)
    config = ccrelay_ssh.load_config()
    if not config.get("default"):
        return identity_credentials_interaction(args, config)
    credential_gate = validate_identity_credentials(args, config)
    if credential_gate:
        return credential_gate
    allow_create = resolve_identity_mode(args)
    if allow_create is None:
        return identity_mode_interaction(config)
    dedicated_username, directory_template = resolve_identity_details(args, config)
    if allow_create and identity_details_confirmation_required(
            args, config, dedicated_username, directory_template):
        return identity_details_interaction(args, config, dedicated_username, directory_template)
    model_gate = ccrelay_center.model_config_bootstrap_gate()
    if not model_gate.get("ready"):
        model_gate["blockedCommand"] = "ssh identity apply"
        model_gate["resume"] = (
            "模型配置完成并通过测试后，重新执行原 ssh identity apply 命令；"
            "只有下一次调用才允许执行远端变更。"
        )
        return model_gate
    execution_mode = resolve_bootstrap_execution_mode(args)
    if execution_mode is None:
        return bootstrap_execution_mode_interaction()
    result = ccrelay_identity.apply(
        args.cluster_id,
        args.center_node,
        args.nodes,
        allow_create,
        dedicated_username,
        args.connect_timeout,
        remote_directory_template=directory_template,
        concurrency=getattr(args, "concurrency", ccrelay_identity.DEFAULT_SSH_CONCURRENCY),
    )
    result["bootstrapExecutionMode"] = execution_mode
    if result.get("effectiveCapability"):
        result["persistence"] = persist_identity_state(args, result, "APPLY")
        decision = identity_failure_decision(result)
        if decision is not None:
            decision["identityResult"] = result
            decision["bootstrapExecutionMode"] = execution_mode
            return decision
    if result.get("effectiveCapability"):
        result["nextStage"] = {
            "stage": "CENTER_BOOTSTRAP_REQUIRED",
            "executionMode": execution_mode,
            "automatic": execution_mode == "AUTO_EXECUTE_REMAINING",
            "requiredBeforeDeploy": True,
        }
    if identity_capability_is_ready(result):
        result["bootstrapStage"] = ccrelay_bootstrap.set_stage(
            args.cluster_id, ccrelay_bootstrap.SSH_READY)["stage"]
    return result


def ssh_identity_verify(args: argparse.Namespace) -> Any:
    resolve_identity_target_args(args, initialize=True)
    config = ccrelay_ssh.load_config()
    if not config.get("default"):
        return identity_credentials_interaction(args, config)
    credential_gate = validate_identity_credentials(args, config)
    if credential_gate:
        return credential_gate
    allow_create = resolve_identity_mode(args)
    if allow_create is None:
        return identity_mode_interaction(config)
    dedicated_username, _directory_template = resolve_identity_details(args, config)
    result = ccrelay_identity.verify(
        args.cluster_id,
        args.center_node,
        ccrelay_identity.normalize_nodes(args.nodes, args.center_node),
        allow_create,
        dedicated_username,
        args.connect_timeout,
        concurrency=getattr(args, "concurrency", ccrelay_identity.DEFAULT_SSH_CONCURRENCY),
        expected_target_nodes=ccrelay_identity.normalize_nodes(args.nodes),
    )
    ccrelay_identity.persist_identity_outcome(result)
    result["persistence"] = persist_identity_state(args, result, "VERIFY")
    decision = identity_failure_decision(result)
    if decision is not None:
        decision["identityResult"] = result
        return decision
    if identity_capability_is_ready(result):
        result["bootstrapStage"] = ccrelay_bootstrap.set_stage(
            args.cluster_id, ccrelay_bootstrap.SSH_READY)["stage"]
    return result


def ssh_identity_select_mode(args: argparse.Namespace) -> Any:
    resolve_identity_target_args(args, initialize=True)
    config = ccrelay_ssh.load_config()
    if not config.get("default"):
        return identity_credentials_interaction(args, config)
    credential_gate = validate_identity_credentials(args, config)
    if credential_gate:
        return credential_gate
    if args.allow_create is None:
        return identity_mode_interaction(config)
    allow_create = bool(args.allow_create)
    dedicated_username, directory_template = resolve_identity_details(args, config)
    identity = config.setdefault("clusterIdentity", {})
    identity.update({
        "clusterId": args.cluster_id,
        "selectionRequired": False,
        "accountMode": "DEDICATED_PENDING" if allow_create else "EXISTING_ACCOUNT",
        "dedicatedAccountCreationAllowed": allow_create,
    })
    dedicated = identity.setdefault("dedicatedAccount", {})
    dedicated.update({
        "username": dedicated_username,
        "status": "PENDING" if allow_create else "DISABLED",
        "detailsConfirmed": bool(args.confirm_details) if allow_create else False,
    })
    config.setdefault("runtime", {})["remoteDirectoryTemplate"] = directory_template
    ccrelay_ssh.save_config(config)
    if allow_create and not args.confirm_details:
        return identity_details_interaction(args, config, dedicated_username, directory_template)
    return {
        "success": True,
        "status": "IDENTITY_MODE_SELECTED",
        "taskCreated": False,
        "accountMode": identity["accountMode"],
        "dedicatedAccountCreationAllowed": allow_create,
        "dedicatedUsername": dedicated_username,
        "local": identity_policy_summary(config),
        "nextStage": "SSH_IDENTITY_APPLY",
    }


def ssh_identity_rotate_key(args: argparse.Namespace) -> Any:
    resolve_identity_target_args(args, initialize=True)
    config = ccrelay_ssh.load_config()
    if not config.get("default"):
        return identity_credentials_interaction(args, config)
    credential_gate = validate_identity_credentials(args, config)
    if credential_gate:
        return credential_gate
    allow_create = resolve_identity_mode(args)
    if allow_create is None:
        return identity_mode_interaction(config)
    dedicated_username, directory_template = resolve_identity_details(args, config)
    if allow_create and identity_details_confirmation_required(
            args, config, dedicated_username, directory_template):
        return identity_details_interaction(args, config, dedicated_username, directory_template)
    result = ccrelay_identity.apply(
        args.cluster_id,
        args.center_node,
        args.nodes,
        allow_create,
        dedicated_username,
        args.connect_timeout,
        rotate_key=True,
        remote_directory_template=directory_template,
        concurrency=getattr(args, "concurrency", ccrelay_identity.DEFAULT_SSH_CONCURRENCY),
    )
    if result.get("effectiveCapability"):
        result["persistence"] = persist_identity_state(args, result, "ROTATE_KEY")
    return result


def resolve_identity_mode(args: argparse.Namespace) -> Optional[bool]:
    if args.allow_create is not None:
        return bool(args.allow_create)
    config = ccrelay_ssh.load_config()
    identity = config.get("clusterIdentity") or {}
    threshold = max(1, int(identity.get("fullMeshThreshold", ccrelay_ssh.DEFAULT_CLUSTER_FULL_MESH_THRESHOLD)))
    target_count = len(getattr(args, "nodes", []) or identity.get("targetNodes") or [])
    if target_count > threshold:
        return False
    if bool(identity.get("selectionRequired", False)):
        return None
    if "dedicatedAccountCreationAllowed" in identity:
        return bool(identity.get("dedicatedAccountCreationAllowed"))
    return None


def identity_interaction_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    payload["agentAction"] = "RETURN_VERBATIM_RESPONSE_AND_STOP"
    payload["mustStopCurrentTurn"] = True
    payload["interaction"] = {
        "options": payload.get("options") or [],
        "fields": payload.get("fields") or [],
    }
    payload["verbatimResponse"] = render_identity_interaction(payload)
    return payload


def render_identity_interaction(payload: Dict[str, Any]) -> str:
    stage = payload.get("stage")
    if stage == "PARTIAL_FAILURE_REQUIRES_DECISION":
        lines = [
            "部分节点未完成当前步骤。",
            "",
            f"成功节点: {payload.get('successNodeCount', 0)}/{payload.get('totalNodeCount', 0)}",
            f"失败节点: {payload.get('failedNodeCount', 0)}/{payload.get('totalNodeCount', 0)}",
            "",
        ]
        for failure in payload.get("failureReport") or []:
            lines.extend([
                f"- {failure.get('nodeKey') or 'unknown'}",
                f"  阶段: {failure.get('failureStage') or 'UNKNOWN'}",
                f"  原因: {failure.get('summary') or failure.get('failureType') or '当前步骤未完成'}",
                f"  建议: {failure.get('governanceHint') or '修正原因后重新执行失败步骤'}",
            ])
        lines.extend(["", "请选择：", ""])
        for index, option in enumerate(payload.get("options") or [], start=1):
            lines.append(f"{index}. {option.get('label')}")
        lines.extend(["", "请回复选项序号。"])
        return "\n".join(lines)
    if stage == "DEDICATED_ACCOUNT_DETAILS_CONFIRMATION":
        summary = payload.get("summary") or {}
        lines = [
            "即将创建以下专用账号：",
            "",
            f"账号: {summary.get('dedicatedUsername') or 'ccrelay'}",
            "部署目录: 按节点和 Relay 端口自动生成",
            "节点间免密: 开启",
            "端口: 部署时自动选择",
            "",
        ]
        for index, option in enumerate(payload.get("options") or [], start=1):
            lines.append(f"{index}. {option.get('label')}")
        lines.extend(["", "请回复选项序号。"])
        return "\n".join(lines)
    lines = [str(payload.get("prompt") or "请选择下一步操作。"), ""]
    initial_credentials = stage == "SSH_CREDENTIALS_REQUIRED"
    for index, option in enumerate(payload.get("options") or [], start=1):
        lines.append(f"{index}. {option.get('label')}")
        if initial_credentials:
            continue
        for key, label in (
                ("applicability", "适用场景"),
                ("securityWarning", "风险警告"),
                ("command", "命令")):
            value = option.get(key)
            if value:
                lines.append(f"  {label}: {value}")
    if initial_credentials:
        lines.extend(["", "请回复选项序号。选定后再填写对应信息。"])
        return "\n".join(lines)
    fields = payload.get("fields") or []
    if fields:
        lines.extend(["", "需要填写："])
        for field in fields:
            required = "必填" if field.get("required") else "可选"
            lines.append(f"- {field.get('label')}（{required}）")
            if field.get("hint"):
                lines.append(f"  说明: {field['hint']}")
    return "\n".join(lines)


def identity_mode_interaction(config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    return identity_interaction_payload({
        "status": "NEED_USER_INPUT",
        "stage": "SSH_ACCOUNT_MODE_REQUIRED",
        "taskCreated": False,
        "prompt": "SSH 访问验证已通过。请选择 Relay 的运行账号：",
        "options": [
            {"id": "ALLOW_DEDICATED_ACCOUNT", "label": "创建 Skill 专用账号（推荐）", "recommended": True},
            {"id": "USE_EXISTING_ACCOUNT", "label": "使用当前 SSH 账号", "recommended": False},
            {"id": "CANCEL", "label": "取消", "recommended": False},
        ],
        "fields": [],
        "currentPolicy": identity_policy_summary(config or ccrelay_ssh.load_config()),
    })


def identity_credentials_interaction(args: argparse.Namespace, config: Dict[str, Any]) -> Dict[str, Any]:
    nodes = ccrelay_identity.normalize_nodes(args.nodes, args.center_node)
    node_defaults = [
        {"node": node["nodeKey"], "useDefault": True, "username": None, "password": None,
         "port": node["port"]}
        for node in nodes
    ]
    return identity_interaction_payload({
        "status": "NEED_USER_INPUT",
        "stage": "SSH_CREDENTIALS_REQUIRED",
        "taskCreated": False,
        "prompt": "当前尚未配置通用 SSH 访问方式。请选择：",
        "options": [
            {"id": "CONFIGURE_DEFAULT_AND_NODE_OVERRIDES", "label": "配置通用凭据，并按需补充节点独立凭据（推荐）", "recommended": True},
            {"id": "USE_EXISTING_PASSWORDLESS_ACCOUNT", "label": "使用已有的免密通用账号", "recommended": False,
             "command": "<CLI> ssh config set-passwordless-default --username <user> --port 22"},
            {"id": "CONFIGURE_DEFAULT_ONLY", "label": "仅配置一套通用凭据并测试全部节点", "recommended": False},
            {"id": "CANCEL", "label": "取消", "recommended": False},
        ],
        "fields": [
            {"name": "defaultUsername", "label": "通用 SSH 用户名", "required": True,
             "default": None, "hint": "必须由用户输入，不从本机用户名、节点名称或历史验收数据推断", "secret": False},
            {"name": "defaultPassword", "label": "通用 SSH 密码", "required": True,
             "default": None, "hint": "安全输入，不回显", "secret": True},
            {"name": "defaultSshPort", "label": "通用 SSH 端口", "required": True,
             "default": 22, "secret": False},
            {"name": "saveAsDefault", "label": "保存为 Skill 通用 SSH 凭据", "required": True,
             "default": True, "hint": "保存到当前 Skill 的 .local 配置，后续优先复用", "secret": False},
            {"name": "nodeOverrides", "label": "节点独立 SSH 凭据", "required": False,
             "default": node_defaults, "secret": True,
             "itemFields": ["node", "useDefault", "username", "password", "port"]},
            {"name": "passwordlessUsername", "label": "已有免密 SSH 用户名", "required": False,
             "default": None, "hint": "选择使用已有免密通用账号时必填，不从历史记录推断", "secret": False,
             "visibleWhen": {"optionId": "USE_EXISTING_PASSWORDLESS_ACCOUNT"}},
            {"name": "passwordlessSshPort", "label": "已有免密 SSH 端口", "required": False,
             "default": 22, "secret": False,
             "visibleWhen": {"optionId": "USE_EXISTING_PASSWORDLESS_ACCOUNT"}},
            {"name": "passwordlessPrivateKeyPath", "label": "SSH 私钥路径", "required": False,
             "default": None, "hint": "可选；为空时使用系统 SSH config、Agent 或默认密钥", "secret": True,
             "visibleWhen": {"optionId": "USE_EXISTING_PASSWORDLESS_ACCOUNT"}},
        ],
        "currentPolicy": identity_policy_summary(config),
        "resume": "保存所选通用访问方式后立即只读测试全部节点；失败节点再保存 ip:port 级独立凭据，然后重新执行 ssh identity plan。",
    })


def validate_identity_credentials(args: argparse.Namespace, config: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    nodes = ccrelay_identity.normalize_nodes(args.nodes, args.center_node)
    def probe(node: Dict[str, Any]) -> Dict[str, Any]:
        result = ccrelay_ssh.probe_connection(
            node["host"], node["port"], node.get("username"), args.connect_timeout)
        return {"host": node["host"], "port": node["port"], **result}

    def probe_error(node: Dict[str, Any], exc: Exception) -> Dict[str, Any]:
        return {
            "success": False,
            "host": node["host"],
            "port": node["port"],
            "failureType": "SSH_PROBE_FAILED",
            "summary": str(exc),
        }

    results = ccrelay_identity.parallel_map_ordered(
        nodes,
        probe,
        getattr(args, "concurrency", ccrelay_identity.DEFAULT_SSH_CONCURRENCY),
        probe_error,
    )
    failures = [result for result in results if not result.get("success")]
    if not failures:
        ccrelay_identity.persist_failed_nodes([])
        return None
    node_credentials = config.get("nodes") or {}
    failed_nodes = []
    for result in failures:
        key = ccrelay_ssh.node_key(result.get("host"), result.get("port", 22))
        failed_nodes.append({
            "node": key,
            "credentialScope": result.get("credentialScope") or (
                "NODE" if key in node_credentials else "DEFAULT"),
            "failureType": result.get("failureType") or "SSH_CONNECTION_FAILED",
            "summary": result.get("summary") or "SSH 连接验证失败",
        })
    ccrelay_identity.persist_failed_nodes(failed_nodes)
    authentication_failures = [
        item for item in failed_nodes if item["failureType"] == "AUTHENTICATION_FAILED"
    ]
    connection_failures = [
        item for item in failed_nodes if item["failureType"] != "AUTHENTICATION_FAILED"
    ]
    options = []
    if authentication_failures:
        options.extend([
            {"id": "CONFIGURE_NODE_OVERRIDES", "label": "为认证失败节点配置独立 SSH 凭据（推荐）", "recommended": True},
            {"id": "UPDATE_DEFAULT_CREDENTIAL", "label": "更新通用 SSH 凭据并重测全部节点", "recommended": False},
        ])
    if connection_failures:
        options.extend([
            {"id": "RETRY_UNREACHABLE_NODES", "label": "重试当前不可达节点", "recommended": not authentication_failures},
            {"id": "KEEP_FAILED_NODES", "label": "保留在部署范围，稍后重试", "recommended": False},
            {"id": "EXCLUDE_FAILED_NODES", "label": "确认从部署范围移除所选节点", "recommended": False,
             "command": "<CLI> ssh identity targets exclude --node <host:port> --confirm true"},
        ])
    options.extend([
        {"id": "RETRY_VALIDATION", "label": "不修改配置，重新验证全部节点", "recommended": False},
        {"id": "CANCEL", "label": "取消配置", "recommended": False},
    ])
    fields = []
    if authentication_failures:
        fields.append({
            "name": "nodeOverrides",
            "label": "认证失败节点独立 SSH 凭据",
            "required": True,
            "default": [
                {"node": item["node"], "username": None, "password": None}
                for item in authentication_failures
            ],
            "secret": True,
            "itemFields": ["node", "username", "password"],
        })
    return identity_interaction_payload({
        "status": "NEED_USER_INPUT",
        "stage": "SSH_CREDENTIALS_INVALID",
        "taskCreated": False,
        "prompt": "SSH 凭据尚未通过全部节点验证。请更新通用凭据，或为失败节点配置 ip:port 级独立凭据。",
        "options": options,
        "fields": fields,
        "validation": {
            "allNodesValid": False,
            "nodeCount": len(results),
            "failedNodeCount": len(failures),
            "failedNodes": failed_nodes,
            "authenticationFailureCount": len(authentication_failures),
            "connectionFailureCount": len(connection_failures),
        },
        "currentPolicy": identity_policy_summary(config),
        "resume": "保存凭据后重新执行原 ssh identity 命令；只有全部节点验证通过后才会进入账号模式选择。",
    })


def resolve_identity_target_args(args: argparse.Namespace, initialize: bool) -> List[Dict[str, Any]]:
    nodes = ccrelay_identity.resolve_target_nodes(
        args.cluster_id,
        getattr(args, "nodes", []) or [],
        getattr(args, "center_node", None),
        initialize,
    )
    args.nodes = ccrelay_identity.node_values(nodes)
    return nodes


def resolve_identity_details(args: argparse.Namespace, config: Dict[str, Any]) -> tuple[str, str]:
    identity = config.get("clusterIdentity") or {}
    dedicated = identity.get("dedicatedAccount") or {}
    runtime = config.get("runtime") or {}
    username = args.dedicated_username or dedicated.get("username") or ccrelay_identity.DEFAULT_DEDICATED_USERNAME
    template = args.remote_directory_template or runtime.get(
        "remoteDirectoryTemplate") or ccrelay_identity.DEFAULT_REMOTE_DIRECTORY_TEMPLATE
    return (
        ccrelay_identity.normalize_dedicated_username(username),
        ccrelay_identity.normalize_remote_directory_template(template),
    )


def identity_details_confirmation_required(args: argparse.Namespace, config: Dict[str, Any],
                                           dedicated_username: str, directory_template: str) -> bool:
    if args.confirm_details:
        return False
    identity = config.get("clusterIdentity") or {}
    dedicated = identity.get("dedicatedAccount") or {}
    runtime = config.get("runtime") or {}
    return not (
        bool(dedicated.get("detailsConfirmed", False))
        and dedicated.get("username") == dedicated_username
        and runtime.get("remoteDirectoryTemplate") == directory_template
    )


def identity_details_interaction(args: argparse.Namespace, config: Dict[str, Any],
                                 dedicated_username: str, directory_template: str) -> Dict[str, Any]:
    nodes = ccrelay_identity.normalize_nodes(args.nodes, args.center_node)
    previews = ccrelay_identity.deployment_directory_previews(
        nodes, dedicated_username, directory_template, True)
    return identity_interaction_payload({
        "status": "NEED_USER_INPUT",
        "stage": "DEDICATED_ACCOUNT_DETAILS_CONFIRMATION",
        "taskCreated": False,
        "prompt": "即将使用以下 Skill 专用账号和部署目录。请选择确认、修改或改用现有账号模式。",
        "options": [
            {"id": "CONFIRM_DETAILS", "label": "确认", "recommended": True},
            {"id": "CHANGE_DETAILS", "label": "修改账号或目录", "recommended": False},
            {"id": "CANCEL", "label": "取消", "recommended": False},
        ],
        "summary": {
            "dedicatedUsername": dedicated_username,
            "dedicatedPassword": "由 Skill 随机生成并受保护保存",
            "remoteDirectoryTemplate": directory_template,
            "deploymentDirectories": previews,
            "relayPort": "部署时自动探测、预占并注册",
        },
        "fields": [
            {"name": "dedicatedUsername", "label": "Skill 专用账号名", "required": True,
             "default": dedicated_username, "secret": False},
            {"name": "remoteDirectoryTemplate", "label": "远端部署目录模板", "required": True,
             "default": directory_template, "secret": False,
             "hint": "可用变量: ${runtimeUser}, ${bootstrapUser}, ${productName}, ${host}, ${relayPort}; ${sshUser} 是 ${runtimeUser} 的兼容别名"},
        ],
        "currentPolicy": identity_policy_summary(config),
        "resume": "确认时使用 --confirm-details true；修改时同时传入 --dedicated-username 和 --remote-directory-template 后重新预览。",
    })


def identity_policy_summary(config: Dict[str, Any]) -> Dict[str, Any]:
    identity = config.get("clusterIdentity") or {}
    dedicated = identity.get("dedicatedAccount") or {}
    return {
        "configured": not bool(identity.get("selectionRequired", True)),
        "accountMode": identity.get("accountMode", "EXISTING_ACCOUNT"),
        "dedicatedAccountCreationAllowed": bool(identity.get("dedicatedAccountCreationAllowed", False)),
        "fullMeshThreshold": int(identity.get("fullMeshThreshold", ccrelay_ssh.DEFAULT_CLUSTER_FULL_MESH_THRESHOLD)),
        "dedicatedUsername": dedicated.get("username", ccrelay_identity.DEFAULT_DEDICATED_USERNAME),
        "detailsConfirmed": bool(dedicated.get("detailsConfirmed", False)),
    }


def resolve_bootstrap_execution_mode(args: argparse.Namespace) -> Optional[str]:
    value = getattr(args, "execution_mode", None)
    if value is None or not str(value).strip():
        return None
    normalized = str(value).strip().upper()
    aliases = {
        "AUTO": "AUTO_EXECUTE_REMAINING",
        "AUTO_EXECUTE_REMAINING": "AUTO_EXECUTE_REMAINING",
        "INSPECT": "INSPECT_STEP_BY_STEP",
        "INSPECT_STEP_BY_STEP": "INSPECT_STEP_BY_STEP",
    }
    if normalized not in aliases:
        raise CliError("execution-mode 必须是 AUTO_EXECUTE_REMAINING 或 INSPECT_STEP_BY_STEP")
    return aliases[normalized]


def bootstrap_execution_mode_interaction(identity_state: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    return identity_interaction_payload({
        "status": "NEED_USER_INPUT",
        "stage": "BOOTSTRAP_EXECUTION_MODE_REQUIRED",
        "taskCreated": False,
        "prompt": "环境准备信息已经确认。请选择后续执行方式：",
        "options": [
            {"id": "AUTO_EXECUTE_REMAINING", "label": "自动完成剩余部署（推荐）", "recommended": True,
             "executionMode": "AUTO_EXECUTE_REMAINING"},
            {"id": "INSPECT_STEP_BY_STEP", "label": "逐步检视", "recommended": False,
             "executionMode": "INSPECT_STEP_BY_STEP"},
            {"id": "CANCEL", "label": "取消", "recommended": False},
        ],
        "fields": [],
        "steps": [
            {"id": "RESOURCE_DISCOVERY", "label": "探测节点环境与硬件资源"},
            {"id": "CENTER_SELECTION", "label": "判断本地中心可达性并自动选择中心节点"},
            {"id": "SSH_IDENTITY_INITIALIZATION", "label": "按已确认模式创建专用账号并验证互信"},
            {"id": "REMOTE_CENTER_BOOTSTRAP", "label": "在选中节点启动中心和 Relay sidecar"},
            {"id": "CENTER_STATE_SYNC", "label": "同步身份、策略和集群状态"},
            {"id": "RELAY_DEPLOYMENT", "label": "部署并注册远端 relay"},
            {"id": "CLUSTER_VERIFICATION", "label": "验证健康、授权和协同能力"},
        ],
        "currentCapability": (identity_state or {}).get("effectiveCapability"),
        "resume": "选择后先执行资源探测和中心选择，再将 executionMode 传给 ssh identity apply；不得提前指定中心或修改远端。",
    })


def center_selection_required_interaction(args: argparse.Namespace, execution_mode: str) -> Dict[str, Any]:
    nodes = ccrelay_identity.normalize_nodes(args.nodes)
    return identity_interaction_payload({
        "status": "NEED_ACTION",
        "stage": "CENTER_SELECTION_REQUIRED",
        "taskCreated": False,
        "prompt": "账号策略与执行模式已确认。请先探测节点资源并自动选择 CC center，再执行身份 apply。",
        "options": [
            {"id": "AUTO_SELECT_CENTER", "label": "自动探测并选择中心（推荐）", "recommended": True},
            {"id": "INSPECT_CENTER_CANDIDATES", "label": "先查看中心候选节点", "recommended": False},
            {"id": "MANUAL_CENTER", "label": "手动指定中心", "recommended": False},
            {"id": "CANCEL", "label": "暂不继续，保留当前配置", "recommended": False},
        ],
        "fields": [],
        "executionMode": execution_mode,
        "candidateNodes": [node["nodeKey"] for node in nodes],
        "resume": "先执行 center plan/bootstrap 自动选择中心；拿到 selectedNode 后，再把该节点作为 --center-node 传给 ssh identity apply。",
    })


def persist_identity_state(args: argparse.Namespace, state: Dict[str, Any], operation: str) -> Any:
    nodes = []
    for item in state.get("nodes") or []:
        privilege = item.get("privilegeSummary") or {}
        nodes.append({
            "nodeKey": item.get("nodeKey"),
            "bootstrapCredentialScope": item.get("bootstrapCredentialScope"),
            "bootstrapUsername": item.get("bootstrapUsername"),
            "runtimeUsername": item.get("runtimeUsername"),
            "osType": item.get("osType"),
            "accountStatus": item.get("accountStatus"),
            "keyInstallStatus": item.get("keyInstallStatus"),
            "centerAccessStatus": item.get("centerAccessStatus"),
            "mutualAccessStatus": item.get("mutualAccessStatus"),
            "privilegeSummaryJson": json.dumps(privilege, ensure_ascii=False),
            "lastErrorCode": item.get("lastErrorCode"),
            "lastErrorSummary": item.get("lastErrorSummary"),
            "lastVerifiedTime": state.get("lastVerifiedTime"),
        })
    edges = []
    for edge in state.get("trustEdges") or []:
        edges.append({
            "sourceNodeKey": edge.get("sourceNodeKey"),
            "targetNodeKey": edge.get("targetNodeKey"),
            "runtimeUsername": edge.get("runtimeUsername"),
            "keyFingerprint": edge.get("keyFingerprint"),
            "status": edge.get("status"),
            "latencyMs": edge.get("latencyMs"),
            "lastErrorCode": edge.get("lastErrorCode"),
            "lastErrorSummary": edge.get("lastErrorSummary"),
            "lastVerifiedTime": state.get("lastVerifiedTime"),
        })
    body = {
        "clusterId": state.get("clusterId", args.cluster_id),
        "replaceSnapshot": True,
        "accountMode": state.get("accountMode"),
        "dedicatedAccountCreationAllowed": state.get("dedicatedAccountCreationAllowed"),
        "dedicatedUsername": state.get("dedicatedUsername"),
        "dedicatedAccountStatus": ccrelay_identity.dedicated_account_status(
            state, state.get("accountMode") == "DEDICATED_MANAGED"),
        "clusterKeyMode": state.get("clusterKeyMode"),
        "clusterKeyFingerprint": state.get("clusterKeyFingerprint"),
        "centerNodeId": state.get("centerNodeId"),
        "operation": operation,
        "operatorId": args.operator_id,
        "nodes": nodes,
        "trustEdges": edges,
    }
    try:
        return request_json(args, "POST", "/api/skill/ssh/identity/state", body=body)
    except urllib.error.HTTPError as exc:
        return deferred_identity_persistence(args, operation, body, f"HTTP_{exc.code}")
    except urllib.error.URLError as exc:
        return deferred_identity_persistence(args, operation, body, str(exc.reason))


def deferred_identity_persistence(
    args: argparse.Namespace,
    operation: str,
    body: Dict[str, Any],
    reason: str,
) -> Dict[str, Any]:
    return {
        "status": "DEFERRED",
        "persisted": False,
        "failureType": "CENTER_IDENTITY_SYNC_DEFERRED",
        "summary": "CC center 尚未可用，身份状态已保存在 Skill 本地；中心启动后必须执行 ssh identity verify 完成同步。",
        "reason": reason,
        "centerUrl": args.center,
        "clusterId": body.get("clusterId"),
        "operation": operation,
        "requiredBeforeDeploy": True,
        "resumeCommand": "ssh identity verify",
    }


def ssh_test(args: argparse.Namespace) -> Any:
    return ccrelay_ssh.test_connection(
        args.host,
        args.port,
        args.username,
        args.connect_timeout,
        args.bootstrap_key,
    )


def ssh_preflight(args: argparse.Namespace) -> Any:
    return prepare_center_ssh(args)


def ssh_prepare_center(args: argparse.Namespace) -> Any:
    return prepare_center_ssh(args)


def prepare_center_ssh(args: argparse.Namespace) -> Any:
    config = ccrelay_ssh.load_config()
    dedicated = (config.get("clusterIdentity") or {}).get("dedicatedAccount") or {}
    selected_username = args.username
    dedicated_password = None
    if selected_username and selected_username == dedicated.get("username"):
        try:
            dedicated_password = ccrelay_identity.dedicated_password(
                (config.get("clusterIdentity") or {}).get("clusterId", "default"),
                ccrelay_ssh.node_key(args.host, args.port), False)
        except (KeyError, ccrelay_ssh.SshCredentialError):
            dedicated_password = None
    local_result = ccrelay_ssh.test_connection(
        args.host,
        args.port,
        args.username,
        args.connect_timeout,
        bootstrap_key=False,
        password_override=dedicated_password,
    )
    if not local_result.get("success"):
        local_result["preflight"] = True
        local_result["readyForCenterDeploy"] = False
        return local_result
    center_key = request_json(args, "GET", "/api/skill/ssh/center-key")
    public_key = center_key.get("publicKey") if isinstance(center_key, dict) else None
    if not public_key:
        raise CliError("CC center did not return an SSH public key")
    selected_username = args.username or local_result.get("username")
    bootstrap = ccrelay_ssh.bootstrap_public_key_value(
        args.host,
        args.port,
        selected_username,
        public_key,
        args.connect_timeout,
        password_override=dedicated_password,
    )
    if not bootstrap.get("success"):
        bootstrap["centerKey"] = {"fingerprint": center_key.get("fingerprint"), "algorithm": center_key.get("algorithm")}
        return bootstrap
    center_result = request_json(
        args,
        "POST",
        "/api/skill/ssh/preflight",
        body={
            "host": args.host,
            "port": args.port,
            "username": selected_username,
            "timeoutMs": args.connect_timeout * 1000,
            "sshArguments": ccrelay_ssh.ssh_arguments_for(args.host, args.port),
        },
    )
    success = isinstance(center_result, dict) and center_result.get("status") == "READY"
    return {
        "success": success,
        "status": "READY" if success else "FAILED",
        "readyForCenterDeploy": success,
        "host": args.host,
        "port": args.port,
        "username": selected_username,
        "credentialScope": local_result.get("credentialScope"),
        "centerKey": {"fingerprint": center_key.get("fingerprint"), "algorithm": center_key.get("algorithm")},
        "centerPreflight": center_result,
    }


def deploy_report(args: argparse.Namespace) -> Any:
    body = merge_body(
        args,
        {
            "taskId": args.task_id,
            "sessionId": args.session_id,
            "targetNodeId": args.target_node_id,
            "deployMode": args.deploy_mode,
            "status": args.status,
            "relayEndpoint": args.relay_endpoint,
            "version": args.version,
            "healthPassed": args.health_passed,
            "registered": args.registered,
            "stdoutSummary": args.stdout_summary,
            "stderrSummary": args.stderr_summary,
            "exitCode": args.exit_code,
            "retryable": args.retryable,
        },
    )
    return request_json(args, "POST", "/api/skill/relay/deploy/report", body=body)


def deploy_resume(args: argparse.Namespace) -> Any:
    preflight = prepare_center_ssh(args)
    if not preflight.get("readyForCenterDeploy"):
        return {
            "success": False,
            "status": "NEED_USER_INPUT" if preflight.get("status") == "NEED_USER_INPUT" else "PREFLIGHT_FAILED",
            "taskId": args.task_id,
            "taskResumed": False,
            "preflight": preflight,
        }
    resumed = request_json(args, "POST", f"/api/skill/relay/deploy/{quote_path(args.task_id)}/resume")
    return {
        "success": bool(resumed),
        "status": "RESUMED" if resumed else "RESUME_FAILED",
        "taskId": args.task_id,
        "taskResumed": bool(resumed),
        "preflight": preflight,
    }


def task_create(args: argparse.Namespace) -> Any:
    request_body = body_from_json_options(args)
    session_id = args.session_id or request_body.get("sessionId")
    task_type = args.task_type or request_body.get("taskType")
    deploy_task = str(task_type or "").upper() == "DEPLOY_RELAY"
    if not str(session_id or "").strip() and not deploy_task:
        raise CliError("sessionId is required; use --session-id <sessionId> or provide sessionId in --json/--json-file")
    payload = json_source(args.payload_json, args.payload_file)
    if deploy_task:
        enrich_deploy_payload(args, payload)
        blocked = deploy_registration_preflight(args, payload)
        if blocked is not None:
            return blocked
        blocked = deploy_ssh_preflight(args, payload)
        if blocked is not None:
            return blocked
        if not str(session_id or "").strip():
            session_id = open_deployment_session(args, payload)
    return submit_task_create(
        args, request_body, session_id, task_type, args.target_node_id, payload,
        task_id=args.task_id, request_id=args.request_id, parent_task_id=args.parent_task_id,
        source_node_id=args.source_node_id, timeout_ms=args.timeout_ms,
    )


def submit_task_create(
    args: argparse.Namespace,
    request_body: Dict[str, Any],
    session_id: str,
    task_type: str,
    target_node_id: Optional[str],
    payload: Any,
    *,
    task_id: Optional[str] = None,
    request_id: Optional[str] = None,
    parent_task_id: Optional[str] = None,
    source_node_id: Optional[str] = None,
    timeout_ms: Optional[int] = None,
) -> Any:
    body = dict(request_body)
    body.update(
        compact_dict({
            "taskId": task_id,
            "sessionId": session_id,
            "requestId": request_id,
            "parentTaskId": parent_task_id,
            "taskType": task_type,
            "sourceNodeId": source_node_id,
            "targetNodeId": target_node_id,
            "payload": payload,
            "timeoutMs": timeout_ms,
        })
    )
    return request_json(args, "POST", "/api/skill/tasks/create", body=body)


def normalize_batch_target_node_ids(values: Iterable[str]) -> List[str]:
    targets: List[str] = []
    seen = set()
    for value in values or []:
        for item in str(value).split(","):
            target = item.strip()
            if not target or target in seen:
                continue
            if ":" not in target:
                raise CliError(f"target node id must include relay port: {target}")
            seen.add(target)
            targets.append(target)
    if not targets:
        raise CliError("at least one target node id is required")
    return targets


def _batch_target_preflight(
    args: argparse.Namespace, target_node_id: str, payload: Dict[str, Any],
) -> Optional[Dict[str, Any]]:
    child_args = argparse.Namespace(**vars(args))
    child_args.target_node_id = target_node_id
    try:
        return deploy_ssh_preflight(child_args, payload)
    except Exception as exc:
        return {
            "success": False,
            "status": "PREFLIGHT_FAILED",
            "taskCreated": False,
            "failureType": "SSH_PREFLIGHT_EXCEPTION",
            "summary": str(exc),
        }


def task_create_batch(args: argparse.Namespace) -> Any:
    request_body = body_from_json_options(args)
    task_type = str(args.task_type or request_body.get("taskType") or "DEPLOY_RELAY").upper()
    if task_type != "DEPLOY_RELAY":
        raise CliError("task create-batch only supports --task-type DEPLOY_RELAY")
    target_node_ids = normalize_batch_target_node_ids(args.target_node_ids)
    concurrency = ccrelay_identity.normalize_concurrency(args.concurrency)
    payload_template = json_source(args.payload_json, args.payload_file) or {}
    if not isinstance(payload_template, dict):
        raise CliError("deployment payload must be a JSON object")

    batch_id = str(uuid.uuid4())
    session_id = args.session_id or request_body.get("sessionId")
    prepared: List[Dict[str, Any]] = []
    for index, target_node_id in enumerate(target_node_ids):
        payload = copy.deepcopy(payload_template)
        child_args = argparse.Namespace(**vars(args))
        child_args.target_node_id = target_node_id
        enrich_deploy_payload(child_args, payload)
        prepared.append({
            "index": index,
            "targetNodeId": target_node_id,
            "payload": payload,
        })

    registration_checked = False
    for item in prepared:
        deploy_mode = str(item["payload"].get("deployMode") or "CENTER_DEPLOY").upper()
        if deploy_mode == "SELF_REPLICATE":
            continue
        child_args = argparse.Namespace(**vars(args))
        child_args.target_node_id = item["targetNodeId"]
        blocked = deploy_registration_preflight(child_args, item["payload"])
        args.center = child_args.center
        args.center_configured_by = getattr(child_args, "center_configured_by", getattr(args, "center_configured_by", "BATCH"))
        if blocked is not None:
            return {
                "batchId": batch_id,
                "status": "PREPARATION_FAILED",
                "concurrency": concurrency,
                "targetCount": len(target_node_ids),
                "results": [
                    {"targetNodeId": target, "status": "NOT_SUBMITTED", "reason": blocked}
                    for target in target_node_ids
                ],
            }
        registration_checked = True
        break
    for item in prepared:
        apply_center_endpoints(item["payload"], args.center)

    if not session_id:
        session_id = open_deployment_session(args, payload_template)

    if registration_checked:
        def preflight_result(item: Dict[str, Any]) -> Optional[Dict[str, Any]]:
            mode = str(item["payload"].get("deployMode") or "CENTER_DEPLOY").upper()
            if mode != "CENTER_DEPLOY":
                return None
            return _batch_target_preflight(args, item["targetNodeId"], item["payload"])

        preflight_results = ccrelay_identity.parallel_map_ordered(prepared, preflight_result, concurrency)
    else:
        preflight_results = [None] * len(prepared)

    def create_item(item_and_preflight: Any) -> Dict[str, Any]:
        item, preflight = item_and_preflight
        target_node_id = item["targetNodeId"]
        if preflight is not None:
            return {"targetNodeId": target_node_id, "status": preflight.get("status", "PREFLIGHT_FAILED"),
                    "taskCreated": False, "preflight": preflight}
        task_id = str(uuid.uuid4())
        request_id = f"{args.request_id or batch_id}-{item['index']}"
        try:
            response = submit_task_create(
                args, request_body, session_id, task_type, target_node_id, item["payload"],
                task_id=task_id, request_id=request_id, parent_task_id=args.parent_task_id,
                source_node_id=args.source_node_id, timeout_ms=args.timeout_ms,
            )
            response_task_id = response.get("taskId") if isinstance(response, dict) else None
            return {"targetNodeId": target_node_id, "status": "CREATED", "taskCreated": True,
                    "taskId": response_task_id or task_id, "response": response}
        except Exception as exc:
            return {"targetNodeId": target_node_id, "status": "CREATE_FAILED", "taskCreated": False,
                    "taskId": task_id, "error": str(exc)}

    results = ccrelay_identity.parallel_map_ordered(
        list(zip(prepared, preflight_results)), create_item, concurrency,
    )
    return {
        "batchId": batch_id,
        "sessionId": session_id,
        "taskType": task_type,
        "concurrency": concurrency,
        "targetCount": len(target_node_ids),
        "createdCount": sum(1 for result in results if result.get("taskCreated")),
        "results": results,
    }


def open_deployment_session(args: argparse.Namespace, payload: Any) -> str:
    source_node_id = str(getattr(args, "source_node_id", "") or "").strip()
    if not source_node_id and isinstance(payload, dict):
        source_node_id = str(payload.get("sourceNodeId") or "").strip()
    opened = request_json(
        args,
        "POST",
        "/api/skill/session/open",
        body=compact_dict({
            "initiatorType": "SYSTEM",
            "initiatorId": "ccrelay-deployment",
            "sourceNodeId": source_node_id,
        }),
    )
    session_id = str((opened or {}).get("sessionId") or "").strip() if isinstance(opened, dict) else ""
    if not session_id:
        raise CliError("Center did not return sessionId for DEPLOY_RELAY")
    return session_id


def enrich_deploy_payload(args: argparse.Namespace, payload: Any) -> None:
    """Fill deployment-only fields from center identity, with bootstrap fallback."""
    if not isinstance(payload, dict):
        return
    payload.setdefault("replaceExistingRelay", True)
    target_node_id = str(getattr(args, "target_node_id", "") or "").strip()
    if not target_node_id or ":" not in target_node_id:
        return

    target_host, target_port_text = target_node_id.rsplit(":", 1)
    target_relay_port = int(target_port_text) if target_port_text.isdigit() else None
    config = ccrelay_ssh.load_config()
    identity = config.get("clusterIdentity") or {}
    registered_target = None
    try:
        candidate = request_json(
            args,
            "GET",
            f"/api/skill/relay/nodes/{quote_path(target_node_id)}",
        )
        if isinstance(candidate, dict) and candidate.get("nodeId"):
            registered_target = candidate
    except (urllib.error.HTTPError, urllib.error.URLError, OSError):
        registered_target = None
    registered_relay_host = None
    if registered_target is not None:
        target_relay_port = int(registered_target.get("port") or target_relay_port or 0) or target_relay_port
        relay_endpoint = str(registered_target.get("relayEndpoint") or "").strip()
        if relay_endpoint:
            registered_relay_host = urllib.parse.urlparse(relay_endpoint).hostname
    local_target_state = next(
        (
            item for item in identity.get("managedNodes") or []
            if str(item.get("host") or "").strip().lower() == target_host.lower()
        ),
        {},
    )
    center_identity = None
    try:
        candidate = request_json(
            args,
            "GET",
            "/api/skill/ssh/identity",
            query={"clusterId": "default"},
        )
        if isinstance(candidate, dict) and candidate.get("configured") is True:
            center_identity = candidate
    except (urllib.error.HTTPError, urllib.error.URLError, OSError):
        center_identity = None

    target_state = local_target_state
    runtime_username = None
    if center_identity is not None:
        target_state = next(
            (
                item for item in center_identity.get("nodes") or []
                if str(item.get("nodeKey") or "").split(":", 1)[0].lower() in {
                    target_host.lower(),
                    str(registered_relay_host or "").lower(),
                }
            ),
            {},
        )
        runtime_username = target_state.get("runtimeUsername")
        if not runtime_username:
            runtime_username = center_identity.get("dedicatedUsername")
    if target_state:
        target_key_host = str(target_state.get("nodeKey") or "").split(":", 1)[0].strip()
        if target_key_host:
            target_host = target_key_host
    elif registered_relay_host:
        target_host = registered_relay_host
    elif registered_target is not None:
        target_host = str(registered_target.get("host") or target_host).strip()
    else:
        dedicated = identity.get("dedicatedAccount") or {}
        runtime_username = (
            local_target_state.get("runtimeUsername")
            or (dedicated.get("username") if identity.get("accountMode") == "DEDICATED_MANAGED" else None)
        )
    if not runtime_username:
        default_credential, _scope = ccrelay_ssh.resolve_credential(
            target_host, int(local_target_state.get("port") or 22)
        )
        runtime_username = (default_credential or {}).get("username")

    payload.setdefault("host", target_host)
    if target_relay_port and not any(payload.get(key) for key in ("relayPort", "ccRelayPort", "targetRelayPort")):
        payload["relayPort"] = target_relay_port
    if not any(payload.get(key) for key in ("port", "sshPort", "targetPort")):
        payload["port"] = int(target_state.get("port") or 22)
    if runtime_username and not any(payload.get(key) for key in ("username", "sshUser", "targetUser")):
        payload["username"] = runtime_username


def deploy_registration_preflight(args: argparse.Namespace, payload: Any) -> Optional[Dict[str, Any]]:
    values = payload if isinstance(payload, dict) else {}
    if not (values.get("relayPort") or values.get("ccRelayPort") or values.get("targetRelayPort")):
        target_node_id = str(getattr(args, "target_node_id", "") or "")
        if ":" in target_node_id:
            port_text = target_node_id.rsplit(":", 1)[1]
            if port_text.isdigit() and int(port_text) > 0:
                values["relayPort"] = int(port_text)
    deploy_mode = str(values.get("deployMode") or "CENTER_DEPLOY").upper()
    if deploy_mode == "SELF_REPLICATE":
        apply_center_endpoints(values, args.center)
        return None
    target_host = values.get("host") or values.get("sshHost") or values.get("targetHost")
    if not target_host:
        return None
    center_url = str(args.center or "").rstrip("/")
    center_host = (urllib.parse.urlparse(center_url).hostname or "").lower()
    if center_host not in {"127.0.0.1", "localhost", "::1"}:
        apply_center_endpoints(values, args.center)
        return None
    if center_host in {"127.0.0.1", "localhost", "::1"}:
        planned = ccrelay_center.plan([], payload=values, selection="AUTO", timeout_seconds=15)
        if planned.get("status") != "CENTER_PLAN_READY":
            planned["blockedCommand"] = "task create"
            planned["blockedStage"] = "REMOTE_CENTER_BOOTSTRAP"
            return planned
        bootstrapped = ccrelay_center.bootstrap(
            planned,
            _center_hmac_secret(create=True),
            timeout_seconds=max(900, int(getattr(args, "timeout", 60))),
        )
        if bootstrapped.get("status") != "REMOTE_CENTER_READY":
            bootstrapped["taskCreated"] = False
            bootstrapped["blockedCommand"] = "task create"
            return bootstrapped
        args.center = bootstrapped["centerUrl"]
        args.center_configured_by = "PERSISTED"
        try:
            sync_args = argparse.Namespace(**vars(args))
            sync_args.cluster_id = "default"
            sync_args.operator_id = os.getenv("USERNAME") or os.getenv("USER")
            sync_args.connect_timeout = 15
            bootstrapped["identitySync"] = sync_identity_to_selected_center(sync_args, planned)
        except Exception as exc:
            rollback = ccrelay_center.rollback_bootstrap(bootstrapped)
            return {
                "success": False,
                "status": "CENTER_STATE_SYNC_FAILED",
                "taskCreated": False,
                "failureType": "CENTER_STATE_SYNC_FAILED",
                "summary": str(exc),
                "centerBootstrap": bootstrapped,
                "rollback": rollback,
                "interaction": ccrelay_center.recovery_interaction(),
            }
        bootstrapped["previousLocalCenter"] = center_stop(args)
    apply_center_endpoints(values, args.center)
    return None


def apply_center_endpoints(payload: Dict[str, Any], center_url: str) -> None:
    endpoints = {
        "--wdsavs.ai.remote-cc.relay.center-register-endpoint=": build_url(
            center_url, "/api/skill/relay/register"),
        "--wdsavs.ai.remote-cc.relay.center-heartbeat-endpoint=": build_url(
            center_url, "/api/skill/relay/heartbeat"),
        "--wdsavs.ai.remote-cc.relay.center-grant-validate-endpoint=": build_url(
            center_url, "/api/skill/relay/access/validate"),
    }
    target_host = payload.get("host") or payload.get("sshHost") or payload.get("targetHost")
    relay_port = payload.get("relayPort") or payload.get("ccRelayPort") or payload.get("targetRelayPort")
    if target_host and relay_port:
        endpoints["--wdsavs.ai.remote-cc.relay.relay-endpoint="] = (
            f"http://{target_host}:{relay_port}/api/ai/remote-cc/chat"
        )
    command_arguments = payload.get("commandArguments") or []
    if isinstance(command_arguments, str):
        command_arguments = [command_arguments]
    retained = [
        str(argument) for argument in command_arguments
        if not any(str(argument).startswith(prefix) for prefix in endpoints)
    ]
    retained.extend(prefix + value for prefix, value in endpoints.items())
    payload["commandArguments"] = retained


def deploy_ssh_preflight(args: argparse.Namespace, payload: Any) -> Optional[Dict[str, Any]]:
    deploy_payload = payload if isinstance(payload, dict) else {}
    deploy_mode = str(deploy_payload.get("deployMode") or "CENTER_DEPLOY").upper()
    if deploy_mode == "SELF_REPLICATE":
        # Let the center classify the actual SSH fallback result. A failed
        # self-replication can be caused by archive, network, or script errors.
        return None
    host = deploy_payload.get("host") or deploy_payload.get("sshHost") or deploy_payload.get("targetHost")
    port = deploy_payload.get("port") or deploy_payload.get("sshPort") or deploy_payload.get("targetPort") or 22
    username = deploy_payload.get("username") or deploy_payload.get("sshUser") or deploy_payload.get("targetUser")
    if not host:
        return {
            "success": False,
            "status": "NEED_USER_INPUT",
            "taskCreated": False,
            "failureType": "SSH_TARGET_MISSING",
            "summary": "部署任务启用了中心 SSH 兜底，但 payload 缺少 SSH 主机。",
            "interaction": {
                "prompt": "请补充部署目标 SSH 信息后重新创建任务。",
                "options": [
                    {"id": "PROVIDE_TARGET", "label": "补充目标信息（推荐）", "recommended": True},
                    {"id": "DISABLE_FALLBACK", "label": "关闭中心 SSH 兜底"},
                    {"id": "CANCEL", "label": "取消本次部署"},
                ],
                "fields": [
                    {"name": "host", "label": "目标 IP/主机名", "required": True, "default": None, "secret": False},
                    {"name": "port", "label": "SSH 端口", "required": True, "default": 22, "secret": False},
                    {"name": "username", "label": "SSH 用户名", "required": False, "default": None, "secret": False},
                ],
            },
        }
    preflight_args = argparse.Namespace(**vars(args))
    preflight_args.host = str(host)
    preflight_args.port = int(port)
    preflight_args.username = str(username) if username else None
    preflight_args.connect_timeout = 15
    result = prepare_center_ssh(preflight_args)
    if result.get("readyForCenterDeploy"):
        resolved_username = result.get("username")
        if resolved_username and not username:
            deploy_payload["username"] = resolved_username
        return None
    result["taskCreated"] = False
    result["blockedCommand"] = "task create"
    result["deployMode"] = deploy_mode
    result["enableCenterFallback"] = bool(deploy_payload.get("enableCenterFallback", False))
    return result


def task_cancel(args: argparse.Namespace) -> Any:
    body = merge_body(args, {"reason": args.reason})
    return request_json(args, "POST", f"/api/skill/tasks/{quote_path(args.task_id)}/cancel", body=body)


def ensure_agent_context(args: argparse.Namespace, keep_open: bool = False) -> Dict[str, Any]:
    ensure_a2a_namespace_defaults(args)
    ensure_target_available(args)
    grant = get_existing_agent_grant(args)
    restored_session_id = grant.get("sessionId") if isinstance(grant, dict) else None
    session_owned_by_cli = not bool(args.session_id or restored_session_id)
    session_id = args.session_id or restored_session_id or open_agent_session(args)
    collaboration_state = getattr(args, "_collaboration_state", None)
    if collaboration_state is None and getattr(args, "target_node_id", None):
        collaboration_state = initialize_agent_collaboration(args, session_id, [args.target_node_id])
    if not grant:
        grant = get_or_create_agent_grant(args, session_id)
    if grant.get("decision") and grant.get("decision") != "ALLOW":
        if session_owned_by_cli and not keep_open:
            close_session_by_id(args, session_id)
        raise CliError(f"target agent is not ready: decision={grant.get('decision')}")
    grant_id = args.grant_id or grant.get("grantId")
    if not grant_id:
        raise CliError("grantId is required and could not be created")
    context_args = argparse.Namespace(**vars(args))
    ensure_a2a_namespace_defaults(context_args)
    context_args.session_id = session_id
    context_args.grant_id = grant_id
    fill_missing(context_args, "source_node_id", grant.get("sourceNodeId"))
    fill_missing(context_args, "target_node_id", grant.get("targetNodeId") or args.target_node_id)
    fill_missing(context_args, "signed_token", grant.get("signedToken"))
    fill_missing(context_args, "expires_at", grant.get("expiresAt"))
    complete_a2a_context(context_args)
    return {
        "sessionId": context_args.session_id,
        "grantId": context_args.grant_id,
        "sourceNodeId": context_args.source_node_id,
        "targetNodeId": context_args.target_node_id,
        "signedToken": context_args.signed_token,
        "expiresAt": context_args.expires_at,
        "targetRelayEndpoint": context_args.target_relay_endpoint,
        "centerGrantValidateEndpoint": context_args.center_grant_validate_endpoint,
        "sessionOwnedByCli": session_owned_by_cli,
        "collaborationState": collaboration_state,
    }


def ensure_a2a_namespace_defaults(args: argparse.Namespace) -> None:
    for name in ("target_relay_endpoint", "center_grant_validate_endpoint", "signed_token", "expires_at"):
        if not hasattr(args, name):
            setattr(args, name, None)


def ensure_target_available(args: argparse.Namespace) -> None:
    if not getattr(args, "target_node_id", None):
        return
    scan = request_json(args, "GET", "/api/skill/relay/heartbeat/scan")
    statuses = scan.get("nodeStatuses") if isinstance(scan, dict) else None
    status = statuses.get(args.target_node_id) if isinstance(statuses, dict) else None
    if status and status != "AVAILABLE":
        raise CliError(f"target agent {args.target_node_id} is {status}; recover it through ccrelay-cli before use")


def open_agent_session(args: argparse.Namespace) -> str:
    request = argparse.Namespace(**vars(args))
    request.initiator_type = "CODEX"
    request.initiator_id = "ccrelay-cli-agent"
    session = session_open(request)
    session_id = session.get("sessionId") if isinstance(session, dict) else None
    if not session_id:
        raise CliError("failed to open agent session")
    return session_id


def get_or_create_agent_grant(args: argparse.Namespace, session_id: str) -> Dict[str, Any]:
    request = argparse.Namespace(**vars(args))
    request.session_id = session_id
    request.request_id = None
    request.capabilities = ",".join(DEFAULT_A2A_CAPABILITIES)
    request.ttl_ms = None
    request.parent_task_id = None
    request.trace_id = None
    grant = access_request(request)
    return grant if isinstance(grant, dict) else {}


def get_existing_agent_grant(args: argparse.Namespace) -> Dict[str, Any]:
    if not args.grant_id:
        return {}
    grant = request_json(args, "GET", f"/api/skill/relay/access/{quote_path(args.grant_id)}")
    return grant if isinstance(grant, dict) else {}


def a2a_params_from_context(args: argparse.Namespace, context: Dict[str, Any]) -> Dict[str, Any]:
    params = compact_dict(
        {
            "sessionId": context.get("sessionId"),
            "grantId": context.get("grantId"),
            "signedToken": context.get("signedToken"),
            "sourceNodeId": context.get("sourceNodeId"),
            "targetNodeId": context.get("targetNodeId") or args.target_node_id,
            "targetRelayEndpoint": context.get("targetRelayEndpoint"),
            "centerGrantValidateEndpoint": context.get("centerGrantValidateEndpoint"),
            "expiresAt": context.get("expiresAt"),
        }
    )
    collaboration = context.get("collaborationState")
    if isinstance(collaboration, dict):
        coordinator = collaboration.get("coordinatorNodeId")
        target_node_id = context.get("targetNodeId") or args.target_node_id
        params["metadata"] = compact_dict(
            {
                "collaborationMode": collaboration.get("collaborationMode"),
                "coordinatorNodeId": coordinator,
                "coordinatorEpoch": collaboration.get("coordinatorEpoch"),
                "participantNodeIds": collaboration.get("participantNodeIds"),
                "collaborationPolicy": collaboration.get("collaborationPolicy"),
                "agentRole": "COORDINATOR" if coordinator == target_node_id else "PARTICIPANT",
            }
        )
        params["agentRole"] = params["metadata"]["agentRole"]
    return params


def initialize_agent_collaboration(args: argparse.Namespace, session_id: str, participant_node_ids: List[str]) -> Dict[str, Any]:
    body = {
        "collaborationMode": getattr(args, "collaboration_mode", None),
        "participantNodeIds": participant_node_ids,
        "collaborationPolicy": json_source(getattr(args, "collaboration_policy_json", None), None) or {},
    }
    response = request_json(
        args,
        "POST",
        f"/api/skill/session/{quote_path(session_id)}/collaboration/initialize",
        body=body,
    )
    return response if isinstance(response, dict) else {}


def close_if_needed(args: argparse.Namespace, context: Dict[str, Any]) -> None:
    if context.get("sessionOwnedByCli") and not args.keep_session:
        close_session_by_id(args, context["sessionId"])
        context["sessionClosed"] = True


def close_session_by_id(args: argparse.Namespace, session_id: str) -> Any:
    return request_json(args, "POST", f"/api/skill/session/{quote_path(session_id)}/close")


def agent_run(args: argparse.Namespace) -> Any:
    context = ensure_agent_context(args)
    response = None
    error = None
    center = None
    try:
        params = a2a_params_from_context(args, context)
        params.update(json_source(args.params_json, None) or {})
        params["messages"] = [{"role": "user", "content": args.prompt}]
        params["executionMode"] = args.mode
        params["react"] = react_config(args)
        put_model_config(params, args.model_config_file)
        response = request_json(agent_timeout_args(args), "POST", "/api/skill/a2a/message/send", body=json_rpc(lambda_id("agent-run"), "message/send", params))
        center = center_resolve(args)
    except Exception as exc:
        error = str(exc)
    finally:
        close_if_needed(args, context)
    return compact_dict(
        {
            "center": center,
            "sessionId": context["sessionId"],
            "targetNodeId": args.target_node_id,
            "grantId": context["grantId"],
            "response": response,
            "error": error,
            "sessionClosed": context.get("sessionClosed", False),
        }
    )


def agent_fanout(args: argparse.Namespace) -> Any:
    session_id = args.session_id or open_agent_session(args)
    target_node_ids = parse_node_ids(args.target_node_ids)
    collaboration_state = initialize_agent_collaboration(args, session_id, target_node_ids)
    results = []
    created_tasks = []
    for target_node_id in target_node_ids:
        child_args = argparse.Namespace(**vars(args))
        child_args.session_id = session_id
        child_args.target_node_id = target_node_id
        child_args.keep_session = True
        child_args._collaboration_state = collaboration_state
        try:
            created = agent_task_create(child_args)
            task_id = nested_value(created, ["task", "result", "taskId"])
            task_result = {
                "targetNodeId": target_node_id,
                "fanoutStatus": "CREATED",
                "grantId": created.get("grantId"),
                "taskId": task_id,
                "task": created.get("task"),
            }
            created_tasks.append((child_args, task_result))
            results.append(task_result)
        except Exception as exc:
            results.append({"targetNodeId": target_node_id, "fanoutStatus": "FAILED", "message": str(exc)})
    for child_args, task_result in created_tasks:
        task_result["statusFallback"] = safe_agent_task_get_until(
            child_args,
            task_result.get("taskId"),
            task_result.get("grantId"),
            args.poll_seconds,
        )
    return {
        "sessionId": session_id,
        "fanoutMode": "ASYNC_TASKS_WITH_STATUS_FALLBACK",
        "collaboration": collaboration_state,
        "results": results,
        "sessionClosed": False,
        "nextActions": [
            "使用 agent task-events 读取单个任务事件",
            "使用 agent inject 向运行中任务注入提示词",
            "使用 agent adjust 调整 ReAct 控制参数",
            "使用 agent stop 停止任务",
            "完成协作后使用 session close 关闭会话",
        ],
    }


def agent_task_create(args: argparse.Namespace) -> Any:
    context = ensure_agent_context(args)
    params = a2a_params_from_context(args, context)
    params.update(json_source(args.params_json, None) or {})
    put_if_not_none(params, "requestId", getattr(args, "request_id", None))
    put_if_not_none(params, "taskId", getattr(args, "task_id", None))
    params["input"] = {"prompt": args.prompt}
    params["messages"] = [{"role": "user", "content": args.prompt}]
    params["executionMode"] = args.mode
    params["react"] = react_config(args)
    put_model_config(params, args.model_config_file)
    response = request_json(agent_timeout_args(args), "POST", "/api/skill/a2a/tasks/create", body=json_rpc(lambda_id("agent-task"), "tasks/create", params))
    return {
        "sessionId": context["sessionId"],
        "targetNodeId": args.target_node_id,
        "grantId": context["grantId"],
        "task": response,
        "sessionOwnedByCli": context["sessionOwnedByCli"],
    }


def agent_task_get(args: argparse.Namespace) -> Any:
    context = ensure_agent_context(args, keep_open=True)
    return request_json(
        args,
        "GET",
        f"/api/skill/a2a/tasks/{quote_path(args.task_id)}",
        query=a2a_params_from_context(args, context),
    )


def agent_task_events(args: argparse.Namespace) -> Any:
    context = ensure_agent_context(args, keep_open=True)
    query = a2a_params_from_context(args, context)
    try:
        return stream_response(args, f"/api/skill/a2a/tasks/{quote_path(args.task_id)}/events", query=query)
    except urllib.error.HTTPError as exc:
        fallback = safe_agent_task_status(args, query)
        return {
            "status": "EVENT_STREAM_FAILED_STATUS_QUERY_USED",
            "streamError": f"HTTP {exc.code} {exc.reason}",
            "task": fallback,
        }
    except CliError as exc:
        fallback = safe_agent_task_status(args, query)
        return {
            "status": "EVENT_STREAM_EMPTY_STATUS_QUERY_USED",
            "streamError": str(exc),
            "task": fallback,
        }
    except TimeoutError as exc:
        fallback = safe_agent_task_status(args, query)
        return {
            "status": "EVENT_STREAM_TIMEOUT_STATUS_QUERY_USED",
            "streamError": str(exc),
            "task": fallback,
        }


def agent_stop(args: argparse.Namespace) -> Any:
    context = ensure_agent_context(args, keep_open=True)
    return request_json(
        args,
        "POST",
        f"/api/skill/a2a/tasks/{quote_path(args.task_id)}/cancel",
        query=a2a_params_from_context(args, context),
        body={"reason": args.reason},
    )


def agent_inject(args: argparse.Namespace) -> Any:
    context = ensure_agent_context(args, keep_open=True)
    params = a2a_params_from_context(args, context)
    control = {
        "type": "INTERRUPT",
        "taskId": args.task_id,
        "prompt": args.prompt,
    }
    params["control"] = control
    params["metadata"] = {"control": control}
    params["messages"] = [{"role": "user", "content": args.prompt}]
    return request_json(args, "POST", "/api/skill/a2a/message/send", body=json_rpc(lambda_id("agent-inject"), "message/send", params))


def agent_adjust(args: argparse.Namespace) -> Any:
    context = ensure_agent_context(args, keep_open=True)
    params = a2a_params_from_context(args, context)
    control = {
        "type": "ADJUST",
        "taskId": args.task_id,
        "react": react_config(args),
    }
    params["control"] = control
    params["metadata"] = {"control": control}
    params["messages"] = [{"role": "user", "content": "Adjust running agent control parameters."}]
    return request_json(args, "POST", "/api/skill/a2a/message/send", body=json_rpc(lambda_id("agent-adjust"), "message/send", params))


def safe_agent_task_get_until(args: argparse.Namespace, task_id: Optional[str], grant_id: Optional[str], poll_seconds: float) -> Any:
    if not task_id:
        return {"status": "TASK_ID_MISSING"}
    deadline = time.time() + max(0.0, poll_seconds or 0.0)
    last = None
    while True:
        query_args = argparse.Namespace(**vars(args))
        query_args.task_id = task_id
        query_args.grant_id = grant_id
        try:
            last = agent_task_get(query_args)
        except Exception as exc:
            last = {"status": "STATUS_QUERY_FAILED", "message": str(exc)}
        status = task_status_value(last)
        if status in {"SUCCESS", "SUCCEEDED", "FAILED", "CANCELLED", "ERROR"} or time.time() >= deadline:
            return last
        time.sleep(2)


def task_status_value(value: Any) -> Optional[str]:
    if not isinstance(value, dict):
        return None
    status = value.get("status") or nested_value(value, ["result", "status"]) or nested_value(value, ["task", "status"])
    return str(status).upper() if status else None


def observation_query(args: argparse.Namespace) -> Dict[str, Any]:
    query = compact_dict(
        {
            "taskId": args.task_id,
            "taskIds": parse_node_ids(args.task_ids),
            "parentTaskId": args.parent_task_id,
            "sessionId": args.session_id,
            "targetNodeId": args.target_node_id,
            "sinceSequenceNo": args.since_sequence_no,
            "sinceCreatedTimeMs": args.since_created_time_ms,
            "lastMs": args.last_ms,
            "limit": args.limit,
            "tailLines": args.tail_lines,
            "maxBytes": args.max_bytes,
            "perEventMaxBytes": args.per_event_max_bytes,
            "include": parse_csv(args.include),
            "eventTypes": parse_csv(args.event_types),
            "authorizationScope": args.authorization_scope,
        }
    )
    if not query.get("taskIds"):
        query.pop("taskIds", None)
    if not query.get("include"):
        query.pop("include", None)
    if not query.get("eventTypes"):
        query.pop("eventTypes", None)
    return query


def nested_value(value: Any, path: List[str]) -> Any:
    current = value
    for item in path:
        if not isinstance(current, dict):
            return None
        current = current.get(item)
    return current


def react_config(args: argparse.Namespace) -> Dict[str, Any]:
    return compact_dict(
        {
            "enabled": str(getattr(args, "mode", "ReAct")).lower() == "react",
            "mode": getattr(args, "mode", "ReAct"),
            "maxSteps": getattr(args, "max_steps", None),
            "commandWhitelist": parse_csv(getattr(args, "command_whitelist", "")),
            "stepTimeoutMs": getattr(args, "step_timeout_ms", None),
            "taskTimeoutMs": getattr(args, "task_timeout_ms", None),
            "auditLevel": getattr(args, "audit_level", None),
            "allowAi": getattr(args, "allow_ai", None),
        }
    )


def safe_agent_task_status(args: argparse.Namespace, query: Dict[str, Any]) -> Any:
    fallback_args = argparse.Namespace(**vars(args))
    fallback_args.timeout = max(float(getattr(args, "timeout", 0) or 0), 30.0)
    try:
        return request_json(
            fallback_args,
            "GET",
            f"/api/skill/a2a/tasks/{quote_path(args.task_id)}",
            query=query,
        )
    except Exception as exc:
        return {
            "status": "STATUS_QUERY_FAILED",
            "message": str(exc),
        }


def agent_timeout_args(args: argparse.Namespace) -> argparse.Namespace:
    updated = argparse.Namespace(**vars(args))
    updated.timeout = max(float(getattr(args, "timeout", 0) or 0), 180.0)
    return updated


def a2a_message_send(args: argparse.Namespace) -> Any:
    body = body_from_json_options(args)
    if body:
        return request_json(args, "POST", "/api/skill/a2a/message/send", body=body)
    params = a2a_params(args)
    params.update(json_source(args.params_json, None) or {})
    if args.messages_json:
        params["messages"] = read_json_arg(args.messages_json)
    elif args.prompt:
        params["messages"] = [{"role": "user", "content": args.prompt}]
    put_model_config(params, args.model_config_file)
    return request_json(args, "POST", "/api/skill/a2a/message/send", body=json_rpc(args.id, "message/send", params))


def a2a_task_create(args: argparse.Namespace) -> Any:
    body = body_from_json_options(args)
    if body:
        return request_json(args, "POST", "/api/skill/a2a/tasks/create", body=body)
    params = a2a_params(args)
    params.update(json_source(args.params_json, None) or {})
    put_if_not_none(params, "requestId", args.request_id)
    put_if_not_none(params, "taskId", args.task_id)
    if args.input_json:
        params["input"] = read_json_arg(args.input_json)
    elif args.prompt:
        params["input"] = {"prompt": args.prompt}
    put_model_config(params, args.model_config_file)
    return request_json(args, "POST", "/api/skill/a2a/tasks/create", body=json_rpc(args.id, "tasks/create", params))


def remote_chat(args: argparse.Namespace) -> Any:
    body = body_from_json_options(args)
    if not body:
        body = {"messages": [{"role": "user", "content": args.prompt}]} if args.prompt else {}
        put_model_config(body, args.model_config_file)
    return request_json(args, "POST", "/api/ai/remote-cc/chat", body=body, base=args.relay)


def remote_self_replicate(args: argparse.Namespace) -> Any:
    body = merge_body(
        args,
        {
            "taskId": args.task_id,
            "sessionId": args.session_id,
            "sourceNodeId": args.source_node_id,
            "targetNodeId": args.target_node_id,
            "deployMode": "SELF_REPLICATE",
            "host": args.host,
            "port": args.port,
            "username": args.username,
            "relayPort": args.relay_port,
            "scriptPath": args.script_path,
            "artifactPath": args.artifact_path,
            "remoteDirectory": args.remote_directory,
            "timeoutMs": args.timeout_ms,
            "artifactVersion": args.artifact_version,
            "commandArguments": parse_csv(args.command_arguments),
        },
    )
    return request_json(args, "POST", "/internal/deploy/self-replicate", body=body, base=args.relay)


def request_json(
    args: argparse.Namespace,
    method: str,
    path: str,
    body: Any = None,
    query: Optional[Dict[str, Any]] = None,
    base: Optional[str] = None,
) -> Any:
    text = request_text(args, method, path, body=body, query=query, base=base)
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def stream_response(
    args: argparse.Namespace,
    path: str,
    query: Optional[Dict[str, Any]] = None,
    base: Optional[str] = None,
) -> None:
    url = build_url(base or args.center, path, query)
    request = urllib.request.Request(url, method="GET")
    line_count = 0
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            for raw_line in response:
                line_count += 1
                print(raw_line.decode("utf-8", errors="replace"), end="")
    except TimeoutError:
        if line_count > 0:
            return None
        raise CliError("stream timed out before receiving any event")
    return None


def request_text(
    args: argparse.Namespace,
    method: str,
    path: str,
    body: Any = None,
    query: Optional[Dict[str, Any]] = None,
    base: Optional[str] = None,
) -> str:
    url = build_url(base or args.center, path, query)
    data = None
    headers = {"Accept": "application/json, text/event-stream, text/plain"}
    if body is not None:
        data = json.dumps(compact_dict(body), ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=args.timeout) as response:
        return response.read().decode("utf-8", errors="replace")


def build_url(base: str, path: str, query: Optional[Dict[str, Any]] = None) -> str:
    normalized_base = base.rstrip("/")
    normalized_path = path if path.startswith("/") else f"/{path}"
    url = f"{normalized_base}{normalized_path}"
    clean_query = compact_dict(query or {})
    if clean_query:
        url += "?" + urllib.parse.urlencode(clean_query, doseq=True)
    return url


def body_from_json_options(args: argparse.Namespace) -> Dict[str, Any]:
    body: Dict[str, Any] = {}
    json_file = getattr(args, "json_file", None)
    json_text = getattr(args, "json", None)
    if json_file:
        body.update(read_json_file(json_file))
    if json_text:
        body.update(read_json_arg(json_text))
    return body


def merge_body(args: argparse.Namespace, values: Dict[str, Any]) -> Dict[str, Any]:
    body = body_from_json_options(args)
    body.update(compact_dict(values))
    return body


def grant_validate_body(args: argparse.Namespace) -> Dict[str, Any]:
    body = merge_body(
        args,
        {
            "grantId": args.grant_id,
            "sessionId": args.session_id,
            "sourceNodeId": args.source_node_id,
            "targetNodeId": args.target_node_id,
            "signedToken": args.signed_token,
            "expiresAt": args.expires_at,
            "allowedCapabilities": parse_csv(args.capabilities),
        },
    )
    if not args.no_sign:
        sign_grant_validate_body(body, args.hmac_secret)
    return body


def sign_grant_validate_body(body: Dict[str, Any], secret: str) -> None:
    body.setdefault("requestTimestamp", str(int(time.time() * 1000)))
    body.setdefault("requestNonce", str(uuid.uuid4()))
    capabilities = body.get("allowedCapabilities") or []
    payload = "|".join(
        [
            str(body.get("grantId") or ""),
            str(body.get("sessionId") or ""),
            str(body.get("sourceNodeId") or ""),
            str(body.get("targetNodeId") or ""),
            ",".join(str(item) for item in capabilities),
            str(body.get("expiresAt") or ""),
            str(body.get("signedToken") or ""),
            str(body.get("requestTimestamp") or ""),
            str(body.get("requestNonce") or ""),
        ]
    )
    if not secret:
        raise CliError("HMAC secret 未配置；请先执行 center ensure 或设置 WDSAVS_AI_HMAC_SECRET")
    digest = hmac.new(secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).digest()
    body["requestSignature"] = base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def a2a_params(args: argparse.Namespace) -> Dict[str, Any]:
    complete_a2a_context(args)
    return compact_dict(
        {
            "sessionId": args.session_id,
            "grantId": args.grant_id,
            "signedToken": args.signed_token,
            "sourceNodeId": args.source_node_id,
            "targetNodeId": args.target_node_id,
            "targetRelayEndpoint": args.target_relay_endpoint,
            "centerGrantValidateEndpoint": args.center_grant_validate_endpoint,
            "expiresAt": args.expires_at,
        }
    )


def a2a_query(args: argparse.Namespace) -> Dict[str, Any]:
    return a2a_params(args)


def complete_a2a_context(args: argparse.Namespace) -> None:
    if args.grant_id and not args.signed_token:
        grant = request_json(args, "GET", f"/api/skill/relay/access/{quote_path(args.grant_id)}")
        if isinstance(grant, dict):
            fill_missing(args, "session_id", grant.get("sessionId"))
            fill_missing(args, "source_node_id", grant.get("sourceNodeId"))
            fill_missing(args, "target_node_id", grant.get("targetNodeId"))
            fill_missing(args, "signed_token", grant.get("signedToken"))
            fill_missing(args, "expires_at", grant.get("expiresAt"))
            fill_missing(args, "target_relay_endpoint", grant.get("targetRelayEndpoint"))
            fill_missing(args, "center_grant_validate_endpoint", grant.get("centerGrantValidateEndpoint"))
            if not args.signed_token:
                fill_missing(
                    args,
                    "signed_token",
                    sign_grant_token(
                        args.grant_id,
                        args.session_id,
                        args.source_node_id,
                        args.target_node_id,
                        grant.get("allowedCapabilities") or [],
                        args.expires_at,
                        args.hmac_secret,
                    ),
                )
    if args.target_node_id and not args.target_relay_endpoint:
        node = request_json(args, "GET", f"/api/skill/relay/nodes/{quote_path(args.target_node_id)}")
        if isinstance(node, dict):
            fill_missing(args, "target_relay_endpoint", node.get("relayEndpoint"))
    if not args.center_grant_validate_endpoint:
        fill_missing(
            args,
            "center_grant_validate_endpoint",
            build_url(args.center, "/api/skill/relay/access/validate"),
        )


def fill_missing(args: argparse.Namespace, name: str, value: Any) -> None:
    if value is not None and not getattr(args, name, None):
        setattr(args, name, str(value))


def sign_grant_token(
    grant_id: Optional[str],
    session_id: Optional[str],
    source_node_id: Optional[str],
    target_node_id: Optional[str],
    allowed_capabilities: Iterable[Any],
    expires_at: Optional[str],
    secret: str,
) -> str:
    payload = "|".join(
        [
            str(grant_id or ""),
            str(session_id or ""),
            str(source_node_id or ""),
            str(target_node_id or ""),
            ",".join(str(item) for item in allowed_capabilities or []),
            str(expires_at or ""),
        ]
    )
    if not secret:
        raise CliError("HMAC secret 未配置；请先执行 center ensure 或设置 WDSAVS_AI_HMAC_SECRET")
    signature = hmac.new(secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).digest()
    encoded_payload = base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii").rstrip("=")
    encoded_signature = base64.urlsafe_b64encode(signature).decode("ascii").rstrip("=")
    return f"{encoded_payload}.{encoded_signature}"


def json_rpc(request_id: str, method: str, params: Dict[str, Any]) -> Dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}


def put_model_config(target: Dict[str, Any], model_config_file: Optional[str]) -> None:
    if model_config_file:
        target["modelConfig"] = read_model_config(model_config_file)


def read_text_arg(value: Optional[str], file_path: Optional[str]) -> Optional[str]:
    if file_path:
        return Path(file_path).read_text(encoding="utf-8").rstrip("\r\n")
    return value


def read_model_config(path: str) -> Dict[str, Any]:
    text = Path(path).read_text(encoding="utf-8")
    if path.lower().endswith(".json"):
        return json.loads(text)
    result: Dict[str, Any] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or ":" not in line:
            continue
        key, value = line.split(":", 1)
        cleaned = value.strip().strip('"').strip("'")
        if key.strip() in {"model", "provider", "endpoint", "remoteEndpoint", "baseUrl", "relayCommand", "apiPath"}:
            result[key.strip()] = cleaned
    if "baseUrl" in result and "endpoint" not in result:
        result["endpoint"] = result.pop("baseUrl")
    if "relayCommand" in result:
        result["claudeCode"] = {"command": result.pop("relayCommand")}
    return result


def json_source(inline: Optional[str], file_path: Optional[str]) -> Optional[Any]:
    if file_path:
        return read_json_file(file_path)
    if inline:
        return read_json_arg(inline)
    return None


def read_json_file(path: str) -> Any:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def read_json_arg(value: str) -> Any:
    if value.startswith("@"):
        return read_json_file(value[1:])
    return json.loads(value)


def parse_csv(value: Optional[str]) -> List[str]:
    if not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]


def parse_node_ids(value: Optional[str]) -> List[str]:
    if not value:
        return []
    return [item.strip() for item in re.split(r"[\s,]+", value) if item.strip()]


def parse_bool(value: str) -> bool:
    lowered = value.strip().lower()
    if lowered in {"true", "1", "yes", "y", "on"}:
        return True
    if lowered in {"false", "0", "no", "n", "off"}:
        return False
    raise argparse.ArgumentTypeError(f"invalid boolean value: {value}")


def parse_bool_value(value: Any, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    try:
        return parse_bool(str(value))
    except argparse.ArgumentTypeError as exc:
        raise CliError(str(exc)) from exc


def compact_dict(source: Dict[str, Any]) -> Dict[str, Any]:
    return {key: value for key, value in source.items() if value is not None}


def sanitize_runtime_config_response(value: Any) -> Any:
    if isinstance(value, list):
        return [sanitize_runtime_config_response(item) for item in value]
    if isinstance(value, dict):
        sanitized = {key: sanitize_runtime_config_response(item) for key, item in value.items()}
        if sanitized.get("secret") is True and "value" in sanitized:
            sanitized["value"] = None
        return sanitized
    return value


def put_if_not_none(target: Dict[str, Any], key: str, value: Any) -> None:
    if value is not None:
        target[key] = value


def quote_path(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def lambda_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4()}"


def write_output(result: Any, raw: bool = False) -> None:
    if raw or isinstance(result, str):
        print(result)
        return
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.exit(main())
