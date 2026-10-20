#!/usr/bin/env bash
# =============================================================================
# Plan 2 端到端验收脚本（E5）
#
# 覆盖：
#   1. 重置测试数据（只动 1390000005x 测试账号 + 测试票档）
#   2. 两层限购：单笔 max_per_order、场次累计 purchase_limit
#   3. 并发抢票：6 个用户抢 5 件库存，验证不超卖、不卖负
#   4. 异步落库：订单最终生成、Redis/DB 库存一致、Stream pending == 0
#   5. 支付出票：绑定观演人 → 支付 → tb_ticket 一票一人
#   6. 取消/超时：库存、限购计数、观演人释放
#
# 前置条件：
#   - 后端已启动：http://localhost:8081/api
#   - MySQL（root/123456，db_ticket）和 Redis 容器（ticket_redis，密码 123456）可用
#   - 场次 1 已开票；票档 2 是干净测试票档（没有非测试订单/票）
#
# 用法：
#   ./docs/verify/plan2-e2e.sh
#   可用环境变量覆盖：BASE_URL、TIER_ID、SESSION_ID、CONCURRENCY、BUY_QTY 等
#
# 退出码：0 = 全部通过；1 = 有失败
# =============================================================================
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081/api}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-123456}"
DB_NAME="${DB_NAME:-db_ticket}"
REDIS_CONTAINER="${REDIS_CONTAINER:-ticket_redis}"
REDIS_PASS="${REDIS_PASS:-123456}"

TIER_ID="${TIER_ID:-2}"
SESSION_ID="${SESSION_ID:-1}"
PHONE_PREFIX="${PHONE_PREFIX:-1390000005}"
CONCURRENCY="${CONCURRENCY:-6}"
BUY_QTY="${BUY_QTY:-2}"
TEST_STOCK="${TEST_STOCK:-5}"

PASS=0
FAIL=0
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [ -t 1 ]; then
    C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_CYAN=$'\033[1;36m'; C_RESET=$'\033[0m'
else
    C_GREEN=''; C_RED=''; C_CYAN=''; C_RESET=''
fi

section() { printf '\n%s== %s ==%s\n' "$C_CYAN" "$1" "$C_RESET"; }
ok()      { PASS=$((PASS + 1)); printf '  %s✅ %s%s\n' "$C_GREEN" "$1" "$C_RESET"; }
bad()     { FAIL=$((FAIL + 1)); printf '  %s❌ %s%s\n' "$C_RED" "$1" "$C_RESET"; }

assert_eq() {
    local name=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        ok "$name（$actual）"
    else
        bad "$name：期望 [$expected]，实际 [$actual]"
    fi
}

assert_contains() {
    local name=$1 haystack=$2 needle=$3
    if printf '%s' "$haystack" | grep -q -- "$needle"; then
        ok "$name"
    else
        bad "$name：响应中没有「$needle」→ $haystack"
    fi
}

