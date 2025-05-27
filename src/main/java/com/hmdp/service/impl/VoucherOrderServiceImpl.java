package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2 判断是否开始
        LocalDateTime beginTime = voucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还未开始");
        }
        // 3 判断是否结束
        LocalDateTime endTime = voucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        // 4 判断库存是否充足
        Integer stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // add 增加一人一单逻辑
        // a.1获取用户id
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // a.2判断是否存在
            if (count > 0) {
                return Result.fail("用户已经购买过了");
            }

            // 5 扣减库存
            boolean success = seckillVoucherService
                    .update()
                    .setSql("stock = stock-1")
                    .eq("voucher_id", voucherId).gt("stock", 0)  //乐观锁解决超卖问题 用于更新业务（此处为扣减库存）
                    .update();
            if (!success) {
                return Result.fail("库存不足");
            }
            // 6 创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 用户id
            voucherOrder.setUserId(userId);
            // 代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            return Result.ok(orderId);
        }
    }
}
