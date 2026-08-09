#!/bin/bash
# 知伴代码质量标准尺子 — 所有测量者（Claude / Codex / CLI）共用这一把。
# 用法：cd 到项目根目录，运行 bash scripts/measure.sh
# 每一行输出格式：指标名 | 当前值 | 目标值 | 状态
# 不要改这把尺子。改之前先达成共识。

set -e
cd "$(dirname "$0")/.."

# grep -c 在 0 匹配时既打印 0 又返回非零退出码，直接 `|| echo 0` 会叠加成 "0\n0"，
# 导致 `[ "$n" -eq 0 ]` 报 "integer expression expected"。gcount 保证只返回一个整数。
gcount() { local n; n=$(grep -c "$1" "$2" 2>/dev/null || true); echo "${n:-0}"; }

SEP="-----------------------------------------------------------"
echo "$SEP"
echo "知伴代码质量标准测量（HEAD: $(git rev-parse --short HEAD 2>/dev/null || echo 'unknown')）"
echo "运行时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "$SEP"

# ===== 维度1：架构设计 =====
echo ""
echo "【维度1：架构设计】"
echo ""

# 1a. 构造参数 > 8 的类（含 class Foo(...) 简写主构造函数）
echo "1a. 构造参数 > 8 的类（目标: 0 个）:"
# 匹配带主构造函数的类声明：class Name( / class Name<T>( / ... constructor( 。排除 fun 行。
grep -rnE "class [A-Za-z0-9_]+(<[^>]*>)?[[:space:]]*\(|constructor[[:space:]]*\(" app/src/main agent/*/src --include="*.kt" 2>/dev/null | grep -v "/build/" | grep -v "fun " | while IFS=: read -r file line rest; do
  # 豁免 data class / enum / sealed / object——它们是值类型或实体，不是 DI 服务类，"抽 collaborator"
  # 不适用；@Entity/@Immutable 在上一行的也豁免（Room 实体、不可变值对象）。
  case "$rest" in
    *"data class "*|*"enum class "*|*"sealed class "*|*"object "*) continue ;;
  esac
  case "$(sed -n "$((line-1))p" "$file" 2>/dev/null)" in
    *@Entity*|*@Immutable*) continue ;;
  esac
  # 从该行起取 60 行，定位第一个 (（主构造参数表起点），数其中顶层逗号得参数数，并修正尾逗号。
  params=$(sed -n "${line},$((line+60))p" "$file" | awk '
    { buf = buf $0 " " }
    END {
      p = index(buf, "(")
      if (p == 0) { print 0; exit }
      depth = 0; commas = 0; started = 0; lastSig = ""
      for (i = p; i <= length(buf); i++) {
        c = substr(buf, i, 1)
        if (c == "(") { depth++; started = 1; continue }
        if (c == ")") { if (depth > 0) depth--; if (depth == 0) break; continue }
        if (depth == 1) {
          if (c == ",") { commas++; lastSig = "," }
          else if (c != " " && c != "\t") lastSig = c
        }
      }
      if (!started || (commas == 0 && lastSig == "")) { print 0; exit }
      n = commas + 1
      if (lastSig == ",") n--   # Kotlin 允许尾逗号
      print n
    }
  ')
  if [ -n "$params" ] && [ "$params" -gt 8 ] 2>/dev/null; then
    cls=$(echo "$rest" | grep -oE "class [A-Za-z0-9_]+" | head -1 | sed 's/class //')
    echo "  ❌ $cls ($file:$line) = $params 个参数"
  fi
done
echo "  （无输出 = 全部 ≤8 ✅；已豁免 data class/enum/sealed/object 与 @Entity 实体；近似：泛型默认值逗号或字符串括号偶有偏差）"

# 1b. @Provides 方法体内无业务字符串
echo ""
echo -n "1b. @Provides 含业务字符串（目标: 0）: "
count=$(grep -A10 "@Provides" app/src/main/java/com/zhiban/rebuild/di/*.kt 2>/dev/null | grep -c '回答风格\|提示词\|文案\|val.*=.*"' || true); count=${count:-0}
echo "$count"

# 1c. AgentDataRepository 是否直接持有 database
echo ""
echo -n "1c. AgentDataRepository 持有 database（目标: 不持有）: "
result=$(gcount "private val database" app/src/main/java/com/zhiban/rebuild/data/agent/AgentDataRepository.kt)
if [ "$result" -gt 0 ]; then echo "❌ 持有"; else echo "✅ 不持有"; fi

# 1d. agent 子模块反向依赖 app
echo ""
echo -n "1d. agent 子模块反向依赖 app（目标: 0 行）: "
count=$(grep -rn "import com.zhiban.rebuild.ui\.\|import com.zhiban.rebuild.data\." agent/*/src --include="*.kt" 2>/dev/null | grep -v "/build/" | wc -l)
echo "$count 行"

