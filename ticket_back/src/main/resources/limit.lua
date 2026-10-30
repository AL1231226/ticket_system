-- 滑动窗口限流
-- KEYS[1] = ticker:limit:user:{userId} 或 ticker:limit:ip:{ip}
-- ARGV[1] = now(ms)
-- ARGV[2] = 窗口大小(ms)
-- ARGV[3] = 阈值
-- ARGV[4] = 唯一后缀（同一毫秒的多个请求不能互相覆盖）
--
-- 返回 1=放行，0=被限流

local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

-- 1. 清掉窗口外的记录
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)

-- 2. 统计窗口内请求数
local count = redis.call('ZCARD', KEYS[1])
if count >= limit then
    return 0
end

-- 3. 记录本次请求并续期
redis.call('ZADD', KEYS[1], now, now .. '-' .. ARGV[4])
redis.call('PEXPIRE', KEYS[1], window)
return 1
