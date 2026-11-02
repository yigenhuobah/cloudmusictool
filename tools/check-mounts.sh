#!/usr/bin/env bash
# 云音乐工具箱 · 挂载点体检 v3
#
# 数据源：ProfileExportTest 导出的 build/profile9608.json / profile9613.json /
# profile9615.json（与代码共用同一份清单）。
# 检查项：
#   1) aapt2 dump badging 的 versionCode 必须与所选 profile JSON 顶层的
#      versionCode 一致（--allow-version 可跳过）；未显式 --profile 时按
#      APK versionCode 自动选择 profile（9006008/9006013/9006015）；
#   2) 每个 mount 的 classCandidates 至少有一个能在 classes 表中找到；
#   3) 有 methodName 的 mount，必须在 methods 表中找到同参数/返回类型的方法；
#   4) 有 anchors 的 mount，至少一个 classCandidate 的方法体里包含全部锚点
#      字符串（与 DexKit ClassMatcher.addUsingString 的 Contains 语义一致）。
#
# 用法:
#   tools/check-mounts.sh [APK路径] [--apk APK] [--dexsql PATH] [--profile JSON]
#                         [--aapt2 PATH] [--allow-version] [--skip-anchors]
#
# 参数优先级: CLI > 环境变量 > 常见安装路径。
# 环境变量:
#   TARGET_APK, DEXSQL, PROFILE_JSON, AAPT2, ALLOW_VERSION=1, SKIP_ANCHORS=1
#   （旧变量 APK 仍作为 TARGET_APK 的别名）
#
# 缓存/临时文件固定在 <repo>/tools/.dexsql-cache/（已在 .gitignore 中忽略）。
# 本脚本不做 dexsql 查询缓存，每次都用当前 APK 直接查询；apk.meta 记录
# 路径/大小/mtime/sha256，同路径替换 APK 时会清理旧临时结果并重新查询。
#
# 输出: OK / GONE / ERROR；GONE 或 ERROR 时退出码非 0。
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="$REPO_ROOT/tools/.dexsql-cache"

TARGET_APK="${TARGET_APK:-${APK:-}}"
DEXSQL="${DEXSQL:-}"
PROFILE_JSON="${PROFILE_JSON:-}"
AAPT2="${AAPT2:-}"
ALLOW_VERSION="${ALLOW_VERSION:-0}"
SKIP_ANCHORS="${SKIP_ANCHORS:-0}"
POSITIONAL=""

usage() {
    cat <<'EOF'
Usage:
  tools/check-mounts.sh [APK_PATH] [--apk APK] [--dexsql PATH] [--profile JSON]
                        [--aapt2 PATH] [--allow-version] [--skip-anchors]

Arguments:
  APK_PATH / --apk      目标 APK（网易云 9.6.08 / 9.6.13 / 9.6.15）；也可用 TARGET_APK 环境变量
  --dexsql PATH         dexsql 可执行文件；也可用 DEXSQL
  --profile JSON        ProfileExportTest 导出的 profile9608.json / profile9613.json /
                        profile9615.json；也可用 PROFILE_JSON（期望 versionCode 取自该 JSON）。
                        不传时按 APK versionCode 自动选择：
                        9006008 -> profile9608.json，9006013 -> profile9613.json，
                        9006015 -> profile9615.json（缺失时回退 profile9613.json 并 WARN）
  --aapt2 PATH          aapt2 可执行文件；也可用 AAPT2（缺失时无法自动选 profile，
                        也会跳过 versionCode 校验）
  --allow-version       不强制 versionCode 与 profile JSON 一致
  --skip-anchors        跳过 DexKitAnchors 校验（锚点缺失不再计入 GONE）
  -h, --help            显示本帮助

Resolution order: CLI > environment > common install paths.
Cache: <repo>/tools/.dexsql-cache/
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --apk)
            [ $# -ge 2 ] || { echo "ERROR: --apk needs a value" >&2; exit 2; }
            TARGET_APK="$2"; shift 2 ;;
        --apk=*)
            TARGET_APK="${1#*=}"; shift ;;
        --dexsql)
            [ $# -ge 2 ] || { echo "ERROR: --dexsql needs a value" >&2; exit 2; }
            DEXSQL="$2"; shift 2 ;;
        --dexsql=*)
            DEXSQL="${1#*=}"; shift ;;
        --profile)
            [ $# -ge 2 ] || { echo "ERROR: --profile needs a value" >&2; exit 2; }
            PROFILE_JSON="$2"; shift 2 ;;
        --profile=*)
            PROFILE_JSON="${1#*=}"; shift ;;
        --aapt2)
            [ $# -ge 2 ] || { echo "ERROR: --aapt2 needs a value" >&2; exit 2; }
            AAPT2="$2"; shift 2 ;;
        --aapt2=*)
            AAPT2="${1#*=}"; shift ;;
        --allow-version)
            ALLOW_VERSION=1; shift ;;
        --skip-anchors)
            SKIP_ANCHORS=1; shift ;;
        -h|--help)
            usage; exit 0 ;;
        -*)
            echo "ERROR: unknown option $1" >&2; usage >&2; exit 2 ;;
        *)
            if [ -n "$POSITIONAL" ]; then
                echo "ERROR: unexpected argument $1" >&2; exit 2
            fi
            POSITIONAL="$1"; shift ;;
    esac