# ===== 维度2：代码质量 =====
echo ""
echo "$SEP"
echo "【维度2：代码质量】"
echo ""

# 2a. 物理行 >1000 的文件（精确列出）
echo "2a. >1000 物理行的文件（目标: 0 个）:"
find app/src/main agent/*/src -name "*.kt" -not -path "*/build/*" -exec wc -l {} + 2>/dev/null | awk '$1>1000 && $2!="total"{printf "  %s %s\n",$1,$2}' | sort -rn
echo "  （无输出 = 0 个 ✅）"

# 2b. 物理行 600-1000 的文件数
echo ""
echo -n "2b. 600-1000 物理行的文件（目标: ≤5）: "
count=$(find app/src/main agent/*/src -name "*.kt" -not -path "*/build/*" -exec wc -l {} + 2>/dev/null | awk '$1>600 && $1<=1000 && $2!="total"' | wc -l)
echo "$count 个"

# 2c. detekt 结构警告（需跑 gradle，这里标注）
echo ""
echo "2c. detekt 结构警告（目标: 0）: 需跑 ./gradlew detekt 查看报告"
echo "    detekt 阻断 + ktlint（目标: 0）: 需跑 ./gradlew check"

# ===== 维度3：性能 =====
echo ""
echo "$SEP"
echo "【维度3：性能】"
echo ""

# 3a. ContactDao.search LIKE 数
echo -n "3a. ContactDao.search 中 LIKE 数（目标: 0，改用 FTS）: "
count=$(gcount "LIKE" app/src/main/java/com/zhiban/rebuild/data/contact/ContactDao.kt)
echo "$count 处"

# 3b. 主 TAB collectAsState 数
echo ""
echo "3b. 主 TAB collectAsState 数（目标: 每个 ≤5）:"
for f in RelationTab CalendarTab SkillTab ProfileTab; do
  count=$(gcount "collectAsState" app/src/main/java/com/zhiban/rebuild/ui/tabs/$f.kt)
  status="✅"
  if [ "$count" -gt 5 ]; then status="❌"; fi
  echo "  $f: $count $status"
done

# 3c. composition 期间 IO（remember 里做文件操作）
echo ""
echo -n "3c. remember 里执行 IO（目标: 0）: "
# 只找 remember 块内有 read/listFiles/cacheDir/query 等 IO 操作的
count=$(grep -rn "remember.*{" app/src/main/java/com/zhiban/rebuild/ui --include="*.kt" 2>/dev/null | grep -v "/build/" | grep -c "read\|listFiles\|cacheDir\|query\|\.walk\|File(" || true); count=${count:-0}
echo "$count 处"

# ===== 维度4：安全性 =====
echo ""
echo "$SEP"
echo "【维度4：安全性】"
echo ""

# 4a. 硬编码密钥
echo -n "4a. 硬编码密钥（目标: 0）: "
count=$(grep -rn 'sk-[A-Za-z0-9]\|Bearer [A-Za-z0-9]\|api_key.*=.*"' app/src/main agent/*/src --include="*.kt" 2>/dev/null | grep -v "/build/" | wc -l)
echo "$count"

# 4b. 日程标题进未加密存储
echo ""
echo -n "4b. 日程标题进未加密存储（目标: 0）: "
count=$(grep -rn "KEY_TITLE\|putString.*title\|\.putString.*title" app/src/main/java/com/zhiban/rebuild/data/calendar/ --include="*.kt" 2>/dev/null | wc -l)
echo "$count"

# 4c. 裸网络调用（排除 Gate/Policy/Provider/DI/import）
echo ""
echo -n "4c. 裸网络调用（目标: 0）: "
# 找 OkHttpClient/WebSocket 的直接使用，排除 DI 定义、import、Provider 封装
count=$(grep -rn "\.newWebSocket\|\.execute(\|\.newCall(" app/src/main agent/*/src --include="*.kt" 2>/dev/null | grep -v "/build/" | grep -v "Test\|import\|Policy\|Gate\|ResilientProvider\|OpenAiCompatible\|StepFunCloud\|McpTransport\|VolcEmbedding\|NetworkModule\|ProviderModule" | wc -l)
echo "$count 处"

# ===== 维度5：可测试性 =====
echo ""
echo "$SEP"
echo "【维度5：可测试性】"
echo ""

