package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    @Resource
    private StringRedisTemplate  stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    /**
     * 利用setnx方法进行加锁，同时增加过期时间，防止死锁，此方法可以保证加锁和增加过期时间具有原子性
     * @param timeoutSec
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        // 1 获取线程id
        long threadId = Thread.currentThread().getId();
        // 2 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //解决误删问题
        // 1 获取线程标识
        long id = Thread.currentThread().getId();
        String threadId = ID_PREFIX + id;
        // 2 获取锁的标识
        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 3 判断标识是否一致 一致才删锁
        if (threadId.equals(lockId)){
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
