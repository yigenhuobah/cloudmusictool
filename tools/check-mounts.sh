#!/usr/bin/env bash
# 云音乐工具箱 · 挂载点体检 v2
#
# 数据源：ProfileExportTest 导出的 build/profile9608.json / profile9613.json
#（与代码共用同一份清单，9.6.08 / 9.6.13 各一份）。
# 检查项：
#   1) aapt2 dump badging 的 versionCode 必须与所选 profile JSON 顶层的
#      versionCode 一致（--allow-version 可跳过）；
#   2) 每个 mount 的 classCandidates 至少有一个能在 classes 表中找到；
#   3) 有 methodName 的 mount，必须在 methods 表中找到同参数/返回类型的方法。
#
# 用法:
#   tools/check-mounts.sh [APK路径] [--apk APK] [--dexsql PATH] [--profile JSON]
#                         [--aapt2 PATH] [--allow-version]
#
# 参数优先级: CLI > 环境变量 > 常见安装路径。
# 环境变量:
#   TARGET_APK, DEXSQL, PROFILE_JSON, AAPT2, ALLOW_VERSION=1
#   （旧变量 APK 仍作为 TARGET_APK 的别名）
#
# 缓存/临时文件固定在 <repo>/tools/.dexsql-cache/（已在 .gitignore 中忽略）。
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
POSITIONAL=""

