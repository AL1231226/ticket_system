local stock = redis.call('GET', KEYS[1])
if (not stock) or (tonumber(stock) < tonumber(ARGV[3])) then
    return 1
end

local bought = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
if bought + tonumber(ARGV[3]) > tonumber(ARGV[4]) then
    return 2
end

redis.call('DECRBY', KEYS[1], ARGV[3])
redis.call('HINCRBY', KEYS[2], ARGV[1], ARGV[3])
redis.call('XADD', KEYS[3], '*',
    'orderId',   ARGV[2],
    'userId',    ARGV[1],
    'tierId',    ARGV[5],
    'sessionId', ARGV[6],
    'quantity',  ARGV[3])

return 0



