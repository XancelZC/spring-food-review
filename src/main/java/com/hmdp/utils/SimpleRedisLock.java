package com.hmdp.utils;

import cn.hutool.core.io.resource.ClassPathResource;
import cn.hutool.core.lang.UUID;
import org.apache.ibatis.javassist.ClassPath;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
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
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
        static {
            UNLOCK_SCRIPT = new DefaultRedisScript<>();
            UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
            UNLOCK_SCRIPT.setResultType(Long.class);
        }


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
//        // 1 获取线程标识
//        long id = Thread.currentThread().getId();
//        String threadId = ID_PREFIX + id;
//        // 2 获取锁的标识
//        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        // 3 判断标识是否一致 一致才删锁
//        if (threadId.equals(lockId)){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
        //使用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
