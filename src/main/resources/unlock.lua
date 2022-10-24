-- 比较县城标示与锁中标示是否一致
if (redis.call('get', KEYS[1]) == ARGS[1]) then
    -- 释放锁 del key
    return redis.call('del', KEYS[1])
end
return 0