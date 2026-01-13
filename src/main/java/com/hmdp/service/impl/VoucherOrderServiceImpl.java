package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀是否已经开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        // 判断秒杀是否已经结束
        if (voucher.getBeginTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        // 判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()){
//            // 获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }

        // 分布式锁：创建锁对象
        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock(1000);
        // 判断是否获取成功
        if(!isLock){
            // 获取锁失败，返回错误
            return Result.fail("不允许重复下单");
        }
        try{
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();

        // 查询订单
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 判断是否存在
        if(count > 0){
            return Result.fail("用户购买过了");
        }
        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户id

        // 代金券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);

        // 返回订单id
        return Result.ok(orderId);
    }
}
