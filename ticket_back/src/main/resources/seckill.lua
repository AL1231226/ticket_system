--优惠劵id
local id=ARGV[1]
--用户id
local userid=ARGV[2]
--订单id
local orderid=ARGV[3]
--库存key
local stockey="seckill:stock:"..id
--订单key
local orderkey="seckill:order:"..id
--判断库存
if tonumber(redis.call("get",stockey))<=0 then
    return 1
end
--判断用户是否已经下单
if redis.call("sismember",orderkey,userid)==1 then
    return 2
end
--减库存
redis.call("incrby",stockey,-1)
--记录订单
redis.call("sadd",orderkey,userid)
redis.call("xadd","stream.orders","*","voucherId",id,"userId",userid,"id",orderid)
return 0