done

# ---- 工具定位辅助 ---------------------------------------------------------
is_executable_file() {
    local path="$1"
    [ -n "$path" ] && [ -f "$path" ] || return 1
    case "$path" in
        *.exe|*.EXE) return 0 ;;
    esac
    [ -x "$path" ]
}

first_executable() {
    local candidate
    for candidate in "$@"; do
        if is_executable_file "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# SDK 根目录：环境变量、local.properties、常见安装位置（不写死机器路径）。
sdk_roots() {
    local value line
    for value in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [ -n "$value" ] && printf '%s\n' "$value"
    done
    if [ -f "$REPO_ROOT/local.properties" ]; then
        line="$(grep -E '^[[:space:]]*sdk\.dir[[:space:]]*=' \
            "$REPO_ROOT/local.properties" | tail -n 1 || true)"
        value="${line#*=}"
        value="$(printf '%s' "$value" \
            | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
                  -e 's/\\\\/\\/g' -e 's/\\:/:/g')"
        [ -n "$value" ] && printf '%s\n' "$value"
    fi
    printf '%s\n' \
        "$HOME/Android/Sdk" \
        "$HOME/Library/Android/sdk" \
        "$HOME/AppData/Local/Android/Sdk" \
        "/usr/lib/android-sdk" \
        "/opt/android-sdk"
}

# ---- APK 定位 -------------------------------------------------------------
if [ -z "$TARGET_APK" ]; then
    TARGET_APK="${POSITIONAL:-}"
