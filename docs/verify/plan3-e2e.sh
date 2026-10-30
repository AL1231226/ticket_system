#!/usr/bin/env bash
# =============================================================================
# Plan 3 端到端验收脚本（E11-9）
#
# 覆盖：
#   1. 补位主链路：A 抢票并支付 → B 候补 → A 退票 → B 收到站内信 + source=1 订单
#   2. 支付/退款/取消：B 支付 → B 退款 → C 补位 → C 取消 → 回公开池
#   3. 超时再回流：补位订单超时 → 候补行 3 → 下一位 → 队列空回公开池
#   4. 负向：重复候补、已补位退出、重复退款、未支付退款、已核销退票、越权查询
#   5. 并发：两笔退款同时回流，队列只有 1 人时只生成 1 张候补订单
#   6. 风控：单用户 5 次/秒，第 6 次被拒，窗口过后恢复
#   7. 一致性：Redis 库存 == DB stock、total_stock = stock + lock_stock、XPENDING=0
#   8. 回归：plan2-e2e.sh 74 项仍全绿（KEEP_TEST_DATA=1 / SKIP_PLAN2=1 可跳过）
#
# 前置条件：
#   - 后端已启动：http://localhost:8081/api
#   - MySQL（root/123456，db_ticket）和 Redis 容器（ticket_redis，密码 123456）可用
#   - 场次 1 已开票；票档 2 是干净测试票档
#
# 用法：
#   bash docs/verify/plan3-e2e.sh
#   可用环境变量覆盖：BASE_URL、TIER_ID、SESSION_ID、PHONE_PREFIX、KEEP_TEST_DATA、SKIP_PLAN2
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
PHONE_PREFIX="${PHONE_PREFIX:-1390000007}"
KEEP_TEST_DATA="${KEEP_TEST_DATA:-0}"
SKIP_PLAN2="${SKIP_PLAN2:-0}"

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

