package com.mediquery.module.user.controller;

import com.mediquery.common.exception.BusinessException;
import com.mediquery.common.dto.Result;
import com.mediquery.integration.oss.OssClient;
import com.mediquery.module.user.entity.User;
import com.mediquery.module.user.mapper.UserMapper;
import com.mediquery.module.user.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.mediquery.module.operationlog.aspect.AuditLog;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 用户控制器：个人信息 / 修改密码 / 文件上传。
 *
 * <p>重构后不再提供管理员用户管理：本项目只剩在线问诊一条主线，
 * 后台能做的审计动作已经在 {@code /api/v1/admin/operation-logs} 里，
 * 用户列表与启用禁用属于另一个产品的问题，删掉比留着一半更好。
 */
@Tag(name = "用户")
@RestController
@RequestMapping("/api/v1")
public class UserController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final OssClient ossClient;

    public UserController(UserMapper userMapper, PasswordEncoder passwordEncoder, OssClient ossClient) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.ossClient = ossClient;
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/user/me")
    public Result<UserVO> me(@RequestAttribute(required = false) Integer userId) {
        if (userId == null) throw BusinessException.unauthorized("请先登录");
        return Result.success(UserVO.from(userMapper.selectById(userId)));
    }

    @Operation(summary = "更新个人资料")
    @PutMapping("/user/profile")
    public Result<UserVO> updateProfile(@RequestAttribute(required = false) Integer userId, @RequestBody User updates) {
        if (userId == null) throw BusinessException.unauthorized("请先登录");
        var user = userMapper.selectById(userId);
        if (user == null) throw BusinessException.notFound("用户不存在");
        if (updates.getRealName() != null) user.setRealName(updates.getRealName());
        if (updates.getAge() != null) user.setAge(updates.getAge());
        if (updates.getSex() != null) user.setSex(updates.getSex());
        if (updates.getPhone() != null) user.setPhone(updates.getPhone());
        if (updates.getEmail() != null) user.setEmail(updates.getEmail());
        // 头像走 upload 拿到 OSS 地址后再回填。之前漏了这个字段，
        // 结果前端上传成功了、地址也拿到了，但保存时被静默丢弃，刷新就变回旧头像。
        if (updates.getAvatarUrl() != null) user.setAvatarUrl(updates.getAvatarUrl());
        userMapper.updateById(user);
        return Result.success(UserVO.from(user));
    }

    @Operation(summary = "修改密码")
    @PutMapping("/user/password")
    @AuditLog(value = "修改密码", module = "账户安全")
    public Result<?> updatePassword(@RequestAttribute(required = false) Integer userId, @RequestBody Map<String, String> body) {
        if (userId == null) throw BusinessException.unauthorized("请先登录");
        var user = userMapper.selectById(userId);
        if (user == null) throw BusinessException.notFound("用户不存在");
        if (!passwordEncoder.matches(body.get("oldPassword"), user.getPassword())) throw BusinessException.badRequest("原密码错误");
        user.setPassword(passwordEncoder.encode(body.get("newPassword")));
        userMapper.updateById(user);
        return Result.success();
    }

    @Operation(summary = "上传文件")
    @PostMapping("/files/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file) { return Result.success(ossClient.upload(file)); }
}
