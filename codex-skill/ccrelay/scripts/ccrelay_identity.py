"""SSH identity policy orchestration for the standalone Skill runtime."""

from __future__ import annotations

import base64
import concurrent.futures
import json
import os
import re
import shlex
import subprocess
import time
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Tuple, TypeVar

import ccrelay_ssh


DEFAULT_CLUSTER_ID = "default"
DEFAULT_DEDICATED_USERNAME = "ccrelay"
DEFAULT_PRODUCT_NAME = "ccrelay"
DEFAULT_REMOTE_DIRECTORY_TEMPLATE = "/home/${runtimeUser}/${productName}/${host}-${relayPort}"
DEFAULT_SSH_CONCURRENCY = 4
MAX_SSH_CONCURRENCY = 32
POSIX_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
DEDICATED_USERNAME_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_.-]{0,63}$")
TEMPLATE_VARIABLE_PATTERN = re.compile(r"\$\{([A-Za-z][A-Za-z0-9]*)}")
ALLOWED_TEMPLATE_VARIABLES = {
    "bootstrapUser", "runtimeUser", "sshUser", "productName", "host", "relayPort",
}
T = TypeVar("T")
R = TypeVar("R")


def normalize_concurrency(value: Any) -> int:
    try:
        concurrency = int(value)
    except (TypeError, ValueError) as exc:
        raise ccrelay_ssh.SshCredentialError("--concurrency 必须是 1 到 32 的整数") from exc
    if concurrency < 1 or concurrency > MAX_SSH_CONCURRENCY:
        raise ccrelay_ssh.SshCredentialError("--concurrency 必须是 1 到 32 的整数")
    return concurrency


def parallel_map_ordered(items: Iterable[T], operation: Callable[[T], R], concurrency: int,
                         on_error: Optional[Callable[[T, Exception], R]] = None) -> List[R]:
    values = list(items)
    if not values:
        return []
    worker_count = min(normalize_concurrency(concurrency), len(values))

    def execute(item: T) -> R:
        try:
            return operation(item)
        except Exception as exc:
            if on_error is None:
                raise
            return on_error(item, exc)

    if worker_count == 1:
        return [execute(item) for item in values]
    with concurrent.futures.ThreadPoolExecutor(max_workers=worker_count) as executor:
        return list(executor.map(execute, values))


def parse_node(value: str) -> Dict[str, Any]:
    text = ccrelay_ssh.require_text(value, "node")
    if "=" in text:
        address, username = text.rsplit("=", 1)
    else:
        address, username = text, None
    if ":" in address:
        host, port_text = address.rsplit(":", 1)
        if port_text.isdigit():
            port = int(port_text)
        else:
            host, port = address, 22
    else:
        host, port = address, 22
    return {"host": ccrelay_ssh.require_text(host, "host"), "port": ccrelay_ssh.normalize_port(port),
            "username": username.strip() if username and username.strip() else None,
            "nodeKey": ccrelay_ssh.node_key(host, port)}


def normalize_nodes(values: Iterable[str], center_node: Optional[str] = None) -> List[Dict[str, Any]]:
    nodes: Dict[str, Dict[str, Any]] = {}
    for value in values or []:
        for item in str(value).split(","):
            if not item.strip():
                continue
            node = parse_node(item.strip())
            nodes[node["nodeKey"]] = node
    if center_node:
        node = parse_node(center_node)
        nodes[node["nodeKey"]] = node
    if not nodes:
        raise ccrelay_ssh.SshCredentialError("至少需要一个 --node；中心节点可用 --center-node 指定")
    return list(nodes.values())


def node_record(node: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "host": node["host"],
        "port": int(node.get("port") or 22),
        "nodeKey": node.get("nodeKey") or ccrelay_ssh.node_key(node["host"], node.get("port") or 22),
        "username": node.get("username") or node.get("bootstrapUsername"),
    }


def node_values(nodes: Iterable[Dict[str, Any]]) -> List[str]:
    values = []
    for node in nodes or []:
        username = node.get("username") or node.get("bootstrapUsername")
        value = f"{node['host']}:{int(node.get('port') or 22)}"
        values.append(value + (f"={username}" if username else ""))
    return values


