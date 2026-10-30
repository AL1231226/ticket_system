#!/usr/bin/env bash
# =============================================================================
# Plan 4 社区端到端验收脚本（E12-10）
#
# 覆盖：
#   1. 匿名读公开、匿名写 401（动态/评论/关注/上传）
#   2. 上传图片 → 发布动态 → 详情装配
#   3. 全站 Feed / 关注 Feed / 游标分页
#   4. 点赞切换、Top5、DB 计数 / Redis 镜像一致
#   5. 评论发布 / 列表 / 游标分页 / 越权删除 / 软删 / 评论数同步
#   6. 关注/取关幂等、双方计数、关注/粉丝列表、共同关注、Feed 回填/清理
#   7. 公开主页资料、计数、isFollowing
#   8. 按用户 / 按演出查询、删除权限、删除后读取过滤
#   9. Redis 与 DB 一致性（Feed / 点赞 / 关注）
#  10. 清理测试数据 + 回归 plan3-e2e.sh（内含 plan2）
#
# 前置条件：
#   - 后端已启动：http://localhost:8081/api
#   - MySQL（root/123456，db_ticket）和 Redis 容器（ticket_redis，密码 123456）可用
#   - 上传目录 D:\ticket_photos\uploads 可写
#
# 用法：
#   bash docs/verify/plan4-community-e2e.sh
#   可用环境变量覆盖：BASE_URL、PHONE_PREFIX、KEEP_TEST_DATA、SKIP_PLAN3、PLAN3_PHONE_PREFIX
#
# 退出码：0 = 全部通过；1 = 有失败
# =============================================================================
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081/api}"
BASE_HOST="${BASE_URL%/api}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-123456}"
DB_NAME="${DB_NAME:-db_ticket}"
REDIS_CONTAINER="${REDIS_CONTAINER:-ticket_redis}"
REDIS_PASS="${REDIS_PASS:-123456}"

PHONE_PREFIX="${PHONE_PREFIX:-1390000008}"
KEEP_TEST_DATA="${KEEP_TEST_DATA:-0}"
SKIP_PLAN3="${SKIP_PLAN3:-0}"
PLAN3_PHONE_PREFIX="${PLAN3_PHONE_PREFIX:-1390000007}"
UPLOAD_DIR="${UPLOAD_DIR:-D:/ticket_photos/uploads}"

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

assert_not_contains() {
    local name=$1 haystack=$2 needle=$3
    if printf '%s' "$haystack" | grep -q -- "$needle"; then
        bad "$name：响应中仍出现「$needle」→ $haystack"
    else
        ok "$name"
    fi
}

assert_in_list() {
    local name=$1 list=$2 needle=$3
    if printf '%s\n' $list | grep -qx -- "$needle"; then
        ok "$name"
    else
        bad "$name：列表 [$list] 中没有 $needle"
    fi
}

assert_not_in_list() {
    local name=$1 list=$2 needle=$3
    if printf '%s\n' $list | grep -qx -- "$needle"; then
        bad "$name：列表 [$list] 中仍出现 $needle"
    else
        ok "$name"
    fi
}

assert_success() {
    local name=$1 resp=$2
    if printf '%s' "$resp" | grep -q '"success":true'; then
        ok "$name"
    else
        bad "$name：$resp"
    fi
}

assert_fail_msg() {
    local name=$1 resp=$2 msg=$3
    if printf '%s' "$resp" | grep -q '"success":false' \
        && printf '%s' "$resp" | grep -q -- "$msg"; then
        ok "$name"
    else
        bad "$name：期望失败「$msg」→ $resp"
    fi
}

