-- 1. 参数列表

-- 1.1优惠券id
local voucherId = ARGV[1]
-- 1.2用户id
local userId = ARGV[2]

-- 数据key
-- 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单key
local orderKey = 'seckill:order:' .. voucherId

--3.脚本业务

--3.1 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足
    return 1
end

--3.2 判断用户是否下单 sismember orderKey userId
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 已下单
    return 2
end

-- 3.3 扣库存
redis.call('incrby', stockKey, -1)
-- 3.4下单
redis.call('sadd', orderKey, userId)

return 0