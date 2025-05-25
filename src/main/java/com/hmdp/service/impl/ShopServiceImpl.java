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
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
//      Shop shop = cacheClient.queryWithPassThrough(
//              CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
//      Shop shop = cacheClient.queryWithMutex(
//              CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);


        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        //1 更新数据库
        updateById(shop);
        //2 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);


        return Result.ok();
    }







//    //线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//    //逻辑过期
//    public Shop queryWithLogicalExpire(Long id) {
//        //1 根据id查询Redis是否有缓存
//        String key = CACHE_SHOP_KEY + id;
//        String json = stringRedisTemplate.opsForValue().get(key);
//
//        //2 如果不存在，直接返回
//        if (StrUtil.isBlank(json)){
//            return null;
//        }
//        // 3 如果命中 需要先将json反序列化成对象
//        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        //4 判断是否过期
//        if (expireTime.isAfter(LocalDateTime.now())){
//            //4.1 没有过期 直接返回
//            return shop;
//        }
//        // 4.2 已经过期了 需要缓存重建
//        // 5 实现缓存重建
//        // 5.1.获取互斥锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        // 5.2 判断是否获取锁成功
//        if (isLock){
//            CACHE_REBUILD_EXECUTOR.submit( ()->{
//                try {
//                    //重建缓存
//                    this.saveShop2Redis(id,20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    unlock(lockKey);
//                }
//            });
//        }
//        //返回过期的店铺消息
//        return shop;
//    }
//
//    //缓存击穿
//    public Shop queryWithMutex(Long id){
//        //1 根据id查询Redis是否有缓存
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        //2 如果存在，返回
//        if (StrUtil.isNotBlank(shopJson)){
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 判断命中的是否是空值 （上面判断的是是不是空，如果是“”的话其实是属于空的，这里判断如果不是null的话那就是空了）
//        if (shopJson != null){
//            return null;
//        }
//
//        // 4 实现缓存重建
//        // 4.1.获取互斥锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lockKey);
//            // 4.2.判断是否获取成功
//            if (!isLock){
//                // 4.3.失败 则休眠并重试
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            // 4.4.成功 则根据id查询数据库
//            shop = getById(id);
//            //4 数据库中不存在 返回404
//            if (shop == null){
//                // 不存在 将空值写入Redis
//                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            //5 数据库中存在 写入Redis （设置超时时间30min兜底）
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 6 释放互斥锁
//            unlock(lockKey);
//        }
//        // 7 返回
//        return shop;
//    }
//
//    //缓存穿透
//    public Shop queryWithPassThrough(Long id) {
//        //1 根据id查询Redis是否有缓存
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        //2 如果存在，返回
//        if (StrUtil.isNotBlank(shopJson)){
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 判断命中的是否是空值 （上面判断的是是不是空，如果是“”的话其实是属于空的，这里判断如果不是null的话那就是空了）
//        if (shopJson != null){
//            return null;
//        }
//
//        //3 如果不存在，查询数据库
//        Shop shop = getById(id);
//        //4 数据库中不存在 返回404
//        if (shop == null){
//            // 不存在 将空值写入Redis
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        //5 数据库中存在 写入Redis （设置超时时间30min兜底）
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //6 返回
//        return shop;
//    }

//    private boolean tryLock(String key){
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    private void unlock(String key){
//        stringRedisTemplate.delete(key);
//    }
//
//    //缓存写入
//    public void saveShop2Redis(Long id, Long expireSeconds){
//        // 1 查询店铺数据
//        Shop shop = getById(id);
//        // 2 封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        // 3 写入Redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
//    }
}