sql()   { mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" -D "$DB_NAME" --default-character-set=utf8mb4 -N -s -e "$1" 2>/dev/null | tr -d '\r'; }
redis() { docker exec "$REDIS_CONTAINER" redis-cli --no-auth-warning -a "$REDIS_PASS" "$@" 2>/dev/null | tr -d '\r'; }
code()  { curl -s -m 10 -o /dev/null -w '%{http_code}' "$@"; }

json_get() {
    python -c '
import sys, json
d = json.loads(sys.stdin.buffer.read().decode("utf-8"))
for part in sys.argv[1].split("."):
    d = d[int(part)] if part.isdigit() else d[part]
print(d)
' "$1" 2>/dev/null | tr -d '\r'
}

json_len_at() {
    python -c '
import sys, json
d = json.loads(sys.stdin.buffer.read().decode("utf-8"))
for part in sys.argv[1].split("."):
    d = d[int(part)] if part.isdigit() else d[part]
print(len(d))
' "$1" 2>/dev/null | tr -d '\r'
}

json_join() {
    python -c '
import sys, json
d = json.loads(sys.stdin.buffer.read().decode("utf-8"))
for part in sys.argv[1].split("."):
    d = d[int(part)] if part.isdigit() else d[part]
print(" ".join(str(item[sys.argv[2]]) for item in d))
' "$1" "$2" 2>/dev/null | tr -d '\r'
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

upload_image() {
    curl -s -m 10 -X POST "$BASE_URL/upload/blog" \
        -H "authorization: $1" \
        -F "file=@$2;type=image/png"
}

publish_post() {
    curl -s -m 10 -X POST "$BASE_URL/post" \
        -H "authorization: $1" -H 'Content-Type: application/json' -d "$2"
}

post_detail()      { curl -s -m 10 "$BASE_URL/post/$1"; }
post_detail_as()   { curl -s -m 10 "$BASE_URL/post/$1" -H "authorization: $2"; }
delete_post()      { curl -s -m 10 -X DELETE "$BASE_URL/post/$1" -H "authorization: $2"; }
toggle_like()      { curl -s -m 10 -X PUT "$BASE_URL/post/$1/like" -H "authorization: $2"; }
liked_users()      { curl -s -m 10 "$BASE_URL/post/$1/likes?limit=${2:-5}"; }
post_of_user()     { curl -s -m 10 "$BASE_URL/post/of/user/$1?count=${2:-10}"; }
post_of_show()     { curl -s -m 10 "$BASE_URL/post/of/show/$1?count=${2:-10}"; }

scroll_anon() {
    local count=${1:-10} last=${2:-} offset=${3:-0} url
    url="$BASE_URL/post/scroll?count=$count&offset=$offset"
    [ -n "$last" ] && url="$url&lastId=$last"
    curl -s -m 10 "$url"
}

scroll_follow() {
    local token=$1 count=${2:-10} last=${3:-} offset=${4:-0} url
    url="$BASE_URL/post/scroll?count=$count&mode=follow&offset=$offset"
    [ -n "$last" ] && url="$url&lastId=$last"
    curl -s -m 10 "$url" -H "authorization: $token"
}

add_comment() {
    curl -s -m 10 -X POST "$BASE_URL/comment" \
        -H "authorization: $1" -H 'Content-Type: application/json' \
        -d "{\"postId\":$2,\"content\":\"$3\"}"
}

list_comments() {
    local post=$1 count=${2:-10} last=${3:-} url
    url="$BASE_URL/comment/of/post/$post?count=$count"
    [ -n "$last" ] && url="$url&lastId=$last"
    curl -s -m 10 "$url"
}

delete_comment() { curl -s -m 10 -X DELETE "$BASE_URL/comment/$1" -H "authorization: $2"; }

toggle_follow() {
    curl -s -m 10 -X POST "$BASE_URL/follow/$2/$3" -H "authorization: $1"
}

follow_list() {
    local uid=$1 type=$2 count=${3:-10} last=${4:-} url
    url="$BASE_URL/follow/list/$uid?type=$type&count=$count"
    [ -n "$last" ] && url="$url&lastId=$last"
    curl -s -m 10 "$url"
}

common_follow() { curl -s -m 10 "$BASE_URL/follow/common/$1" -H "authorization: $2"; }
profile_anon()  { curl -s -m 10 "$BASE_URL/user/profile/$1"; }
profile_as()    { curl -s -m 10 "$BASE_URL/user/profile/$1" -H "authorization: $2"; }

# -----------------------------------------------------------------------------
# DB / Redis helpers
# -----------------------------------------------------------------------------
post_status()        { sql "select status from tb_post where id=$1"; }
post_liked()         { sql "select liked from tb_post where id=$1"; }
post_comments_count(){ sql "select comments from tb_post where id=$1"; }
post_like_rows()     { sql "select count(*) from tb_post_like where post_id=$1"; }
active_comment_rows(){ sql "select count(*) from tb_post_comment where post_id=$1 and status=0"; }
comment_status()     { sql "select status from tb_post_comment where id=$1"; }
follow_rows()        { sql "select count(*) from tb_follow where user_id=$1 and follow_user_id=$2"; }
fans_db()            { sql "select fans from tb_user_info where user_id=$1"; }
followee_db()        { sql "select followee from tb_user_info where user_id=$1"; }
active_posts()       { sql "select count(*) from tb_post where user_id=$1 and status=0"; }
zscore()             { redis ZSCORE "$1" "$2"; }
sismember()          { redis SISMEMBER "$1" "$2"; }

cleanup_test_data() {
    local uids post_ids uid pid
    uids=$(sql "select id from tb_user where phone like '${PHONE_PREFIX}%'")
    post_ids=$(sql "select id from tb_post where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')")
    for pid in $post_ids; do
        redis ZREM ticker:feed:global "$pid" >/dev/null 2>&1
        redis DEL "ticker:post:liked:$pid" >/dev/null 2>&1
    done
    for uid in $uids; do
        redis DEL "ticker:feed:$uid" "ticker:follow:$uid" >/dev/null 2>&1
    done
    sql "delete from tb_post_comment where post_id in (select id from tb_post where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')) or user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_post_like where post_id in (select id from tb_post where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%')) or user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_follow where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%') or follow_user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_post where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_user_info where user_id in (select id from tb_user where phone like '${PHONE_PREFIX}%');
         delete from tb_user where phone like '${PHONE_PREFIX}%';" >/dev/null
}

# -----------------------------------------------------------------------------
# 0. 预检查与清理
# -----------------------------------------------------------------------------
section "0. 预检查与清理"

assert_eq "后端可访问" 200 "$(code "$BASE_URL/show/1")"
assert_eq "MySQL 可访问" "1" "$(sql 'select 1')"
assert_eq "Redis 可访问" "PONG" "$(redis PING)"
if [ "$(code "$BASE_URL/show/1")" != "200" ] || [ "$(redis PING)" != "PONG" ]; then
    printf '后端 / MySQL / Redis 未就绪，退出\n' >&2
    exit 1
fi

cleanup_test_data
# curl 是原生 Windows 程序，-F @文件 用 C:/... 形式更稳；没有 cygpath 时退回 TMP_DIR
UPLOAD_FILE="$TMP_DIR/e2e.png"
if command -v cygpath >/dev/null 2>&1; then
    UPLOAD_FILE="$(cygpath -m "$TMP_DIR")/e2e.png"
fi
printf 'e2e-png' > "$UPLOAD_FILE"

TOKEN_A=$(login_or_die "${PHONE_PREFIX}0")
TOKEN_B=$(login_or_die "${PHONE_PREFIX}1")
TOKEN_C=$(login_or_die "${PHONE_PREFIX}2")
TOKEN_D=$(login_or_die "${PHONE_PREFIX}3")
UID_A=$(sql "select id from tb_user where phone='${PHONE_PREFIX}0'")
UID_B=$(sql "select id from tb_user where phone='${PHONE_PREFIX}1'")
UID_C=$(sql "select id from tb_user where phone='${PHONE_PREFIX}2'")
UID_D=$(sql "select id from tb_user where phone='${PHONE_PREFIX}3'")
assert_eq "4 个测试用户已创建" 4 "$(sql "select count(*) from tb_user where phone like '${PHONE_PREFIX}%'")"
assert_eq "测试用户详情行已创建" 4 "$(sql "select count(*) from tb_user_info where user_id in ($UID_A,$UID_B,$UID_C,$UID_D)")"

# -----------------------------------------------------------------------------
# 1. 匿名权限
# -----------------------------------------------------------------------------
section "1. 匿名读公开、写 401"

assert_eq "匿名可读全站 Feed" 200 "$(code "$BASE_URL/post/scroll?count=5")"
assert_eq "匿名可读动态详情" 200 "$(code "$BASE_URL/post/1")"
assert_eq "匿名可读评论列表" 200 "$(code "$BASE_URL/comment/of/post/1")"
assert_eq "匿名可读关注列表" 200 "$(code "$BASE_URL/follow/list/10001?type=followees")"
assert_eq "匿名可读公开主页" 200 "$(code "$BASE_URL/user/profile/10001")"
assert_eq "匿名上传 401" 401 "$(code -X POST "$BASE_URL/upload/blog" -F "file=@$UPLOAD_FILE;type=image/png")"
assert_eq "匿名发布动态 401" 401 "$(code -X POST "$BASE_URL/post" -H 'Content-Type: application/json' -d '{"content":"anon"}')"
assert_eq "匿名点赞 401" 401 "$(code -X PUT "$BASE_URL/post/1/like")"
assert_eq "匿名评论 401" 401 "$(code -X POST "$BASE_URL/comment" -H 'Content-Type: application/json' -d '{"postId":1,"content":"anon"}')"
assert_eq "匿名关注 401" 401 "$(code -X POST "$BASE_URL/follow/10002/true")"
RESP=$(curl -s -m 10 "$BASE_URL/follow/common/10002")
assert_fail_msg "匿名共同关注要求登录" "$RESP" "请先登录"

# -----------------------------------------------------------------------------
# 2. 上传与发布
# -----------------------------------------------------------------------------
section "2. 上传图片 → 发布动态"

RESP=$(upload_image "$TOKEN_A" "$UPLOAD_FILE")
assert_success "A 上传图片" "$RESP"
UPLOAD_NAME=$(printf '%s' "$RESP" | json_get data.name)
UPLOAD_URL=$(printf '%s' "$RESP" | json_get data.url)
assert_eq "上传返回 name 以 /blogs/ 开头" 1 "$(printf '%s' "$UPLOAD_NAME" | grep -c '^/blogs/')"
assert_eq "上传图片可公开访问" 200 "$(code "$BASE_HOST$UPLOAD_URL")"

RESP=$(publish_post "$TOKEN_A" "{\"title\":\"e2e title A1\",\"content\":\"e2e post A1\",\"showId\":1,\"sessionId\":1,\"images\":[\"$UPLOAD_URL\"]}")
assert_success "A 发布 A1" "$RESP"
POST_A1=$(printf '%s' "$RESP" | json_get data)
POST_A1_STATUS=$(post_status "$POST_A1")
assert_eq "A1 落库正常" 0 "$POST_A1_STATUS"

RESP=$(publish_post "$TOKEN_A" '{"title":"e2e title A2","content":"e2e post A2","showId":1}')
assert_success "A 发布 A2" "$RESP"
POST_A2=$(printf '%s' "$RESP" | json_get data)

RESP=$(publish_post "$TOKEN_B" '{"title":"e2e title B1","content":"e2e post B1","showId":2}')
assert_success "B 发布 B1" "$RESP"
POST_B1=$(printf '%s' "$RESP" | json_get data)

DETAIL=$(post_detail "$POST_A1")
assert_success "匿名可读 A1 详情" "$DETAIL"
assert_eq "A1 作者正确" "$UID_A" "$(printf '%s' "$DETAIL" | json_get data.userId)"
assert_eq "A1 图片 URL 正确" "$UPLOAD_URL" "$(printf '%s' "$DETAIL" | json_get data.images.0)"
assert_eq "匿名 A1 likedByMe=false" False "$(printf '%s' "$DETAIL" | json_get data.likedByMe)"

# -----------------------------------------------------------------------------
# 3. Feed 与游标分页
# -----------------------------------------------------------------------------
section "3. Feed 与游标分页"

SCROLL=$(scroll_anon 10)
assert_success "匿名 read 全站 Feed" "$SCROLL"
GLOBAL_IDS=$(printf '%s' "$SCROLL" | json_join data.posts id)
assert_in_list "全站 Feed 含 A1" "$GLOBAL_IDS" "$POST_A1"
assert_in_list "全站 Feed 含 A2" "$GLOBAL_IDS" "$POST_A2"
assert_in_list "全站 Feed 含 B1" "$GLOBAL_IDS" "$POST_B1"

PAGE1=$(scroll_anon 2)
PAGE1_IDS=$(printf '%s' "$PAGE1" | json_join data.posts id)
PAGE1_COUNT=$(printf '%s' "$PAGE1" | json_len_at data.posts)
PAGE1_NEXT=$(printf '%s' "$PAGE1" | json_get data.nextCursor.lastId)
PAGE1_OFFSET=$(printf '%s' "$PAGE1" | json_get data.nextCursor.offset)
assert_eq "Feed 第一页 2 条" 2 "$PAGE1_COUNT"
PAGE2=$(scroll_anon 2 "$PAGE1_NEXT" "$PAGE1_OFFSET")
PAGE2_IDS=$(printf '%s' "$PAGE2" | json_join data.posts id)
for pid in $PAGE1_IDS; do
    assert_not_in_list "Feed 第二页不重复 $pid" "$PAGE2_IDS" "$pid"
done

# -----------------------------------------------------------------------------
# 4. 点赞
# -----------------------------------------------------------------------------
section "4. 点赞切换 / Top5 / 计数"

RESP=$(toggle_like "$POST_A1" "$TOKEN_B")
assert_success "B 点赞 A1" "$RESP"
assert_eq "B 首次点赞 liked=true" True "$(printf '%s' "$RESP" | json_get data.liked)"
assert_eq "B 首次点赞 likedCount=1" 1 "$(printf '%s' "$RESP" | json_get data.likedCount)"

RESP=$(toggle_like "$POST_A1" "$TOKEN_B")
assert_eq "B 取消点赞 liked=false" False "$(printf '%s' "$RESP" | json_get data.liked)"
assert_eq "B 取消点赞 likedCount=0" 0 "$(printf '%s' "$RESP" | json_get data.likedCount)"

RESP=$(toggle_like "$POST_A1" "$TOKEN_B")
assert_eq "B 再次点赞 liked=true" True "$(printf '%s' "$RESP" | json_get data.liked)"

RESP=$(toggle_like "$POST_A1" "$TOKEN_C")
assert_eq "C 点赞 likedCount=2" 2 "$(printf '%s' "$RESP" | json_get data.likedCount)"
assert_eq "DB liked=2" 2 "$(post_liked "$POST_A1")"
assert_eq "DB 点赞明细=2" 2 "$(post_like_rows "$POST_A1")"
assert_eq "Redis 点赞 ZSet=2" 2 "$(redis ZCARD "ticker:post:liked:$POST_A1")"

LIKES=$(liked_users "$POST_A1" 5)
assert_eq "最近点赞用户 2 人" 2 "$(printf '%s' "$LIKES" | json_len_at data)"
LIKE_IDS=$(printf '%s' "$LIKES" | json_join data userId)
assert_in_list "Top5 含 B" "$LIKE_IDS" "$UID_B"
assert_in_list "Top5 含 C" "$LIKE_IDS" "$UID_C"
assert_eq "点赞列表最新是 C" "$UID_C" "$(printf '%s\n' $LIKE_IDS | sed -n '1p')"
assert_eq "B 视角 likedByMe=true" True "$(post_detail_as "$POST_A1" "$TOKEN_B" | json_get data.likedByMe)"

# -----------------------------------------------------------------------------
# 5. 评论
# -----------------------------------------------------------------------------
section "5. 评论 / 分页 / 越权 / 计数"

RESP=$(add_comment "$TOKEN_B" "$POST_A1" "e2e comment B")
assert_success "B 评论 A1" "$RESP"
COMMENT_B=$(printf '%s' "$RESP" | json_get data.id)
RESP=$(add_comment "$TOKEN_C" "$POST_A1" "e2e comment C")
assert_success "C 评论 A1" "$RESP"
COMMENT_C=$(printf '%s' "$RESP" | json_get data.id)
assert_eq "A1 评论数=2" 2 "$(post_comments_count "$POST_A1")"
assert_eq "A1 正常评论=2" 2 "$(active_comment_rows "$POST_A1")"

COMMENTS=$(list_comments "$POST_A1" 10)
assert_eq "评论列表 2 条" 2 "$(printf '%s' "$COMMENTS" | json_len_at data.comments)"
COMMENT_IDS=$(printf '%s' "$COMMENTS" | json_join data.comments id)
assert_eq "评论最新是 C" "$COMMENT_C" "$(printf '%s\n' $COMMENT_IDS | sed -n '1p')"

CPAGE1=$(list_comments "$POST_A1" 1)
CPAGE1_ID=$(printf '%s' "$CPAGE1" | json_join data.comments id)
CPAGE1_NEXT=$(printf '%s' "$CPAGE1" | json_get data.nextCursor.lastId)
assert_eq "评论第一页 1 条" 1 "$(printf '%s' "$CPAGE1" | json_len_at data.comments)"
assert_eq "评论第一页是 C" "$COMMENT_C" "$CPAGE1_ID"
CPAGE2=$(list_comments "$POST_A1" 1 "$CPAGE1_NEXT")
CPAGE2_ID=$(printf '%s' "$CPAGE2" | json_join data.comments id)
assert_eq "评论第二页是 B" "$COMMENT_B" "$CPAGE2_ID"

RESP=$(delete_comment "$COMMENT_C" "$TOKEN_B")
assert_fail_msg "B 删除 C 的评论被拒" "$RESP" "无权删除他人评论"
assert_eq "越权删除后评论数仍为 2" 2 "$(post_comments_count "$POST_A1")"

RESP=$(delete_comment "$COMMENT_B" "$TOKEN_B")
assert_success "B 删除自己的评论" "$RESP"
assert_eq "评论软删 status=1" 1 "$(comment_status "$COMMENT_B")"
assert_eq "删除后评论数=1" 1 "$(post_comments_count "$POST_A1")"
assert_eq "删除后正常评论=1" 1 "$(active_comment_rows "$POST_A1")"
COMMENTS2=$(list_comments "$POST_A1" 10)
COMMENT_IDS2=$(printf '%s' "$COMMENTS2" | json_join data.comments id)
assert_not_in_list "评论列表不含已删评论" "$COMMENT_IDS2" "$COMMENT_B"

# -----------------------------------------------------------------------------
# 6. 关注 / 粉丝 / 共同关注 / Feed
# -----------------------------------------------------------------------------
section "6. 关注 / 粉丝 / 共同关注 / Feed"

RESP=$(toggle_follow "$TOKEN_B" "$UID_A" true)
assert_success "B 关注 A" "$RESP"
assert_eq "B 关注后 following=true" True "$(printf '%s' "$RESP" | json_get data.following)"
assert_eq "B 关注后 A fans=1" 1 "$(printf '%s' "$RESP" | json_get data.fans)"
assert_eq "关注明细=1" 1 "$(follow_rows "$UID_B" "$UID_A")"
assert_eq "A fans 计数=1" 1 "$(fans_db "$UID_A")"
assert_eq "B followee 计数=1" 1 "$(followee_db "$UID_B")"
assert_eq "Redis 关注集合含 A" 1 "$(sismember "ticker:follow:$UID_B" "$UID_A")"

RESP=$(toggle_follow "$TOKEN_B" "$UID_A" true)
assert_eq "重复关注幂等 fans=1" 1 "$(printf '%s' "$RESP" | json_get data.fans)"
assert_eq "重复关注明细仍为 1" 1 "$(follow_rows "$UID_B" "$UID_A")"
assert_eq "重复关注 followee 仍为 1" 1 "$(followee_db "$UID_B")"

B_FEED=$(scroll_follow "$TOKEN_B" 10)
assert_success "B 关注 Feed 可读" "$B_FEED"
B_FEED_IDS=$(printf '%s' "$B_FEED" | json_join data.posts id)
assert_in_list "关注 Feed 回填 A1" "$B_FEED_IDS" "$POST_A1"
assert_in_list "关注 Feed 回填 A2" "$B_FEED_IDS" "$POST_A2"

RESP=$(toggle_follow "$TOKEN_C" "$UID_A" true)
assert_eq "C 关注后 A fans=2" 2 "$(printf '%s' "$RESP" | json_get data.fans)"

PROFILE_A=$(profile_anon "$UID_A")
assert_success "匿名读 A 主页" "$PROFILE_A"
assert_eq "A 主页 fans=2" 2 "$(printf '%s' "$PROFILE_A" | json_get data.fans)"
assert_eq "A 主页 posts=2" 2 "$(printf '%s' "$PROFILE_A" | json_get data.posts)"
assert_eq "匿名 A 主页 isFollowing=false" False "$(printf '%s' "$PROFILE_A" | json_get data.isFollowing)"
PROFILE_AB=$(profile_as "$UID_A" "$TOKEN_B")
assert_eq "B 看 A 主页 isFollowing=true" True "$(printf '%s' "$PROFILE_AB" | json_get data.isFollowing)"
PROFILE_BB=$(profile_as "$UID_B" "$TOKEN_B")
assert_eq "B 看自己 isFollowing=false" False "$(printf '%s' "$PROFILE_BB" | json_get data.isFollowing)"

FANS=$(follow_list "$UID_A" fans 10)
FANS_IDS=$(printf '%s' "$FANS" | json_join data.users userId)
assert_in_list "A 粉丝列表含 B" "$FANS_IDS" "$UID_B"
assert_in_list "A 粉丝列表含 C" "$FANS_IDS" "$UID_C"
assert_eq "A 粉丝列表最新是 C" "$UID_C" "$(printf '%s\n' $FANS_IDS | sed -n '1p')"
FOLLOWEES=$(follow_list "$UID_B" followees 10)
FOLLOWEE_IDS=$(printf '%s' "$FOLLOWEES" | json_join data.users userId)
assert_in_list "B 关注列表含 A" "$FOLLOWEE_IDS" "$UID_A"

FAN_P1=$(follow_list "$UID_A" fans 1)
FAN_P1_ID=$(printf '%s' "$FAN_P1" | json_join data.users userId)
FAN_P1_NEXT=$(printf '%s' "$FAN_P1" | json_get data.nextCursor.lastId)
FAN_P2=$(follow_list "$UID_A" fans 1 "$FAN_P1_NEXT")
FAN_P2_ID=$(printf '%s' "$FAN_P2" | json_join data.users userId)
assert_eq "粉丝第一页是 C" "$UID_C" "$FAN_P1_ID"
assert_eq "粉丝第二页是 B" "$UID_B" "$FAN_P2_ID"

COMMON=$(common_follow "$UID_C" "$TOKEN_B")
assert_success "B 查与 C 的共同关注" "$COMMON"
COMMON_IDS=$(printf '%s' "$COMMON" | json_join data userId)
assert_eq "共同关注只有 A" 1 "$(printf '%s' "$COMMON" | json_len_at data)"
assert_in_list "共同关注含 A" "$COMMON_IDS" "$UID_A"

RESP=$(toggle_follow "$TOKEN_B" "$UID_B" true)
assert_fail_msg "禁止关注自己" "$RESP" "不能关注自己"
RESP=$(toggle_follow "$TOKEN_B" "999999999" true)
assert_fail_msg "关注不存在用户被拒" "$RESP" "用户不存在"

RESP=$(toggle_follow "$TOKEN_B" "$UID_A" false)
assert_success "B 取关 A" "$RESP"
assert_eq "B 取关后 following=false" False "$(printf '%s' "$RESP" | json_get data.following)"
assert_eq "B 取关后 A fans=1" 1 "$(printf '%s' "$RESP" | json_get data.fans)"
assert_eq "取关明细=0" 0 "$(follow_rows "$UID_B" "$UID_A")"
assert_eq "取关后 A fans 计数=1" 1 "$(fans_db "$UID_A")"
assert_eq "取关后 B followee=0" 0 "$(followee_db "$UID_B")"
assert_eq "Redis 关注集合不含 A" 0 "$(sismember "ticker:follow:$UID_B" "$UID_A")"
assert_eq "取关后 Feed 清理 A1" "" "$(zscore "ticker:feed:$UID_B" "$POST_A1")"
assert_eq "取关后 Feed 清理 A2" "" "$(zscore "ticker:feed:$UID_B" "$POST_A2")"
COMMON_AFTER=$(common_follow "$UID_C" "$TOKEN_B")
assert_eq "取关后共同关注为空" 0 "$(printf '%s' "$COMMON_AFTER" | json_len_at data)"
PROFILE_AFTER=$(profile_as "$UID_A" "$TOKEN_B")
assert_eq "取关后 B 看 A isFollowing=false" False "$(printf '%s' "$PROFILE_AFTER" | json_get data.isFollowing)"

# -----------------------------------------------------------------------------
# 7. 按用户 / 按演出
# -----------------------------------------------------------------------------
section "7. 按用户 / 按演出"

OF_USER=$(post_of_user "$UID_A" 10)
assert_success "按用户查 A 动态" "$OF_USER"
OF_USER_IDS=$(printf '%s' "$OF_USER" | json_join data.posts id)
assert_in_list "A 动态含 A1" "$OF_USER_IDS" "$POST_A1"
assert_in_list "A 动态含 A2" "$OF_USER_IDS" "$POST_A2"
assert_not_in_list "A 动态不含 B1" "$OF_USER_IDS" "$POST_B1"

OF_SHOW=$(post_of_show 1 10)
assert_success "按演出 1 查动态" "$OF_SHOW"
OF_SHOW_IDS=$(printf '%s' "$OF_SHOW" | json_join data.posts id)
assert_in_list "演出 1 含 A1" "$OF_SHOW_IDS" "$POST_A1"
assert_in_list "演出 1 含 A2" "$OF_SHOW_IDS" "$POST_A2"
assert_not_in_list "演出 1 不含 B1" "$OF_SHOW_IDS" "$POST_B1"

# -----------------------------------------------------------------------------
# 8. 删除权限 / 删除后读取过滤 / 主页计数
# -----------------------------------------------------------------------------
section "8. 删除权限与读取过滤"

RESP=$(delete_post "$POST_A1" "$TOKEN_C")
assert_fail_msg "C 删除 A1 被拒" "$RESP" "只能删除自己的动态"
assert_eq "越权删除后 A1 仍正常" 0 "$(post_status "$POST_A1")"

RESP=$(delete_post "$POST_A2" "$TOKEN_A")
assert_success "A 删除 A2" "$RESP"
assert_eq "A2 软删 status=1" 1 "$(post_status "$POST_A2")"
DETAIL_A2=$(post_detail "$POST_A2")
assert_fail_msg "删除后详情不可读" "$DETAIL_A2" "动态不存在"
SCROLL_AFTER_DELETE=$(scroll_anon 10)
GLOBAL_AFTER_DELETE=$(printf '%s' "$SCROLL_AFTER_DELETE" | json_join data.posts id)
assert_not_in_list "删除后全站 Feed 不含 A2" "$GLOBAL_AFTER_DELETE" "$POST_A2"
assert_eq "删除后全局 Feed 镜像清理 A2" "" "$(zscore ticker:feed:global "$POST_A2")"
assert_eq "删除后 A 有效动态=1" 1 "$(active_posts "$UID_A")"
PROFILE_A_AFTER=$(profile_anon "$UID_A")
assert_eq "删除后 A 主页 posts=1" 1 "$(printf '%s' "$PROFILE_A_AFTER" | json_get data.posts)"

# -----------------------------------------------------------------------------
# 9. 一致性
# -----------------------------------------------------------------------------
section "9. Redis / DB 一致性"

assert_eq "A1 likes 与明细一致" "$(post_liked "$POST_A1")" "$(post_like_rows "$POST_A1")"
assert_eq "A1 likes 与 Redis 一致" "$(post_like_rows "$POST_A1")" "$(redis ZCARD "ticker:post:liked:$POST_A1")"
assert_eq "A1 comments 与正常评论一致" "$(post_comments_count "$POST_A1")" "$(active_comment_rows "$POST_A1")"
assert_eq "A fans 与明细一致" "$(fans_db "$UID_A")" "$(sql "select count(*) from tb_follow where follow_user_id=$UID_A")"
assert_eq "B followee 与明细一致" "$(followee_db "$UID_B")" "$(sql "select count(*) from tb_follow where user_id=$UID_B")"
assert_eq "C 关注集合含 A" 1 "$(sismember "ticker:follow:$UID_C" "$UID_A")"
assert_eq "A1 仍在全站 Feed" 1 "$(if [ -n "$(zscore ticker:feed:global "$POST_A1")" ]; then echo 1; else echo 0; fi)"

# -----------------------------------------------------------------------------
# 10. 清理与回归
# -----------------------------------------------------------------------------
section "10. 清理与回归"

if [ "$KEEP_TEST_DATA" = "1" ]; then
    printf '  KEEP_TEST_DATA=1，保留测试数据（A1=%s B1=%s），跳过清理和 plan3 回归\n' "$POST_A1" "$POST_B1"
else
    cleanup_test_data
    if [ -n "${UPLOAD_NAME:-}" ]; then
        rm -f "$UPLOAD_DIR$UPLOAD_NAME"
        if [ -f "$UPLOAD_DIR$UPLOAD_NAME" ]; then
            bad "上传文件未清理：$UPLOAD_DIR$UPLOAD_NAME"
        else
            ok "上传文件已清理"
        fi
    fi
    assert_eq "清理后测试用户=0" 0 "$(sql "select count(*) from tb_user where phone like '${PHONE_PREFIX}%'")"
    assert_eq "清理后测试动态=0" 0 "$(sql "select count(*) from tb_post where user_id in ($UID_A,$UID_B,$UID_C,$UID_D)")"
    assert_eq "清理后测试关注=0" 0 "$(sql "select count(*) from tb_follow where user_id in ($UID_A,$UID_B,$UID_C,$UID_D) or follow_user_id in ($UID_A,$UID_B,$UID_C,$UID_D)")"
    assert_eq "清理后测试评论=0" 0 "$(sql "select count(*) from tb_post_comment where user_id in ($UID_A,$UID_B,$UID_C,$UID_D)")"
    assert_eq "清理后测试点赞=0" 0 "$(sql "select count(*) from tb_post_like where user_id in ($UID_A,$UID_B,$UID_C,$UID_D)")"
    assert_eq "清理后全站 Feed 无 A1" "" "$(zscore ticker:feed:global "$POST_A1")"
    assert_eq "清理后全站 Feed 无 B1" "" "$(zscore ticker:feed:global "$POST_B1")"

    if [ "$SKIP_PLAN3" = "1" ]; then
        printf '  SKIP_PLAN3=1，跳过 plan3 回归\n'
    else
        section "11. Plan 3 回归（含 Plan 2）"
        SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
        if PHONE_PREFIX="$PLAN3_PHONE_PREFIX" KEEP_TEST_DATA=0 SKIP_PLAN2=0 \
            bash "$SCRIPT_DIR/plan3-e2e.sh" > "$TMP_DIR/plan3.log" 2>&1; then
            if grep -q '失败 0 项' "$TMP_DIR/plan3.log"; then
                ok "plan3-e2e.sh 全绿（内部已回归 plan2）"
            else
                bad "plan3 退出码 0，但没有看到「失败 0 项」"
                tail -10 "$TMP_DIR/plan3.log"
            fi
        else
            bad "plan3-e2e.sh 回归失败"
            tail -30 "$TMP_DIR/plan3.log"
        fi
    fi
fi

printf '\n结果：通过 %s 项，失败 %s 项\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