def active_target_nodes(config: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
    current = config if config is not None else ccrelay_ssh.load_config()
    identity = current.get("clusterIdentity") or {}
    excluded = {
        str(item.get("nodeKey") or "")
        for item in identity.get("excludedNodes") or []
        if isinstance(item, dict)
    }
    return [
        node_record(item)
        for item in identity.get("targetNodes") or []
        if isinstance(item, dict) and item.get("host") and item.get("nodeKey") not in excluded
    ]


def resolve_target_nodes(cluster_id: str, values: Iterable[str], center_node: Optional[str] = None,
                         initialize: bool = True) -> List[Dict[str, Any]]:
    supplied_values = list(values or [])
    supplied = normalize_nodes(supplied_values) if supplied_values else []
    config = ccrelay_ssh.load_config()
    identity = config.setdefault("clusterIdentity", {})
    persisted = active_target_nodes(config)
    if not identity.get("targetNodes"):
        if not supplied:
            raise ccrelay_ssh.SshCredentialError("尚未保存部署目标节点，请先通过 bootstrap next --node 指定完整节点集合")
        if not initialize:
            raise ccrelay_ssh.SshCredentialError("尚未保存部署目标节点")
        identity.update({
            "clusterId": cluster_id,
            "targetNodes": [node_record(item) for item in supplied],
            "excludedNodes": [],
            "failedNodes": [],
        })
        ccrelay_ssh.save_config(config)
        persisted = active_target_nodes(config)
    elif supplied:
        persisted_keys = {item["nodeKey"] for item in persisted}
        unknown = [item["nodeKey"] for item in supplied if item["nodeKey"] not in persisted_keys]
        if unknown:
            raise ccrelay_ssh.SshCredentialError(
                "命令包含尚未加入部署目标集合的节点: " + ", ".join(unknown)
                + "；请先使用 ssh identity targets add 显式加入")
    if not persisted:
        raise ccrelay_ssh.SshCredentialError("当前部署目标集合为空；请先显式加入节点")
    if center_node:
        center_key = parse_node(center_node)["nodeKey"]
        if center_key not in {item["nodeKey"] for item in persisted}:
            raise ccrelay_ssh.SshCredentialError("中心节点必须属于当前部署目标集合: " + center_key)
    return persisted


def update_target_nodes(cluster_id: str, values: Iterable[str], action: str,
                        confirmed: bool = False) -> Dict[str, Any]:
    requested = normalize_nodes(values)
    config = ccrelay_ssh.load_config()
    identity = config.setdefault("clusterIdentity", {})
    targets = {
        item["nodeKey"]: node_record(item)
        for item in identity.get("targetNodes") or []
        if isinstance(item, dict) and item.get("host")
    }
    excluded = {
        item["nodeKey"]: node_record(item)
        for item in identity.get("excludedNodes") or []
        if isinstance(item, dict) and item.get("host")
    }
    normalized_action = str(action or "").upper()
    if normalized_action == "ADD":
        for item in requested:
            record = node_record(item)
            targets[record["nodeKey"]] = record
            excluded.pop(record["nodeKey"], None)
    elif normalized_action == "EXCLUDE":
        if not confirmed:
            raise ccrelay_ssh.SshCredentialError("排除部署目标节点必须显式传入 --confirm true")
        unknown = [item["nodeKey"] for item in requested if item["nodeKey"] not in targets]
        if unknown:
            raise ccrelay_ssh.SshCredentialError("无法排除未登记的目标节点: " + ", ".join(unknown))
        for item in requested:
            excluded[item["nodeKey"]] = targets[item["nodeKey"]]
    else:
        raise ccrelay_ssh.SshCredentialError("不支持的目标集合操作: " + str(action))
    identity.update({
        "clusterId": cluster_id,
        "targetNodes": list(targets.values()),
        "excludedNodes": list(excluded.values()),
    })
    failed_keys = {item["nodeKey"] for item in active_target_nodes(config)}
    identity["failedNodes"] = [
        item for item in identity.get("failedNodes") or []
        if isinstance(item, dict) and item.get("nodeKey") in failed_keys
    ]
    ccrelay_ssh.save_config(config)
    return target_status(cluster_id, config)


def target_status(cluster_id: str = DEFAULT_CLUSTER_ID,
                  config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    current = config if config is not None else ccrelay_ssh.load_config()
    identity = current.get("clusterIdentity") or {}
    targets = [
        node_record(item) for item in identity.get("targetNodes") or []
        if isinstance(item, dict) and item.get("host")
    ]
    active = active_target_nodes(current)
    return {
        "clusterId": identity.get("clusterId", cluster_id),
        "targetNodeCount": len(targets),
        "activeTargetNodeCount": len(active),
        "managedNodeCount": len(identity.get("managedNodes") or []),
        "failedNodeCount": len(identity.get("failedNodes") or []),
        "excludedNodeCount": len(identity.get("excludedNodes") or []),
        "targetNodes": targets,
        "activeTargetNodes": active,
        "failedNodes": identity.get("failedNodes") or [],
        "excludedNodes": identity.get("excludedNodes") or [],
    }


def persist_failed_nodes(failures: Iterable[Dict[str, Any]]) -> None:
    config = ccrelay_ssh.load_config()
    identity = config.setdefault("clusterIdentity", {})
    active = {item["nodeKey"]: item for item in active_target_nodes(config)}
    records = []
    for failure in failures or []:
        key = failure.get("nodeKey") or failure.get("node")
        target = active.get(str(key))
        if not target:
            continue
        records.append({
            **target,
            "failureType": failure.get("failureType") or "SSH_CONNECTION_FAILED",
            "summary": failure.get("summary") or "SSH 连接验证失败",
        })
    identity["failedNodes"] = records
    ccrelay_ssh.save_config(config)


def persist_identity_outcome(state: Dict[str, Any]) -> None:
    config = ccrelay_ssh.load_config()
    identity = config.setdefault("clusterIdentity", {})
    active = {item["nodeKey"]: item for item in active_target_nodes(config)}
    successful = []
    failed = []
    allow_create = bool(state.get("dedicatedAccountCreationAllowed"))
    by_key = {item.get("nodeKey"): item for item in state.get("nodes") or []}
    failures_by_node: Dict[str, Dict[str, Any]] = {}
    for failure in identity_failure_records(state):
        failures_by_node.setdefault(str(failure.get("nodeKey") or ""), failure)
    for key, target in active.items():
        item = by_key.get(key) or {}
        ready = item.get("centerAccessStatus") == "READY"
        if allow_create:
            ready = ready and item.get("accountStatus") == "ACTIVE" and key not in failures_by_node
        if ready:
            successful.append({
                **target,
                "bootstrapUsername": item.get("bootstrapUsername") or target.get("username"),
                "runtimeUsername": item.get("runtimeUsername"),
                "osType": item.get("osType"),
            })
        else:
            failure = failures_by_node.get(key) or {}
            failed.append({
                **target,
                "failureType": failure.get("failureType") or "IDENTITY_NOT_READY",
                "failureStage": failure.get("failureStage") or "IDENTITY_VERIFICATION",
                "summary": failure.get("summary") or "节点身份或互信尚未验证完成",
            })
    identity["managedNodes"] = successful
    identity["failedNodes"] = failed
    ccrelay_ssh.save_config(config)


def identity_failure_records(state: Dict[str, Any]) -> List[Dict[str, Any]]:
    records: Dict[Tuple[str, str, str], Dict[str, Any]] = {}

    def add(node_key: Any, stage: str, failure_type: Any, summary: Any,
            related_node: Optional[str] = None) -> None:
        normalized_node = str(node_key or "").strip()
        if not normalized_node:
            return
        normalized_type = str(failure_type or "IDENTITY_NOT_READY").strip()
        key = (normalized_node, stage, str(related_node or ""))
        records[key] = {
            "nodeKey": normalized_node,
            "failureStage": stage,
            "failureType": normalized_type,
            "summary": str(summary or "节点身份或互信尚未验证完成"),
            "retryable": normalized_type not in {"DEDICATED_ACCOUNT_CONFLICT", "CANCELLED"},
            **({"relatedNodeKey": related_node} if related_node else {}),
        }

    for item in state.get("nodes") or []:
        if not isinstance(item, dict):
            continue
        node_key = item.get("nodeKey")
        apply_result = item.get("applyResult") if isinstance(item.get("applyResult"), dict) else {}
        if item.get("accountStatus") == "FAILED":
            add(
                node_key,
                "DEDICATED_ACCOUNT_PROVISION",
                apply_result.get("failureType") or apply_result.get("status")
                or item.get("lastErrorCode") or "DEDICATED_ACCOUNT_PROVISION_FAILED",
                item.get("lastErrorSummary") or apply_result.get("summary"),
            )
        if item.get("keyInstallStatus") == "FAILED":
            add(
                node_key,
                "SSH_KEY_INSTALL",
                apply_result.get("failureType") or apply_result.get("status")
                or item.get("lastErrorCode") or "SSH_KEY_INSTALL_FAILED",
                item.get("lastErrorSummary") or apply_result.get("summary"),
            )
        if item.get("centerAccessStatus") != "READY":
            center_probe = item.get("centerProbe") if isinstance(item.get("centerProbe"), dict) else {}
            add(
                node_key,
                "CENTER_TO_NODE_VERIFY",
                center_probe.get("failureType") or center_probe.get("status")
                or item.get("lastErrorCode") or "CENTER_ACCESS_FAILED",
                center_probe.get("summary") or item.get("lastErrorSummary"),
            )

    for edge in state.get("trustEdges") or []:
        if not isinstance(edge, dict) or edge.get("status") == "READY":
            continue
        source = str(edge.get("sourceNodeKey") or "").strip()
        target = str(edge.get("targetNodeKey") or "").strip()
        summary = edge.get("lastErrorSummary") or "节点间 SSH 互信验证失败"
        failure_type = edge.get("lastErrorCode") or "NODE_TRUST_VERIFY_FAILED"
        add(source, "NODE_TO_NODE_VERIFY", failure_type, summary, target or None)
        add(target, "NODE_TO_NODE_VERIFY", failure_type, summary, source or None)

    if (state.get("dedicatedAccountCreationAllowed")
            and state.get("effectiveCapability") != "FULL_MESH"
            and state.get("nodeToNodeStatus") != "READY"
            and not records):
        for item in state.get("nodes") or []:
            if isinstance(item, dict):
                add(
                    item.get("nodeKey"),
                    "NODE_TO_NODE_VERIFY",
                    "NODE_TRUST_INCOMPLETE",
                    "节点间 SSH 互信尚未完整验证",
                )

    return list(records.values())


def local_status(cluster_id: str = DEFAULT_CLUSTER_ID) -> Dict[str, Any]:
    config = ccrelay_ssh.load_config()
    identity = config.get("clusterIdentity") or {}
    dedicated = identity.get("dedicatedAccount") or {}
    return {
        "clusterId": identity.get("clusterId", cluster_id),
        "selectionRequired": bool(identity.get("selectionRequired", False)),
        "accountMode": identity.get("accountMode", "EXISTING_ACCOUNT"),
        "dedicatedAccountCreationAllowed": bool(identity.get("dedicatedAccountCreationAllowed", False)),
        "fullMeshThreshold": int(identity.get("fullMeshThreshold", ccrelay_ssh.DEFAULT_CLUSTER_FULL_MESH_THRESHOLD)),
        "dedicatedUsername": dedicated.get("username", DEFAULT_DEDICATED_USERNAME),
        "dedicatedAccountStatus": dedicated.get("status", "DISABLED"),
        "detailsConfirmed": bool(dedicated.get("detailsConfirmed", False)),
        "clusterKeyFingerprint": (dedicated.get("clusterKey") or {}).get("fingerprint"),
        "passwordSecretCount": len(dedicated.get("passwordSecrets") or {}),
        "remoteDirectoryTemplate": (config.get("runtime") or {}).get(
            "remoteDirectoryTemplate", DEFAULT_REMOTE_DIRECTORY_TEMPLATE),
        "configPath": str(ccrelay_ssh.config_path()),
    }


def plan(cluster_id: str, center_node: str, node_values: Iterable[str], allow_create: bool,
         dedicated_username: str, timeout_seconds: int,
         remote_directory_template: Optional[str] = None,
         concurrency: int = DEFAULT_SSH_CONCURRENCY) -> Dict[str, Any]:
    dedicated_username = normalize_dedicated_username(dedicated_username)
    directory_template = normalize_remote_directory_template(remote_directory_template)
    nodes = normalize_nodes(node_values, center_node)
    concurrency = normalize_concurrency(concurrency)

    def probe_node(node: Dict[str, Any]) -> Dict[str, Any]:
        result = ccrelay_ssh.probe_connection(node["host"], node["port"], node.get("username"), timeout_seconds)
        selected_username = result.get("username") or node.get("username")
        capability = probe_environment(node, selected_username, timeout_seconds, dedicated_username) if result.get("success") else {
            "osType": "UNKNOWN", "canCreateAccount": False, "canInstallKey": False,
            "summary": result.get("summary"),
        }
        return {
            **node,
            "bootstrapSuccess": bool(result.get("success")),
            "bootstrapCredentialScope": result.get("credentialScope"),
            "bootstrapUsername": selected_username,
            "bootstrapStatus": result.get("status"),
            "bootstrapFailureType": result.get("failureType"),
            "accountStatus": "CONFLICT" if allow_create and capability.get("dedicatedAccountConflict")
            else "ACTIVE" if allow_create and capability.get("dedicatedAccountManaged")
            else "UNVERIFIED" if allow_create and capability.get("dedicatedExists") == "true"
            and capability.get("dedicatedInspection") == "INACCESSIBLE"
            else "PLANNED" if allow_create and capability.get("canCreateAccount") else "EXISTING",
            "runtimeUsername": dedicated_username if allow_create else selected_username,
            "osType": capability.get("osType", "UNKNOWN"),
            "canCreateAccount": capability.get("canCreateAccount", False),
            "canInstallKey": capability.get("canInstallKey", False),
            "privilegeSummary": capability,
            "lastErrorSummary": "同名专用账号已存在但不是 Skill 受管账号" if capability.get("dedicatedAccountConflict")
            else None if result.get("success") else result.get("summary"),
        }

    def probe_error(node: Dict[str, Any], exc: Exception) -> Dict[str, Any]:
        return {
            **node,
            "bootstrapSuccess": False,
            "bootstrapUsername": node.get("username"),
            "bootstrapStatus": "FAILED",
            "bootstrapFailureType": "SSH_PROBE_FAILED",
            "accountStatus": "PLANNED" if allow_create else "EXISTING",
            "runtimeUsername": dedicated_username if allow_create else node.get("username"),
            "osType": "UNKNOWN",
            "canCreateAccount": False,
            "canInstallKey": False,
            "privilegeSummary": {"osType": "UNKNOWN", "summary": str(exc)},
            "lastErrorSummary": str(exc),
        }

    node_results = parallel_map_ordered(nodes, probe_node, concurrency, probe_error)
    failures = [item for item in node_results if not item.get("bootstrapSuccess")]
    permission_failures = [item for item in node_results if allow_create and item.get("bootstrapSuccess") and (
        (not item.get("canCreateAccount")
         and not (item.get("privilegeSummary", {}).get("dedicatedExists") == "true"
                  and item.get("privilegeSummary", {}).get("dedicatedInspection") == "INACCESSIBLE"))
        or item.get("privilegeSummary", {}).get("dedicatedAccountConflict"))]
    return {
        "clusterId": cluster_id,
        "accountMode": "DEDICATED_MANAGED" if allow_create else "EXISTING_ACCOUNT",
        "dedicatedAccountCreationAllowed": allow_create,
        "dedicatedUsername": dedicated_username,
        "remoteDirectoryTemplate": directory_template,
        "deploymentDirectories": deployment_directory_previews(
            nodes, dedicated_username, directory_template, allow_create),
        "centerNodeId": parse_node(center_node)["nodeKey"],
        "status": "READY_TO_APPLY" if not failures and not permission_failures else "NEED_USER_INPUT",
        "taskCreated": False,
        "requiresUserInput": bool(failures or permission_failures),
        "nodes": node_results,
        "interaction": plan_interaction(failures, permission_failures) if failures or permission_failures else None,
    }


def apply(cluster_id: str, center_node: str, node_values: Iterable[str], allow_create: bool,
           dedicated_username: str, timeout_seconds: int, rotate_key: bool = False,
           remote_directory_template: Optional[str] = None,
           concurrency: int = DEFAULT_SSH_CONCURRENCY) -> Dict[str, Any]:
    dedicated_username = normalize_dedicated_username(dedicated_username)
    directory_template = normalize_remote_directory_template(remote_directory_template)
    concurrency = normalize_concurrency(concurrency)
    planned = plan(cluster_id, center_node, node_values, allow_create, dedicated_username, timeout_seconds,
                   directory_template, concurrency)
    if planned["status"] != "READY_TO_APPLY":
        return planned
    nodes = normalize_nodes(node_values, center_node)
    cluster_key_path = None
    if allow_create:
        key = ensure_cluster_key(cluster_id, rotate_key)
        cluster_key_path = key
        public_key = key.with_suffix(".pub").read_text(encoding="utf-8").strip()
        passwords = {
            item["nodeKey"]: dedicated_password(cluster_id, item["nodeKey"], False)
            for item in planned["nodes"]
        }

        def provision_node(source: Dict[str, Any]) -> Dict[str, Any]:
            item = dict(source)
            password = passwords[item["nodeKey"]]
            result = None
            if ((item.get("privilegeSummary") or {}).get("dedicatedExists") == "true"
                    and (item.get("privilegeSummary") or {}).get("dedicatedInspection") == "INACCESSIBLE"):
                result = verify_dedicated_login(item, dedicated_username, password, key, timeout_seconds)
            if not result or not result.get("success"):
                result = provision_dedicated(item, dedicated_username, password, public_key, key, timeout_seconds)
            item["applyResult"] = result
            if not result.get("success"):
                item["accountStatus"] = "FAILED"
                item["lastErrorSummary"] = result.get("summary")
            else:
                item["accountStatus"] = "ACTIVE"
                item["runtimeUsername"] = dedicated_username
                item["keyInstallStatus"] = "READY"
            return item

        def provision_error(item: Dict[str, Any], exc: Exception) -> Dict[str, Any]:
            return {**item, "accountStatus": "FAILED", "keyInstallStatus": "FAILED",
                    "lastErrorSummary": str(exc),
                    "applyResult": {"success": False, "summary": str(exc)}}

        planned["nodes"] = parallel_map_ordered(
            planned["nodes"], provision_node, concurrency, provision_error)
        key_fingerprint = fingerprint(public_key)
        cluster_key_mode = "SHARED_KEYPAIR"
    else:
        center = parse_node(center_node)
        center_user = next(item.get("bootstrapUsername") for item in planned["nodes"] if item["nodeKey"] == center["nodeKey"])
        key_result = ensure_remote_center_key(center, center_user, timeout_seconds)
        if not key_result.get("success"):
            return {**planned, "status": "FAILED", "failureType": "CENTER_KEY_FAILED", "summary": key_result.get("summary")}
        key_fingerprint = key_result.get("fingerprint")
        cluster_key_mode = "CENTER_ONLY_KEYPAIR"
        def install_center_key(source: Dict[str, Any]) -> Dict[str, Any]:
            item = dict(source)
            if item["nodeKey"] == center["nodeKey"]:
                item["keyInstallStatus"] = "CENTER_LOCAL"
                return item
            installed = ccrelay_ssh.bootstrap_public_key_value(
                item["host"], item["port"], item.get("bootstrapUsername"), key_result["publicKey"], timeout_seconds)
            item["applyResult"] = installed
            item["keyInstallStatus"] = "READY" if installed.get("success") else "FAILED"
            item["lastErrorSummary"] = None if installed.get("success") else installed.get("summary")
            return item

        def install_error(item: Dict[str, Any], exc: Exception) -> Dict[str, Any]:
            return {**item, "keyInstallStatus": "FAILED", "lastErrorSummary": str(exc),
                    "applyResult": {"success": False, "summary": str(exc)}}

        planned["nodes"] = parallel_map_ordered(
            planned["nodes"], install_center_key, concurrency, install_error)
    verified = verify(
        cluster_id, center_node, planned["nodes"], allow_create, dedicated_username, timeout_seconds,
        key_fingerprint, cluster_key_mode, concurrency, expected_target_nodes=nodes)
    config = ccrelay_ssh.load_config()
    identity = config.setdefault("clusterIdentity", {})
    identity.update({"clusterId": cluster_id, "selectionRequired": False, "accountMode": planned["accountMode"],
                     "dedicatedAccountCreationAllowed": allow_create,
                     "identityCenterNodeId": planned.get("centerNodeId")})
    dedicated = identity.setdefault("dedicatedAccount", {})
    dedicated.update({"username": dedicated_username, "status": dedicated_account_status(verified, allow_create),
                      "detailsConfirmed": allow_create})
    config.setdefault("runtime", {})["remoteDirectoryTemplate"] = directory_template
    if allow_create:
        key = cluster_key_path or ensure_cluster_key(cluster_id, False)
        dedicated["clusterKey"] = {"mode": cluster_key_mode, "algorithm": "ED25519",
                                    "privateKeyPath": str(key), "publicKeyPath": str(key.with_suffix(".pub")),
                                    "fingerprint": key_fingerprint}
    else:
        dedicated["clusterKey"] = {"mode": cluster_key_mode, "fingerprint": key_fingerprint}
    ccrelay_ssh.save_config(config)
    persist_identity_outcome(verified)
    return {**verified, "plan": planned, "local": local_status(cluster_id)}


def dedicated_account_status(state: Dict[str, Any], allow_create: bool) -> str:
    if not allow_create:
        return "DISABLED"
    if state.get("effectiveCapability") == "FULL_MESH":
        return "ACTIVE"
    nodes = state.get("nodes") or []
    if nodes and all(item.get("accountStatus") == "FAILED" for item in nodes):
        return "FAILED"
    return "PARTIAL"


def verify(cluster_id: str, center_node: str, nodes: List[Dict[str, Any]], allow_create: bool,
           dedicated_username: str, timeout_seconds: int, key_fingerprint: Optional[str] = None,
           cluster_key_mode: Optional[str] = None,
           concurrency: int = DEFAULT_SSH_CONCURRENCY,
           expected_target_nodes: Optional[List[Dict[str, Any]]] = None) -> Dict[str, Any]:
    concurrency = normalize_concurrency(concurrency)
    dedicated_key = ensure_cluster_key(cluster_id, False) if allow_create else None
    def enrich_node(source: Dict[str, Any]) -> Dict[str, Any]:
        item = dict(source)
        if not item.get("bootstrapUsername"):
            credential, scope = ccrelay_ssh.resolve_credential(item["host"], item["port"])
            item["bootstrapUsername"] = item.get("username") or (credential or {}).get("username")
            item["bootstrapCredentialScope"] = scope
        if not item.get("privilegeSummary") and item.get("bootstrapUsername"):
            item["privilegeSummary"] = probe_environment(
                item, item["bootstrapUsername"], timeout_seconds, dedicated_username)
        item.setdefault("runtimeUsername", dedicated_username if allow_create else item.get("bootstrapUsername"))
        return item

    def enrich_error(item: Dict[str, Any], exc: Exception) -> Dict[str, Any]:
        return {**item, "runtimeUsername": dedicated_username if allow_create else item.get("bootstrapUsername"),
                "privilegeSummary": {"osType": "UNKNOWN", "summary": str(exc)}}

    nodes = parallel_map_ordered(nodes, enrich_node, concurrency, enrich_error)
    center = parse_node(center_node)
    center_item = next((item for item in nodes if item["nodeKey"] == center["nodeKey"]), None)
    center_user = (center_item or {}).get("bootstrapUsername") or center.get("username")
    def verify_center_access(item: Dict[str, Any]) -> Dict[str, Any]:
        if item["nodeKey"] == center["nodeKey"]:
            item_result = dict(item)
            item_result["centerAccessStatus"] = "READY"
            item_result["centerProbe"] = {
                "success": True,
                "status": "CENTER_NODE_LOCAL",
                "summary": "中心节点本机不执行自我 SSH 验证",
            }
            return item_result
        runtime_user = dedicated_username if allow_create else item.get("bootstrapUsername")
        center_ssh_arguments = ccrelay_ssh.ssh_arguments_for(center["host"], center["port"])
        command = center_verify_command(
            item, runtime_user, allow_create, (center_item or {}).get("privilegeSummary"), center_user,
            center_ssh_arguments)
        center_probe = ccrelay_ssh.run_authenticated_command(
            center["host"], center["port"], center_user, command, timeout_seconds)
        if (allow_create and not center_probe.get("success")
                and center_user != dedicated_username and dedicated_key is not None):
            direct_command = center_verify_command(
                item, runtime_user, True, (center_item or {}).get("privilegeSummary"),
                dedicated_username, center_ssh_arguments)
            center_probe = ccrelay_ssh.run_key_command(
                center["host"], center["port"], dedicated_username, dedicated_key,
                direct_command, timeout_seconds)
        item_result = dict(item)
        item_result["centerAccessStatus"] = "READY" if center_probe.get("success") else "FAILED"
        item_result["centerProbe"] = center_probe
        return item_result

    def center_access_error(item: Dict[str, Any], exc: Exception) -> Dict[str, Any]:
        return {**item, "centerAccessStatus": "FAILED",
                "centerProbe": {"success": False, "status": "FAILED", "summary": str(exc)}}

    node_results = parallel_map_ordered(
        nodes, verify_center_access, concurrency, center_access_error)
    edges: List[Dict[str, Any]] = []
    if allow_create:
        edge_pairs = [(source, target) for source in nodes for target in nodes
                      if source["nodeKey"] != target["nodeKey"]]

        def verify_edge(pair: Tuple[Dict[str, Any], Dict[str, Any]]) -> Dict[str, Any]:
            source, target = pair
            source_user = source.get("bootstrapUsername")
            source_ssh_arguments = ccrelay_ssh.ssh_arguments_for(source["host"], source["port"])
            command = node_verify_command(
                target, dedicated_username, source.get("privilegeSummary"), source_user,
                source_ssh_arguments)
            probe = ccrelay_ssh.run_authenticated_command(
                source["host"], source["port"], source_user, command, timeout_seconds)
            if (not probe.get("success") and source_user != dedicated_username
                    and dedicated_key is not None):
                direct_command = node_verify_command(
                    target, dedicated_username, source.get("privilegeSummary"),
                    dedicated_username, source_ssh_arguments)
                probe = ccrelay_ssh.run_key_command(
                    source["host"], source["port"], dedicated_username, dedicated_key,
                    direct_command, timeout_seconds)
            return {"sourceNodeKey": source["nodeKey"], "targetNodeKey": target["nodeKey"],
                    "runtimeUsername": dedicated_username, "keyFingerprint": key_fingerprint,
                    "status": "READY" if probe.get("success") else "FAILED",
                    "latencyMs": probe.get("latencyMs"), "lastErrorSummary": probe.get("summary")}

        def edge_error(pair: Tuple[Dict[str, Any], Dict[str, Any]], exc: Exception) -> Dict[str, Any]:
            source, target = pair
            return {"sourceNodeKey": source["nodeKey"], "targetNodeKey": target["nodeKey"],
                    "runtimeUsername": dedicated_username, "keyFingerprint": key_fingerprint,
                    "status": "FAILED", "latencyMs": None, "lastErrorSummary": str(exc)}

        edges = parallel_map_ordered(edge_pairs, verify_edge, concurrency, edge_error)
    center_ready = bool(node_results) and all(item["centerAccessStatus"] == "READY" for item in node_results)
    expected_node_keys = {
        item["nodeKey"] for item in (expected_target_nodes if expected_target_nodes is not None else nodes)
    }
    verified_node_keys = {
        item["nodeKey"] for item in node_results if item.get("centerAccessStatus") == "READY"
    }
    expected_edges = {
        (source, target) for source in expected_node_keys for target in expected_node_keys if source != target
    }
    verified_edges = {
        (edge.get("sourceNodeKey"), edge.get("targetNodeKey"))
        for edge in edges if edge.get("status") == "READY"
    }
    center_ready = bool(expected_node_keys) and verified_node_keys == expected_node_keys
    mesh_ready = bool(allow_create) and verified_edges == expected_edges
    capability = "FULL_MESH" if center_ready and mesh_ready else "CENTER_ONLY" if center_ready else "DEGRADED"
    return {"clusterId": cluster_id, "accountMode": "DEDICATED_MANAGED" if allow_create else "EXISTING_ACCOUNT",
            "dedicatedAccountCreationAllowed": allow_create, "dedicatedUsername": dedicated_username,
            "centerNodeId": center["nodeKey"], "centerToNodeStatus": "READY" if center_ready else "PARTIAL",
            "nodeToNodeStatus": "READY" if mesh_ready else "NOT_REQUIRED" if not allow_create else "PARTIAL",
            "effectiveCapability": capability, "clusterKeyMode": cluster_key_mode,
            "clusterKeyFingerprint": key_fingerprint, "nodes": node_results, "trustEdges": edges,
            "targetNodeCount": len(expected_node_keys), "verifiedNodeCount": len(verified_node_keys),
            "expectedTrustEdgeCount": len(expected_edges), "verifiedTrustEdgeCount": len(verified_edges),
            "lastVerifiedTime": str(int(time.time() * 1000))}


def probe_environment(node: Dict[str, Any], username: Optional[str], timeout_seconds: int,
                      dedicated_username: str = DEFAULT_DEDICATED_USERNAME) -> Dict[str, Any]:
    if not username:
        return {"osType": "UNKNOWN", "canCreateAccount": False, "canInstallKey": False}
    posix = ccrelay_ssh.run_authenticated_command(
        node["host"], node["port"], username,
        posix_capability_command(dedicated_username), timeout_seconds, include_output=True)
    parsed = parse_capability(posix, "LINUX")
    if parsed.get("success"):
        has_privilege = parsed.get("uid") == "0" or parsed.get("sudo") == "true"
        parsed["canCreateAccount"] = bool(
            has_privilege and parsed.get("shellPath") and parsed.get("accountToolPath")
            and parsed.get("chpasswdPath"))
        parsed["canInstallKey"] = parsed.get("mkdirPath") is not None
        parsed["dedicatedAccountManaged"] = parsed.get("dedicatedManaged") == "true"
        parsed["dedicatedAccountConflict"] = (
            parsed.get("dedicatedExists") == "true"
            and parsed.get("dedicatedInspection") == "UNMANAGED")
        return parsed
    windows = ccrelay_ssh.run_authenticated_command(
        node["host"], node["port"], username,
        f"powershell.exe -NoProfile -NonInteractive -Command \"$p=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent()); $u=Get-LocalUser -Name '{dedicated_username}' -ErrorAction SilentlyContinue; $profile=Join-Path $env:SystemDrive 'Users\\{dedicated_username}'; $managed=(Test-Path (Join-Path $profile '.ssh\\.ccrelay-managed')) -or (Test-Path (Join-Path $profile '.ssh\\id_ed25519_ccrelay_cluster')); Write-Output ('CCRELAY_CAP|os=WINDOWS|admin=' + $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator) + '|dedicatedExists=' + [bool]$u + '|dedicatedManaged=' + $managed)\"",
        timeout_seconds, include_output=True)
    parsed = parse_capability(windows, "WINDOWS")
    if parsed.get("success"):
        is_admin = str(parsed.get("admin")).lower() == "true"
        account_exists = str(parsed.get("dedicatedExists")).lower() == "true"
        parsed["canCreateAccount"] = is_admin
        parsed["canInstallKey"] = is_admin
        parsed["dedicatedAccountManaged"] = str(parsed.get("dedicatedManaged")).lower() == "true"
        parsed["dedicatedInspection"] = (
            "NOT_FOUND" if not account_exists
            else "VERIFIED" if parsed["dedicatedAccountManaged"]
            else "UNMANAGED" if is_admin
            else "INACCESSIBLE")
        parsed["dedicatedAccountConflict"] = (
            account_exists
            and str(parsed.get("dedicatedInspection")).upper() == "UNMANAGED")
    return parsed


def posix_capability_command(dedicated_username: str) -> str:
    quoted_username = shlex.quote(dedicated_username)
    return f"""export PATH=\"{POSIX_PATH}:$PATH\"
uid=$(id -u 2>/dev/null || printf 99999)
sudo_path=$(command -v sudo 2>/dev/null || true)
sudo_ok=false
if [ \"$uid\" = 0 ]; then
  sudo_ok=true
elif [ -n \"$sudo_path\" ] && \"$sudo_path\" -n true >/dev/null 2>&1; then
  sudo_ok=true
fi
bash_path=$(command -v bash 2>/dev/null || true)
sh_path=$(command -v sh 2>/dev/null || true)
shell_path=$bash_path
if [ -z \"$shell_path\" ]; then shell_path=$sh_path; fi
useradd_path=$(command -v useradd 2>/dev/null || true)
adduser_path=$(command -v adduser 2>/dev/null || true)
account_tool_path=$useradd_path
if [ -z \"$account_tool_path\" ]; then account_tool_path=$adduser_path; fi
getent_path=$(command -v getent 2>/dev/null || true)
dedicated_exists=false
dedicated_managed=false
dedicated_inspection=NOT_FOUND
if id -u {quoted_username} >/dev/null 2>&1; then
  dedicated_exists=true
  if [ -n \"$getent_path\" ]; then
    dedicated_home=$(\"$getent_path\" passwd {quoted_username} | cut -d: -f6)
  else
    dedicated_home=$(awk -F: '$1 == \"{dedicated_username}\" {{ print $6; exit }}' /etc/passwd)
  fi
  if [ \"$uid\" = 0 ]; then
    dedicated_inspection=VERIFIED
    if [ -f \"$dedicated_home/.ssh/.ccrelay-managed\" ] || [ -f \"$dedicated_home/.ssh/id_ed25519_ccrelay_cluster\" ]; then
      dedicated_managed=true
    else
      dedicated_inspection=UNMANAGED
    fi
  elif [ -n \"$sudo_path\" ] && \"$sudo_path\" -n test -d \"$dedicated_home\" >/dev/null 2>&1; then
    dedicated_inspection=VERIFIED
    if \"$sudo_path\" -n test -f \"$dedicated_home/.ssh/.ccrelay-managed\" || \"$sudo_path\" -n test -f \"$dedicated_home/.ssh/id_ed25519_ccrelay_cluster\"; then
      dedicated_managed=true
    else
      dedicated_inspection=UNMANAGED
    fi
  elif [ -d \"$dedicated_home\" ] && [ -x \"$dedicated_home\" ]; then
    dedicated_inspection=VERIFIED
    if [ -f \"$dedicated_home/.ssh/.ccrelay-managed\" ] || [ -f \"$dedicated_home/.ssh/id_ed25519_ccrelay_cluster\" ]; then
      dedicated_managed=true
    else
      dedicated_inspection=UNMANAGED
    fi
  else
    dedicated_inspection=INACCESSIBLE
  fi
fi
printf 'CCRELAY_CAP|os=LINUX|uid=%s|sudo=%s|sudoPath=%s|bashPath=%s|shPath=%s|shellPath=%s|runuserPath=%s|suPath=%s|sshPath=%s|mkdirPath=%s|getentPath=%s|accountToolPath=%s|chpasswdPath=%s|dedicatedExists=%s|dedicatedManaged=%s|dedicatedInspection=%s' \
  \"$uid\" \"$sudo_ok\" \"$sudo_path\" \"$bash_path\" \"$sh_path\" \"$shell_path\" \
  \"$(command -v runuser 2>/dev/null || true)\" \"$(command -v su 2>/dev/null || true)\" \
  \"$(command -v ssh 2>/dev/null || true)\" \"$(command -v mkdir 2>/dev/null || true)\" \"$getent_path\" \
  \"$account_tool_path\" \"$(command -v chpasswd 2>/dev/null || true)\" \"$dedicated_exists\" \"$dedicated_managed\" \"$dedicated_inspection\""""


def parse_capability(result: Dict[str, Any], default_os: str) -> Dict[str, Any]:
    output = str(result.get("stdout") or "")
    marker = next((line for line in output.splitlines() if line.startswith("CCRELAY_CAP|")), "")
    values = {"osType": default_os, "success": bool(result.get("success")), "summary": result.get("summary")}
    for item in marker.split("|")[1:]:
        if "=" in item:
            key, value = item.split("=", 1)
            values[{"os": "osType"}.get(key, key)] = value or None
    return values


def normalize_dedicated_username(value: str) -> str:
    username = ccrelay_ssh.require_text(value, "dedicatedUsername")
    if not DEDICATED_USERNAME_PATTERN.fullmatch(username):
        raise ccrelay_ssh.SshCredentialError(
            "专用账号名必须以字母或下划线开头，且只能包含字母、数字、点、下划线和连字符")
    return username


def normalize_remote_directory_template(value: Optional[str]) -> str:
    template = (value or DEFAULT_REMOTE_DIRECTORY_TEMPLATE).strip()
    if not template:
        raise ccrelay_ssh.SshCredentialError("远端部署目录模板不能为空")
    unknown_variables = sorted(set(TEMPLATE_VARIABLE_PATTERN.findall(template)) - ALLOWED_TEMPLATE_VARIABLES)
    if unknown_variables:
        raise ccrelay_ssh.SshCredentialError(
            "远端部署目录模板包含不支持的变量: " + ", ".join(unknown_variables))
    configured_variables = set(TEMPLATE_VARIABLE_PATTERN.findall(template))
    required_variables = {"productName", "host", "relayPort"}
    missing_variables = sorted(required_variables - configured_variables)
    if not ({"runtimeUser", "sshUser"} & configured_variables):
        missing_variables.append("runtimeUser")
    if missing_variables:
        raise ccrelay_ssh.SshCredentialError(
            "远端部署目录模板缺少必要变量: " + ", ".join(missing_variables))
    return template


def deployment_directory_previews(nodes: Iterable[Dict[str, Any]], dedicated_username: str,
                                  template: str, allow_create: bool) -> List[Dict[str, str]]:
    previews = []
    for node in nodes:
        credential, _scope = ccrelay_ssh.resolve_credential(node["host"], node["port"])
        bootstrap_user = node.get("username") or (credential or {}).get("username") or "<bootstrap-user>"
        runtime_user = dedicated_username if allow_create else bootstrap_user
        values = {
            "bootstrapUser": bootstrap_user,
            "runtimeUser": runtime_user,
            "sshUser": runtime_user,
            "productName": DEFAULT_PRODUCT_NAME,
            "host": node["host"],
            "relayPort": "<auto-relay-port>",
        }
        directory = template
        for name, replacement in values.items():
            directory = directory.replace("${" + name + "}", replacement)
        previews.append({"nodeKey": node["nodeKey"], "directory": directory})
    return previews


def ensure_cluster_key(cluster_id: str, rotate: bool = False) -> Path:
    config = ccrelay_ssh.load_config()
    dedicated = (config.setdefault("clusterIdentity", {}).setdefault("dedicatedAccount", {}))
    cluster = dedicated.get("clusterKey") or {}
    configured = cluster.get("privateKeyPath")
    path = Path(configured) if configured else ccrelay_ssh.config_path().parent / "keys" / cluster_id / "cluster_ed25519"
    path = path.expanduser().resolve()
    if rotate:
        path = path.with_name(path.name + "-" + str(int(time.time() * 1000)))
    if not path.is_file() or not path.with_suffix(".pub").is_file():
        path.parent.mkdir(parents=True, exist_ok=True)
        tools = ccrelay_ssh.check_local_tools()
        executable = tools["tools"].get("ssh-keygen")
        if not executable:
            raise ccrelay_ssh.SshCredentialError("本机缺少 ssh-keygen")
        command = [executable, "-t", "ed25519", "-N", "", "-f", str(path), "-C", f"ccrelay-cluster-{cluster_id}"]
        completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", check=False)
        if completed.returncode != 0:
            raise ccrelay_ssh.SshCredentialError("生成集群 SSH 密钥失败")
    ccrelay_ssh._restrict_file(path)
    ccrelay_ssh._restrict_file(path.with_suffix(".pub"))
    return path


def dedicated_password(cluster_id: str, node_key: str, rotate: bool = False) -> str:
    config = ccrelay_ssh.load_config()
    dedicated = config.setdefault("clusterIdentity", {}).setdefault("dedicatedAccount", {})
    secrets_map = dedicated.setdefault("passwordSecrets", {})
    current = None
    if node_key in secrets_map:
        current = ccrelay_ssh.unprotect_secret(secrets_map[node_key])
    if rotate or not ccrelay_ssh.secret_meets_complexity_policy(current):
        secrets_map[node_key] = ccrelay_ssh.protect_secret(ccrelay_ssh.random_secret())
        ccrelay_ssh.save_config(config)
    return ccrelay_ssh.unprotect_secret(secrets_map[node_key])


def verify_dedicated_login(node: Dict[str, Any], username: str, password: str,
                           private_key: Path, timeout_seconds: int) -> Dict[str, Any]:
    key_result = ccrelay_ssh.run_key_command(
        node["host"], node["port"], username, private_key,
        "printf CCRELAY_DEDICATED_READY", timeout_seconds)
    if key_result.get("success"):
        return {**key_result, "status": "DEDICATED_LOGIN_VERIFIED"}
    password_result = ccrelay_ssh.test_connection(
        node["host"], node["port"], username, timeout_seconds,
        password_override=password)
    if password_result.get("success"):
        return {**password_result, "status": "DEDICATED_LOGIN_VERIFIED"}
    return password_result


def provision_dedicated(node: Dict[str, Any], username: str, password: str, public_key: str,
                        private_key: Path, timeout_seconds: int) -> Dict[str, Any]:
    bootstrap_user = node.get("bootstrapUsername")
    if not bootstrap_user:
        return {"success": False, "summary": "缺少引导账号"}
    if node.get("osType") == "WINDOWS":
        script = windows_provision_script(username, password, public_key, private_key.read_text(encoding="utf-8"))
        command = "powershell.exe -NoProfile -NonInteractive -Command -"
    else:
        capability = node.get("privilegeSummary") or {}
        shell_path = capability.get("bashPath") or capability.get("shPath") or capability.get("shellPath")
        if not shell_path:
            return {"success": False, "summary": "远端缺少可用的 Bash 或 POSIX sh"}
        script = normalize_posix_script(linux_provision_script(
            username, password, public_key, private_key.read_text(encoding="utf-8"), shell_path))
        command = f"{shlex.quote(shell_path)} -s"
        if capability.get("uid") != "0":
            sudo_path = capability.get("sudoPath")
            if not sudo_path:
                return {"success": False, "summary": "远端引导账号不是 root，且未探测到可用 sudo"}
            command = f"{shlex.quote(sudo_path)} -n {command}"
    return ccrelay_ssh.run_authenticated_command(node["host"], node["port"], bootstrap_user, command,
                                                 timeout_seconds, stdin_text=script)


def linux_provision_script(username: str, password: str, public_key: str, private_key: str,
                           login_shell: str = "/bin/sh") -> str:
    return f"""export PATH=\"{POSIX_PATH}:$PATH\"
set -eu
created=false
if ! id -u {shlex.quote(username)} >/dev/null 2>&1; then
  if command -v useradd >/dev/null 2>&1; then
    useradd -m -s {shlex.quote(login_shell)} {shlex.quote(username)}
  elif command -v adduser >/dev/null 2>&1; then
    adduser --disabled-password --gecos '' {shlex.quote(username)}
  else
    printf 'CCRELAY_USER_TOOL_MISSING' >&2
    exit 41
  fi
  created=true
fi
home=$(getent passwd {shlex.quote(username)} | cut -d: -f6)
if [ -z \"$home\" ]; then
  printf 'CCRELAY_HOME_MISSING' >&2
  exit 42
fi
if [ \"$created\" = false ] && [ -e \"$home/.ssh\" ] && [ ! -f \"$home/.ssh/.ccrelay-managed\" ] && [ ! -f \"$home/.ssh/id_ed25519_ccrelay_cluster\" ]; then
  printf 'CCRELAY_ACCOUNT_CONFLICT' >&2
  exit 43
fi
printf '%s:%s\\n' {shlex.quote(username)} {shlex.quote(password)} | chpasswd
mkdir -p \"$home/.ssh\"
chmod 700 \"$home/.ssh\"
touch \"$home/.ssh/authorized_keys\"
grep -qxF {shlex.quote(public_key)} \"$home/.ssh/authorized_keys\" || printf '%s\\n' {shlex.quote(public_key)} >> \"$home/.ssh/authorized_keys\"
cat > \"$home/.ssh/id_ed25519_ccrelay_cluster\" <<'CCRELAY_PRIVATE_KEY'
{private_key}
CCRELAY_PRIVATE_KEY
cat > \"$home/.ssh/id_ed25519_ccrelay_cluster.pub\" <<'CCRELAY_PUBLIC_KEY_FILE'
{public_key}
CCRELAY_PUBLIC_KEY_FILE
cp \"$home/.ssh/id_ed25519_ccrelay_cluster\" \"$home/.ssh/id_ed25519\"
cp \"$home/.ssh/id_ed25519_ccrelay_cluster.pub\" \"$home/.ssh/id_ed25519.pub\"
chmod 600 \"$home/.ssh/authorized_keys\" \"$home/.ssh/id_ed25519_ccrelay_cluster\" \"$home/.ssh/id_ed25519\"
chmod 644 \"$home/.ssh/id_ed25519_ccrelay_cluster.pub\" \"$home/.ssh/id_ed25519.pub\"
touch \"$home/.ssh/.ccrelay-managed\"
chown -R {shlex.quote(username)} \"$home/.ssh\"
printf CCRELAY_DEDICATED_READY
"""


def normalize_posix_script(script: str) -> str:
    return script.replace("\r\n", "\n").replace("\r", "\n")


def windows_provision_script(username: str, password: str, public_key: str, private_key: str) -> str:
    def ps(value: str) -> str:
        return "'" + value.replace("'", "''") + "'"
    return f"""$ErrorActionPreference = 'Stop'
$secure = ConvertTo-SecureString {ps(password)} -AsPlainText -Force
$existing = Get-LocalUser -Name {ps(username)} -ErrorAction SilentlyContinue
$created = $false
if (-not $existing) {{
  New-LocalUser -Name {ps(username)} -Password $secure -AccountNeverExpires -PasswordNeverExpires
  $created = $true
}}
$profile = Join-Path $env:SystemDrive ('Users\\' + {ps(username)})
$ssh = Join-Path $profile '.ssh'
if (-not $created -and -not (Test-Path -LiteralPath (Join-Path $ssh '.ccrelay-managed')) -and -not (Test-Path -LiteralPath (Join-Path $ssh 'id_ed25519_ccrelay_cluster'))) {{
  throw 'CCRELAY_ACCOUNT_CONFLICT'
}}
New-Item -ItemType Directory -Force $ssh | Out-Null
$authorizedKeys = Join-Path $ssh 'authorized_keys'
if (-not (Test-Path -LiteralPath $authorizedKeys)) {{ New-Item -ItemType File $authorizedKeys | Out-Null }}
if (-not (Select-String -LiteralPath $authorizedKeys -SimpleMatch {ps(public_key)} -Quiet)) {{ Add-Content -LiteralPath $authorizedKeys -Value {ps(public_key)} -Encoding ascii }}
Set-Content -LiteralPath (Join-Path $ssh 'id_ed25519_ccrelay_cluster') -Value {ps(private_key)} -Encoding ascii
Copy-Item -LiteralPath (Join-Path $ssh 'id_ed25519_ccrelay_cluster') -Destination (Join-Path $ssh 'id_ed25519') -Force
New-Item -ItemType File -Force (Join-Path $ssh '.ccrelay-managed') | Out-Null
icacls $ssh /inheritance:r /grant:r ({ps(username)} + ':(OI)(CI)F') | Out-Null
Write-Output 'CCRELAY_DEDICATED_READY'
"""


def ensure_remote_center_key(node: Dict[str, Any], username: str, timeout_seconds: int) -> Dict[str, Any]:
    command = f"export PATH=\"{POSIX_PATH}:$PATH\"; mkdir -p \"$HOME/.ssh\"; chmod 700 \"$HOME/.ssh\"; if [ ! -s \"$HOME/.ssh/id_ed25519\" ]; then ssh-keygen -q -t ed25519 -N '' -f \"$HOME/.ssh/id_ed25519\" -C ccrelay-center; fi; cat \"$HOME/.ssh/id_ed25519.pub\""
    result = ccrelay_ssh.run_authenticated_command(node["host"], node["port"], username, command, timeout_seconds, include_output=True)
    if not result.get("success"):
        return result
    public_key = next((line.strip() for line in str(result.get("stdout") or "").splitlines() if line.startswith("ssh-")), None)
    if not public_key:
        return {"success": False, "summary": "中心节点未返回 SSH 公钥"}
    return {"success": True, "publicKey": public_key, "fingerprint": fingerprint(public_key), "authMode": result.get("authMode")}


def center_verify_command(target: Dict[str, Any], username: str, dedicated: bool,
                          source_capability: Optional[Dict[str, Any]] = None,
                          source_username: Optional[str] = None,
                          ssh_arguments: Optional[List[str]] = None) -> str:
    capability = source_capability or {}
    target_host = shlex.quote(target["host"])
    target_user = shlex.quote(username)
    target_port = int(target["port"])
    ssh_path = capability.get("sshPath") or "ssh"
    shell_path = capability.get("shPath") or capability.get("shellPath") or "sh"
    arguments = list(ssh_arguments or [])
    if not arguments:
        arguments = ["-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
                     "-o", "ConnectTimeout=10"]
    rendered_arguments = " ".join(shlex.quote(value) for value in arguments)
    command = (f"{shlex.quote(ssh_path)} {rendered_arguments} -p {target_port} "
               f"{target_user}@{target_host} printf CCRELAY_CENTER_TO_NODE_OK")
    if dedicated and source_username != username:
        quoted = shlex.quote(command)
        runuser_path = capability.get("runuserPath")
        sudo_path = capability.get("sudoPath")
        su_path = capability.get("suPath")
        if capability.get("uid") == "0" and runuser_path:
            command = (f"{shlex.quote(runuser_path)} -u {target_user} -- "
                       f"{shlex.quote(shell_path)} -c {quoted}")
        elif sudo_path:
            command = (f"{shlex.quote(sudo_path)} -n -u {target_user} "
                       f"{shlex.quote(shell_path)} -c {quoted}")
        elif capability.get("uid") == "0" and su_path:
            command = (f"{shlex.quote(su_path)} -s {shlex.quote(shell_path)} "
                       f"-c {quoted} {target_user}")
        else:
            return "printf CCRELAY_USER_SWITCH_UNAVAILABLE >&2; exit 44"
    return f"export PATH=\"{POSIX_PATH}:$PATH\"; {command}"


def node_verify_command(target: Dict[str, Any], username: str,
                        source_capability: Optional[Dict[str, Any]] = None,
                        source_username: Optional[str] = None,
                        ssh_arguments: Optional[List[str]] = None) -> str:
    return center_verify_command(
        target, username, True, source_capability, source_username, ssh_arguments)


def fingerprint(public_key: str) -> str:
    parts = public_key.split()
    import hashlib
    digest = hashlib.sha256(base64.b64decode(parts[1])).digest()
    return "SHA256:" + base64.b64encode(digest).decode("ascii").rstrip("=")


def plan_interaction(failures: List[Dict[str, Any]], permission_failures: List[Dict[str, Any]]) -> Dict[str, Any]:
    fields = []
    argument_failures = [item for item in failures
                         if item.get("bootstrapFailureType") == "SSH_ARGUMENTS_INCOMPATIBLE"]
    options = []
    if argument_failures:
        options.extend([
            {"id": "USE_USER_PROVIDED_SSH_ARGUMENTS", "label": "使用用户提供的 SSH 参数", "recommended": True},
            {"id": "RETRY_DEFAULT_SSH_ARGUMENTS", "label": "确认客户端兼容后重试", "recommended": False},
        ])
        fields.extend([
            {"name": "sshArgumentsMode", "label": "SSH 参数来源", "required": True,
             "default": ccrelay_ssh.SSH_ARGUMENT_MODE_USER_PROVIDED,
             "hint": "选择 USER_PROVIDED 后按 argv token 顺序填写", "secret": False},
            {"name": "sshArguments", "label": "用户 SSH 参数 argv token", "required": True,
             "default": ["-o", "StrictHostKeyChecking=no"],
             "hint": "不要填写目标地址和 SSH 端口", "secret": False},
        ])
    if failures and not argument_failures:
        fields.append({"name": "nodeCredential", "label": "失败节点的节点级账号密码", "required": True,
                       "hint": "按 ip:port 单独配置；密码安全输入，不回显", "secret": True})
    if permission_failures:
        fields.append({"name": "accountMode", "label": "账号模式", "required": True,
                       "default": "EXISTING_ACCOUNT", "hint": "无法创建专用账号时可切换为现有账号模式", "secret": False})
    if not argument_failures:
        options.extend([
            {"id": "CREATE_NODE", "label": "补充节点级引导凭据", "recommended": not bool(options)},
            {"id": "USE_EXISTING_ACCOUNT", "label": "切换为现有账号模式", "recommended": False},
            {"id": "CANCEL", "label": "取消配置", "recommended": False},
        ])
    else:
        options.append({"id": "CANCEL", "label": "取消配置", "recommended": False})
    return {"prompt": "SSH 身份计划无法直接执行，需要用户补充凭据、SSH 参数或选择降级策略。",
            "options": options,
            "fields": fields}
