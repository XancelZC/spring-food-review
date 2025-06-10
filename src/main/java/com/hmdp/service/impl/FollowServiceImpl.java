package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2 判断是关注还是取关
        if (isFollow) {
            // 2.1 关注 新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess){
                // 把关注用户的id 放入redis的set集合中
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            // 2.2 取关 删除数据
            remove(new QueryWrapper<Follow>()
                    .eq(("user_id"),userId)
                    .eq("follow_user_id", followUserId));
            // 把关注用户的id从redis中移除
            stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2 查询是否关注
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();
        // 3 判断
        return Result.ok(count>0);
    }
}