fi
if [ -z "$TARGET_APK" ]; then
    for candidate in \
        "$PWD"/base*.apk \
        "$HOME/Downloads"/base*.apk \
        "$HOME/Downloads"/*cloudmusic*.apk \
        "$HOME/Desktop"/base*.apk; do
        if [ -f "$candidate" ]; then
            TARGET_APK="$candidate"
            echo "APK (auto-detected): $TARGET_APK" >&2
            break
        fi
    done
fi

# ---- profile 定位 ---------------------------------------------------------
# 未显式 --profile 时按 APK versionCode 自动选择，见下方「版本与 profile」段。
find_profile_file() {
    local ver="$1"
    local candidate
    for candidate in \
        "$REPO_ROOT/app/build/profile${ver}.json" \
        "$REPO_ROOT/build/profile${ver}.json" \
        "$PWD/app/build/profile${ver}.json" \
        "$PWD/build/profile${ver}.json" \
        "$REPO_ROOT/app/profile${ver}.json"; do
        if [ -f "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# ---- dexsql 定位 ----------------------------------------------------------
if [ -z "$DEXSQL" ]; then
    if command -v dexsql >/dev/null 2>&1; then
        DEXSQL="$(command -v dexsql)"
    elif command -v dexsql.exe >/dev/null 2>&1; then
        DEXSQL="$(command -v dexsql.exe)"
    else
        DEXSQL="$(first_executable \
            "$REPO_ROOT/tools/dexsql/dexsql" \
            "$REPO_ROOT/tools/dexsql/dexsql.exe" \
            "$REPO_ROOT/tools/dexsql.exe" \
            "$HOME/.local/bin/dexsql" \
            "$HOME/bin/dexsql" \
            "/usr/local/bin/dexsql" \
            "$HOME/tools/dexsql/dexsql" \
            "$HOME/tools/dexsql/dexsql.exe" || true)"
    fi
fi

# ---- aapt2 定位 -----------------------------------------------------------
if [ -z "$AAPT2" ]; then
    if command -v aapt2 >/dev/null 2>&1; then
        AAPT2="$(command -v aapt2)"
    else
        while IFS= read -r sdk_root; do
            [ -n "$sdk_root" ] || continue
            AAPT2="$(first_executable \
                "$sdk_root"/build-tools/*/aapt2.exe \
                "$sdk_root"/build-tools/*/aapt2 || true)"
            [ -n "$AAPT2" ] && break
        done < <(sdk_roots)
    fi
fi

# ---- 输入校验 -------------------------------------------------------------
if [ -z "$TARGET_APK" ] || [ ! -f "$TARGET_APK" ]; then
    echo "ERROR: APK not found: ${TARGET_APK:-<unset>}" >&2
    echo "  Fix: pass --apk /path/to/base.apk, or export TARGET_APK=/path/to/base.apk," >&2
    echo "       or place base*.apk in the current directory / ~/Downloads." >&2
    exit 2
fi
if [ -z "$DEXSQL" ] \
    || { ! is_executable_file "$DEXSQL" && ! command -v "$DEXSQL" >/dev/null 2>&1; }; then
    echo "ERROR: dexsql not found: ${DEXSQL:-<unset>}" >&2
    echo "  Fix: install dexsql and put it on PATH, or pass --dexsql /path/to/dexsql," >&2
    echo "       or export DEXSQL=/path/to/dexsql." >&2
    exit 2
fi
if [ -n "$AAPT2" ] && ! is_executable_file "$AAPT2"; then
    echo "WARN: aapt2 is not executable: $AAPT2; skipping versionCode assertion" >&2
    echo "      Fix: pass --aapt2 /path/to/aapt2 or set ANDROID_HOME/ANDROID_SDK_ROOT." >&2
    AAPT2=""
fi

# ---- 版本与 profile ---------------------------------------------------------
# 显式 --profile 优先；否则按 APK versionCode 选择：
#   9006008 -> profile9608.json, 9006013 -> profile9613.json,
#   9006015 -> profile9615.json（缺失时回退 profile9613.json，并放宽版本断言）。
profile_version_code() {
    local file="$1"
    if command -v python3 >/dev/null 2>&1; then
        python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("versionCode", ""))' "$file" 2>/dev/null
    elif command -v python >/dev/null 2>&1; then
        python -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("versionCode", ""))' "$file" 2>/dev/null
    elif command -v jq >/dev/null 2>&1; then
        jq -r '.versionCode // empty' "$file" 2>/dev/null
    fi
}

VERSION_CODE=""
if is_executable_file "$AAPT2"; then
    VERSION_CODE="$("$AAPT2" dump badging "$TARGET_APK" 2>/dev/null \
        | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
    if [ -z "$VERSION_CODE" ]; then
        echo "ERROR: cannot read versionCode from $TARGET_APK" >&2
        exit 2
    fi
fi

PROFILE_AUTO=0
PROFILE_FALLBACK=0
if [ -z "$PROFILE_JSON" ]; then
    if [ -z "$VERSION_CODE" ]; then
        echo "ERROR: cannot auto-select profile without versionCode (aapt2 unavailable)" >&2
        echo "  Fix: pass --profile app/build/profile<ver>.json, or pass --aapt2 /path/to/aapt2," >&2
        echo "       or set ANDROID_HOME/ANDROID_SDK_ROOT." >&2
        exit 2
    fi
    case "$VERSION_CODE" in
        9006008) PROFILE_VER=9608 ;;
        9006013) PROFILE_VER=9613 ;;
        9006015) PROFILE_VER=9615 ;;
        *)
            echo "ERROR: no profile for versionCode=$VERSION_CODE (supported: 9006008/9006013/9006015)" >&2
            echo "  Fix: pass --profile app/build/profile<ver>.json explicitly." >&2
            exit 2 ;;
    esac
    PROFILE_AUTO=1
    PROFILE_JSON="$(find_profile_file "$PROFILE_VER" || true)"
    if [ -z "$PROFILE_JSON" ] && [ "$PROFILE_VER" = "9615" ]; then
        PROFILE_JSON="$(find_profile_file 9613 || true)"
        if [ -n "$PROFILE_JSON" ]; then
            PROFILE_FALLBACK=1
            echo "WARN: profile9615.json 缺失，回退 profile9613.json（9.6.15 复用 9.6.13 mounts）" >&2
            echo "      Fix: ./gradlew :app:testDebugUnitTest --tests com.cloudmusictool.app.ProfileExportTest" >&2
            echo "           生成 app/build/profile9615.json 后可消除本回退。" >&2
        fi
    fi
fi

if [ -z "$PROFILE_JSON" ] || [ ! -f "$PROFILE_JSON" ]; then
    echo "ERROR: profile JSON not found: ${PROFILE_JSON:-<unset>}" >&2
    echo "  Fix: ./gradlew :app:testDebugUnitTest --tests com.cloudmusictool.app.ProfileExportTest" >&2
    echo "       or pass --profile /path/to/profile9608.json|profile9613.json|profile9615.json" >&2
    echo "       / export PROFILE_JSON=..." >&2
    exit 2
fi

echo "APK: $TARGET_APK"
if [ "$PROFILE_AUTO" -eq 1 ]; then
    if [ "$PROFILE_FALLBACK" -eq 1 ]; then
        echo "Profile: $PROFILE_JSON (auto by versionCode=$VERSION_CODE; profile9615.json 缺失，已回退 9613)"
    else
        echo "Profile: $PROFILE_JSON (auto by versionCode=$VERSION_CODE)"
    fi
else
    echo "Profile: $PROFILE_JSON"
fi

# 期望 versionCode 取自 profile JSON 顶层字段（ProfileExportTest 导出时写入），
# 9608 / 9613 / 9615 各自携带对应值；9615 缺失回退 9613 时放宽断言。
if is_executable_file "$AAPT2"; then
    EXPECTED_VERSION_CODE="$(profile_version_code "$PROFILE_JSON")"
    if [ -z "$EXPECTED_VERSION_CODE" ]; then
        echo "ERROR: cannot read versionCode from profile JSON: $PROFILE_JSON (need python3/python or jq)" >&2
        exit 2
    fi
    if [ "$VERSION_CODE" != "$EXPECTED_VERSION_CODE" ] \
        && [ "$ALLOW_VERSION" != "1" ] && [ "$PROFILE_FALLBACK" != "1" ]; then
        echo "ERROR: versionCode=$VERSION_CODE (expected $EXPECTED_VERSION_CODE, per $PROFILE_JSON); use --allow-version to override" >&2
        exit 2
    fi
    if [ "$VERSION_CODE" != "$EXPECTED_VERSION_CODE" ]; then
        if [ "$PROFILE_FALLBACK" -eq 1 ]; then
            echo "WARN: versionCode=$VERSION_CODE (expected $EXPECTED_VERSION_CODE), continuing because profile9615.json 缺失已回退 9613"
        else
            echo "WARN: versionCode=$VERSION_CODE (expected $EXPECTED_VERSION_CODE), continuing due to --allow-version"
        fi
    fi
    echo "versionCode: $VERSION_CODE"
else
    echo "WARN: aapt2 not found; skip versionCode assertion" >&2
    echo "      Fix: pass --aapt2 /path/to/aapt2, export AAPT2=..., or set ANDROID_HOME/ANDROID_SDK_ROOT."
fi

# ---- JSON -> TSV ----------------------------------------------------------
# 缓存/临时文件固定在仓库内 tools/.dexsql-cache/（已 gitignore），不写系统临时目录。
if ! mkdir -p "$CACHE_DIR"; then
    echo "ERROR: cannot create cache dir: $CACHE_DIR" >&2
    exit 2
fi
TSV="$CACHE_DIR/mounts.tsv"
RAW="$CACHE_DIR/query.out"
ERR="$CACHE_DIR/query.err"
trap 'rm -f "$TSV" "$RAW" "$ERR" "${TSV}.clean"' EXIT

# ---- APK 指纹 -------------------------------------------------------------
# 本脚本不缓存查询结果，每次都用当前 APK 重新查询；这里仍记录指纹，
# 以便同路径替换 APK 时明确丢弃上一轮可能残留的临时结果。
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" 2>/dev/null | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" 2>/dev/null | awk '{print $1}'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" 2>/dev/null | awk '{print $NF}'
    fi
}
file_size() {
    wc -c < "$1" 2>/dev/null | tr -d '[:space:]'
}
file_mtime() {
    stat -c %Y "$1" 2>/dev/null \
        || stat -f %m "$1" 2>/dev/null \
        || date -r "$1" +%s 2>/dev/null
}

APK_SIZE="$(file_size "$TARGET_APK")"
APK_MTIME="$(file_mtime "$TARGET_APK")"
APK_SHA="$(sha256_of "$TARGET_APK" || true)"
APK_ABS="$(cd "$(dirname "$TARGET_APK")" 2>/dev/null && pwd)/$(basename "$TARGET_APK")"
META="$CACHE_DIR/apk.meta"

meta_value() {
    [ -f "$META" ] || return 1
    sed -n "s/^$1=//p" "$META" | head -1
}
meta_matches() {
    [ -f "$META" ] || return 1
    [ "$(meta_value path)" = "$APK_ABS" ] || return 1
    [ "$(meta_value size)" = "$APK_SIZE" ] || return 1
    if [ -n "$APK_SHA" ]; then
        [ "$(meta_value sha256)" = "$APK_SHA" ] || return 1
    else
        [ -n "$APK_MTIME" ] || return 1
        [ "$(meta_value mtime)" = "$APK_MTIME" ] || return 1
    fi
    return 0
}

if [ -f "$META" ] && ! meta_matches; then
    echo "APK 指纹已变化（同路径替换），丢弃旧临时结果并重新查询..." >&2
    rm -f "$TSV" "$RAW" "$ERR" "${TSV}.clean"
fi
{
    printf 'version=2\n'
    printf 'path=%s\n' "$APK_ABS"
    printf 'size=%s\n' "$APK_SIZE"
    printf 'mtime=%s\n' "${APK_MTIME:-unknown}"
    printf 'sha256=%s\n' "${APK_SHA:--}"
} > "$META.tmp" && mv -f "$META.tmp" "$META" \
    || echo "WARN: cannot write APK meta: $META" >&2

echo "APK 指纹: size=$APK_SIZE mtime=${APK_MTIME:-unknown} sha256=${APK_SHA:-unavailable}"
echo "签名校验: 完整参数/返回类型（来自 profile JSON）"

json_to_tsv() {
    local file="$1"
    # 字段分隔用 US(\x1f)、锚点分隔用 RS(\x1e)，不用 TAB：
    # IFS 里的 TAB 属于空白字符，空字段（如无参数方法）会被 read 折叠导致列错位。
    if command -v python3 >/dev/null 2>&1; then
        python3 - "$file" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    data = json.load(f)
for m in data.get("mounts", []):
    print("\x1f".join([
        str(m.get("featureId", "")),
        str(m.get("logicalName", "")),
        ",".join(m.get("classCandidates") or []),
        m.get("methodName") or "",
        m.get("returnType") or "",
        ",".join(m.get("paramTypes") or []),
        "\x1e".join(m.get("anchors") or []),
    ]))
PY
    elif command -v python >/dev/null 2>&1; then
        python - "$file" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    data = json.load(f)
for m in data.get("mounts", []):
    print("\x1f".join([
        str(m.get("featureId", "")),
        str(m.get("logicalName", "")),
        ",".join(m.get("classCandidates") or []),
        m.get("methodName") or "",
        m.get("returnType") or "",
        ",".join(m.get("paramTypes") or []),
        "\x1e".join(m.get("anchors") or []),
    ]))
PY
    elif command -v jq >/dev/null 2>&1; then
        jq -r '.mounts[] | [.featureId, .logicalName,
            (.classCandidates | join(",")), (.methodName // ""),
            (.returnType // ""), (.paramTypes | join(",")),
            ((.anchors // []) | join("\u001e"))] | join("\u001f")' "$file"
    else
        return 3
    fi
}

if ! json_to_tsv "$PROFILE_JSON" > "$TSV"; then
    echo "ERROR: cannot parse $PROFILE_JSON (need python3/python or jq)" >&2
    exit 2
fi

# Windows 上 Python 的文本 stdout 会输出 CRLF，read 只会去掉 LF，
# 这里统一去掉 CR，避免 param_descriptors 末尾带 \r 导致签名全部不匹配。
tr -d '\r' < "$TSV" > "${TSV}.clean" && mv "${TSV}.clean" "$TSV"

M_FID=(); M_LOG=(); M_CLASSES=(); M_METHOD=(); M_RET=(); M_PARAMS=(); M_ANCHORS=()
MOUNT_COUNT=0
while IFS=$'\x1f' read -r fid logical classes method ret params anchors; do
    [ -n "$fid" ] || continue
    MOUNT_COUNT=$((MOUNT_COUNT + 1))
    M_FID[$MOUNT_COUNT]="$fid"
    M_LOG[$MOUNT_COUNT]="$logical"
    M_CLASSES[$MOUNT_COUNT]="$classes"
    M_METHOD[$MOUNT_COUNT]="$method"
    M_RET[$MOUNT_COUNT]="$ret"
    M_PARAMS[$MOUNT_COUNT]="$params"
    M_ANCHORS[$MOUNT_COUNT]="$anchors"
done < "$TSV"

if [ "$MOUNT_COUNT" -eq 0 ]; then
    echo "ERROR: profile has no mounts" >&2
    exit 2
fi

# ---- 生成批量 SQL ---------------------------------------------------------
sql_escape() {
    printf '%s' "$1" | sed "s/'/''/g"
}

class_values=""
method_values=""
class_first=1
method_first=1
declare -A MOUNT_ROWS
declare -A ROW_MOUNT
ROW=0

for ((i = 1; i <= MOUNT_COUNT; i++)); do
    rows=""
    IFS=',' read -r -a candidates <<< "${M_CLASSES[$i]}"
    for cls in "${candidates[@]}"; do
        [ -n "$cls" ] || continue
        ROW=$((ROW + 1))
        ROW_MOUNT[$ROW]=$i
        rows="$rows $ROW"
        if [[ "$cls" == L*\; ]]; then
            desc="$cls"
        else
            desc="L${cls//./\/};"
        fi
        cls_sql="$(sql_escape "$desc")"
        if [ $class_first -eq 1 ]; then
            class_values="SELECT '$ROW' AS k, '$cls_sql' AS cls"
            class_first=0
        else
            class_values="$class_values UNION ALL SELECT '$ROW', '$cls_sql'"
        fi
        if [ -n "${M_METHOD[$i]}" ]; then
            nm_sql="$(sql_escape "${M_METHOD[$i]}")"
            ret_sql="$(sql_escape "${M_RET[$i]}")"
            prm_sql="$(sql_escape "${M_PARAMS[$i]}")"
            if [ $method_first -eq 1 ]; then
                method_values="SELECT '$ROW' AS k, '$cls_sql' AS cls, '$nm_sql' AS nm, '$ret_sql' AS ret, '$prm_sql' AS prm"
                method_first=0
            else
                method_values="$method_values UNION ALL SELECT '$ROW', '$cls_sql', '$nm_sql', '$ret_sql', '$prm_sql'"
            fi
        fi
    done
    MOUNT_ROWS[$i]="$rows"
done

# ---- anchors 批量 SQL -----------------------------------------------------
# DexKit ClassMatcher.addUsingString 默认 Contains：类的方法体字符串包含 anchor。
# 多个 anchor 之间是 AND（必须全部命中）；这里为每个（候选类, anchor）生成一行，
# 与运行时 findClass(anchors...) 的口径一致。LIKE 用 '!' 作转义符，
# 避免 anchor 里的 _ / % 被当成通配符。
anchor_like_pattern() {
    local value="$1"
    value="${value//!/!!}"
    value="${value//%/!%}"
    value="${value//_/!_}"
    printf '%%%s%%' "$value"
}

anchor_values=""
anchor_first=1
declare -A ANCHOR_ROWS_FOR
declare -A ANCHOR_TOTAL_FOR
AROW=0
for ((i = 1; i <= MOUNT_COUNT && SKIP_ANCHORS != 1; i++)); do
    [ -n "${M_ANCHORS[$i]}" ] || continue
    alist=()
    IFS=$'\x1e' read -r -a alist <<< "${M_ANCHORS[$i]}"
    ANCHOR_TOTAL_FOR[$i]=${#alist[@]}
    IFS=',' read -r -a candidates <<< "${M_CLASSES[$i]}"
    for ai in "${!alist[@]}"; do
        anchor="${alist[$ai]}"
        [ -n "$anchor" ] || continue
        pat_sql="$(sql_escape "$(anchor_like_pattern "$anchor")")"
        for cls in "${candidates[@]}"; do
            [ -n "$cls" ] || continue
            if [[ "$cls" == L*\; ]]; then
                desc="$cls"
            else
                desc="L${cls//./\/};"
            fi
            cls_sql="$(sql_escape "$desc")"
            AROW=$((AROW + 1))
            arow_key="$i:$ai"
            ANCHOR_ROWS_FOR[$arow_key]="${ANCHOR_ROWS_FOR[$arow_key]:-} $AROW"
            if [ $anchor_first -eq 1 ]; then
                anchor_values="SELECT '$AROW' AS k, '$cls_sql' AS cls, '$pat_sql' AS pat"
                anchor_first=0
            else
                anchor_values="$anchor_values UNION ALL SELECT '$AROW', '$cls_sql', '$pat_sql'"
            fi
        done
    done
done

# dexsql 版本差异：有 param_descriptors/return_descriptor 列时直接比对，
# 否则退化为 descriptor 字符串匹配。
HAS_SIG_COLUMNS=1
METHOD_SCHEMA="$("$DEXSQL" tables --input "$TARGET_APK" 2>/dev/null \
    | sed -n '/^methods$/,/^[a-z_]/p')"
if ! printf '%s\n' "$METHOD_SCHEMA" | grep -q 'param_descriptors'; then
    HAS_SIG_COLUMNS=0
fi

CLASS_SQL="SELECT 'C' AS kind, m.k AS k, (SELECT count(*) FROM classes c WHERE c.descriptor = m.cls) AS n FROM ($class_values) m;"

if [ -n "$method_values" ]; then
    if [ "$HAS_SIG_COLUMNS" = "1" ]; then
        METHOD_COND="x.return_descriptor = m.ret AND x.param_descriptors = m.prm"
    else
        METHOD_COND="x.descriptor = '(' || m.prm || ')' || m.ret"
    fi
    METHOD_SQL="SELECT 'M' AS kind, m.k AS k, (SELECT count(*) FROM methods x WHERE x.class_descriptor = m.cls AND x.name = m.nm AND x.method_kind IS NOT NULL AND $METHOD_COND) AS n FROM ($method_values) m;"
else
    METHOD_SQL=""
fi

if [ -n "$anchor_values" ]; then
    ANCHOR_SQL="SELECT 'A' AS kind, m.k AS k, (SELECT count(*) FROM method_strings x WHERE x.method_class = m.cls AND x.value LIKE m.pat ESCAPE '!') AS n FROM ($anchor_values) m;"
else
    ANCHOR_SQL=""
fi

{
    printf '%s\n' "$CLASS_SQL"
    if [ -n "$METHOD_SQL" ]; then
        printf '%s\n' "$METHOD_SQL"
    fi
    if [ -n "$ANCHOR_SQL" ]; then
        printf '%s\n' "$ANCHOR_SQL"
    fi
    printf '.exit\n'
} | "$DEXSQL" query --input "$TARGET_APK" --interactive > "$RAW" 2> "$ERR"
DEXSQL_STATUS=$?
if [ $DEXSQL_STATUS -ne 0 ]; then
    echo "ERROR: dexsql exited with $DEXSQL_STATUS" >&2
    sed -n '1,20p' "$ERR" >&2
    exit 2
fi
if grep -qi 'error' "$ERR" 2>/dev/null; then
    echo "ERROR: dexsql reported an error" >&2
    sed -n '1,20p' "$ERR" >&2
    exit 2
fi

# ---- 解析结果 -------------------------------------------------------------
declare -A CLASS_N
declare -A METHOD_N
declare -A ANCHOR_N
CLASS_ROWS=0
METHOD_ROWS=0
ANCHOR_ROWS=0

trim() {
    printf '%s' "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

while IFS= read -r line; do
    case "$line" in
        \|*) ;;
        *) continue ;;
    esac
    IFS='|' read -r -a parts <<< "$line"
    kind="$(trim "${parts[1]:-}")"
    key="$(trim "${parts[2]:-}")"
    val="$(trim "${parts[3]:-}")"
    if ! [[ "$key" =~ ^[0-9]+$ && "$val" =~ ^[0-9]+$ ]]; then
        continue
    fi
    case "$kind" in
        C) CLASS_N[$key]="$val"; CLASS_ROWS=$((CLASS_ROWS + 1)) ;;
        M) METHOD_N[$key]="$val"; METHOD_ROWS=$((METHOD_ROWS + 1)) ;;
        A) ANCHOR_N[$key]="$val"; ANCHOR_ROWS=$((ANCHOR_ROWS + 1)) ;;
    esac
done < "$RAW"

if [ "$CLASS_ROWS" -eq 0 ]; then
    echo "ERROR: dexsql returned no class rows" >&2
    sed -n '1,20p' "$RAW" >&2
    exit 2
fi
if [ -n "$METHOD_SQL" ] && [ "$METHOD_ROWS" -eq 0 ]; then
    echo "ERROR: dexsql returned no method rows" >&2
    sed -n '1,20p' "$RAW" >&2
    exit 2
fi
if [ -n "$ANCHOR_SQL" ] && [ "$ANCHOR_ROWS" -eq 0 ]; then
    echo "ERROR: dexsql returned no anchor rows" >&2
    sed -n '1,20p' "$RAW" >&2
    exit 2
fi

# ---- anchors 判定 ---------------------------------------------------------
# 每个带 anchors 的 mount：至少一个 classCandidate 的方法体包含全部锚点。
A_OK=0
A_GONE=0
ANCHOR_MOUNTS=0
ANCHOR_TOTAL=0
declare -A MOUNT_ANCHOR_FAIL
declare -A MOUNT_ANCHOR_MISS
if [ "$SKIP_ANCHORS" != "1" ]; then
    for ((i = 1; i <= MOUNT_COUNT; i++)); do
        [ -n "${M_ANCHORS[$i]}" ] || continue
        ANCHOR_MOUNTS=$((ANCHOR_MOUNTS + 1))
        ANCHOR_TOTAL=$((ANCHOR_TOTAL + ${ANCHOR_TOTAL_FOR[$i]:-0}))
        alist=()
        IFS=$'\x1e' read -r -a alist <<< "${M_ANCHORS[$i]}"
        missing=""
        for ai in "${!alist[@]}"; do
            anchor="${alist[$ai]}"
            [ -n "$anchor" ] || continue
            hit=0
            arow_key="$i:$ai"
            for row in ${ANCHOR_ROWS_FOR[$arow_key]:-}; do
                if [ "${ANCHOR_N[$row]:-0}" -gt 0 ]; then
                    hit=1
                    break
                fi
            done
            if [ "$hit" -eq 0 ]; then
                missing="$missing $anchor;"
            fi
        done
        if [ -n "$missing" ]; then
            MOUNT_ANCHOR_FAIL[$i]=1
            MOUNT_ANCHOR_MISS[$i]="$missing"
            A_GONE=$((A_GONE + 1))
        else
            A_OK=$((A_OK + 1))
        fi
    done
fi

# ---- 逐项判定 -------------------------------------------------------------
PASS=0
GONE=0
ERRORS=0
FAILED_LIST=""

echo "--------------------------------------------------------------------------------"

for ((i = 1; i <= MOUNT_COUNT; i++)); do
    found=0
    reasons=""
    for row in ${MOUNT_ROWS[$i]}; do
        class_n="${CLASS_N[$row]:-0}"
        if [ "$class_n" -gt 0 ]; then
            if [ -z "${M_METHOD[$i]}" ]; then
                found=1
                break
            fi
            method_n="${METHOD_N[$row]:-0}"
            if [ "$method_n" -gt 0 ]; then
                found=1
                break
            fi
            reasons="$reasons ${M_CLASSES[$i]}#${M_METHOD[$i]} 签名不匹配;"
        else
            reasons="$reasons ${M_CLASSES[$i]} 类不存在;"
        fi
    done

    if [ "${MOUNT_ANCHOR_FAIL[$i]:-0}" -eq 1 ]; then
        found=0
        reasons="$reasons anchors 缺失:${MOUNT_ANCHOR_MISS[$i]}"
    fi

    label="${M_FID[$i]}/${M_LOG[$i]}"
    if [ $found -eq 1 ]; then
        printf 'OK    %-62s %s#%s (%s)%s\n' \
            "$label" "${M_CLASSES[$i]}" "${M_METHOD[$i]:--}" \
            "${M_PARAMS[$i]}" "${M_RET[$i]:--}"
        PASS=$((PASS + 1))
    else
        printf 'GONE  %-62s %s#%s  %s\n' \
            "$label" "${M_CLASSES[$i]}" "${M_METHOD[$i]:--}" "$reasons"
        GONE=$((GONE + 1))
        FAILED_LIST="${FAILED_LIST}${label}\n"
    fi
done

echo "--------------------------------------------------------------------------------"
if [ "$SKIP_ANCHORS" = "1" ]; then
    echo "[anchors] skipped (--skip-anchors)"
elif [ "$ANCHOR_MOUNTS" -eq 0 ]; then
    echo "[anchors] no mounts with anchors"
else
    echo "[anchors] mounts=$ANCHOR_MOUNTS anchors=$ANCHOR_TOTAL OK=$A_OK GONE=$A_GONE"
    for ((i = 1; i <= MOUNT_COUNT; i++)); do
        [ -n "${M_ANCHORS[$i]}" ] || continue
        label="${M_FID[$i]}/${M_LOG[$i]}"
        if [ "${MOUNT_ANCHOR_FAIL[$i]:-0}" -eq 1 ]; then
            printf 'GONE  %-62s missing:%s\n' "$label" "${MOUNT_ANCHOR_MISS[$i]}"
        else
            printf 'OK    %-62s anchors=%s\n' "$label" "${ANCHOR_TOTAL_FOR[$i]:-0}"
        fi
    done
fi
echo "--------------------------------------------------------------------------------"
echo "RESULT: OK=$PASS GONE=$GONE ERROR=$ERRORS (mounts=$MOUNT_COUNT)"
if [ "$SKIP_ANCHORS" = "1" ]; then
    echo "ANCHORS: skipped"
else
    echo "ANCHORS: OK=$A_OK GONE=$A_GONE (mounts_with_anchors=$ANCHOR_MOUNTS, anchors=$ANCHOR_TOTAL)"
fi
if [ "$GONE" -gt 0 ] || [ "$ERRORS" -gt 0 ]; then
    if [ "$GONE" -gt 0 ]; then
        echo "GONE mounts:"
        printf "$FAILED_LIST"
    fi
    exit 1
fi
