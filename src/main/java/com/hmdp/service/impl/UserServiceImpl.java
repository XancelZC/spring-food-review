package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){ //invalid 不合格的
            //2 如果不符合 返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3 符合 生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4 保存到Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,CACHE_NULL_TTL, TimeUnit.MINUTES);
        //5 发送验证码（暂时不做）
        log.debug("发送验证码成功：{}",code);
        return Result.ok();
    }

    /**
     * 登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1 提交手机号验证码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //2 如果手机号码格式不合格
            return Result.fail("手机号码格式不合格！");
        }
        //3 从redis中取出验证码 校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        //4 如果不一致
        if (cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //5 验证码一致 查询用户是否存在
        //mybatis plus
        User user = query().eq("phone", phone).one();
        if (user == null){
            //不存在 则创建新用户
           user = createUserWithPhone(phone);
        }

        //6 保存用户到Redis
            //6.1 随机生成token，作为登录凭证
        String token = UUID.randomUUID().toString(true);
            //6.2 将user对象转换成HashMap用来存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                //由于usermap中有Long型的id，无法存储到redis，这里需要修改
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().
                        setIgnoreNullValue(true).
                        setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
            //6.3 存储到redis中
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
            //6.4设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //7 返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return null;
    }


}