usage() {
    cat <<'EOF'
Usage:
  tools/check-mounts.sh [APK_PATH] [--apk APK] [--dexsql PATH] [--profile JSON]
                        [--aapt2 PATH] [--allow-version]

Arguments:
  APK_PATH / --apk      目标 APK（网易云 9.6.08 / 9.6.13）；也可用 TARGET_APK 环境变量
  --dexsql PATH         dexsql 可执行文件；也可用 DEXSQL
  --profile JSON        ProfileExportTest 导出的 profile9608.json / profile9613.json；
                        也可用 PROFILE_JSON（期望 versionCode 取自该 JSON）
  --aapt2 PATH          aapt2 可执行文件；也可用 AAPT2（缺失时跳过 versionCode 校验）
  --allow-version       不强制 versionCode 与 profile JSON 一致
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
if [ -z "$PROFILE_JSON" ]; then
    for candidate in \
        "$REPO_ROOT/app/build/profile9608.json" \
        "$REPO_ROOT/app/build/profile9613.json" \
        "$REPO_ROOT/build/profile9608.json" \
        "$REPO_ROOT/build/profile9613.json" \
        "$PWD/app/build/profile9608.json" \
        "$PWD/app/build/profile9613.json" \
        "$PWD/build/profile9608.json" \
        "$PWD/build/profile9613.json" \
        "$REPO_ROOT/app/profile9608.json" \
        "$REPO_ROOT/app/profile9613.json"; do
        if [ -f "$candidate" ]; then
            PROFILE_JSON="$candidate"
            break
        fi
    done
fi

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
if [ -z "$PROFILE_JSON" ] || [ ! -f "$PROFILE_JSON" ]; then
    echo "ERROR: profile JSON not found: ${PROFILE_JSON:-<unset>}" >&2
    echo "  Fix: ./gradlew :app:testDebugUnitTest --tests com.cloudmusictool.app.ProfileExportTest" >&2
    echo "       or pass --profile /path/to/profile9608.json|profile9613.json / export PROFILE_JSON=..." >&2
    exit 2
fi
if [ -n "$AAPT2" ] && ! is_executable_file "$AAPT2"; then
    echo "WARN: aapt2 is not executable: $AAPT2; skipping versionCode assertion" >&2
    echo "      Fix: pass --aapt2 /path/to/aapt2 or set ANDROID_HOME/ANDROID_SDK_ROOT." >&2
    AAPT2=""
fi

# ---- 版本断言 -------------------------------------------------------------
# 期望 versionCode 取自 profile JSON 顶层字段（ProfileExportTest 导出时写入），
# 9608 / 9613 各自携带对应值，不再硬编码单一版本号。
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

echo "APK: $TARGET_APK"
echo "Profile: $PROFILE_JSON"

if is_executable_file "$AAPT2"; then
    VERSION_CODE="$("$AAPT2" dump badging "$TARGET_APK" 2>/dev/null \
        | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
    if [ -z "$VERSION_CODE" ]; then
        echo "ERROR: cannot read versionCode from $TARGET_APK" >&2
        exit 2
    fi
    EXPECTED_VERSION_CODE="$(profile_version_code "$PROFILE_JSON")"
    if [ -z "$EXPECTED_VERSION_CODE" ]; then
        echo "ERROR: cannot read versionCode from profile JSON: $PROFILE_JSON (need python3/python or jq)" >&2
        exit 2
    fi
    if [ "$VERSION_CODE" != "$EXPECTED_VERSION_CODE" ] && [ "$ALLOW_VERSION" != "1" ]; then
        echo "ERROR: versionCode=$VERSION_CODE (expected $EXPECTED_VERSION_CODE, per $PROFILE_JSON); use --allow-version to override" >&2
        exit 2
    fi
    if [ "$VERSION_CODE" != "$EXPECTED_VERSION_CODE" ]; then
        echo "WARN: versionCode=$VERSION_CODE (expected $EXPECTED_VERSION_CODE), continuing due to --allow-version"
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

json_to_tsv() {
    local file="$1"
    if command -v python3 >/dev/null 2>&1; then
        python3 - "$file" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    data = json.load(f)
for m in data.get("mounts", []):
    print("\t".join([
        str(m.get("featureId", "")),
        str(m.get("logicalName", "")),
        ",".join(m.get("classCandidates") or []),
        m.get("methodName") or "",
        m.get("returnType") or "",
        ",".join(m.get("paramTypes") or []),
    ]))
PY
    elif command -v python >/dev/null 2>&1; then
        python - "$file" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    data = json.load(f)
for m in data.get("mounts", []):
    print("\t".join([
        str(m.get("featureId", "")),
        str(m.get("logicalName", "")),
        ",".join(m.get("classCandidates") or []),
        m.get("methodName") or "",
        m.get("returnType") or "",
        ",".join(m.get("paramTypes") or []),
    ]))
PY
    elif command -v jq >/dev/null 2>&1; then
        jq -r '.mounts[] | [.featureId, .logicalName,
            (.classCandidates | join(",")), (.methodName // ""),
            (.returnType // ""), (.paramTypes | join(","))] | @tsv' "$file"
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

M_FID=(); M_LOG=(); M_CLASSES=(); M_METHOD=(); M_RET=(); M_PARAMS=()
MOUNT_COUNT=0
while IFS=$'\t' read -r fid logical classes method ret params; do
    [ -n "$fid" ] || continue
    MOUNT_COUNT=$((MOUNT_COUNT + 1))
    M_FID[$MOUNT_COUNT]="$fid"
    M_LOG[$MOUNT_COUNT]="$logical"
    M_CLASSES[$MOUNT_COUNT]="$classes"
    M_METHOD[$MOUNT_COUNT]="$method"
    M_RET[$MOUNT_COUNT]="$ret"
    M_PARAMS[$MOUNT_COUNT]="$params"
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

{
    printf '%s\n' "$CLASS_SQL"
    if [ -n "$METHOD_SQL" ]; then
        printf '%s\n' "$METHOD_SQL"
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
CLASS_ROWS=0
METHOD_ROWS=0

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
echo "RESULT: OK=$PASS GONE=$GONE ERROR=$ERRORS (mounts=$MOUNT_COUNT)"
if [ "$GONE" -gt 0 ] || [ "$ERRORS" -gt 0 ]; then
    if [ "$GONE" -gt 0 ]; then
        echo "GONE mounts:"
        printf "$FAILED_LIST"
    fi
    exit 1
fi
