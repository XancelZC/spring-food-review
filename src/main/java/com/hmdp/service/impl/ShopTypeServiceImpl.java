package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.Collator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

//    @Override
//    public Result queryList() {
//        //1 首先查询Redis中有没有缓存
//        String key = "cache:shopType";
//        List<String> types = stringRedisTemplate.opsForList().range(key, 0, -1);
//        //2 如果有直接返回
//        if (types != null && !types.isEmpty()){
//            return Result.ok(types);
//        }
//        //3 如果没有 前去数据库查询
//        List<ShopType> typeList = query().orderByAsc("sort").list();
//        //4 数据库没查到 返回404
//        if (typeList == null || typeList.isEmpty()){
//            return Result.fail("404 无商铺类型");
//        }
//        //5 数据库查到了 先存到Redis 再返回
//        List<String> typeNames = typeList.stream()
//                .map(type -> JSONUtil.toJsonStr(type))
//                .collect(Collectors.toList());
//        stringRedisTemplate.opsForList().rightPushAll(key,typeNames);
//
//        return Result.ok(typeNames);
//    }

    @Override
    public Result queryList() {
        String key = CACHE_SHOPTYPE;

        // 1. 尝试从缓存获取
        List<String> cachedList = stringRedisTemplate.opsForList().range(key, 0, -1);

        if (cachedList != null && !cachedList.isEmpty()) {
            // 2. 缓存命中，转换数据格式
            List<ShopType> result = cachedList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(result);
        }

        // 3. 缓存未命中，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList.isEmpty()) {
            return Result.fail("404 无商铺类型");
        }

        // 4. 写入缓存（保持原有JSON格式）
        List<String> jsonList = typeList.stream()
                .map(shopType -> JSONUtil.toJsonStr(shopType))
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key, jsonList);
        stringRedisTemplate.expire(key, 1, TimeUnit.HOURS);

        return Result.ok(typeList);
    }
}
