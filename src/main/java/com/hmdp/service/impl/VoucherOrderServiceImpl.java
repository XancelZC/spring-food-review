package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 静态代码块初始化加载lua脚本
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //创建一个线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    //在类创建的之后就立马开启任务
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.进行下单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    IVoucherOrderService proxy;

    /**
     * 进行下单
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 从订单信息里获取用户id（从线程池中取出的是一个全新线程，不是主线程，所以不能从BaseContext中获取用户信息）
        Long userId = voucherOrder.getUserId();
        //创建锁对象（可重入），指定锁的名称
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        //尝试获取锁对象
        boolean isLock = redisLock.tryLock();
        //判断是否获取锁成功
        if (!isLock){
            //获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try{
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            //释放锁
            redisLock.unlock();
        }
    }


    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);

    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.获取用户id
        Long userId = UserHolder.getUser().getId();
        //2.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //3.判断结果是否为0
        int r=result.intValue();
        if(r!=0){
            //3.1不为0，表示没有购买资格
            return Result.fail(r==1?"库存不足": "不能重复下单");
        }
        //3.2为0，表示有购买资格，把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        //4.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //5.保存在阻塞队列中
        orderTasks.add(voucherOrder);
        //6.获取代理对象
        proxy=(IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    /**
     * 创建订单存入数据库
     *
     * @param voucherOrder
     * @return
     */
    @Transactional
    public Result createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //判断是否存在
        if(count>0){
            //用户已经购买过了
            log.error("用户已经购买过了");
            return null;
        }

        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();

        if (!success){
            //扣减失败
            log.error("库存不足");
            return null;
        }
        save(voucherOrder);

        return null;
    }


//    @Transactional
//    public  Result createVoucherOrder(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        synchronized(userId.toString().intern()){
//            // 5.1.查询订单
//            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//            // 5.2.判断是否存在
//            if (count > 0) {
//                // 用户已经购买过了
//                return Result.fail("用户已经购买过一次！");
//            }
//
//            // 6.扣减库存
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock - 1") // set stock = stock - 1
//                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
//                    .update();
//            if (!success) {
//                // 扣减失败
//                return Result.fail("库存不足！");
//            }
//
//            // 7.创建订单
//            VoucherOrder voucherOrder = new VoucherOrder();
//            // 7.1.订单id
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            // 7.2.用户id
//            voucherOrder.setUserId(userId);
//            // 7.3.代金券id
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//
//            // 7.返回订单id
//            return Result.ok(orderId);
//        }
//    }
//
//    /**
//     * 秒杀优惠券
//     * @param voucherId
//     * @return
//     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1. 查询优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2. 判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            //2.1还没有开始
//            return Result.fail("秒杀尚未开始");
//        }
//        //2.2已经开始
//        //3.判断库存是否充足
//        if (voucher.getStock() < 1){
//            //3.1库存不足
//            return Result.fail("库存不足");
//        }
//        //3.2库存充足
//        //根据用户id和优惠券id查询订单
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:" + userId);
//        RLock lock = redissonClient.getLock(RedisConstants.LOCK_KEY_PREFIX + RedisConstants.SECKILL_ORDER_KEY + userId);
//        //获取锁
//        boolean isLock = lock.tryLock();    //空参默认失败不等待，直接返回结果
//        //加锁失败
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
//
//        try {
//            //获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//    }
}
