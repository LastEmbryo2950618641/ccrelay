#!/usr/bin/env python3
"""Remote CC center planning and bootstrap support."""

from __future__ import annotations

import json
import os
import shlex
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

import ccrelay_identity
import ccrelay_ssh


DEFAULT_PORT_START = 18191
DEFAULT_PORT_END = 18290
DEFAULT_RELAY_PORT_START = 18091
DEFAULT_RELAY_PORT_END = 18190
DEFAULT_MIN_MEMORY_MB = 512
DEFAULT_MIN_DISK_MB = 1024
CENTER_SELECTION_FILE = "center-selection.json"
CENTER_BOOTSTRAP_ATTEMPT_FILE = "center-bootstrap-attempt.json"
MODEL_CONFIG_FILE = "cc-model-config.yml"


def skill_root() -> Path:
    return Path(__file__).resolve().parents[1]


def model_config_path() -> Path:
    configured = str(os.environ.get("CCRELAY_MODEL_CONFIG_FILE") or "").strip()
    return Path(configured).expanduser().resolve() if configured else skill_root() / ".local" / MODEL_CONFIG_FILE


def model_config_status() -> Dict[str, Any]:
    path = model_config_path()
    if not path.is_file():
        return {"ready": False, "status": "RELAY_READY_AI_UNAVAILABLE", "failureType": "MODEL_CONFIG_REQUIRED",
                "path": str(path), "summary": f"模型配置不存在: {path}"}
    values: Dict[str, str] = {}
    try:
        for raw_line in path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or ":" not in line:
                continue
            key, value = line.split(":", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    except OSError as exc:
        return {"ready": False, "status": "RELAY_READY_AI_UNAVAILABLE", "failureType": "MODEL_CONFIG_READ_FAILED",
                "path": str(path), "summary": str(exc)}
    missing = [key for key in ("model", "baseUrl", "apiKey") if not values.get(key)]
    if missing:
        return {"ready": False, "status": "RELAY_READY_AI_UNAVAILABLE", "failureType": "MODEL_CONFIG_INCOMPLETE",
                "path": str(path), "missing": missing, "summary": "模型配置缺少: " + ", ".join(missing)}
    return {"ready": True, "status": "MODEL_CONFIG_READY", "path": str(path), "model": values["model"],
            "baseUrl": values["baseUrl"]}


def model_config_bootstrap_gate(force_redeploy: bool = False) -> Dict[str, Any]:
    """Check model configuration before an operation can change remote state."""
    state = load_state()
    has_configured_center = bool(state.get("centerUrl") and state.get("relayPort"))
    if has_configured_center and not force_redeploy:
        return {
            "ready": True,
            "status": "MODEL_CONFIG_NOT_REQUIRED",
            "skipped": True,
            "reason": "CONFIGURED_CENTER_REUSE",
        }
    result = model_config_status()
    if result.get("ready"):
        return {"ready": True, **result}
    interaction = {
        "prompt": "在继续部署前，需要先选择并完成 CC 模型配置。",
        "options": [
            {"id": "DISCOVER_LOCAL_CONFIG", "label": "使用检测到的本机模型配置", "recommended": True},
            {"id": "CONFIGURE_MODEL", "label": "填写新的模型配置", "recommended": False},
            {"id": "CANCEL", "label": "取消", "recommended": False},
        ],
        "fields": [
            {"name": "model", "label": "模型", "required": True, "secret": False},
            {"name": "baseUrl", "label": "Base URL", "required": True, "secret": False},
            {"name": "apiKey", "label": "API key", "required": True, "secret": True},
        ],
    }
    return {
        "ready": False,
        "success": False,
        "status": "NEED_USER_INPUT",
        "stage": "MODEL_CONFIG_PREPARE",
        "failureType": result.get("failureType") or "MODEL_CONFIG_REQUIRED",
        "summary": result.get("summary") or "远端 Center 引导前必须准备模型配置。",
        "path": result.get("path"),
        "missing": result.get("missing") or [],
        "taskCreated": False,
        "blockedCommand": "center bootstrap",
        "agentAction": "RETURN_VERBATIM_RESPONSE_AND_STOP",
        "mustStopCurrentTurn": True,
        "interaction": interaction,
        "verbatimResponse": (
            "在继续部署前，需要先选择并完成 CC 模型配置。\n\n"
            "1. 使用检测到的本机模型配置\n"
            "2. 填写新的模型配置\n"
            "3. 取消\n\n"
            "请回复选项序号。"
        ),
        "inputOrder": [
            "DISCOVER_LOCAL_CONFIG_OR_CONFIGURE_MODEL",
            "API_KEY_INPUT_METHOD",
            "MODEL_API_TEST",
            "BOOTSTRAP_EXECUTION_MODE",
        ],
        "nextStage": "BOOTSTRAP_EXECUTION_MODE_REQUIRED",
        "resume": "先按选项完成模型配置发现/输入、API 测试和写入；完成后重新执行原引导命令，再展示自动执行或逐步检视选项。",
    }


def state_path() -> Path:
    return skill_root() / ".local" / CENTER_SELECTION_FILE


def bootstrap_attempt_path() -> Path:
    return skill_root() / ".local" / CENTER_BOOTSTRAP_ATTEMPT_FILE


def load_state() -> Dict[str, Any]:
    path = state_path()
    if not path.is_file():
        return {}
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
        return loaded if isinstance(loaded, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def load_bootstrap_attempt() -> Dict[str, Any]:
    path = bootstrap_attempt_path()
    if not path.is_file():
        return {}
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
        return loaded if isinstance(loaded, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def persisted_center_url() -> Optional[str]:
    return str(load_state().get("centerUrl") or "").strip() or None


def save_state(state: Dict[str, Any]) -> Path:
    path = state_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    ccrelay_ssh._restrict_file(temporary)
    temporary.replace(path)
    ccrelay_ssh._restrict_file(path)
    return path


def save_bootstrap_attempt(state: Dict[str, Any]) -> Path:
    path = bootstrap_attempt_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    ccrelay_ssh._restrict_file(temporary)
    temporary.replace(path)
    ccrelay_ssh._restrict_file(path)
    return path


def mark_bootstrap_failure(attempt: Dict[str, Any], result: Dict[str, Any], phase: str) -> Dict[str, Any]:
    attempt.update({
        "status": "CENTER_BOOTSTRAP_FAILED",
        "phase": phase,
        "failureType": result.get("failureType") or result.get("status"),
        "summary": result.get("summary"),
        "updatedAt": int(time.time() * 1000),
    })
    path = save_bootstrap_attempt(attempt)
    result["attemptStatePath"] = str(path)
    return result


def update_bootstrap_attempt(attempt: Dict[str, Any], phase: str, **values: Any) -> None:
    attempt.update({"phase": phase, "updatedAt": int(time.time() * 1000), **values})
    save_bootstrap_attempt(attempt)


def clear_bootstrap_attempt() -> None:
    path = bootstrap_attempt_path()
    if path.is_file():
        path.unlink()


def discover_candidates(explicit_nodes: Iterable[str], payload: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
    values = [str(item) for item in explicit_nodes or [] if str(item).strip()]
    payload = payload or {}
    for key in ("centerCandidateNodes", "clusterNodes", "nodes"):
        items = payload.get(key) or []
        if isinstance(items, str):
            items = [items]
        for item in items:
            if isinstance(item, dict):
                host = item.get("host") or item.get("ip")
                port = item.get("sshPort") or item.get("port") or 22
                username = item.get("username") or item.get("sshUser")
                if host:
                    values.append(f"{host}:{port}" + (f"={username}" if username else ""))
            elif item:
                values.append(str(item))
    target_host = payload.get("host") or payload.get("sshHost") or payload.get("targetHost")
    if target_host:
        target_port = payload.get("sshPort") or payload.get("targetPort") or payload.get("port") or 22
        target_user = payload.get("username") or payload.get("sshUser") or payload.get("targetUser")
        values.append(f"{target_host}:{target_port}" + (f"={target_user}" if target_user else ""))
    config = ccrelay_ssh.load_config()
    identity = config.get("clusterIdentity") or {}
    persisted_targets = ccrelay_identity.active_target_nodes(config)
    for item in persisted_targets or identity.get("managedNodes") or []:
        host = item.get("host")
        port = item.get("port") or 22
        username = item.get("bootstrapUsername")
        if host:
            values.append(f"{host}:{port}" + (f"={username}" if username else ""))
    if not values:
        for node_key in ((identity.get("dedicatedAccount") or {}).get("passwordSecrets") or {}).keys():
            values.append(str(node_key))
    if not values:
        return []
    return ccrelay_identity.normalize_nodes(values)


def recovery_plan(explicit_nodes: Iterable[str]) -> Optional[Dict[str, Any]]:
    attempt = load_bootstrap_attempt()
    selected = dict(attempt.get("selectedResources") or {})
    node_key = str(attempt.get("nodeKey") or selected.get("nodeKey") or "").strip()
    center_port = parse_int(attempt.get("centerPort"))
    relay_port = parse_int(attempt.get("relayPort"))
    center_url = str(attempt.get("centerUrl") or "").strip()
    remote_directory = str(attempt.get("remoteDirectory") or "").strip()
    if not node_key or not selected.get("host") or not center_port or not relay_port \
            or not center_url or not remote_directory:
        return None
    requested = {
        ccrelay_identity.parse_node(str(item))["nodeKey"]
        for item in explicit_nodes or [] if str(item).strip()
    }
    if requested and node_key not in requested:
        return None
    selected.update({
        "nodeKey": node_key,
        "port": attempt.get("sshPort") or selected.get("port") or 22,
        "availablePort": center_port,
        "relayPort": relay_port,
        "eligible": True,
    })
    return {
        "success": True,
        "status": "CENTER_PLAN_READY",
        "stage": "REMOTE_CENTER_RECOVERY",
        "selectionMode": "RECOVERY",
        "selected": selected,
        "centerUrl": center_url,
        "selectionReason": "恢复最近一次未完成的远端 Center bootstrap。",
        "candidates": [selected],
        "taskCreated": False,
        "recovery": True,
    }


def plan(
    explicit_nodes: Iterable[str],
    payload: Optional[Dict[str, Any]] = None,
    selection: str = "AUTO",
    exclusions: Iterable[str] = (),
    port_start: Optional[int] = None,
    port_end: Optional[int] = None,
    timeout_seconds: int = 15,
    manual_host: Optional[str] = None,
    manual_port: Optional[int] = None,
    manual_scheme: str = "http",
    manual_base_path: str = "",
    strict_explicit_nodes: bool = False,
    concurrency: int = ccrelay_identity.DEFAULT_SSH_CONCURRENCY,
) -> Dict[str, Any]:
    if str(selection or "AUTO").upper() == "MANUAL" and not manual_host:
        return manual_interaction()
    config = ccrelay_ssh.load_config()
    center_config = ((config.get("runtime") or {}).get("centerBootstrap") or {})
    selected_port_start = normalize_port(port_start or center_config.get("portStart") or DEFAULT_PORT_START)
    selected_port_end = normalize_port(port_end or center_config.get("portEnd") or DEFAULT_PORT_END)
    if selected_port_end < selected_port_start:
        raise ccrelay_ssh.SshCredentialError("中心端口范围无效：结束端口不能小于起始端口")
    if manual_host:
        nodes = discover_candidates(explicit_nodes, payload)
        matched = next((item for item in nodes if item["host"] == manual_host), None)
        candidates = [matched or ccrelay_identity.parse_node(f"{manual_host}:22")]
        selected_port_start = normalize_port(manual_port or selected_port_start)
        selected_port_end = max(selected_port_end, selected_port_start + 1)
    else:
        excluded = {str(item).strip() for item in exclusions or [] if str(item).strip()}
        discovered = (
            [ccrelay_identity.parse_node(str(item)) for item in explicit_nodes or [] if str(item).strip()]
            if strict_explicit_nodes
            else discover_candidates(explicit_nodes, payload)
        )
        candidates = [
            item for item in discovered
            if item["nodeKey"] not in excluded and item["host"] not in excluded
        ]
    if not candidates:
        return no_candidate_interaction("没有可用于自动选择的远端节点。")
    concurrency = ccrelay_identity.normalize_concurrency(concurrency)
    probed = ccrelay_identity.parallel_map_ordered(
        candidates,
        lambda item: probe_candidate(
            item, selected_port_start, selected_port_end, timeout_seconds, config),
        concurrency,
        lambda item, exc: {
            **item,
            "probeSuccess": False,
            "failureType": "RESOURCE_PROBE_FAILED",
            "summary": str(exc),
        },
    )
    minimum_memory = int(center_config.get("minimumMemoryMb") or DEFAULT_MIN_MEMORY_MB)
    minimum_disk = int(center_config.get("minimumDiskMb") or DEFAULT_MIN_DISK_MB)
    for item in probed:
        eligible = bool(item.get("probeSuccess") and item.get("availablePort") and item.get("relayPort")
                        and item.get("availablePort") != item.get("relayPort") and item.get("directoryWritable"))
        eligible = eligible and int(item.get("memoryAvailableMb") or 0) >= minimum_memory
        eligible = eligible and int(item.get("diskAvailableMb") or 0) >= minimum_disk
        item["eligible"] = eligible
        item["score"] = resource_score(item) if eligible else None
        if not eligible and not item.get("failureType"):
            item["failureType"] = (
                "PORT_PAIR_NOT_AVAILABLE"
                if item.get("probeSuccess") and (not item.get("availablePort") or not item.get("relayPort"))
                else "RESOURCE_REQUIREMENT_NOT_MET"
            )
    eligible_candidates = [item for item in probed if item.get("eligible")]
    eligible_candidates.sort(key=lambda item: (-float(item["score"]), item["nodeKey"]))
    if not eligible_candidates:
        result = no_candidate_interaction("候选节点均未通过 SSH、权限、端口或最低资源检查。")
        result["candidates"] = [public_candidate(item) for item in probed]
        return result
    selected = eligible_candidates[0]
    base_path = normalize_base_path(manual_base_path)
    center_url = f"{manual_scheme or 'http'}://{selected['host']}:{selected['availablePort']}{base_path}"
    return {
        "success": True,
        "status": "CENTER_PLAN_READY",
        "stage": "REMOTE_CENTER_BOOTSTRAP",
        "selectionMode": "MANUAL" if manual_host else "AUTO",
        "selected": public_candidate(selected),
        "centerUrl": center_url,
        "portRange": {"start": selected_port_start, "end": selected_port_end},
        "minimumResources": {"memoryAvailableMb": minimum_memory, "diskAvailableMb": minimum_disk},
        "selectionReason": selection_reason(selected, eligible_candidates),
        "candidates": [public_candidate(item) for item in probed],
        "taskCreated": False,
        "inspection": {
            "prompt": "已完成远端中心候选探测并生成推荐方案，请选择当前步骤的执行方式。",
            "options": [
                {"id": "AUTO_EXECUTE_STEP", "label": "按推荐方案执行本步（推荐）", "recommended": True},
                {"id": "CONFIGURE_STEP_MANUALLY", "label": "手动配置本步"},
                {"id": "AUTO_EXECUTE_REMAINING", "label": "执行本步并让后续全部自动执行"},
                {"id": "EXCLUDE_NODES_AND_RETRY", "label": "排除候选节点后重新规划"},
                {"id": "CANCEL", "label": "取消"},
            ],
            "fields": [],
        },
    }


def probe_candidate(node: Dict[str, Any], port_start: int, port_end: int, timeout_seconds: int,
                    config: Dict[str, Any]) -> Dict[str, Any]:
    access = runtime_access(node, config)
    if not access.get("username"):
        return {**node, "probeSuccess": False, "failureType": "SSH_CREDENTIAL_REQUIRED"}
    result = run_command(
        node, access, "/bin/sh -s", timeout_seconds,
        stdin_text=linux_probe_script(port_start, port_end), include_output=True)
    values = parse_marker(result.get("stdout"), "CCRELAY_RESOURCE|") if result.get("success") else {}
    if not values:
        result = run_command(
            node, access, "powershell.exe -NoProfile -NonInteractive -Command -", timeout_seconds,
            stdin_text=windows_probe_script(port_start, port_end), include_output=True)
        values = parse_marker(result.get("stdout"), "CCRELAY_RESOURCE|") if result.get("success") else {}
    if not values:
        return {
            **node,
            "probeSuccess": False,
            "failureType": "RESOURCE_PROBE_FAILED",
            "summary": result.get("summary"),
            "runtimeUsername": access.get("username"),
        }
    return {
        **node,
        "probeSuccess": True,
        "runtimeUsername": access.get("username"),
        "accessMode": access.get("mode"),
        "_keyPath": str(access.get("keyPath")) if access.get("keyPath") else None,
        "osType": values.get("os", "UNKNOWN").upper(),
        "architecture": values.get("arch"),
        "cpuCores": parse_int(values.get("cpu")),
        "loadAverage": parse_float(values.get("load")),
        "memoryAvailableMb": parse_int(values.get("memoryMb")),
        "diskAvailableMb": parse_int(values.get("diskMb")),
        "homeDirectory": values.get("home"),
        "availablePort": parse_int(values.get("port")) or None,
        "relayPort": parse_int(values.get("relayPort")) or None,
        "directoryWritable": str(values.get("writable", "false")).lower() == "true",
    }


def bootstrap(plan_result: Dict[str, Any], hmac_secret: str, timeout_seconds: int = 180) -> Dict[str, Any]:
    if plan_result.get("status") != "CENTER_PLAN_READY":
        return plan_result
    selected = dict(plan_result["selected"])
    config = ccrelay_ssh.load_config()
    dedicated = (config.get("clusterIdentity") or {}).get("dedicatedAccount") or {}
    center_key_algorithm = ccrelay_identity.normalize_key_algorithm(
        (dedicated.get("clusterKey") or {}).get("algorithm") or dedicated.get("keyAlgorithm"))
    if center_key_algorithm == "AUTO":
        center_key_algorithm = "RSA"
    access = runtime_access(selected, config)
    bundle = skill_root() / "assets" / "runtime-bundle" / ccrelay_identity.DEFAULT_PRODUCT_NAME
    if not (bundle / "app.jar").is_file():
        return failure("CENTER_BUNDLE_MISSING", f"中心运行时制品不存在: {bundle}", plan_result)
    center_port = int(selected["availablePort"])
    relay_port = int(selected.get("relayPort") or center_port + 1)
    if relay_port == center_port:
        relay_port += 1
    remote_directory = render_remote_directory(selected, relay_port, config, access)
    previous_attempt = load_bootstrap_attempt()
    attempt = {
        "status": "CENTER_BOOTSTRAP_IN_PROGRESS",
        "phase": "DIRECTORY_CREATE",
        "centerUrl": plan_result.get("centerUrl"),
        "nodeKey": selected.get("nodeKey"),
        "sshPort": selected.get("port") or 22,
        "centerPort": center_port,
        "relayPort": relay_port,
        "remoteDirectory": remote_directory,
        "selectedResources": public_candidate(selected),
        "startedAt": int(time.time() * 1000),
        "updatedAt": int(time.time() * 1000),
    }
    save_bootstrap_attempt(attempt)
    if previous_attempt.get("centerUrl") == plan_result.get("centerUrl") \
            and previous_attempt.get("remoteDirectory") == remote_directory \
            and parse_int(previous_attempt.get("relayPort")) == relay_port:
        update_bootstrap_attempt(attempt, "REMOTE_CENTER_RECLAIM")
        reclaimed = probe_reclaimable_center(
            plan_result, selected, config, access, remote_directory, center_port, relay_port,
            max(10, min(timeout_seconds, 30)),
        )
        if reclaimed.get("success"):
            return finalize_bootstrap(
                plan_result, selected, config, access, remote_directory, center_port, relay_port,
                plan_result["centerUrl"], reclaimed["health"], reclaimed["relayHealth"],
                reclaimed["relayRegistration"], reclaimed["crossNodeReachability"],
                {"success": True, "status": "REUSED_EXISTING_ARTIFACTS"},
                {"success": True, "status": "REUSED_EXISTING_RELAY"},
                reclaimed=True,
            )
    update_bootstrap_attempt(attempt, "DIRECTORY_CREATE")
    created = create_remote_directory(selected, access, remote_directory, timeout_seconds)
    if not created.get("success"):
        return mark_bootstrap_failure(
            attempt, failure("CENTER_DIRECTORY_CREATE_FAILED", created.get("summary"), plan_result),
            "DIRECTORY_CREATE",
        )
    bundle_sources = center_bundle_sources(bundle, selected.get("osType"))
    update_bootstrap_attempt(attempt, "BUNDLE_TRANSFER")
    transfer = ccrelay_ssh.copy_to_remote(
        selected["host"], int(selected["port"]), access["username"], bundle_sources,
        remote_directory, timeout_seconds=max(300, timeout_seconds), key_path=access.get("keyPath"),
        allow_password_fallback=access.get("mode") != "DEDICATED_KEY")
    if not transfer.get("success"):
        return mark_bootstrap_failure(
            attempt, failure("CENTER_BUNDLE_TRANSFER_FAILED", transfer.get("summary"), plan_result),
            "BUNDLE_TRANSFER",
        )
    update_bootstrap_attempt(attempt, "CENTER_START")
    started = start_remote_center(
        selected, access, remote_directory, center_port, hmac_secret, timeout_seconds,
        center_key_algorithm)
    if not started.get("success"):
        diagnostic = diagnose_remote_center(selected, access, remote_directory, center_port, 20)
        cleanup = stop_remote_center(selected, access, remote_directory, 20)
        result = failure("CENTER_START_FAILED", started.get("summary"), plan_result)
        result.update({"diagnostic": diagnostic, "cleanup": cleanup})
        return mark_bootstrap_failure(attempt, result, "CENTER_START")
    center_url = plan_result["centerUrl"]
    update_bootstrap_attempt(attempt, "CENTER_LOCAL_HEALTH")
    remote_health = wait_remote_health(selected, access, center_port, timeout_seconds)
    if not remote_health.get("success"):
        diagnostic = diagnose_remote_center(selected, access, remote_directory, center_port, 20)
        cleanup = stop_remote_center(selected, access, remote_directory, 20)
        result = failure(
            "CENTER_LOCAL_HEALTH_TIMEOUT",
            diagnostic.get("summary") or f"远端中心在候选节点本机未通过健康检查: {center_url}",
            plan_result,
        )
        result.update({
            "centerUrl": center_url,
            "selected": public_candidate(selected),
            "remoteDirectory": remote_directory,
            "diagnostic": diagnostic,
            "cleanup": cleanup,
        })
        return mark_bootstrap_failure(attempt, result, "CENTER_LOCAL_HEALTH")
    update_bootstrap_attempt(attempt, "CENTER_CONTROL_PLANE_REACHABILITY")
    health = wait_health(center_url, timeout_seconds)
    if not health:
        diagnostic = diagnose_remote_center(selected, access, remote_directory, center_port, 20)
        cleanup = stop_remote_center(selected, access, remote_directory, 20)
        result = failure(
            "CENTER_CONTROL_PLANE_UNREACHABLE",
            f"远端中心在节点本机健康，但当前控制端无法访问: {center_url}",
            plan_result,
        )
        result.update({
            "centerUrl": center_url,
            "selected": public_candidate(selected),
            "remoteDirectory": remote_directory,
            "diagnostic": diagnostic,
            "cleanup": cleanup,
        })
        return mark_bootstrap_failure(attempt, result, "CENTER_CONTROL_PLANE_REACHABILITY")
    update_bootstrap_attempt(attempt, "CENTER_CROSS_NODE_REACHABILITY")
    reachability = verify_from_candidates(center_url, plan_result.get("candidates") or [], selected, config, 20)
    if reachability.get("status") == "FAILED":
        diagnostic = diagnose_remote_center(selected, access, remote_directory, center_port, 20)
        cleanup = stop_remote_center(selected, access, remote_directory, 20)
        result = failure("CENTER_CROSS_NODE_UNREACHABLE", reachability.get("summary"), plan_result)
        result.update({"diagnostic": diagnostic, "cleanup": cleanup, "crossNodeReachability": reachability})
        return mark_bootstrap_failure(attempt, result, "CENTER_CROSS_NODE_REACHABILITY")
    update_bootstrap_attempt(attempt, "RELAY_ARTIFACT_SYNC")
    relay_sync = sync_relay_artifacts(selected, access, remote_directory, config, bundle, timeout_seconds)
    if not relay_sync.get("success"):
        cleanup = stop_remote_center(selected, access, remote_directory, 20)
        result = failure("CENTER_RELAY_ARTIFACT_SYNC_FAILED", relay_sync.get("summary"), plan_result)
        result.update({
            "centerUrl": center_url,
            "selected": public_candidate(selected),
            "remoteDirectory": remote_directory,
            "artifactSync": relay_sync,
            "cleanup": cleanup,
        })
        return mark_bootstrap_failure(attempt, result, "RELAY_ARTIFACT_SYNC")
    update_bootstrap_attempt(attempt, "CENTER_RELAY_START")
    relay_start = start_remote_relay(
        selected, access, remote_directory, center_port, relay_port, hmac_secret, timeout_seconds,
    )
    if not relay_start.get("success"):
        diagnostic = diagnose_remote_relay(selected, access, remote_directory, relay_port, 20, 160)
        cleanup = stop_remote_center(selected, access, remote_directory, 20)
        result = failure("CENTER_RELAY_START_FAILED", relay_start.get("summary"), plan_result)
        result.update({
            "centerUrl": center_url,
            "selected": public_candidate(selected),
            "remoteDirectory": remote_directory,
            "relayPort": relay_port,
            "relayStart": relay_start,
            "diagnostic": diagnostic,
            "cleanup": cleanup,
        })
        return mark_bootstrap_failure(attempt, result, "CENTER_RELAY_START")
    update_bootstrap_attempt(attempt, "CENTER_RELAY_LOCAL_HEALTH")
    relay_health = wait_remote_relay_health(selected, access, relay_port, timeout_seconds)
    if not relay_health.get("success"):
        diagnostic = diagnose_remote_relay(
            selected, access, remote_directory, relay_port, 20, 160,
        )
        cleanup = stop_remote_center(selected, access, remote_directory, 20)
        result = failure(
            "CENTER_RELAY_LOCAL_HEALTH_FAILED",
            diagnostic.get("summary") or relay_health.get("summary"),
            plan_result,
        )
        result.update({
            "centerUrl": center_url,
            "selected": public_candidate(selected),
            "remoteDirectory": remote_directory,
            "relayPort": relay_port,
            "relayHealth": relay_health,
            "diagnostic": diagnostic,
            "cleanup": cleanup,
        })
        return mark_bootstrap_failure(attempt, result, "CENTER_RELAY_LOCAL_HEALTH")
    relay_endpoint = f"http://{selected['host']}:{relay_port}/api/ai/remote-cc/chat"
    update_bootstrap_attempt(attempt, "CENTER_RELAY_REGISTRATION")
    relay_registration = wait_center_relay_registration(
        center_url, selected["host"], relay_port, relay_endpoint, timeout_seconds,
    )
    if not relay_registration.get("success"):
        diagnostic = {
            "center": diagnose_remote_center(selected, access, remote_directory, center_port, 20, 160),
            "relay": diagnose_remote_relay(selected, access, remote_directory, relay_port, 20, 160),
        }
        cleanup = stop_remote_center(selected, access, remote_directory, 20)
        result = failure("CENTER_RELAY_REGISTRATION_FAILED", relay_registration.get("summary"), plan_result)
        result.update({
            "centerUrl": center_url,
            "selected": public_candidate(selected),
            "remoteDirectory": remote_directory,
            "relayPort": relay_port,
            "relayHealth": relay_health,
            "relayRegistration": relay_registration,
            "diagnostic": diagnostic,
            "cleanup": cleanup,
        })
        return mark_bootstrap_failure(attempt, result, "CENTER_RELAY_REGISTRATION")
    return finalize_bootstrap(
        plan_result, selected, config, access, remote_directory, center_port, relay_port,
        center_url, health, relay_health, relay_registration, reachability, relay_sync, relay_start,
    )


def finalize_bootstrap(plan_result: Dict[str, Any], selected: Dict[str, Any], config: Dict[str, Any],
                       access: Dict[str, Any], remote_directory: str, center_port: int, relay_port: int,
                       center_url: str, health: Dict[str, Any], relay_health: Dict[str, Any],
                       relay_registration: Dict[str, Any], reachability: Dict[str, Any],
                       relay_sync: Dict[str, Any], relay_start: Dict[str, Any],
                       reclaimed: bool = False) -> Dict[str, Any]:
    relay_endpoint = f"http://{selected['host']}:{relay_port}/api/ai/remote-cc/chat"
    relay_node = relay_registration.get("node") or {}
    state = {
        "centerUrl": center_url,
        "mode": "REMOTE",
        "selectionMode": plan_result.get("selectionMode"),
        "nodeKey": selected["nodeKey"],
        "host": selected["host"],
        "sshPort": selected["port"],
        "centerPort": center_port,
        "relayPort": relay_port,
        "relayEndpoint": relay_endpoint,
        "relayNodeId": relay_node.get("nodeId"),
        "runtimeUsername": access["username"],
        "remoteDirectory": remote_directory,
        "selectedResources": public_candidate(selected),
        "selectionReason": plan_result.get("selectionReason"),
        "health": health,
        "relayHealth": relay_health,
        "relayRegistration": relay_registration,
        "relayProcess": {
            "pidFile": remote_path(remote_directory, "center-relay.pid", selected.get("osType")),
            "logFile": remote_path(remote_directory, "center-relay.log", selected.get("osType")),
            "status": "RUNNING",
        },
        "centerProcess": {
            "pidFile": remote_path(remote_directory, "center.pid", selected.get("osType")),
            "logFile": remote_path(remote_directory, "center-runtime.log", selected.get("osType")),
            "status": "RUNNING",
        },
        "crossNodeReachability": reachability,
        "relayArtifacts": "READY",
        "centerRelayStatus": "CENTER_RELAY_READY",
        "updatedAt": int(time.time() * 1000),
    }
    path = save_state(state)
    clear_bootstrap_attempt()
    identity = config.setdefault("clusterIdentity", {})
    identity["center"] = {
        "nodeId": relay_node.get("nodeId") or selected["nodeKey"],
        "sshNodeKey": selected["nodeKey"],
        "centerUrl": center_url,
        "centerPort": center_port,
        "relayPort": relay_port,
        "relayEndpoint": relay_endpoint,
        "remoteDirectory": remote_directory,
        "selectionMode": plan_result.get("selectionMode"),
    }
    ccrelay_ssh.save_config(config)
    return {
        "success": True,
        "status": "REMOTE_CENTER_READY",
        "centerUrl": center_url,
        "selected": public_candidate(selected),
        "remoteDirectory": remote_directory,
        "health": health,
        "relayHealth": relay_health,
        "relayRegistration": relay_registration,
        "crossNodeReachability": reachability,
        "artifactSync": relay_sync,
        "relayStart": relay_start,
        "relayProcess": {
            "pidFile": remote_path(remote_directory, "center-relay.pid", selected.get("osType")),
            "logFile": remote_path(remote_directory, "center-relay.log", selected.get("osType")),
            "status": "RUNNING",
        },
        "centerProcess": {
            "pidFile": remote_path(remote_directory, "center.pid", selected.get("osType")),
            "logFile": remote_path(remote_directory, "center-runtime.log", selected.get("osType")),
            "status": "RUNNING",
        },
        "centerRelayStatus": "CENTER_RELAY_READY",
        "statePath": str(path),
        "selectionReason": plan_result.get("selectionReason"),
        "reclaimed": reclaimed,
    }


def probe_reclaimable_center(plan_result: Dict[str, Any], selected: Dict[str, Any], config: Dict[str, Any],
                             access: Dict[str, Any], remote_directory: str, center_port: int,
                             relay_port: int, timeout_seconds: int) -> Dict[str, Any]:
    center_url = plan_result["centerUrl"]
    center_local = wait_remote_health(selected, access, center_port, timeout_seconds)
    if not center_local.get("success"):
        return {"success": False, "status": "CENTER_NOT_RECLAIMABLE"}
    health = wait_health(center_url, timeout_seconds)
    if not health:
        return {"success": False, "status": "CENTER_NOT_RECLAIMABLE"}
    relay_health = wait_remote_relay_health(selected, access, relay_port, timeout_seconds)
    if not relay_health.get("success"):
        return {"success": False, "status": "CENTER_RELAY_NOT_RECLAIMABLE"}
    relay_endpoint = f"http://{selected['host']}:{relay_port}/api/ai/remote-cc/chat"
    relay_registration = wait_center_relay_registration(
        center_url, selected["host"], relay_port, relay_endpoint, timeout_seconds,
    )
    if not relay_registration.get("success"):
        return {"success": False, "status": "CENTER_RELAY_NOT_RECLAIMABLE"}
    reachability = verify_from_candidates(
        center_url, plan_result.get("candidates") or [], selected, config, timeout_seconds,
    )
    if reachability.get("status") == "FAILED":
        return {"success": False, "status": "CENTER_CROSS_NODE_UNREACHABLE"}
    return {
        "success": True,
        "status": "REMOTE_CENTER_RECLAIMABLE",
        "health": health,
        "relayHealth": relay_health,
        "relayRegistration": relay_registration,
        "crossNodeReachability": reachability,
        "remoteDirectory": remote_directory,
    }


def rollback_bootstrap(result: Dict[str, Any], timeout_seconds: int = 20) -> Dict[str, Any]:
    selected = result.get("selected") or {}
    remote_directory = result.get("remoteDirectory")
    stopped = None
    if selected.get("host") and selected.get("port") and remote_directory:
        config = ccrelay_ssh.load_config()
        access = runtime_access(selected, config)
        stopped = stop_remote_center(selected, access, remote_directory, timeout_seconds)
        identity = config.setdefault("clusterIdentity", {})
        center = identity.get("center") or {}
        if center.get("centerUrl") == result.get("centerUrl"):
            identity.pop("center", None)
            ccrelay_ssh.save_config(config)
    path = state_path()
    state = load_state()
    removed_state = False
    if path.is_file() and state.get("centerUrl") == result.get("centerUrl"):
        path.unlink()
        removed_state = True
    return {
        "status": "ROLLED_BACK",
        "centerStopped": bool(stopped and stopped.get("success")),
        "relayStopped": bool(stopped and stopped.get("success")),
        "selectionStateRemoved": removed_state,
        "stopSummary": (stopped or {}).get("summary"),
    }


def runtime_access(node: Dict[str, Any], config: Dict[str, Any]) -> Dict[str, Any]:
    identity = config.get("clusterIdentity") or {}
    dedicated = identity.get("dedicatedAccount") or {}
    key_text = (dedicated.get("clusterKey") or {}).get("privateKeyPath")
    key_path = Path(key_text).expanduser().resolve() if key_text else None
    if identity.get("accountMode") == "DEDICATED_MANAGED" and key_path and key_path.is_file():
        return {
            "username": dedicated.get("username") or "ccrelay",
            "keyPath": key_path,
            "mode": "DEDICATED_KEY",
        }
    credential, scope = ccrelay_ssh.resolve_credential(node["host"], int(node["port"]))
    return {
        "username": node.get("username") or (credential or {}).get("username"),
        "keyPath": None,
        "mode": scope or "BOOTSTRAP",
    }


def center_bundle_sources(bundle: Path, os_type: Any) -> List[Path]:
    archive = "runtime-windows.zip" if str(os_type or "").upper() == "WINDOWS" else "runtime.tar.gz"
    sources = [bundle / "app.jar", bundle / archive]
    missing = [str(item) for item in sources if not item.is_file()]
    if missing:
        raise ccrelay_ssh.SshCredentialError("中心运行时制品不存在: " + ", ".join(missing))
    return sources


def relay_artifact_sources(bundle: Path, config: Dict[str, Any], center_os_type: Any) -> List[Path]:
    sources = [
        bundle / "install-relay.sh",
        bundle / "install-relay.ps1",
        bundle / "bundle-manifest.json",
        bundle / "config",
        bundle / "bin" / "prepare_cc_config.py",
        bundle / "bin" / "ccrelay-cli",
        bundle / "bin" / "ccrelay-cli.cmd",
        bundle / "bin" / "start.sh",
        bundle / "tools",
    ]
    managed_nodes = (config.get("clusterIdentity") or {}).get("managedNodes") or []
    center_os = str(center_os_type or "").upper()
    os_types = {str(item.get("osType") or "").upper() for item in managed_nodes}
    unknown_os = not os_types or any(item not in {"LINUX", "WINDOWS"} for item in os_types)
    if (unknown_os or "WINDOWS" in os_types) and center_os != "WINDOWS":
        sources.append(bundle / "runtime-windows.zip")
    if (unknown_os or "LINUX" in os_types) and center_os != "LINUX":
        sources.append(bundle / "runtime.tar.gz")
    missing = [str(item) for item in sources if not item.exists()]
    if missing:
        raise ccrelay_ssh.SshCredentialError("relay 部署制品不存在: " + ", ".join(missing))
    return sources


def sync_relay_artifacts(selected: Dict[str, Any], access: Dict[str, Any], remote_directory: str,
                         config: Dict[str, Any], bundle: Path, timeout_seconds: int) -> Dict[str, Any]:
    model_status = model_config_status()
    if not model_status.get("ready"):
        return {"success": False, **model_status}
    sources = relay_artifact_sources(bundle, config, selected.get("osType"))
    artifact_result = copy_relay_artifact_sources(
        selected, access, sources, remote_directory, timeout_seconds,
    )
    if not artifact_result.get("success"):
        return artifact_result
    config_result = ccrelay_ssh.copy_to_remote(
        selected["host"], int(selected["port"]), access["username"], [Path(model_status["path"])],
        remote_directory, timeout_seconds=max(60, timeout_seconds), key_path=access.get("keyPath"),
        allow_password_fallback=access.get("mode") != "DEDICATED_KEY",
    )
    if not config_result.get("success"):
        return {"success": False, "status": "RELAY_READY_AI_UNAVAILABLE", "failureType": "MODEL_CONFIG_TRANSFER_FAILED",
                "summary": config_result.get("summary"), "artifactTransfer": artifact_result,
                "modelConfigTransfer": config_result}
    if str(selected.get("osType") or "").upper() == "WINDOWS":
        command = (f"$bundle='{remote_directory.replace(chr(39), chr(39) + chr(39))}'; "
                   "New-Item -ItemType Directory -Force -Path (Join-Path $bundle 'config') | Out-Null; "
                   "Move-Item -Force (Join-Path $bundle 'cc-model-config.yml') (Join-Path $bundle 'config/cc-model-config.yml'); "
                   "(Get-Item (Join-Path $bundle 'config/cc-model-config.yml')).Attributes = 'Hidden'")
    else:
        quoted = shlex.quote(remote_directory)
        command = (f"bundle={quoted}; mkdir -p \"$bundle/config\"; "
                   "mv -f \"$bundle/cc-model-config.yml\" \"$bundle/config/cc-model-config.yml\"; "
                   "chmod 600 \"$bundle/config/cc-model-config.yml\"")
    installed = run_command(selected, access, command, max(30, timeout_seconds))
    if not installed.get("success"):
        return {"success": False, "status": "RELAY_READY_AI_UNAVAILABLE", "failureType": "MODEL_CONFIG_INSTALL_FAILED",
                "summary": installed.get("summary"), "artifactTransfer": artifact_result,
                "modelConfigTransfer": config_result}
    return {"success": True, "status": "RELAY_ARTIFACTS_AND_MODEL_CONFIG_READY",
            "artifactTransfer": artifact_result, "modelConfigTransfer": config_result,
            "modelConfigInstall": installed, "modelConfig": {"model": model_status.get("model"),
            "baseUrl": model_status.get("baseUrl")}}


def copy_relay_artifact_sources(selected: Dict[str, Any], access: Dict[str, Any], sources: List[Path],
                                remote_directory: str, timeout_seconds: int) -> Dict[str, Any]:
    transfer_plan = relay_artifact_transfer_plan(sources, remote_directory, selected.get("osType"))
    remote_directories = list(dict.fromkeys(destination for _, destination, _ in transfer_plan))
    prepared = prepare_remote_artifact_directories(
        selected, access, remote_directories, timeout_seconds,
    )
    if not prepared.get("success"):
        return {
            "success": False,
            "status": "RELAY_ARTIFACT_DIRECTORY_PREPARE_FAILED",
            "summary": prepared.get("summary"),
            "directoryPrepare": prepared,
            "transfers": [],
        }
    transfers = []
    for source, destination, display_name in transfer_plan:
        result = ccrelay_ssh.copy_to_remote(
            selected["host"], int(selected["port"]), access["username"], [source],
            destination, timeout_seconds=max(300, timeout_seconds), key_path=access.get("keyPath"),
            allow_password_fallback=access.get("mode") != "DEDICATED_KEY",
        )
        transfers.append({"source": display_name, **result})
        if not result.get("success"):
            return {
                "success": False,
                "status": "RELAY_ARTIFACT_TRANSFER_FAILED",
                "summary": result.get("summary"),
                "failedSource": display_name,
                "directoryPrepare": prepared,
                "transfers": transfers,
            }
    return {
        "success": True,
        "status": "RELAY_ARTIFACTS_TRANSFERRED",
        "directoryPrepare": prepared,
        "transfers": transfers,
    }


def relay_artifact_transfer_plan(sources: List[Path], remote_directory: str,
                                 os_type: Any) -> List[tuple[Path, str, str]]:
    separator = "\\" if str(os_type or "").upper() == "WINDOWS" else "/"
    remote_root = remote_directory.rstrip("/\\")
    plan = []
    for source in sources:
        if source.is_file():
            if source.parent.name == "bin":
                destination = separator.join((remote_root, "bin"))
                display_name = separator.join(("bin", source.name))
            else:
                destination = remote_root
                display_name = source.name
            plan.append((source, destination, display_name))
            continue
        for artifact in sorted(item for item in source.rglob("*") if item.is_file()):
            relative = artifact.relative_to(source)
            destination_parts = [remote_root, source.name, *relative.parts[:-1]]
            destination = separator.join(part.strip("/\\") for part in destination_parts if part)
            if remote_root.startswith(separator):
                destination = separator + destination
            plan.append((artifact, destination, separator.join((source.name, *relative.parts))))
    return plan


def prepare_remote_artifact_directories(selected: Dict[str, Any], access: Dict[str, Any],
                                        directories: List[str], timeout_seconds: int) -> Dict[str, Any]:
    if str(selected.get("osType") or "").upper() == "WINDOWS":
        quoted = ",".join("'" + item.replace("'", "''") + "'" for item in directories)
        command = (f"$paths=@({quoted}); foreach ($path in $paths) {{ "
                   "New-Item -ItemType Directory -Force -Path $path | Out-Null }")
    else:
        command = "mkdir -p " + " ".join(shlex.quote(item) for item in directories)
    return run_command(selected, access, command, max(30, timeout_seconds))


def sync_persisted_relay_artifacts(timeout_seconds: int = 180) -> Dict[str, Any]:
    state = load_state()
    selected = dict(state.get("selectedResources") or {})
    if not selected.get("host") or not state.get("remoteDirectory"):
        return {"success": False, "status": "CENTER_NOT_CONFIGURED", "summary": "没有已配置的远端中心"}
    selected.update({"port": state.get("sshPort") or 22, "nodeKey": state.get("nodeKey")})
    config = ccrelay_ssh.load_config()
    access = runtime_access(selected, config)
    bundle = skill_root() / "assets" / "runtime-bundle" / ccrelay_identity.DEFAULT_PRODUCT_NAME
    result = sync_relay_artifacts(
        selected, access, state["remoteDirectory"], config, bundle, timeout_seconds,
    )
    return {
        **result,
        "status": "CENTER_RELAY_ARTIFACTS_READY" if result.get("success") else "CENTER_RELAY_ARTIFACTS_FAILED",
        "centerUrl": state.get("centerUrl"),
        "remoteDirectory": state.get("remoteDirectory"),
        "selected": public_candidate(selected),
    }


def diagnose_persisted_center(timeout_seconds: int = 20, log_lines: int = 80,
                              requested_source: str = "AUTO") -> Dict[str, Any]:
    source = str(requested_source or "AUTO").upper()
    state = load_state() if source != "ATTEMPT" else {}
    state_source = "ACTIVE_CENTER"
    if source == "ATTEMPT" or not state.get("remoteDirectory"):
        state = load_bootstrap_attempt()
        state_source = "LAST_BOOTSTRAP_ATTEMPT"
    selected = dict(state.get("selectedResources") or {})
    if not selected.get("host") or not state.get("remoteDirectory") or not state.get("centerPort"):
        return {
            "success": False,
            "status": "CENTER_NOT_CONFIGURED",
            "summary": "没有已配置的远端中心或可诊断的 bootstrap 尝试",
        }
    selected.update({"port": state.get("sshPort") or 22, "nodeKey": state.get("nodeKey")})
    config = ccrelay_ssh.load_config()
    access = runtime_access(selected, config)
    result = diagnose_remote_center(
        selected, access, state["remoteDirectory"], int(state["centerPort"]), timeout_seconds, log_lines,
    )
    relay = None
    if state.get("relayPort"):
        relay = diagnose_remote_relay(
            selected, access, state["remoteDirectory"], int(state["relayPort"]), timeout_seconds, log_lines,
        )
    return {
        **result,
        "status": "CENTER_DIAGNOSTIC_READY" if result.get("success") else "CENTER_DIAGNOSTIC_FAILED",
        "centerUrl": state.get("centerUrl"),
        "relayPort": state.get("relayPort"),
        "relayEndpoint": state.get("relayEndpoint"),
        "relayNodeId": state.get("relayNodeId"),
        "relay": relay,
        "stateSource": state_source,
        "bootstrapStatus": state.get("status") if state_source == "LAST_BOOTSTRAP_ATTEMPT" else None,
        "bootstrapPhase": state.get("phase") if state_source == "LAST_BOOTSTRAP_ATTEMPT" else None,
        "bootstrapFailureType": state.get("failureType") if state_source == "LAST_BOOTSTRAP_ATTEMPT" else None,
        "remoteDirectory": state.get("remoteDirectory"),
        "selected": public_candidate(selected),
    }


def active_center_status(timeout_seconds: int = 20) -> Optional[Dict[str, Any]]:
    state = load_state()
    selected = dict(state.get("selectedResources") or {})
    if not selected.get("host") or not state.get("centerUrl") or not state.get("relayPort"):
        return None
    selected.update({"port": state.get("sshPort") or 22, "nodeKey": state.get("nodeKey")})
    config = ccrelay_ssh.load_config()
    access = runtime_access(selected, config)
    health = wait_health(state["centerUrl"], timeout_seconds)
    relay_health = wait_remote_relay_health(
        selected, access, int(state["relayPort"]), timeout_seconds,
    )
    relay_endpoint = state.get("relayEndpoint") or (
        f"http://{selected['host']}:{int(state['relayPort'])}/api/ai/remote-cc/chat"
    )
    relay_registration = wait_center_relay_registration(
        state["centerUrl"], selected["host"], int(state["relayPort"]), relay_endpoint, timeout_seconds,
    ) if health and relay_health.get("success") else {"success": False}
    if health and relay_health.get("success") and relay_registration.get("success"):
        return {
            "success": True,
            "status": "REMOTE_CENTER_READY",
            "alreadyReady": True,
            "centerUrl": state["centerUrl"],
            "selected": public_candidate(selected),
            "remoteDirectory": state.get("remoteDirectory"),
            "health": health,
            "relayHealth": relay_health,
            "relayRegistration": relay_registration,
            "crossNodeReachability": state.get("crossNodeReachability"),
            "centerRelayStatus": "CENTER_RELAY_READY",
            "statePath": str(state_path()),
        }
    return {
        "success": False,
        "status": "CENTER_CONFIGURED_BUT_UNAVAILABLE",
        "failureType": "CENTER_CONFIGURED_BUT_UNAVAILABLE",
        "centerUrl": state.get("centerUrl"),
        "selected": public_candidate(selected),
        "remoteDirectory": state.get("remoteDirectory"),
        "health": health,
        "relayHealth": relay_health,
        "relayRegistration": relay_registration,
        "summary": "已配置的远端 Center 未通过健康、Relay 注册或心跳门禁；不会自动创建数据分叉中心。",
    }


def cleanup_bootstrap_attempt(timeout_seconds: int = 20) -> Dict[str, Any]:
    attempt = load_bootstrap_attempt()
    selected = dict(attempt.get("selectedResources") or {})
    if not selected.get("host") or not attempt.get("remoteDirectory"):
        return {"success": False, "status": "CENTER_BOOTSTRAP_ATTEMPT_NOT_FOUND"}
    selected.update({"port": attempt.get("sshPort") or 22, "nodeKey": attempt.get("nodeKey")})
    config = ccrelay_ssh.load_config()
    access = runtime_access(selected, config)
    diagnostic = diagnose_persisted_center(timeout_seconds, 80, "ATTEMPT")
    stopped = stop_remote_center(selected, access, attempt["remoteDirectory"], timeout_seconds)
    removed = False
    if stopped.get("success"):
        path = bootstrap_attempt_path()
        if path.is_file():
            path.unlink()
            removed = True
    return {
        **stopped,
        "status": "CENTER_BOOTSTRAP_ATTEMPT_CLEANED" if stopped.get("success") else "CENTER_BOOTSTRAP_ATTEMPT_CLEANUP_FAILED",
        "diagnostic": diagnostic,
        "attemptStateRemoved": removed,
        "remoteDirectory": attempt.get("remoteDirectory"),
        "selected": public_candidate(selected),
    }


def stop_persisted_center(timeout_seconds: int = 20) -> Dict[str, Any]:
    state = load_state()
    selected = dict(state.get("selectedResources") or {})
    if not selected.get("host") or not state.get("remoteDirectory"):
        return {"success": False, "status": "CENTER_NOT_CONFIGURED", "summary": "没有已配置的远端中心"}
    selected.update({"port": state.get("sshPort") or 22, "nodeKey": state.get("nodeKey")})
    config = ccrelay_ssh.load_config()
    access = runtime_access(selected, config)
    stopped = stop_remote_center(selected, access, state["remoteDirectory"], timeout_seconds)
    if stopped.get("success"):
        path = state_path()
        if path.is_file():
            path.unlink()
        center = (config.get("clusterIdentity") or {}).get("center") or {}
        if center.get("centerUrl") == state.get("centerUrl"):
            config["clusterIdentity"].pop("center", None)
            ccrelay_ssh.save_config(config)
    return {
        **stopped,
        "status": "REMOTE_CENTER_STOPPED" if stopped.get("success") else "REMOTE_CENTER_STOP_FAILED",
        "centerStopped": bool(stopped.get("success")),
        "relayStopped": bool(stopped.get("success")),
        "centerUrl": state.get("centerUrl"),
        "remoteDirectory": state.get("remoteDirectory"),
        "selected": public_candidate(selected),
    }


def remove_remote_runtime(node: Dict[str, Any], access: Dict[str, Any], directory: str,
                          timeout_seconds: int) -> Dict[str, Any]:
    if str(node.get("osType") or "").upper() == "WINDOWS":
        escaped = directory.replace("'", "''")
        command = (
            "powershell.exe -NoProfile -NonInteractive -Command \""
            f"$bundle='{escaped}'; foreach($name in @('center-relay.pid','center.pid','relay.pid')){{"
            "$f=Join-Path $bundle $name; if(Test-Path $f){$processId=Get-Content $f -ErrorAction SilentlyContinue; "
            "if($processId){Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue}}}; "
            "if(Test-Path $bundle){Remove-Item -Recurse -Force $bundle}; Write-Output 'CCRELAY_RUNTIME_REMOVED'\""
        )
    else:
        quoted = shlex.quote(directory)
        command = (
            f"bundle={quoted}; for name in center-relay.pid center.pid relay.pid; do "
            "f=\"$bundle/$name\"; if [ -f \"$f\" ]; then p=$(cat \"$f\" 2>/dev/null || true); "
            "[ -z \"$p\" ] || kill \"$p\" >/dev/null 2>&1 || true; fi; done; "
            "rm -rf -- \"$bundle\"; printf 'CCRELAY_RUNTIME_REMOVED\\n'"
        )
    return run_command(node, access, command, timeout_seconds, include_output=True)


def cleanup_persisted_cluster(timeout_seconds: int = 30) -> Dict[str, Any]:
    state = load_state()
    config = ccrelay_ssh.load_config()
    identity = config.get("clusterIdentity") or {}
    managed = identity.get("managedNodes") or []
    center = identity.get("center") or {}
    selected = dict(state.get("selectedResources") or {})
    if not selected.get("host"):
        selected = {"host": center.get("sshNodeKey", "").split(":", 1)[0],
                    "port": int(state.get("sshPort") or 22), "osType": "LINUX"}
    if not selected.get("host"):
        return {"success": False, "status": "CENTER_NOT_CONFIGURED", "summary": "没有可清理的远端中心状态"}
    selected["port"] = int(selected.get("port") or state.get("sshPort") or 22)
    selected_key = f"{selected['host']}:{selected['port']}"
    relay_port = int(state.get("relayPort") or center.get("relayPort") or 18192)
    targets: List[Dict[str, Any]] = []
    for item in managed:
        if item.get("host"):
            targets.append(dict(item))
    if not any(f"{item.get('host')}:{int(item.get('port') or 22)}" == selected_key for item in targets):
        targets.append(selected)
    results = []
    for item in targets:
        item["port"] = int(item.get("port") or 22)
        access = runtime_access(item, config)
        if f"{item.get('host')}:{item['port']}" == selected_key and state.get("remoteDirectory"):
            directory = state["remoteDirectory"]
        else:
            directory = render_remote_directory(item, relay_port, config, access)
        result = remove_remote_runtime(item, access, directory, timeout_seconds)
        results.append({"node": public_candidate(item), "remoteDirectory": directory, **result})
    success = all(item.get("success") for item in results) if results else False
    state_path_value = state_path()
    if state_path_value.is_file():
        state_path_value.unlink()
    if center.get("centerUrl") == state.get("centerUrl") or not state.get("centerUrl"):
        identity.pop("center", None)
        ccrelay_ssh.save_config(config)
    return {
        "success": success,
        "status": "REMOTE_CLUSTER_CLEANED" if success else "REMOTE_CLUSTER_CLEANUP_FAILED",
        "preserved": ["SSH_CREDENTIALS", "DEDICATED_ACCOUNT", "CLUSTER_KEY", "PASSWORDLESS_TRUST"],
        "results": results,
    }


def run_command(node: Dict[str, Any], access: Dict[str, Any], command: str, timeout_seconds: int,
                stdin_text: Optional[str] = None, include_output: bool = False) -> Dict[str, Any]:
    if access.get("keyPath"):
        return ccrelay_ssh.run_key_command(
            node["host"], int(node["port"]), access["username"], access["keyPath"], command,
            timeout_seconds, stdin_text=stdin_text, include_output=include_output)
    return ccrelay_ssh.run_authenticated_command(
        node["host"], int(node["port"]), access["username"], command,
        timeout_seconds, stdin_text=stdin_text, include_output=include_output)


def linux_probe_script(port_start: int, port_end: int) -> str:
    return f"""export PATH="{ccrelay_ssh.REMOTE_POSIX_PATH}:$PATH"
set -eu
cpu=$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || printf 1)
load=$(awk '{{print $1}}' /proc/loadavg 2>/dev/null || printf 0)
memory_kb=$(awk '/MemAvailable:/ {{print $2; exit}}' /proc/meminfo 2>/dev/null || printf 0)
disk_kb=$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2 {{print $4}}')
arch=$(uname -m 2>/dev/null || printf unknown)
writable=false
test -w "$HOME" && writable=true
  port=''
  relay_port=''
  p={port_start}
  while [ "$p" -le {port_end} ]; do
  used=false
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk '{{print $4}}' | grep -Eq '[:.]'"$p"'$' && used=true || true
  elif command -v netstat >/dev/null 2>&1; then
    netstat -ltn 2>/dev/null | awk '{{print $4}}' | grep -Eq '[:.]'"$p"'$' && used=true || true
  fi
   if [ "$used" = false ]; then
     if [ -z "$port" ]; then port=$p; else relay_port=$p; break; fi
   fi
   p=$((p + 1))
 done
 printf 'CCRELAY_RESOURCE|os=LINUX|arch=%s|cpu=%s|load=%s|memoryMb=%s|diskMb=%s|home=%s|writable=%s|port=%s|relayPort=%s\n' \
   "$arch" "$cpu" "$load" "$((memory_kb / 1024))" "$((disk_kb / 1024))" "$HOME" "$writable" "$port" "$relay_port"
"""


def windows_probe_script(port_start: int, port_end: int) -> str:
    return f"""$ErrorActionPreference = 'Stop'
$os = Get-CimInstance Win32_OperatingSystem
$cpu = (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors
$disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$($env:SystemDrive)'"
 $selectedPort = 0
 $selectedRelayPort = 0
 foreach ($candidate in {port_start}..{port_end}) {{
  try {{
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $candidate)
     $listener.Start(); $listener.Stop();
     if ($selectedPort -eq 0) {{ $selectedPort = $candidate }} else {{ $selectedRelayPort = $candidate; break }}
  }} catch {{ }}
}}
$homeWritable = $false
try {{ $probe = Join-Path $env:USERPROFILE '.ccrelay-write-probe'; [IO.File]::WriteAllText($probe, '1'); Remove-Item $probe; $homeWritable = $true }} catch {{ }}
 Write-Output ('CCRELAY_RESOURCE|os=WINDOWS|arch=' + $env:PROCESSOR_ARCHITECTURE + '|cpu=' + $cpu + '|load=0|memoryMb=' + [int]($os.FreePhysicalMemory / 1024) + '|diskMb=' + [int]($disk.FreeSpace / 1MB) + '|home=' + $env:USERPROFILE.Replace('\\','/') + '|writable=' + $homeWritable.ToString().ToLower() + '|port=' + $selectedPort + '|relayPort=' + $selectedRelayPort)
"""


def create_remote_directory(node: Dict[str, Any], access: Dict[str, Any], directory: str,
                            timeout_seconds: int) -> Dict[str, Any]:
    if str(node.get("osType") or "").upper() == "WINDOWS":
        escaped = directory.replace("'", "''")
        command = f"powershell.exe -NoProfile -NonInteractive -Command \"New-Item -ItemType Directory -Force -Path '{escaped}' | Out-Null\""
    else:
        command = f"export PATH=\"{ccrelay_ssh.REMOTE_POSIX_PATH}:$PATH\"; mkdir -p {shlex.quote(directory)}"
    return run_command(node, access, command, timeout_seconds)


def start_remote_center(node: Dict[str, Any], access: Dict[str, Any], directory: str, port: int,
                        hmac_secret: str, timeout_seconds: int,
                        key_algorithm: str = "RSA") -> Dict[str, Any]:
    if str(node.get("osType") or "").upper() == "WINDOWS":
        script = windows_start_script(directory, port, hmac_secret, key_algorithm)
        command = "powershell.exe -NoProfile -NonInteractive -Command -"
    else:
        script = linux_start_script(directory, port, hmac_secret, key_algorithm)
        command = "/bin/sh -s"
    return run_command(node, access, command, timeout_seconds, stdin_text=script, include_output=True)


def start_remote_relay(node: Dict[str, Any], access: Dict[str, Any], directory: str, center_port: int,
                       relay_port: int, hmac_secret: str, timeout_seconds: int) -> Dict[str, Any]:
    if str(node.get("osType") or "").upper() == "WINDOWS":
        script = windows_relay_start_script(
            directory, node["host"], center_port, relay_port, hmac_secret,
        )
        command = "powershell.exe -NoProfile -NonInteractive -Command -"
    else:
        script = linux_relay_start_script(
            directory, node["host"], center_port, relay_port, hmac_secret,
        )
        command = "/bin/sh -s"
    return run_command(
        node, access, command, max(20, timeout_seconds), stdin_text=script, include_output=True,
    )


def wait_remote_health(node: Dict[str, Any], access: Dict[str, Any], port: int,
                       timeout_seconds: int) -> Dict[str, Any]:
    timeout = max(10, int(timeout_seconds))
    if str(node.get("osType") or "").upper() == "WINDOWS":
        command = (
            "powershell.exe -NoProfile -NonInteractive -Command \""
            f"$deadline=(Get-Date).AddSeconds({timeout}); "
            f"$url='http://127.0.0.1:{port}/api/skill/health'; "
            "while((Get-Date)-lt $deadline){try{$r=Invoke-RestMethod -TimeoutSec 3 $url; "
            "if($r.status -eq 'UP'){Write-Output 'CCRELAY_REMOTE_HEALTH_UP'; exit 0}}catch{}; "
            "Start-Sleep -Seconds 1}; exit 1\""
        )
        return run_command(node, access, command, timeout + 5)
    url = shlex.quote(f"http://127.0.0.1:{port}/api/skill/health")
    script = f"""export PATH="{ccrelay_ssh.REMOTE_POSIX_PATH}:$PATH"
deadline=$(( $(date +%s) + {timeout} ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 3 {url} 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && exit 0
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T 3 -O - {url} 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && exit 0
  else
    exit 47
  fi
  sleep 1
done
exit 1
"""
    return run_command(node, access, "/bin/sh -s", timeout + 5, stdin_text=script)


def wait_remote_relay_health(node: Dict[str, Any], access: Dict[str, Any], relay_port: int,
                             timeout_seconds: int) -> Dict[str, Any]:
    timeout = max(10, int(timeout_seconds))
    if str(node.get("osType") or "").upper() == "WINDOWS":
        command = (
            "powershell.exe -NoProfile -NonInteractive -Command \""
            f"$deadline=(Get-Date).AddSeconds({timeout}); "
            f"$url='http://127.0.0.1:{relay_port}/health'; "
            "while((Get-Date)-lt $deadline){try{$r=Invoke-RestMethod -TimeoutSec 3 $url; "
            "if($r.status -eq 'UP'){Write-Output 'CCRELAY_REMOTE_RELAY_HEALTH_UP'; exit 0}}catch{}; "
            "Start-Sleep -Seconds 1}; exit 1\""
        )
        result = run_command(node, access, command, timeout + 5, include_output=True)
    else:
        url = shlex.quote(f"http://127.0.0.1:{relay_port}/health")
        script = f"""export PATH="{ccrelay_ssh.REMOTE_POSIX_PATH}:$PATH"
deadline=$(( $(date +%s) + {timeout} ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 3 {url} 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && exit 0
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T 3 -O - {url} 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && exit 0
  else
    exit 47
  fi
  sleep 1
done
exit 1
"""
        result = run_command(
            node, access, "/bin/sh -s", timeout + 5, stdin_text=script, include_output=True,
        )
    if result.get("success"):
        return {**result, "status": "UP", "relayPort": relay_port}
    return {
        **result,
        "status": "DOWN",
        "relayPort": relay_port,
        "summary": result.get("summary") or f"Relay 本机健康检查超时: 127.0.0.1:{relay_port}",
    }


def wait_center_relay_registration(center_url: str, relay_host: str, relay_port: int,
                                   relay_endpoint: str, timeout_seconds: int) -> Dict[str, Any]:
    deadline = time.monotonic() + max(10, int(timeout_seconds))
    url = center_url.rstrip("/") + "/api/skill/relay/nodes"
    last_error = None
    last_node = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                value = json.loads(response.read().decode("utf-8", errors="replace"))
            nodes = value if isinstance(value, list) else []
            for node in nodes:
                if not isinstance(node, dict):
                    continue
                same_host_port = str(node.get("host") or "") == str(relay_host) and parse_int(node.get("port")) == relay_port
                same_endpoint = str(node.get("relayEndpoint") or "").rstrip("/") == relay_endpoint.rstrip("/")
                if not same_host_port and not same_endpoint:
                    continue
                last_node = node
                heartbeat_ready = bool(str(node.get("lastHeartbeatTime") or "").strip())
                available = str(node.get("status") or "").upper() == "AVAILABLE"
                role = str((node.get("environmentSummary") or {}).get("nodeRole") or "").upper()
                ai_ready = str((node.get("environmentSummary") or {}).get("aiReadiness") or "").upper() == "READY"
                if heartbeat_ready and available and ai_ready and role == "CENTER_RELAY":
                    return {
                        "success": True,
                        "status": "CENTER_RELAY_REGISTERED",
                        "node": node,
                        "registrationEndpoint": url,
                    }
        except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
            last_error = str(exc)
        time.sleep(1)
    detail = ""
    if last_node:
        detail = (
            f"; lastStatus={last_node.get('status')}, "
            f"lastHeartbeatTime={last_node.get('lastHeartbeatTime')}, "
            f"nodeRole={(last_node.get('environmentSummary') or {}).get('nodeRole')}, "
            f"aiReadiness={(last_node.get('environmentSummary') or {}).get('aiReadiness')}"
        )
    elif last_error:
        detail = f"; lastError={last_error}"
    return {
        "success": False,
        "status": "CENTER_RELAY_REGISTRATION_TIMEOUT",
        "node": last_node,
        "registrationEndpoint": url,
        "summary": f"中心 Relay 未在超时内完成注册和首次心跳{detail}",
    }


def diagnose_remote_center(node: Dict[str, Any], access: Dict[str, Any], directory: str, port: int,
                           timeout_seconds: int, log_lines: int = 80) -> Dict[str, Any]:
    log_lines = max(20, min(int(log_lines), 500))
    if str(node.get("osType") or "").upper() == "WINDOWS":
        escaped = directory.replace("'", "''")
        command = (
            "powershell.exe -NoProfile -NonInteractive -Command \""
            f"$bundle='{escaped}'; $pidFile=Join-Path $bundle 'center.pid'; "
            "$alive=$false; if(Test-Path $pidFile){$processId=Get-Content $pidFile -ErrorAction SilentlyContinue; "
            "if($processId){$alive=[bool](Get-Process -Id $processId -ErrorAction SilentlyContinue)}}; "
            f"$healthy=$false; try{{$r=Invoke-RestMethod -TimeoutSec 3 'http://127.0.0.1:{port}/api/skill/health'; "
            "$healthy=($r.status -eq 'UP')}catch{}; "
            "Write-Output ('CCRELAY_CENTER_DIAGNOSTIC|processAlive=' + $alive.ToString().ToLower() + "
            "'|localHealth=' + $healthy.ToString().ToLower()); "
            "$logs=@((Join-Path $bundle 'center-runtime.log'),(Join-Path $bundle 'center-error.log')); "
            f"foreach($log in $logs){{if(Test-Path $log){{Get-Content $log -Tail {log_lines}}}}}\""
        )
        result = run_command(node, access, command, timeout_seconds, include_output=True)
    else:
        quoted = shlex.quote(directory)
        url = shlex.quote(f"http://127.0.0.1:{port}/api/skill/health")
        script = f"""export PATH="{ccrelay_ssh.REMOTE_POSIX_PATH}:$PATH"
bundle={quoted}
alive=false
if [ -f "$bundle/center.pid" ]; then
  pid=$(cat "$bundle/center.pid" 2>/dev/null || true)
  if [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1; then alive=true; fi
fi
healthy=false
if command -v curl >/dev/null 2>&1; then
  curl -fsS --max-time 3 {url} 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && healthy=true || true
elif command -v wget >/dev/null 2>&1; then
  wget -q -T 3 -O - {url} 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && healthy=true || true
fi
printf 'CCRELAY_CENTER_DIAGNOSTIC|processAlive=%s|localHealth=%s\n' "$alive" "$healthy"
tail -n {log_lines} "$bundle/center-runtime.log" 2>/dev/null || true
"""
        result = run_command(
            node, access, "/bin/sh -s", timeout_seconds,
            stdin_text=script, include_output=True,
        )
    values = parse_marker(result.get("stdout"), "CCRELAY_CENTER_DIAGNOSTIC|")
    lines = str(result.get("stdout") or "").splitlines()
    log_tail = "\n".join(
        line for line in lines if not line.strip().startswith("CCRELAY_CENTER_DIAGNOSTIC|")
    ).strip()
    process_alive = values.get("processAlive") == "true"
    local_health = values.get("localHealth") == "true"
    if local_health:
        summary = "远端中心本机健康，外部控制链路不可达"
    elif process_alive:
        summary = "远端中心进程仍在运行，但本机健康检查未通过"
    else:
        summary = "远端中心进程已退出"
    return {
        "success": bool(result.get("success")),
        "processAlive": process_alive,
        "localHealth": local_health,
        "summary": summary,
        "logTail": log_tail[-max(4000, log_lines * 300):],
    }


def diagnose_remote_relay(node: Dict[str, Any], access: Dict[str, Any], directory: str, relay_port: int,
                          timeout_seconds: int, log_lines: int = 80) -> Dict[str, Any]:
    log_lines = max(20, min(int(log_lines), 500))
    if str(node.get("osType") or "").upper() == "WINDOWS":
        escaped = directory.replace("'", "''")
        command = (
            "powershell.exe -NoProfile -NonInteractive -Command \""
            f"$bundle='{escaped}'; $pidFile=Join-Path $bundle 'center-relay.pid'; "
            "$alive=$false; if(Test-Path $pidFile){$processId=Get-Content $pidFile -ErrorAction SilentlyContinue; "
            "if($processId){$alive=[bool](Get-Process -Id $processId -ErrorAction SilentlyContinue)}}; "
            f"$healthy=$false; try{{$r=Invoke-RestMethod -TimeoutSec 3 'http://127.0.0.1:{relay_port}/health'; "
            "$healthy=($r.status -eq 'UP')}catch{}; "
            "Write-Output ('CCRELAY_RELAY_DIAGNOSTIC|processAlive=' + $alive.ToString().ToLower() + "
            "'|localHealth=' + $healthy.ToString().ToLower()); "
            "$logs=@((Join-Path $bundle 'center-relay.log'),(Join-Path $bundle 'center-relay.error.log')); "
            f"foreach($log in $logs){{if(Test-Path $log){{Get-Content $log -Tail {log_lines}}}}}\""
        )
        result = run_command(node, access, command, timeout_seconds, include_output=True)
    else:
        quoted = shlex.quote(directory)
        url = shlex.quote(f"http://127.0.0.1:{relay_port}/health")
        script = f"""export PATH="{ccrelay_ssh.REMOTE_POSIX_PATH}:$PATH"
bundle={quoted}
alive=false
if [ -f "$bundle/center-relay.pid" ]; then
  pid=$(cat "$bundle/center-relay.pid" 2>/dev/null || true)
  if [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1; then alive=true; fi
fi
healthy=false
if command -v curl >/dev/null 2>&1; then
  curl -fsS --max-time 3 {url} 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && healthy=true || true
elif command -v wget >/dev/null 2>&1; then
  wget -q -T 3 -O - {url} 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && healthy=true || true
fi
printf 'CCRELAY_RELAY_DIAGNOSTIC|processAlive=%s|localHealth=%s\n' "$alive" "$healthy"
tail -n {log_lines} "$bundle/center-relay.log" 2>/dev/null || true
"""
        result = run_command(
            node, access, "/bin/sh -s", timeout_seconds, stdin_text=script, include_output=True,
        )
    values = parse_marker(result.get("stdout"), "CCRELAY_RELAY_DIAGNOSTIC|")
    lines = str(result.get("stdout") or "").splitlines()
    log_tail = "\n".join(
        line for line in lines if not line.strip().startswith("CCRELAY_RELAY_DIAGNOSTIC|")
    ).strip()
    return {
        "success": bool(result.get("success")),
        "processAlive": values.get("processAlive") == "true",
        "localHealth": values.get("localHealth") == "true",
        "relayPort": relay_port,
        "summary": "中心同机 Relay 诊断完成" if result.get("success") else result.get("summary"),
        "logTail": log_tail[-max(4000, log_lines * 300):],
    }


def linux_start_script(directory: str, port: int, hmac_secret: str,
                       key_algorithm: str = "RSA") -> str:
    return f"""export PATH="{ccrelay_ssh.REMOTE_POSIX_PATH}:$PATH"
set -eu
bundle={shlex.quote(directory)}
app="$bundle/app.jar"
owned_pids="$(ps -eo pid=,args= 2>/dev/null | awk -v app="$app" 'index($0, " -jar " app) {{print $1}}')"
if [ -n "$owned_pids" ]; then
  kill $owned_pids >/dev/null 2>&1 || true
  attempts=0
  while [ "$attempts" -lt 10 ]; do
    remaining=''
    for pid in $owned_pids; do kill -0 "$pid" >/dev/null 2>&1 && remaining="$remaining $pid" || true; done
    [ -z "$remaining" ] && break
    owned_pids="$remaining"
    attempts=$((attempts + 1))
    sleep 1
  done
  [ -z "$remaining" ] || kill -9 $remaining >/dev/null 2>&1 || true
fi
rm -f "$bundle/center.pid"
port_in_use=false
if [ -r /proc/net/tcp ]; then
  port_hex=$(printf '%04X' {port})
  tcp_tables=/proc/net/tcp
  [ ! -r /proc/net/tcp6 ] || tcp_tables="$tcp_tables /proc/net/tcp6"
  awk -v suffix=":$port_hex" 'NR > 1 && $4 == "0A" && substr($2, length($2) - length(suffix) + 1) == suffix {{found=1}} END {{exit found ? 0 : 1}}' $tcp_tables 2>/dev/null && port_in_use=true || true
elif command -v ss >/dev/null 2>&1; then
  ss -ltn 2>/dev/null | awk '{{print $4}}' | grep -Eq '[:.]'{port}'$' && port_in_use=true || true
elif command -v netstat >/dev/null 2>&1; then
  netstat -ltn 2>/dev/null | awk '{{print $4}}' | grep -Eq '[:.]'{port}'$' && port_in_use=true || true
fi
[ "$port_in_use" = false ] || {{ printf 'CCRELAY_CENTER_PORT_IN_USE|port=%s\n' {port} >&2; exit 42; }}
runtime_tmp="$bundle/.runtime-extract.$$"
rm -rf "$runtime_tmp"
mkdir -p "$runtime_tmp"
tar -xzf "$bundle/runtime.tar.gz" -C "$runtime_tmp" --strip-components=1
rm -rf "$bundle/runtime"
mv "$runtime_tmp" "$bundle/runtime"
java_bin="$bundle/runtime/bin/java"
test -f "$bundle/app.jar"
test -x "$java_bin" || chmod +x "$java_bin"
export CCRELAY_PORT={port}
export CCRELAY_DB="$bundle/center-skill.db"
export WDSAVS_AI_HMAC_SECRET={shlex.quote(hmac_secret)}
export CCRELAY_SSH_KEY_ALGORITHM={shlex.quote(ccrelay_identity.normalize_key_algorithm(key_algorithm))}
export SERVER_ADDRESS=0.0.0.0
nohup "$java_bin" -jar "$bundle/app.jar" >> "$bundle/center-runtime.log" 2>&1 < /dev/null &
echo $! > "$bundle/center.pid"
printf 'CCRELAY_CENTER_STARTED|pid=%s|port=%s\n' "$!" {port}
"""


def windows_start_script(directory: str, port: int, hmac_secret: str,
                         key_algorithm: str = "RSA") -> str:
    def ps(value: str) -> str:
        return "'" + value.replace("'", "''") + "'"
    return f"""$ErrorActionPreference = 'Stop'
$bundle = {ps(directory)}
$app = Join-Path $bundle 'app.jar'
$owned = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {{ $_.CommandLine -and $_.CommandLine.IndexOf($app, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and $_.CommandLine -match '(^|\\s)-jar(\\s|$)' }})
foreach ($item in $owned) {{ Stop-Process -Id $item.ProcessId -Force -ErrorAction SilentlyContinue }}
$pidFile = Join-Path $bundle 'center.pid'
Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
$portAvailable = $false
$deadline = (Get-Date).AddSeconds(10)
do {{
  $listener = $null
  try {{
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, {port})
    $listener.Start()
    $portAvailable = $true
  }} catch {{
    Start-Sleep -Seconds 1
  }} finally {{
    if ($listener) {{ $listener.Stop() }}
  }}
}} while (-not $portAvailable -and (Get-Date) -lt $deadline)
if (-not $portAvailable) {{ throw 'CCRELAY_CENTER_PORT_IN_USE|port={port}' }}
$runtime = Join-Path $bundle 'runtime-windows'
$runtimeTemp = Join-Path $bundle ('.runtime-extract-' + [Guid]::NewGuid().ToString('N'))
Expand-Archive -Path (Join-Path $bundle 'runtime-windows.zip') -DestinationPath $runtimeTemp -Force
if (Test-Path $runtime) {{ Remove-Item -Recurse -Force $runtime }}
Move-Item -Force (Get-ChildItem $runtimeTemp | Select-Object -First 1).FullName $runtime
Remove-Item -Recurse -Force $runtimeTemp -ErrorAction SilentlyContinue
$java = Join-Path $bundle 'runtime-windows/bin/java.exe'
if (-not (Test-Path $java)) {{ throw 'Bundled Windows JRE missing' }}
$env:CCRELAY_PORT = '{port}'
$env:CCRELAY_DB = Join-Path $bundle 'center-skill.db'
$env:WDSAVS_AI_HMAC_SECRET = {ps(hmac_secret)}
$env:CCRELAY_SSH_KEY_ALGORITHM = {ps(ccrelay_identity.normalize_key_algorithm(key_algorithm))}
$env:SERVER_ADDRESS = '0.0.0.0'
$log = Join-Path $bundle 'center-runtime.log'
$process = Start-Process -FilePath $java -ArgumentList @('-jar', (Join-Path $bundle 'app.jar')) -WorkingDirectory $bundle -RedirectStandardOutput $log -RedirectStandardError (Join-Path $bundle 'center-error.log') -WindowStyle Hidden -PassThru
Set-Content -Path $pidFile -Value $process.Id
Write-Output ('CCRELAY_CENTER_STARTED|pid=' + $process.Id + '|port={port}')
"""


def linux_relay_start_script(directory: str, relay_host: str, center_port: int, relay_port: int,
                             hmac_secret: str) -> str:
    center_base = f"http://127.0.0.1:{center_port}/api/skill"
    relay_endpoint = f"http://{relay_host}:{relay_port}/api/ai/remote-cc/chat"
    return f"""export PATH="{ccrelay_ssh.REMOTE_POSIX_PATH}:$PATH"
set -eu
bundle={shlex.quote(directory)}
umask 077
mkdir -p "$bundle/config"
printf '%s' {shlex.quote(hmac_secret)} > "$bundle/config/relay-hmac-secret"
chmod 600 "$bundle/config/relay-hmac-secret"
export WDSAVS_CC_RELAY_HOST=0.0.0.0
export WDSAVS_CC_RELAY_PORT={relay_port}
export WDSAVS_CC_RELAY_PID_FILE="$bundle/center-relay.pid"
export WDSAVS_CC_RELAY_LOG_FILE="$bundle/center-relay.log"
export WDSAVS_CC_RELAY_PRESERVE_ARCHIVES=true
export WDSAVS_AI_RELAY_NODE_ID_FILE="$bundle/config/center-relay-node-id.txt"
export WDSAVS_AI_RELAY_NODE_HOST={shlex.quote(relay_host)}
export WDSAVS_AI_RELAY_NODE_ROLE=CENTER_RELAY
export WDSAVS_AI_RELAY_ENDPOINT={shlex.quote(relay_endpoint)}
export WDSAVS_AI_RELAY_REGISTER_ENDPOINT={shlex.quote(center_base + '/relay/register')}
export WDSAVS_AI_RELAY_HEARTBEAT_ENDPOINT={shlex.quote(center_base + '/relay/heartbeat')}
export WDSAVS_AI_RELAY_GRANT_VALIDATE_ENDPOINT={shlex.quote(center_base + '/relay/access/validate')}
export WDSAVS_AI_HMAC_SECRET={shlex.quote(hmac_secret)}
chmod +x "$bundle/install-relay.sh"
"$bundle/install-relay.sh" "$bundle" "--server.port={relay_port}"
"""


def windows_relay_start_script(directory: str, relay_host: str, center_port: int, relay_port: int,
                               hmac_secret: str) -> str:
    def ps(value: str) -> str:
        return "'" + value.replace("'", "''") + "'"
    center_base = f"http://127.0.0.1:{center_port}/api/skill"
    relay_endpoint = f"http://{relay_host}:{relay_port}/api/ai/remote-cc/chat"
    return f"""$ErrorActionPreference = 'Stop'
$bundle = {ps(directory)}
$secretFile = Join-Path $bundle 'config/relay-hmac-secret'
New-Item -ItemType Directory -Force -Path (Split-Path $secretFile -Parent) | Out-Null
Set-Content -Path $secretFile -Value {ps(hmac_secret)} -NoNewline
$env:WDSAVS_CC_RELAY_HOST = '0.0.0.0'
$env:WDSAVS_CC_RELAY_PORT = '{relay_port}'
$env:WDSAVS_CC_RELAY_PID_FILE = Join-Path $bundle 'center-relay.pid'
$env:WDSAVS_CC_RELAY_LOG_FILE = Join-Path $bundle 'center-relay.log'
$env:WDSAVS_CC_RELAY_PRESERVE_ARCHIVES = 'true'
$env:WDSAVS_AI_RELAY_NODE_ID_FILE = Join-Path $bundle 'config/center-relay-node-id.txt'
$env:WDSAVS_AI_RELAY_NODE_HOST = {ps(relay_host)}
$env:WDSAVS_AI_RELAY_NODE_ROLE = 'CENTER_RELAY'
$env:WDSAVS_AI_RELAY_ENDPOINT = {ps(relay_endpoint)}
$env:WDSAVS_AI_RELAY_REGISTER_ENDPOINT = {ps(center_base + '/relay/register')}
$env:WDSAVS_AI_RELAY_HEARTBEAT_ENDPOINT = {ps(center_base + '/relay/heartbeat')}
$env:WDSAVS_AI_RELAY_GRANT_VALIDATE_ENDPOINT = {ps(center_base + '/relay/access/validate')}
$env:WDSAVS_AI_HMAC_SECRET = {ps(hmac_secret)}
& (Join-Path $bundle 'install-relay.ps1') -BundleDirectory $bundle -RelayArguments @('--server.port={relay_port}')
"""


def stop_remote_center(node: Dict[str, Any], access: Dict[str, Any], directory: str,
                       timeout_seconds: int) -> Dict[str, Any]:
    if str(node.get("osType") or "").upper() == "WINDOWS":
        escaped = directory.replace("'", "''")
        command = (
            "powershell.exe -NoProfile -NonInteractive -Command \""
            f"$bundle='{escaped}'; foreach($name in @('center-relay.pid','center.pid')){{"
            "$f=Join-Path $bundle $name; if(Test-Path $f){$processId=Get-Content $f -ErrorAction SilentlyContinue; "
            "if($processId){Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue}; "
            "Remove-Item $f -Force -ErrorAction SilentlyContinue}}}\""
        )
    else:
        quoted = shlex.quote(directory)
        command = (
            f"bundle={quoted}; for name in center-relay.pid center.pid; do f=\"$bundle/$name\"; "
            "if [ -f \"$f\" ]; then p=$(cat \"$f\" 2>/dev/null || true); "
            "[ -z \"$p\" ] || kill \"$p\" >/dev/null 2>&1 || true; rm -f \"$f\"; fi; done"
        )
    return run_command(node, access, command, timeout_seconds)


def wait_health(center_url: str, timeout_seconds: int) -> Optional[Dict[str, Any]]:
    deadline = time.monotonic() + max(10, timeout_seconds)
    url = center_url.rstrip("/") + "/api/skill/health"
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                value = json.loads(response.read().decode("utf-8", errors="replace"))
                if isinstance(value, dict) and str(value.get("status") or "").upper() == "UP":
                    return value
        except (OSError, urllib.error.URLError, json.JSONDecodeError):
            pass
        time.sleep(1)
    return None


def verify_from_candidates(center_url: str, candidates: List[Dict[str, Any]], selected: Dict[str, Any],
                           config: Dict[str, Any], timeout_seconds: int) -> Dict[str, Any]:
    others = [
        item for item in candidates
        if item.get("probeSuccess") and item.get("nodeKey") != selected.get("nodeKey")
    ]
    if not others:
        return {"status": "NOT_APPLICABLE", "verifiedNodes": []}
    verified = []
    for item in others:
        access = runtime_access(item, config)
        url = center_url.rstrip("/") + "/api/skill/health"
        if str(item.get("osType") or "").upper() == "WINDOWS":
            command = f"powershell.exe -NoProfile -NonInteractive -Command \"(Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 '{url}').StatusCode\""
        else:
            quoted = shlex.quote(url)
            command = (
                f"export PATH=\"{ccrelay_ssh.REMOTE_POSIX_PATH}:$PATH\"; "
                f"if command -v curl >/dev/null 2>&1; then curl -fsS --max-time 10 {quoted} >/dev/null; "
                f"elif command -v wget >/dev/null 2>&1; then wget -q -T 10 -O /dev/null {quoted}; "
                "else exit 47; fi"
            )
        result = run_command(item, access, command, timeout_seconds)
        verified.append({
            "nodeKey": item.get("nodeKey"),
            "success": bool(result.get("success")),
            "summary": result.get("summary"),
        })
    failures = [item for item in verified if not item["success"]]
    return {
        "status": "FAILED" if failures else "READY",
        "verifiedNodes": verified,
        "summary": "存在节点无法访问自动选出的远端中心" if failures else None,
    }


def render_remote_directory(node: Dict[str, Any], center_port: int, config: Dict[str, Any],
                            access: Dict[str, Any]) -> str:
    template = (
        (config.get("runtime") or {}).get("remoteDirectoryTemplate")
        or ccrelay_identity.DEFAULT_REMOTE_DIRECTORY_TEMPLATE
    )
    username = access["username"]
    values = {
        "bootstrapUser": node.get("username") or username,
        "runtimeUser": username,
        "sshUser": username,
        "productName": ccrelay_identity.DEFAULT_PRODUCT_NAME,
        "host": node["host"],
        "relayPort": str(center_port),
    }
    directory = template
    for key, value in values.items():
        directory = directory.replace(f"${{{key}}}", str(value))
    if str(node.get("osType") or "").upper() == "WINDOWS":
        home = str(node.get("homeDirectory") or f"C:/Users/{username}").rstrip("/")
        prefix = f"/home/{username}/"
        directory = home + "/" + directory[len(prefix):] if directory.startswith(prefix) else directory
    return directory


def remote_path(directory: str, filename: str, os_type: Any) -> str:
    separator = "\\" if str(os_type or "").upper() == "WINDOWS" else "/"
    return directory.rstrip("/\\") + separator + filename


def resource_score(item: Dict[str, Any]) -> float:
    cpu = max(1, int(item.get("cpuCores") or 1))
    memory_gb = float(item.get("memoryAvailableMb") or 0) / 1024.0
    disk_gb = min(float(item.get("diskAvailableMb") or 0) / 1024.0, 500.0)
    load_per_cpu = float(item.get("loadAverage") or 0) / cpu
    return round(memory_gb * 10.0 + cpu * 5.0 + disk_gb * 0.2 - load_per_cpu * 20.0, 3)


def selection_reason(selected: Dict[str, Any], eligible: List[Dict[str, Any]]) -> str:
    return (
        f"在 {len(eligible)} 个合格候选中评分最高；CPU={selected.get('cpuCores')} 核，"
        f"可用内存={selected.get('memoryAvailableMb')} MB，可用磁盘={selected.get('diskAvailableMb')} MB，"
        f"负载={selected.get('loadAverage')}，评分={selected.get('score')}。"
    )


def public_candidate(item: Dict[str, Any]) -> Dict[str, Any]:
    return {key: value for key, value in item.items() if not key.startswith("_")}


def parse_marker(output: Any, prefix: str) -> Dict[str, str]:
    line = next((item.strip() for item in str(output or "").splitlines() if item.strip().startswith(prefix)), "")
    result: Dict[str, str] = {}
    for item in line.split("|")[1:]:
        if "=" in item:
            key, value = item.split("=", 1)
            result[key] = value
    return result


def parse_int(value: Any) -> int:
    try:
        return int(float(str(value or 0)))
    except (TypeError, ValueError):
        return 0


def parse_float(value: Any) -> float:
    try:
        return float(str(value or 0))
    except (TypeError, ValueError):
        return 0.0


def normalize_port(value: Any) -> int:
    port = int(value)
    if port <= 0 or port > 65535:
        raise ccrelay_ssh.SshCredentialError(f"中心端口无效: {port}")
    return port


def normalize_base_path(value: Optional[str]) -> str:
    path = str(value or "").strip()
    if not path or path == "/":
        return ""
    return "/" + path.strip("/")


def failure(failure_type: str, summary: Any, plan_result: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "success": False,
        "status": "CENTER_BOOTSTRAP_FAILED",
        "failureType": failure_type,
        "summary": str(summary or failure_type),
        "plan": plan_result,
        "interaction": recovery_interaction(),
    }


def manual_interaction() -> Dict[str, Any]:
    return {
        "success": False,
        "status": "NEED_USER_INPUT",
        "stage": "MANUAL_CENTER_CONFIGURATION_REQUIRED",
        "taskCreated": False,
        "interaction": {
            "prompt": "已选择手动指定中心，请填写当前步骤所需的中心信息。",
            "options": [
                {"id": "APPLY_MANUAL_CENTER", "label": "使用填写的中心配置", "recommended": True},
                {"id": "AUTO_EXECUTE_STEP", "label": "改为自动选择本步"},
                {"id": "AUTO_EXECUTE_REMAINING", "label": "后续全部自动执行"},
                {"id": "CANCEL", "label": "取消"},
            ],
            "fields": [
                {"name": "scheme", "label": "协议", "required": True, "default": "http", "secret": False},
                {"name": "host", "label": "中心主机/IP", "required": True, "default": None, "secret": False},
                {"name": "port", "label": "中心端口", "required": False, "default": None,
                 "hint": "留空时仍由 CLI 自动探测", "secret": False},
                {"name": "basePath", "label": "基础路径", "required": False, "default": "", "secret": False},
            ],
        },
    }


def no_candidate_interaction(summary: str) -> Dict[str, Any]:
    return {
        "success": False,
        "status": "NEED_USER_INPUT",
        "stage": "REMOTE_CENTER_AUTO_SELECTION_FAILED",
        "failureType": "NO_ELIGIBLE_CENTER_CANDIDATE",
        "summary": summary,
        "taskCreated": False,
        "interaction": recovery_interaction(),
    }


def recovery_interaction() -> Dict[str, Any]:
    return {
        "prompt": "远端中心自动规划未完成，请选择下一步。",
        "options": [
            {"id": "RETRY_STEP", "label": "重试自动执行本步", "recommended": True},
            {"id": "EXCLUDE_NODES_AND_RETRY", "label": "排除节点后重试"},
            {"id": "CONFIGURE_STEP_MANUALLY", "label": "手动配置本步"},
            {"id": "AUTO_EXECUTE_REMAINING", "label": "调整后继续全自动"},
            {"id": "CANCEL", "label": "取消"},
        ],
        "fields": [],
    }