sql()   { mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" -D "$DB_NAME" --default-character-set=utf8mb4 -N -s -e "$1" 2>/dev/null | tr -d '\r'; }
redis() { docker exec "$REDIS_CONTAINER" redis-cli --no-auth-warning -a "$REDIS_PASS" "$@" 2>/dev/null | tr -d '\r'; }

json_get() {
    python -c '
import sys, json
d = json.loads(sys.stdin.buffer.read().decode("utf-8"))
for part in sys.argv[1].split("."):
    d = d[int(part)] if part.isdigit() else d[part]
print(d)
' "$1" 2>/dev/null
}

json_len() {
    python -c '
import sys, json
d = json.loads(sys.stdin.buffer.read().decode("utf-8"))
print(len(d["data"]))
' 2>/dev/null | tr -d '\r'
}

# -----------------------------------------------------------------------------
# HTTP helpers
# -----------------------------------------------------------------------------
login() {
    local phone=$1 code resp
    curl -s -m 10 -X POST "$BASE_URL/user/code?phone=$phone" >/dev/null
    code=$(redis GET "login:code:$phone")
    if [ -z "$code" ]; then
        printf '  取不到 %s 的验证码（后端/Redis 是否正常？）\n' "$phone" >&2
        return 1
    fi
    resp=$(curl -s -m 10 -X POST "$BASE_URL/user/login" \
        -H 'Content-Type: application/json' \
        -d "{\"phone\":\"$phone\",\"code\":\"$code\"}")
    printf '%s' "$resp" | json_get data
}

login_or_die() {
    local phone=$1 token
    token=$(login "$phone") || { bad "登录失败：$phone"; exit 1; }
    printf '%s' "$token"
}

seckill_raw() {
    local token=$1 tier=$2 qty=$3 ip=${4:-} device=${5:-}
    local args=(-s -m 10 -X POST "$BASE_URL/order/seckill"
        -H "authorization: $token"
        -H 'Content-Type: application/json')
    [ -n "$ip" ] && args+=(-H "X-Forwarded-For: $ip")
    [ -n "$device" ] && args+=(-H "X-Device-Id: $device")
    curl "${args[@]}" -d "{\"tierId\":$tier,\"quantity\":$qty}"
}

seckill() { seckill_raw "$1" "$TIER_ID" "$2" "${3:-}"; }

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

add_attendee() {
    curl -s -m 10 -X POST "$BASE_URL/user/attendee/add" \
        -H "authorization: $1" -H 'Content-Type: application/json' \
        -d "{\"realName\":\"$2\",\"idCard\":\"$3\",\"idCardType\":4}" | json_get data
}

bind_attendees() {
    curl -s -m 10 -X POST "$BASE_URL/order/$2/attendees" \
        -H "authorization: $1" -H 'Content-Type: application/json' \
        -d "{\"attendeeIds\":[$3]}"
}

pay_order()    { curl -s -m 10 -X PUT "$BASE_URL/order/finish?orderId=$2" -H "authorization: $1"; }
refund_order() { curl -s -m 10 -X PUT "$BASE_URL/order/refund?orderId=$2" -H "authorization: $1"; }
cancel_order() { curl -s -m 10 -X PUT "$BASE_URL/order/cancle?orderId=$2" -H "authorization: $1"; }

join_waitlist() {
    curl -s -m 10 -X POST "$BASE_URL/waitlist/join" \
        -H "authorization: $1" -H 'Content-Type: application/json' \
        -d "{\"tierId\":$TIER_ID,\"quantity\":$2}"
}
quit_waitlist() { curl -s -m 10 -X DELETE "$BASE_URL/waitlist/$TIER_ID" -H "authorization: $1"; }
my_waitlist()   { curl -s -m 10 "$BASE_URL/waitlist/my" -H "authorization: $1"; }
position()      { curl -s -m 10 "$BASE_URL/waitlist/$TIER_ID/position" -H "authorization: $1"; }
unread_count()  { curl -s -m 10 "$BASE_URL/notification/unread" -H "authorization: $1"; }
notification_list() { curl -s -m 10 "$BASE_URL/notification/list" -H "authorization: $1"; }

buy_pay() {
    local token=$1 att=$2 qty=${3:-1} resp oid
    resp=$(seckill "$token" "$qty")
    oid=$(printf '%s' "$resp" | json_get data)
    if [ -z "$oid" ] || [ "$oid" = "None" ]; then
        printf '    抢票失败：%s\n' "$resp" >&2
        return 1
    fi
    wait_order_created "$token" "$oid" || return 1
    resp=$(bind_attendees "$token" "$oid" "$att")
    printf '%s' "$resp" | grep -q '"success":true' || { printf '    绑定观演人失败：%s\n' "$resp" >&2; return 1; }
    resp=$(pay_order "$token" "$oid")
    printf '%s' "$resp" | grep -q '"success":true' || { printf '    支付失败：%s\n' "$resp" >&2; return 1; }
    printf '%s' "$oid"
}

# -----------------------------------------------------------------------------
# DB / Redis helpers
# -----------------------------------------------------------------------------
stock_db()     { sql "select stock from tb_ticket_tier where id=$TIER_ID"; }
lock_db()      { sql "select lock_stock from tb_ticket_tier where id=$TIER_ID"; }
total_stock()  { sql "select total_stock from tb_ticket_tier where id=$TIER_ID"; }
stock_redis()  { redis GET "ticker:stock:$TIER_ID"; }
order_status() { sql "select status from tb_ticket_order where id=$1"; }
order_source() { sql "select source from tb_ticket_order where id=$1"; }
ticket_count() { sql "select count(*) from tb_ticket where order_id=$1"; }
ticket_status(){ sql "select status from tb_ticket where order_id=$1 limit 1"; }

wl_status()    { sql "select status from tb_waitlist where user_id=$1 and tier_id=$TIER_ID"; }
wl_order()     { sql "select order_id from tb_waitlist where user_id=$1 and tier_id=$TIER_ID"; }
wl_rowid()     { sql "select id from tb_waitlist where user_id=$1 and tier_id=$TIER_ID"; }
wl_count()     { sql "select count(*) from tb_waitlist where user_id=$1"; }
notif_count()  { sql "select count(*) from tb_notification where user_id=$1 and biz_id=$2 and type=1"; }
zcard()        { redis ZCARD "ticker:waitlist:queue:$TIER_ID"; }
zpos() {
    local r
    r=$(redis ZRANK "ticker:waitlist:queue:$TIER_ID" "$1")
    if [ -z "$r" ]; then echo ""; else echo $((r + 1)); fi
}

test_user_ids() {
    sql "select id from tb_user where phone like '${PHONE_PREFIX}%'"
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

wait_order_status() {
    local oid=$1 expected=$2
    for _ in $(seq 1 40); do
        [ "$(order_status "$oid")" = "$expected" ] && return 0
        sleep 1
    done
    return 1
}

set_test_stock() {
    local n
    n=$(total_stock)
    sql "update tb_ticket_tier set stock=$n, lock_stock=0 where id=$TIER_ID" >/dev/null
    redis SET "ticker:stock:$TIER_ID" "$n" >/dev/null
}

cleanup_test_data() {
    local oids oid
    oids=$(sql "select id from tb_ticket_order where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')")
    for oid in $oids; do
        redis ZREM ticker:delay:order:pay "$oid" >/dev/null
    done

    sql "delete from tb_notification where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_ticket where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_order_attendee where order_id in (select id from tb_ticket_order where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%'));
         delete from tb_ticket_order where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_user_purchase where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_user_attendee where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_waitlist where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');" >/dev/null

    for oid in $(test_user_ids); do
        redis ZREM "ticker:waitlist:queue:$TIER_ID" "$oid" >/dev/null
        redis HDEL "ticker:ordered:$SESSION_ID" "$oid" >/dev/null
        redis DEL "ticker:limit:user:$oid" >/dev/null
    done
    redis DEL "ticker:limit:ip:10.9.9.20" >/dev/null
}

# -----------------------------------------------------------------------------
# 0. 预检查与重置
# -----------------------------------------------------------------------------
section "0. 预检查与重置"

http_code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE_URL/show/1")
if [ "$http_code" != "200" ]; then
    printf '后端不可用（GET /show/1 → %s），请先启动后端\n' "$http_code" >&2
    exit 1
fi
ok "后端可访问"

TIER_ROW=$(sql "select concat_ws(',',max_per_order,status) from tb_ticket_tier where id=$TIER_ID")
if [ -z "$TIER_ROW" ]; then
    printf '票档 %s 不存在\n' "$TIER_ID" >&2
    exit 1
fi
IFS=',' read -r MAX_PER_ORDER TIER_STATUS <<< "$TIER_ROW"
assert_eq "票档 $TIER_ID 已上架" 1 "$TIER_STATUS"

SESSION_ROW=$(sql "select concat_ws(',',purchase_limit,status,if(on_sale_time<=now(),1,0)) from tb_show_session where id=$SESSION_ID")
if [ -z "$SESSION_ROW" ]; then
    printf '场次 %s 不存在\n' "$SESSION_ID" >&2
    exit 1
fi
IFS=',' read -r PURCHASE_LIMIT SESSION_STATUS ON_SALE_OK <<< "$SESSION_ROW"
assert_eq "场次已开票" 1 "$([ "$SESSION_STATUS" = "1" ] || [ "$SESSION_STATUS" = "2" ] && echo 1 || echo 0)"
assert_eq "场次已过开票时间" 1 "$ON_SALE_OK"

cleanup_test_data

TOKEN_A=$(login_or_die "${PHONE_PREFIX}0")
TOKEN_B=$(login_or_die "${PHONE_PREFIX}1")
TOKEN_C=$(login_or_die "${PHONE_PREFIX}2")
TOKEN_D=$(login_or_die "${PHONE_PREFIX}3")
TOKEN_E=$(login_or_die "${PHONE_PREFIX}4")
UID_A=$(sql "select id from tb_user where phone='${PHONE_PREFIX}0'")
UID_B=$(sql "select id from tb_user where phone='${PHONE_PREFIX}1'")
UID_C=$(sql "select id from tb_user where phone='${PHONE_PREFIX}2'")
UID_D=$(sql "select id from tb_user where phone='${PHONE_PREFIX}3'")
UID_E=$(sql "select id from tb_user where phone='${PHONE_PREFIX}4'")
cleanup_test_data

OTHER_ACTIVE=$(sql "select count(*) from tb_ticket_order where tier_id=$TIER_ID and status in (0,1) and user_id not in (select id from tb_user where phone like '${PHONE_PREFIX}%')")
OTHER_SOLD=$(sql "select count(*) from tb_ticket where tier_id=$TIER_ID and user_id not in (select id from tb_user where phone like '${PHONE_PREFIX}%')")
assert_eq "测试票档无其他待支付/已支付订单" 0 "$OTHER_ACTIVE"
assert_eq "测试票档无其他已出票" 0 "$OTHER_SOLD"
if [ "$OTHER_ACTIVE" != "0" ] || [ "$OTHER_SOLD" != "0" ]; then
    printf '票档 %s 不是干净的测试票档，换 TIER_ID 或手工清理后再跑\n' "$TIER_ID" >&2
    exit 1
fi

set_test_stock
S0=$(stock_db)
L0=$(lock_db)
R0=$(stock_redis)
assert_eq "起始 Redis 库存 == DB" "$S0" "$R0"
assert_eq "起始队列为空" 0 "$(zcard)"
printf '初始库存：stock=%s lock=%s redis=%s\n' "$S0" "$L0" "$R0"

ATT_A=$(add_attendee "$TOKEN_A" "E119A" "E119A0001")
ATT_B=$(add_attendee "$TOKEN_B" "E119B" "E119B0001")
ATT_D=$(add_attendee "$TOKEN_D" "E119D" "E119D0001")
if [ -z "$ATT_A" ] || [ -z "$ATT_B" ] || [ -z "$ATT_D" ]; then
    bad "测试观演人创建失败"
    exit 1
fi
ok "测试观演人已创建"

# -----------------------------------------------------------------------------
# 1. 补位主链路：退票 → 队首补位 → 支付 → 退款 → 下一位 → 取消 → 公开池
# -----------------------------------------------------------------------------
section "1. 退票 → 补位 → 支付 → 退款 → 下一位 → 取消"

A1=$(buy_pay "$TOKEN_A" "$ATT_A") || { bad "A1 下单/支付失败"; exit 1; }
assert_eq "A1 已支付" 1 "$(order_status "$A1")"
assert_eq "A1 是普通订单" 0 "$(order_source "$A1")"
assert_eq "A1 支付后 stock" $((S0 - 1)) "$(stock_db)"
assert_eq "A1 支付后 lock" $((L0 + 1)) "$(lock_db)"

RESP=$(join_waitlist "$TOKEN_B" 1); assert_contains "B 加入候补" "$RESP" '"success":true'
RESP=$(join_waitlist "$TOKEN_C" 1); assert_contains "C 加入候补" "$RESP" '"success":true'
assert_eq "B 位置 1" 1 "$(zpos "$UID_B")"
assert_eq "C 位置 2" 2 "$(zpos "$UID_C")"
RESP=$(my_waitlist "$TOKEN_B")
assert_eq "B 的候补列表只有自己 1 条" 1 "$(printf '%s' "$RESP" | json_len)"

RESP=$(refund_order "$TOKEN_A" "$A1"); assert_contains "A1 退款成功" "$RESP" '"success":true'
assert_eq "A1 已退款" 3 "$(order_status "$A1")"
assert_eq "B 候补行 1 已通知" 1 "$(wl_status "$UID_B")"
B1=$(wl_order "$UID_B")
assert_eq "B 拿到补位订单" 1 "$([ -n "$B1" ] && [ "$B1" != "0" ] && echo 1 || echo 0)"
assert_eq "B1 source=1" 1 "$(order_source "$B1")"
assert_eq "B1 待支付" 0 "$(order_status "$B1")"
assert_eq "B1 支付窗口约 10 分钟" 1 "$(sql "select if(timestampdiff(second, now(), expire_time) between 500 and 610, 1, 0) from tb_ticket_order where id=$B1")"
RESP=$(unread_count "$TOKEN_B")
assert_eq "B 未读消息 1" 1 "$(printf '%s' "$RESP" | json_get data.unread)"
RESP=$(notification_list "$TOKEN_B")
assert_eq "B 消息列表 1 条" 1 "$(printf '%s' "$RESP" | json_len)"
assert_eq "B 消息关联补位订单" "$B1" "$(printf '%s' "$RESP" | json_get data.0.bizId | tr -d '\r')"
assert_eq "C 仍在排队" 0 "$(wl_status "$UID_C")"
assert_eq "C 前移到位置 1" 1 "$(zpos "$UID_C")"
assert_eq "B 已出队" "" "$(zpos "$UID_B")"
assert_eq "补位后 stock 不回到公开池" $((S0 - 1)) "$(stock_db)"
assert_eq "补位后 lock 仍在占用" $((L0 + 1)) "$(lock_db)"
assert_eq "补位后 Redis 库存不变" $((S0 - 1)) "$(stock_redis)"
assert_eq "库存不变量" $((S0 + L0)) "$(( $(stock_db) + $(lock_db) ))"

RESP=$(bind_attendees "$TOKEN_B" "$B1" "$ATT_B"); assert_contains "B 绑定观演人" "$RESP" '"success":true'
RESP=$(pay_order "$TOKEN_B" "$B1"); assert_contains "B 支付补位订单" "$RESP" '"success":true'
assert_eq "B1 已支付" 1 "$(order_status "$B1")"
assert_eq "B 候补行 2 已购票" 2 "$(wl_status "$UID_B")"
assert_eq "B1 已出票" 1 "$(ticket_count "$B1")"

RESP=$(refund_order "$TOKEN_B" "$B1"); assert_contains "B 退款补位订单" "$RESP" '"success":true'
assert_eq "B1 已退款" 3 "$(order_status "$B1")"
assert_eq "B 候补行 4" 4 "$(wl_status "$UID_B")"
assert_eq "C 补位成功" 1 "$(wl_status "$UID_C")"
C1=$(wl_order "$UID_C")
assert_eq "C1 source=1" 1 "$(order_source "$C1")"
RESP=$(unread_count "$TOKEN_C")
assert_eq "C 未读消息 1" 1 "$(printf '%s' "$RESP" | json_get data.unread)"
assert_eq "C 消息关联补位订单" "$C1" "$(printf '%s' "$(notification_list "$TOKEN_C")" | json_get data.0.bizId | tr -d '\r')"
assert_eq "B 退款后 stock" $((S0 - 1)) "$(stock_db)"
assert_eq "B 退款后 lock" $((L0 + 1)) "$(lock_db)"
assert_eq "B 退款后 Redis" $((S0 - 1)) "$(stock_redis)"

RESP=$(cancel_order "$TOKEN_C" "$C1"); assert_contains "C 取消补位订单" "$RESP" '"success":true'
assert_eq "C1 已取消" 2 "$(order_status "$C1")"
assert_eq "C 候补行 4" 4 "$(wl_status "$UID_C")"
assert_eq "队列空后 stock 归位" "$S0" "$(stock_db)"
assert_eq "队列空后 lock 归位" "$L0" "$(lock_db)"
assert_eq "队列空后 Redis 归位" "$R0" "$(stock_redis)"

# -----------------------------------------------------------------------------
# 2. 补位订单超时 → 下一位 → 队列空回公开池
# -----------------------------------------------------------------------------
section "2. 补位订单超时 → 下一位"

A2=$(buy_pay "$TOKEN_A" "$ATT_A") || { bad "A2 下单/支付失败"; exit 1; }
assert_eq "A2 支付后 stock" $((S0 - 1)) "$(stock_db)"
RESP=$(join_waitlist "$TOKEN_B" 1); assert_contains "B 加入候补" "$RESP" '"success":true'
RESP=$(join_waitlist "$TOKEN_C" 1); assert_contains "C 加入候补" "$RESP" '"success":true'
RESP=$(refund_order "$TOKEN_A" "$A2"); assert_contains "A2 退款" "$RESP" '"success":true'
B2=$(wl_order "$UID_B")
assert_eq "B2 source=1" 1 "$(order_source "$B2")"
assert_eq "B 候补行 1" 1 "$(wl_status "$UID_B")"

sql "update tb_ticket_order set expire_time = date_sub(now(), interval 1 minute) where id=$B2" >/dev/null
redis ZADD ticker:delay:order:pay "$(( $(date +%s%3N) - 1000 ))" "$B2" >/dev/null
if wait_order_status "$B2" 4; then
    ok "B2 超时失效（4）"
else
    bad "B2 未在 40 秒内失效，status=$(order_status "$B2")"
fi
assert_eq "B 候补行 3 已过期" 3 "$(wl_status "$UID_B")"
assert_eq "C 候补行 1" 1 "$(wl_status "$UID_C")"
C2=$(wl_order "$UID_C")
assert_eq "C2 source=1" 1 "$(order_source "$C2")"
RESP=$(unread_count "$TOKEN_C")
assert_eq "C 未读消息（超时后再补位）" 2 "$(printf '%s' "$RESP" | json_get data.unread)"
assert_eq "超时再补位后 stock" $((S0 - 1)) "$(stock_db)"
assert_eq "超时再补位后 lock" $((L0 + 1)) "$(lock_db)"
assert_eq "超时再补位后 Redis" $((S0 - 1)) "$(stock_redis)"

RESP=$(cancel_order "$TOKEN_C" "$C2"); assert_contains "C 取消 C2" "$RESP" '"success":true'
assert_eq "C 候补行 4（取消）" 4 "$(wl_status "$UID_C")"
assert_eq "队列空 stock 归位" "$S0" "$(stock_db)"
assert_eq "队列空 lock 归位" "$L0" "$(lock_db)"
assert_eq "队列空 Redis 归位" "$R0" "$(stock_redis)"

# -----------------------------------------------------------------------------
# 3. 负向：重复候补、已补位退出、重复退款、未支付退款、已核销退票、越权查询
# -----------------------------------------------------------------------------
section "3. 负向场景"

RESP=$(join_waitlist "$TOKEN_B" 1); assert_contains "B 重新加入候补" "$RESP" '"success":true'
RESP=$(join_waitlist "$TOKEN_B" 1); assert_contains "重复加入被拒" "$RESP" '已在候补中'
RESP=$(quit_waitlist "$TOKEN_B"); assert_contains "B 退出候补" "$RESP" '"success":true'
assert_eq "B 退出后 4" 4 "$(wl_status "$UID_B")"
assert_eq "队列空" 0 "$(zcard)"

A3=$(buy_pay "$TOKEN_A" "$ATT_A") || { bad "A3 下单/支付失败"; exit 1; }
RESP=$(join_waitlist "$TOKEN_B" 1); assert_contains "B 加入候补（A3）" "$RESP" '"success":true'
RESP=$(refund_order "$TOKEN_A" "$A3"); assert_contains "A3 退款补位" "$RESP" '"success":true'
B3=$(wl_order "$UID_B")
assert_eq "B3 source=1" 1 "$(order_source "$B3")"
RESP=$(join_waitlist "$TOKEN_B" 1); assert_contains "已补位再次加入被拒" "$RESP" '已获得补位'
RESP=$(quit_waitlist "$TOKEN_B"); assert_contains "已补位直接退出被拒" "$RESP" '请先取消补位订单'
RESP=$(cancel_order "$TOKEN_B" "$B3"); assert_contains "B 取消补位订单" "$RESP" '"success":true'
assert_eq "B 候补行 4（取消补位订单）" 4 "$(wl_status "$UID_B")"
assert_eq "队列空 stock 归位" "$S0" "$(stock_db)"

RESP=$(position "$TOKEN_E"); assert_contains "未排队用户查位置被拒" "$RESP" '当前不在候补中'
RESP=$(my_waitlist "$TOKEN_E"); assert_eq "未排队用户候补列表为空" 0 "$(printf '%s' "$RESP" | json_len)"

A4=$(buy_pay "$TOKEN_A" "$ATT_A") || { bad "A4 下单/支付失败"; exit 1; }
RESP=$(refund_order "$TOKEN_A" "$A4"); assert_contains "A4 首次退款成功" "$RESP" '"success":true'
RESP=$(refund_order "$TOKEN_A" "$A4"); assert_contains "A4 重复退款被拒" "$RESP" '仅已支付订单可退款'
assert_eq "A4 仍为已退款" 3 "$(order_status "$A4")"

RESP=$(seckill "$TOKEN_A" 1)
A5=$(printf '%s' "$RESP" | json_get data)
wait_order_created "$TOKEN_A" "$A5" || bad "A5 未落库"
assert_eq "A5 待支付" 0 "$(order_status "$A5")"
RESP=$(refund_order "$TOKEN_A" "$A5"); assert_contains "未支付订单退款被拒" "$RESP" '仅已支付订单可退款'
RESP=$(cancel_order "$TOKEN_A" "$A5"); assert_contains "A5 取消清理" "$RESP" '"success":true'
assert_eq "A5 取消后 stock 归位" "$S0" "$(stock_db)"

A6=$(buy_pay "$TOKEN_A" "$ATT_A") || { bad "A6 下单/支付失败"; exit 1; }
sql "update tb_ticket set status=1 where order_id=$A6" >/dev/null
RESP=$(refund_order "$TOKEN_A" "$A6"); assert_contains "已核销订单退款被拒" "$RESP" '已核销'
assert_eq "A6 仍为已支付" 1 "$(order_status "$A6")"
assert_eq "A6 票仍为已核销" 1 "$(ticket_status "$A6")"
sql "update tb_ticket set status=0 where order_id=$A6" >/dev/null
RESP=$(refund_order "$TOKEN_A" "$A6"); assert_contains "恢复未核销后退款成功" "$RESP" '"success":true'
assert_eq "A6 已退款" 3 "$(order_status "$A6")"
assert_eq "A6 退款后 stock 归位" "$S0" "$(stock_db)"
assert_eq "A6 退款后 lock 归位" "$L0" "$(lock_db)"

# -----------------------------------------------------------------------------
# 4. 并发退款：队列只有 1 人，只能生成 1 张候补订单
# -----------------------------------------------------------------------------
section "4. 并发退款只生成一张候补订单"

A7=$(buy_pay "$TOKEN_A" "$ATT_A") || { bad "A7 下单/支付失败"; exit 1; }
D7=$(buy_pay "$TOKEN_D" "$ATT_D") || { bad "D7 下单/支付失败"; exit 1; }
assert_eq "两笔支付后 stock" $((S0 - 2)) "$(stock_db)"
assert_eq "两笔支付后 lock" $((L0 + 2)) "$(lock_db)"
RESP=$(join_waitlist "$TOKEN_B" 1); assert_contains "B 加入候补（并发）" "$RESP" '"success":true'

refund_order "$TOKEN_A" "$A7" >/dev/null &
P1=$!
refund_order "$TOKEN_D" "$D7" >/dev/null &
P2=$!
wait "$P1"; wait "$P2"

assert_eq "A7 已退款" 3 "$(order_status "$A7")"
assert_eq "D7 已退款" 3 "$(order_status "$D7")"
assert_eq "B 只有 1 张待支付补位订单" 1 "$(sql "select count(*) from tb_ticket_order where user_id=$UID_B and source=1 and status=0")"
assert_eq "B 候补行 1" 1 "$(wl_status "$UID_B")"
B7=$(wl_order "$UID_B")
assert_eq "B7 source=1" 1 "$(order_source "$B7")"
RESP=$(unread_count "$TOKEN_B")
assert_eq "B 未读数（四笔补位通知）" 4 "$(printf '%s' "$RESP" | json_get data.unread)"
assert_eq "并发退款后 stock" $((S0 - 1)) "$(stock_db)"
assert_eq "并发退款后 lock" $((L0 + 1)) "$(lock_db)"
assert_eq "并发退款后 Redis" $((S0 - 1)) "$(stock_redis)"
assert_eq "并发退款后库存不变量" $((S0 + L0)) "$(( $(stock_db) + $(lock_db) ))"

RESP=$(cancel_order "$TOKEN_B" "$B7"); assert_contains "B 取消并发补位订单" "$RESP" '"success":true'
assert_eq "B 候补行 4（并发取消）" 4 "$(wl_status "$UID_B")"
assert_eq "最终 stock 归位" "$S0" "$(stock_db)"
assert_eq "最终 lock 归位" "$L0" "$(lock_db)"
assert_eq "最终 Redis 归位" "$R0" "$(stock_redis)"

# -----------------------------------------------------------------------------
# 5. 风控：单用户 5 次/秒
# -----------------------------------------------------------------------------
section "5. 风控：单用户 5 次/秒"

redis DEL "ticker:limit:user:$UID_E" >/dev/null
redis DEL "ticker:limit:ip:10.9.9.20" >/dev/null
for i in $(seq 1 6); do
    seckill_raw "$TOKEN_E" 999999999 1 10.9.9.20 > "$TMP_DIR/risk_$i.json" &
done
wait
RISK_OK=$(grep -l '票档不存在' "$TMP_DIR"/risk_*.json 2>/dev/null | wc -l | tr -d ' ')
RISK_LIMITED=$(grep -l '操作过于频繁' "$TMP_DIR"/risk_*.json 2>/dev/null | wc -l | tr -d ' ')
assert_eq "前 5 次通过风控" 5 "$RISK_OK"
assert_eq "第 6 次被风控拒绝" 1 "$RISK_LIMITED"

COUNTS=$(redis EVAL "return {redis.call('ZCARD', KEYS[1]), redis.call('ZCARD', KEYS[2])}" 2 \
    "ticker:limit:user:$UID_E" "ticker:limit:ip:10.9.9.20")
UCNT=$(printf '%s\n' "$COUNTS" | sed -n '1p' | tr -d '\r')
ICNT=$(printf '%s\n' "$COUNTS" | sed -n '2p' | tr -d '\r')
assert_eq "用户窗口记录 5 条" 5 "$UCNT"
assert_eq "IP 窗口记录 5 条" 5 "$ICNT"

sleep 1.3
RESP=$(seckill_raw "$TOKEN_E" 999999999 1 10.9.9.20)
assert_contains "窗口过后恢复放行" "$RESP" '票档不存在'
assert_eq "用户窗口重置为 1" 1 "$(redis ZCARD "ticker:limit:user:$UID_E")"
redis DEL "ticker:limit:user:$UID_E" "ticker:limit:ip:10.9.9.20" >/dev/null

# -----------------------------------------------------------------------------
# 6. 一致性检查
# -----------------------------------------------------------------------------
section "6. 一致性"

assert_eq "Redis 库存 == DB stock" "$(stock_db)" "$(stock_redis)"
assert_eq "库存不变量 total = stock + lock" "$(total_stock)" "$(( $(stock_db) + $(lock_db) ))"
if wait_pending_zero; then
    ok "Stream pending = 0"
else
    bad "Stream pending != 0"
fi
ACTIVE_TEST_ORDERS=$(sql "select count(*) from tb_ticket_order where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%') and status in (0,1)")
assert_eq "测试账号无待支付/已支付订单" 0 "$ACTIVE_TEST_ORDERS"

# -----------------------------------------------------------------------------
# 7. 清理（可选保留现场）
# -----------------------------------------------------------------------------
section "7. 清理"

if [ "$KEEP_TEST_DATA" = "1" ]; then
    printf '  KEEP_TEST_DATA=1，保留测试数据，跳过清理和 plan2 回归\n'
else
    cleanup_test_data
    set_test_stock
    assert_eq "清理后测试账号无订单" 0 "$(sql "select count(*) from tb_ticket_order where user_id in ($UID_A,$UID_B,$UID_C,$UID_D,$UID_E)")"
    assert_eq "清理后测试账号无票" 0 "$(sql "select count(*) from tb_ticket where user_id in ($UID_A,$UID_B,$UID_C,$UID_D,$UID_E)")"
    assert_eq "清理后候补队列为空" 0 "$(zcard)"
    assert_eq "清理后 stock 归位" "$S0" "$(stock_db)"
    assert_eq "清理后 lock 归位" "$L0" "$(lock_db)"
    assert_eq "清理后 Redis 归位" "$R0" "$(stock_redis)"

    if [ "$SKIP_PLAN2" = "1" ]; then
        printf '  SKIP_PLAN2=1，跳过 plan2 回归\n'
    else
        section "8. Plan 2 回归"
        SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
        if bash "$SCRIPT_DIR/plan2-e2e.sh" > "$TMP_DIR/plan2.log" 2>&1; then
            if grep -q '失败 0 项' "$TMP_DIR/plan2.log"; then
                ok "plan2-e2e.sh 74 项全绿"
            else
                bad "plan2 退出码 0，但没有看到「失败 0 项」"
                tail -5 "$TMP_DIR/plan2.log"
            fi
        else
            bad "plan2-e2e.sh 回归失败"
            tail -20 "$TMP_DIR/plan2.log"
        fi
    fi
fi

printf '\n结果：通过 %s 项，失败 %s 项\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
