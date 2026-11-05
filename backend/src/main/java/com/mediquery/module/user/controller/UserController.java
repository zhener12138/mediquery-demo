package com.mediquery.module.user.controller;

import com.mediquery.common.exception.BusinessException;
import com.mediquery.common.dto.Result;
import com.mediquery.integration.oss.OssClient;
import com.mediquery.module.user.entity.User;
import com.mediquery.module.user.mapper.UserMapper;
import com.mediquery.module.user.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 用户控制器：个人信息/修改密码/文件上传 + 管理员用户管理(列表/启用禁用)
 */
@Tag(name = "用户管理")
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

    @Operation(summary = "用户列表(管理员)")
    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<java.util.List<UserVO>> listUsers() {
        var users = userMapper.selectList(null);
        return Result.success(users.stream().map(UserVO::from).toList());
    }

    @Operation(summary = "启用/禁用用户")
    @PutMapping("/admin/users/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<?> toggleUserStatus(@PathVariable Integer id, @RequestBody Map<String, Integer> body) {
        var user = userMapper.selectById(id);
        if (user != null) { user.setStatus(body.get("status")); userMapper.updateById(user); }
        return Result.success();
    }
}