# Windows 的 mysql 客户端输出 CRLF；不剥掉 \r 的话，for uid in $(...) 拿到的 ID 会带 \r，
# redis HDEL 就删不到真正的字段，测试计数器会越积越多。
sql()   { mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" -D "$DB_NAME" --default-character-set=utf8mb4 -N -s -e "$1" 2>/dev/null | tr -d '\r'; }
redis() { docker exec "$REDIS_CONTAINER" redis-cli --no-auth-warning -a "$REDIS_PASS" "$@" 2>/dev/null | tr -d '\r'; }

json_get() {
    python -c '
import sys, json
# Windows 控制台默认按 GBK 读 stdin，错误响应里的中文会解码失败；
# 这里强制按 UTF-8 读，脚本失败时才能看到后端真正返回的 errorMsg。
d = json.loads(sys.stdin.buffer.read().decode("utf-8"))
for part in sys.argv[1].split("."):
    d = d[int(part)] if part.isdigit() else d[part]
print(d)
' "$1" 2>/dev/null
}

login() {
    local phone=$1 code resp token
    curl -s -m 10 -X POST "$BASE_URL/user/code?phone=$phone" >/dev/null
    code=$(redis GET "login:code:$phone")
    if [ -z "$code" ]; then
        printf '  取不到 %s 的验证码（后端/Redis 是否正常？）\n' "$phone" >&2
        return 1
    fi
    resp=$(curl -s -m 10 -X POST "$BASE_URL/user/login" \
        -H 'Content-Type: application/json' \
        -d "{\"phone\":\"$phone\",\"code\":\"$code\"}")
    token=$(printf '%s' "$resp" | json_get data)
    if [ -z "$token" ]; then
        printf '  登录失败：%s\n' "$resp" >&2
        return 1
    fi
    printf '%s' "$token"
}

login_or_die() {
    local phone=$1 token
    token=$(login "$phone") || { bad "登录失败：$phone"; exit 1; }
    printf '%s' "$token"
}

seckill() {
    local token=$1 qty=$2
    curl -s -m 10 -X POST "$BASE_URL/order/seckill" \
        -H "authorization: $token" \
        -H 'Content-Type: application/json' \
        -d "{\"tierId\":$TIER_ID,\"quantity\":$qty}"
}

wait_order_created() {
    local token=$1 order_id=$2 resp
    for _ in $(seq 1 50); do
        resp=$(curl -s -m 10 "$BASE_URL/order/result/$order_id" -H "authorization: $token")
        if printf '%s' "$resp" | grep -q '"created":true'; then
            return 0
        fi
        if printf '%s' "$resp" | grep -q '"failed":true'; then
            printf '    订单 %s 落库失败：%s\n' "$order_id" "$resp" >&2
            return 1
        fi
        sleep 0.2
    done
    printf '    等订单 %s 生成超时\n' "$order_id" >&2
    return 2
}

wait_pending_zero() {
    local pending
    for _ in $(seq 1 100); do
        pending=$(redis XPENDING stream.orders g1 | head -1)
        [ "$pending" = "0" ] && return 0
        sleep 0.2
    done
    return 1
}

stock_redis() { redis GET "ticker:stock:$TIER_ID"; }
stock_db()    { sql "select stock from tb_ticket_tier where id=$TIER_ID"; }
lock_db()     { sql "select lock_stock from tb_ticket_tier where id=$TIER_ID"; }
order_status() {
    sql "select status from tb_ticket_order where id=$1"
}
purchase_db() {
    sql "select coalesce((select quantity from tb_user_purchase
                          where user_id=(select id from tb_user where phone='$1')
                            and session_id=$SESSION_ID),0)"
}
test_user_ids() {
    sql "select id from tb_user where phone like '${PHONE_PREFIX}%'"
}

set_stock() {
    local n=$1
    sql "update tb_ticket_tier set stock=$n, lock_stock=0 where id=$TIER_ID"
    redis SET "ticker:stock:$TIER_ID" "$n" >/dev/null
}

add_stock() {
    local n=$1
    sql "update tb_ticket_tier set stock=stock+$n where id=$TIER_ID"
    redis INCRBY "ticker:stock:$TIER_ID" "$n" >/dev/null
}

# -----------------------------------------------------------------------------
section "0. 预检查"
# -----------------------------------------------------------------------------
http_code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE_URL/show/1")
if [ "$http_code" != "200" ]; then
    printf '后端不可用（GET /show/1 → %s），请先启动：JAVA_HOME=/d/Java/jdk_17 ./mvnw spring-boot:run\n' "$http_code" >&2
    exit 1
fi
ok "后端可访问"

TIER_ROW=$(sql "select concat_ws(',',max_per_order,status,seat_type) from tb_ticket_tier where id=$TIER_ID")
if [ -z "$TIER_ROW" ]; then
    printf '票档 %s 不存在\n' "$TIER_ID" >&2
    exit 1
fi
IFS=',' read -r MAX_PER_ORDER TIER_STATUS SEAT_TYPE <<< "$TIER_ROW"

SESSION_ROW=$(sql "select concat_ws(',',purchase_limit,status,if(on_sale_time<=now(),1,0)) from tb_show_session where id=$SESSION_ID")
if [ -z "$SESSION_ROW" ]; then
    printf '场次 %s 不存在\n' "$SESSION_ID" >&2
    exit 1
