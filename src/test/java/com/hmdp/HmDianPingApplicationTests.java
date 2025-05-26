package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;


    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300); // 300 个任务

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order"); // 每个线程生成 100 个 ID
                System.out.println("id = " + id);
            }
            latch.countDown(); // 每个任务完成后计数减 1
        };

        long begin = System.currentTimeMillis(); // 记录开始时间

        // 提交 300 个任务到线程池
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        latch.await(); // 等待所有任务完成
        long end = System.currentTimeMillis(); // 记录结束时间

        System.out.println("time = " + (end - begin)); // 计算总耗时
    }
}
