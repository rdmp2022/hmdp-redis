package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    @Override
    public Result queryById(Long id) {
        //解决缓存穿透方式
//        Shop shop = queryWithPassThrouth(id);
//        return Result.ok(shop);

        //解决缓存击穿问题
//        Shop shop = queryWithMutex(id);
//        if (shop == null) {
//            Result.fail("店铺不存在");
//        }
        Shop shop = queryWithLogicalExpire(id);
        return Result.ok(shop);
    }

    private Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //查询redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //如果存在，返回
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        //将json字符串反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        //已过期，尝试获取互斥锁
        boolean isLock = tryLock(LOCK_SHOP_KEY+id);
        if (!isLock){
            return shop;
        }else{
            //成功，开启独立线程，缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(LOCK_SHOP_KEY+id);
                }
            });
        }

        //存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private Shop queryWithMutex(Long id){
        Shop shop = null;
        try {
            String key = CACHE_SHOP_KEY + id;
            //查询redis
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            //如果存在，返回
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 判断命中的是否是空值
            if (shopJson != null){
                return null;
            }
            //实现缓存重建
            //设置互斥锁
            boolean isLock = tryLock(LOCK_SHOP_KEY+id);
            if (!isLock){
                //失败，休眠且重试
                Thread.sleep(200);  //模拟缓存重建
                return queryWithMutex(id);
            }
            //如果不存在根据id查数据库
            shop = getById(id);
            if (shop == null){
                //空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unlock(LOCK_SHOP_KEY+id);
        }
        return shop;
    }

    private Shop queryWithPassThrouth(Long id){
        String key = CACHE_SHOP_KEY + id;
        //查询redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //如果存在，返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //
        if (shopJson != null){
            return null;
        }
        //如果不存在根据id查数据库
        Shop shop = getById(id);
        if (shop == null){
            //空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }


    //上锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }


    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop.getId() == null){
            return Result.fail("店铺不存在");
        }
        //1. 更新数据库
        updateById(shop);
        //2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