fi
IFS=',' read -r PURCHASE_LIMIT SESSION_STATUS ON_SALE_OK <<< "$SESSION_ROW"

assert_eq "票档 $TIER_ID 已上架" 1 "$TIER_STATUS"
assert_eq "场次 $SESSION_ID 已开票（1/2）" 1 "$([ "$SESSION_STATUS" = "1" ] || [ "$SESSION_STATUS" = "2" ] && echo 1 || echo 0)"
assert_eq "场次已过开票时间" 1 "$ON_SALE_OK"
if [ "$TIER_STATUS" != "1" ] || [ "$ON_SALE_OK" != "1" ]; then
    printf '场次/票档未开票，先执行 E4 的种子数据修改\n' >&2
    exit 1
fi
ok "单笔限购=$MAX_PER_ORDER，场次累计限购=$PURCHASE_LIMIT"

if wait_pending_zero; then
    ok "起始 Stream pending = 0"
else
    bad "起始 Stream pending != 0，先排查 stream.orders/g1"
    exit 1
fi

# -----------------------------------------------------------------------------
section "1. 重置测试数据"
# -----------------------------------------------------------------------------
if ! sql "delete from tb_ticket where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
          delete from tb_order_attendee where order_id in (select id from tb_ticket_order where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%'));
          delete from tb_ticket_order where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
          delete from tb_user_purchase where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
          delete from tb_user_attendee where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');"; then
    bad "清理测试数据失败"
    exit 1
fi
ok "已清理测试账号的订单/票/限购/观演人"

for uid in $(test_user_ids); do
    redis HDEL "ticker:ordered:$SESSION_ID" "$uid" >/dev/null
done
ok "已清理测试账号的 Redis 限购计数"

SOLD=$(sql "select count(*) from tb_ticket where tier_id=$TIER_ID")
PENDING=$(sql "select coalesce(sum(quantity),0) from tb_ticket_order where tier_id=$TIER_ID and status=0")
assert_eq "测试票档无其他已出票" 0 "$SOLD"
assert_eq "测试票档无其他待支付单" 0 "$PENDING"
if [ "$SOLD" != "0" ] || [ "$PENDING" != "0" ]; then
    printf '票档 %s 不是干净的测试票档（sold=%s pending=%s），换 TIER_ID 或手工清理后再跑\n' \
        "$TIER_ID" "$SOLD" "$PENDING" >&2
    exit 1
fi

sql "update tb_ticket_tier set stock=total_stock, lock_stock=0 where id=$TIER_ID"
redis DEL "ticker:stock:$TIER_ID" >/dev/null
ok "票档库存已重置"

# -----------------------------------------------------------------------------
section "2. 两层限购（用户 8）"
# -----------------------------------------------------------------------------
LIMIT_PHONE="${PHONE_PREFIX}8"
LIMIT_TOKEN=$(login_or_die "$LIMIT_PHONE")
LIMIT_STOCK=$((PURCHASE_LIMIT + 1))
set_stock "$LIMIT_STOCK"

resp=$(seckill "$LIMIT_TOKEN" $((MAX_PER_ORDER + 1)))
assert_contains "单笔超过 max_per_order 被拒" "$resp" "每单限购"
assert_eq "被拒后库存不变" "$LIMIT_STOCK" "$(stock_redis)"

resp=$(seckill "$LIMIT_TOKEN" "$MAX_PER_ORDER")
assert_contains "累计限购第一单成功" "$resp" '"success":true'
LIMIT_OID1=$(printf '%s' "$resp" | json_get data)
wait_order_created "$LIMIT_TOKEN" "$LIMIT_OID1" || bad "第一单落库失败"

resp=$(seckill "$LIMIT_TOKEN" 3)
assert_contains "累计超限被 Lua 拦截" "$resp" "超出限购数量"

resp=$(seckill "$LIMIT_TOKEN" $((PURCHASE_LIMIT - MAX_PER_ORDER)))
assert_contains "累计买到上限的最后一单成功" "$resp" '"success":true'
LIMIT_OID2=$(printf '%s' "$resp" | json_get data)
wait_order_created "$LIMIT_TOKEN" "$LIMIT_OID2" || bad "第二单落库失败"

resp=$(seckill "$LIMIT_TOKEN" 1)
assert_contains "达到累计上限后再买被拒" "$resp" "超出限购数量"

assert_eq "两单落库后可售库存" 1 "$(stock_db)"
assert_eq "两单落库后锁定库存" "$PURCHASE_LIMIT" "$(lock_db)"
assert_eq "两单落库后 Redis 库存" 1 "$(stock_redis)"

cancel1=$(curl -s -m 10 -X PUT "$BASE_URL/order/cancle?orderId=$LIMIT_OID1" -H "authorization: $LIMIT_TOKEN")
assert_contains "取消第一单" "$cancel1" '"success":true'
cancel2=$(curl -s -m 10 -X PUT "$BASE_URL/order/cancle?orderId=$LIMIT_OID2" -H "authorization: $LIMIT_TOKEN")
assert_contains "取消第二单" "$cancel2" '"success":true'

assert_eq "取消后 DB 可售库存" "$LIMIT_STOCK" "$(stock_db)"
assert_eq "取消后 DB 锁定库存" 0 "$(lock_db)"
assert_eq "取消后 Redis 库存" "$LIMIT_STOCK" "$(stock_redis)"
assert_eq "取消后限购计数清零" 0 "$(purchase_db "$LIMIT_PHONE")"
assert_eq "取消后订单状态=2" 2 "$(order_status "$LIMIT_OID1")"

# -----------------------------------------------------------------------------
section "3. 并发抢票不超卖（用户 0-5）"
# -----------------------------------------------------------------------------
set_stock "$TEST_STOCK"
TOKENS=()
for i in $(seq 0 $((CONCURRENCY - 1))); do
    phone="${PHONE_PREFIX}${i}"
    TOKENS[$i]=$(login_or_die "$phone")
done
ok "已登录 $CONCURRENCY 个测试账号"

for i in $(seq 0 $((CONCURRENCY - 1))); do
    ( seckill "${TOKENS[$i]}" "$BUY_QTY" > "$TMP_DIR/concurrent_$i.json" ) &
done
wait

EXPECTED_SUCCESS=$((TEST_STOCK / BUY_QTY))
CONCURRENT_SUCCESS=0
CONCURRENT_FAIL=0
CONCURRENT_ORDERS=()
for i in $(seq 0 $((CONCURRENCY - 1))); do
    resp=$(cat "$TMP_DIR/concurrent_$i.json")
    if printf '%s' "$resp" | grep -q '"success":true'; then
        oid=$(printf '%s' "$resp" | json_get data)
        if wait_order_created "${TOKENS[$i]}" "$oid"; then
            CONCURRENT_SUCCESS=$((CONCURRENT_SUCCESS + 1))
            CONCURRENT_ORDERS+=("$oid")
        else
            bad "并发订单 $oid 未落库"
        fi
    else
        CONCURRENT_FAIL=$((CONCURRENT_FAIL + 1))
        assert_contains "并发失败请求 $i 原因为售罄" "$resp" "已售罄"
    fi
done

assert_eq "并发成功单数" "$EXPECTED_SUCCESS" "$CONCURRENT_SUCCESS"
assert_eq "并发失败单数" "$((CONCURRENCY - EXPECTED_SUCCESS))" "$CONCURRENT_FAIL"
assert_eq "并发后 Redis 库存" "$((TEST_STOCK - EXPECTED_SUCCESS * BUY_QTY))" "$(stock_redis)"
assert_eq "并发后 DB 可售库存" "$((TEST_STOCK - EXPECTED_SUCCESS * BUY_QTY))" "$(stock_db)"
assert_eq "并发后 DB 锁定库存" "$((EXPECTED_SUCCESS * BUY_QTY))" "$(lock_db)"
if [ "$(stock_redis)" -ge 0 ] 2>/dev/null; then
    ok "库存未变负"
else
    bad "库存为负：$(stock_redis)"
fi
if wait_pending_zero; then ok "并发后 Stream pending = 0"; else bad "并发后仍有 pending 消息"; fi

# -----------------------------------------------------------------------------
section "4. 支付出票（用户 6）"
# -----------------------------------------------------------------------------
add_stock $((BUY_QTY * 2))
PAY_PHONE="${PHONE_PREFIX}6"
PAY_TOKEN=$(login_or_die "$PAY_PHONE")

resp=$(seckill "$PAY_TOKEN" "$BUY_QTY")
assert_contains "支付用户抢票成功" "$resp" '"success":true'
PAY_OID=$(printf '%s' "$resp" | json_get data)
wait_order_created "$PAY_TOKEN" "$PAY_OID" || bad "支付订单未落库"

add_attendee() {
    local token=$1 name=$2 id_card=$3 resp
    resp=$(curl -s -m 10 -X POST "$BASE_URL/user/attendee/add" \
        -H "authorization: $token" \
        -H 'Content-Type: application/json' \
        -d "{\"realName\":\"$name\",\"idCard\":\"$id_card\",\"idCardType\":1}")
    printf '%s' "$resp" | json_get data
}
# 名字用 ASCII：Windows Git Bash 会把原生 curl 参数按 GBK 转换，中文 JSON 会变成非法 UTF-8
PAY_ATT1=$(add_attendee "$PAY_TOKEN" "E2E-A" "110101199001010011")
PAY_ATT2=$(add_attendee "$PAY_TOKEN" "E2E-B" "110101199001010012")
if [ -n "$PAY_ATT1" ] && [ -n "$PAY_ATT2" ]; then
    ok "已创建两位观演人（$PAY_ATT1, $PAY_ATT2）"
else
    bad "创建观演人失败（$PAY_ATT1, $PAY_ATT2）"
fi

bind_resp=$(curl -s -m 10 -X POST "$BASE_URL/order/$PAY_OID/attendees" \
    -H "authorization: $PAY_TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"attendeeIds\":[$PAY_ATT1,$PAY_ATT2]}")
assert_contains "绑定观演人" "$bind_resp" '"success":true'

pay_resp=$(curl -s -m 10 -X PUT "$BASE_URL/order/finish?orderId=$PAY_OID" -H "authorization: $PAY_TOKEN")
assert_contains "支付成功" "$pay_resp" '"success":true'
assert_eq "订单状态=1" 1 "$(order_status "$PAY_OID")"
assert_eq "出票数量" "$BUY_QTY" "$(sql "select count(*) from tb_ticket where order_id=$PAY_OID")"
assert_eq "票均为未使用" "$BUY_QTY" "$(sql "select count(*) from tb_ticket where order_id=$PAY_OID and status=0")"
assert_eq "票面观演人正确" "$BUY_QTY" "$(sql "select count(*) from tb_ticket where order_id=$PAY_OID and real_name in ('E2E-A','E2E-B')")"
if [ "$SEAT_TYPE" = "0" ]; then
    assert_eq "对号票已生成座位号" "$BUY_QTY" "$(sql "select count(*) from tb_ticket where order_id=$PAY_OID and seat_no <> ''")"
else
    assert_eq "站票座位号为空" "$BUY_QTY" "$(sql "select count(*) from tb_ticket where order_id=$PAY_OID and seat_no = ''")"
fi

repeat_pay=$(curl -s -m 10 -X PUT "$BASE_URL/order/finish?orderId=$PAY_OID" -H "authorization: $PAY_TOKEN")
assert_contains "重复支付被拒" "$repeat_pay" "订单状态不允许完成"

PAY_STOCK=$(stock_db)
assert_eq "支付后 DB 可售库存" 3 "$PAY_STOCK"
assert_eq "支付后 DB 锁定库存" 6 "$(lock_db)"
assert_eq "支付后 Redis 库存 == DB" "$PAY_STOCK" "$(stock_redis)"

# -----------------------------------------------------------------------------
section "5. 取消/超时回补（用户 7 验证超时）"
# -----------------------------------------------------------------------------
TIMEOUT_PHONE="${PHONE_PREFIX}7"
TIMEOUT_TOKEN=$(login_or_die "$TIMEOUT_PHONE")

resp=$(seckill "$TIMEOUT_TOKEN" 1)
assert_contains "超时用户抢票成功" "$resp" '"success":true'
TIMEOUT_OID=$(printf '%s' "$resp" | json_get data)
wait_order_created "$TIMEOUT_TOKEN" "$TIMEOUT_OID" || bad "超时订单未落库"
assert_eq "超时单初始状态=0" 0 "$(order_status "$TIMEOUT_OID")"

sql "update tb_ticket_order set expire_time=date_sub(now(), interval 1 minute) where id=$TIMEOUT_OID"
printf '  等待 OrderTimeoutTask 扫描（最多 45 秒）...\n'
TIMEOUT_STATUS=""
for _ in $(seq 1 45); do
    TIMEOUT_STATUS=$(order_status "$TIMEOUT_OID")
    [ "$TIMEOUT_STATUS" = "4" ] && break
    sleep 1
done
assert_eq "超时单被置为已失效（4）" 4 "$TIMEOUT_STATUS"
assert_eq "超时后 DB 可售库存回补" 3 "$(stock_db)"
assert_eq "超时后 DB 锁定库存回补" 6 "$(lock_db)"
assert_eq "超时后 Redis 库存回补" 3 "$(stock_redis)"
assert_eq "超时后限购计数清零" 0 "$(purchase_db "$TIMEOUT_PHONE")"

# -----------------------------------------------------------------------------
section "6. 最终一致性"
# -----------------------------------------------------------------------------
FINAL_DB=$(stock_db)
FINAL_REDIS=$(stock_redis)
assert_eq "最终 Redis 库存 == DB 库存" "$FINAL_DB" "$FINAL_REDIS"
assert_eq "最终可售库存" 3 "$FINAL_DB"
assert_eq "最终锁定库存" 6 "$(lock_db)"
if wait_pending_zero; then ok "最终 Stream pending = 0"; else bad "最终仍有 pending 消息"; fi

assert_eq "测试账号订单总数" 6 "$(sql "select count(*) from tb_ticket_order where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')")"
assert_eq "待支付订单数" 2 "$(sql "select count(*) from tb_ticket_order where status=0 and user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')")"
assert_eq "已支付订单数" 1 "$(sql "select count(*) from tb_ticket_order where status=1 and user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')")"
assert_eq "已取消订单数" 2 "$(sql "select count(*) from tb_ticket_order where status=2 and user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')")"
assert_eq "已失效订单数" 1 "$(sql "select count(*) from tb_ticket_order where status=4 and user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')")"
assert_eq "测试账号票数" 2 "$(sql "select count(*) from tb_ticket where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')")"

# -----------------------------------------------------------------------------
section "7. 清理测试数据"
# -----------------------------------------------------------------------------
if [ "${KEEP_TEST_DATA:-0}" = "1" ]; then
    printf '  KEEP_TEST_DATA=1，保留测试账号 1390000005x 的订单/票/观演人，便于手工检查\n'
else
    sql "delete from tb_ticket where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_order_attendee where order_id in (select id from tb_ticket_order where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%'));
         delete from tb_ticket_order where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_user_purchase where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_user_attendee where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');"
    for uid in $(test_user_ids); do
        redis HDEL "ticker:ordered:$SESSION_ID" "$uid" >/dev/null
    done
    TOTAL_STOCK=$(sql "select total_stock from tb_ticket_tier where id=$TIER_ID")
    sql "update tb_ticket_tier set stock=$TOTAL_STOCK, lock_stock=0 where id=$TIER_ID"
    redis SET "ticker:stock:$TIER_ID" "$TOTAL_STOCK" >/dev/null

    assert_eq "清理后测试账号无订单" 0 "$(sql "select count(*) from tb_ticket_order where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')")"
    assert_eq "清理后测试账号无票" 0 "$(sql "select count(*) from tb_ticket where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')")"
    assert_eq "清理后票档库存归位" "$TOTAL_STOCK" "$(stock_db)"
    assert_eq "清理后 Redis 库存 == DB" "$TOTAL_STOCK" "$(stock_redis)"
    ok "测试数据已清理（KEEP_TEST_DATA=1 可保留现场）"
fi

printf '\n%s结果：通过 %d 项，失败 %d 项%s\n' "$C_CYAN" "$PASS" "$FAIL" "$C_RESET"
if [ "$FAIL" -eq 0 ]; then
    exit 0
else
    exit 1
fi