# 5a. suspend 链路用 runCatching 而非 runSuspendCatching（排除纯同步解析）
echo -n "5a. suspend 链路裸 runCatching（目标: 0 不安全的）: "
# 只找包在 suspend 函数内的 runCatching（近似：同文件有 suspend fun 且用了 runCatching）
suspect=$(grep -rln "runCatching" app/src/main agent/*/src --include="*.kt" 2>/dev/null | grep -v "/build/" | grep -v "runSuspendCatching\|Test" | while read f; do
  if grep -q "suspend fun" "$f" 2>/dev/null; then echo "$f"; fi
done | wc -l)
echo "$suspect 个可疑文件（需人工确认是否在 suspend 路径上）"

# 5b. 测试/生产文件比
echo ""
jvm_test=$(find app/src/test agent/*/src/test -name "*.kt" 2>/dev/null | wc -l)
dev_test=$(find app/src/androidTest -name "*.kt" 2>/dev/null | wc -l)
total_test=$((jvm_test + dev_test))
prod=$(find app/src/main agent/*/src -name "*.kt" -not -path "*/build/*" 2>/dev/null | wc -l)
ratio=$(echo "scale=2; $total_test / $prod" | bc 2>/dev/null || echo "?")
echo "5b. 测试/生产文件比（目标: ≥0.40）:"
echo "    全部测试=$total_test (JVM=$jvm_test + 设备=$dev_test) / 生产=$prod = $ratio"

# 5c. ContactFactDisplayNormalizer @Test 数
echo ""
echo -n "5c. ContactFactDisplayNormalizer @Test（目标: ≥8）: "
count=$(find . -name "ContactFactDisplayNormalizerTest.kt" -not -path "*/build/*" -exec grep -c "@Test" {} \; 2>/dev/null || true); count=${count:-0}
echo "$count"

# ===== 维度7：技术债 =====
echo ""
echo "$SEP"
echo "【维度7：技术债务】"
echo ""

# 7a. 死代码（已知 roadmap 占位已标 @Suppress，这里只查未标注的零引用类）
echo "7a. 未标注的零引用生产类（目标: 0）:"
echo "    （需跑: 对每个 public class grep 类名排除自身和测试，0 命中=死代码）"
echo "    已知 roadmap 占位: PlanLifecycle/PlanValidator/ContactEnrichmentProvider（@Suppress 标注，不算债）"

# 7b. owner_contact_links 与 UserProfileStore 合一
echo ""
echo -n "7b. owner_contact_links 与 UserProfileStore 合一（目标: 已合一）: "
merge=$(grep -c "mergeMissingIdentity\|selfIdentityMissing\|hasIdentity" app/src/main/java/com/zhiban/rebuild/data/agent/ContactAgentDataRepository.kt app/src/main/java/com/zhiban/rebuild/runtime/personalization/UserProfileStore.kt 2>/dev/null | tail -1 | cut -d: -f2)
if [ "$merge" -gt 0 ] 2>/dev/null; then echo "✅ 有合并逻辑"; else echo "❌ 未合一"; fi

# 7c. MemoryToolBindings 走 PlanEnvelopeFactory
echo ""
echo -n "7c. MemoryToolBindings 走 PlanEnvelopeFactory（目标: 是）: "
pf=$(gcount "PlanEnvelopeFactory" app/src/main/java/com/zhiban/rebuild/runtime/tool/MemoryToolBindings.kt)
self_sha=$(gcount "idempotencyKey[[:space:]]*=[[:space:]]*sha256" app/src/main/java/com/zhiban/rebuild/runtime/tool/MemoryToolBindings.kt)
if [ "$pf" -gt 0 ] && [ "$self_sha" -eq 0 ]; then echo "✅"; else echo "❌"; fi

# ===== 总规模 =====
echo ""
echo "$SEP"
echo "【总规模】"
echo ""
echo -n "生产文件: "; find app/src/main agent/*/src -name "*.kt" -not -path "*/build/*" 2>/dev/null | wc -l
echo -n "生产行数: "; find app/src/main agent/*/src -name "*.kt" -not -path "*/build/*" -exec cat {} + 2>/dev/null | wc -l
echo -n "JVM测试: "; find app/src/test agent/*/src/test -name "*.kt" 2>/dev/null | wc -l
echo -n "设备测试: "; find app/src/androidTest -name "*.kt" 2>/dev/null | wc -l
echo ""
echo "$SEP"
echo "测量完毕。这把尺子是唯一的，所有测量者用同一份。"
echo "如果不同的人量出不同的数字——先检查用的是不是这把尺子。"
