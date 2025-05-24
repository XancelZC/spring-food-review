package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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

    @Override
    public Result queryById(Long id) {
//        Shop shop = queryWithPassThrough(id);

        Shop shop = queryWithMutex(id);
        return Result.ok(shop);
    }


    //缓存击穿
    public Shop queryWithMutex(Long id){
        //1 根据id查询Redis是否有缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2 如果存在，返回
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值 （上面判断的是是不是空，如果是“”的话其实是属于空的，这里判断如果不是null的话那就是空了）
        if (shopJson != null){
            return null;
        }

        // 4 实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock){
                // 4.3.失败 则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4.成功 则根据id查询数据库
            shop = getById(id);
            //4 数据库中不存在 返回404
            if (shop == null){
                // 不存在 将空值写入Redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //5 数据库中存在 写入Redis （设置超时时间30min兜底）
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 6 释放互斥锁
            unlock(lockKey);
        }
        // 7 返回
        return shop;
    }

    //缓存穿透
    public Shop queryWithPassThrough(Long id) {
        //1 根据id查询Redis是否有缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2 如果存在，返回
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值 （上面判断的是是不是空，如果是“”的话其实是属于空的，这里判断如果不是null的话那就是空了）
        if (shopJson != null){
            return null;
        }

        //3 如果不存在，查询数据库
        Shop shop = getById(id);
        //4 数据库中不存在 返回404
        if (shop == null){
            // 不存在 将空值写入Redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //5 数据库中存在 写入Redis （设置超时时间30min兜底）
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //6 返回
        return shop;
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


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
