package com.mediquery.module.user.vo;

import com.mediquery.module.user.entity.User;

import java.time.LocalDateTime;

/**
 * 用户信息输出对象——对外只暴露这些字段，绝不返回实体。
 *
 * <h3>为什么要单独造一个 VO</h3>
 *
 * <p>改之前 {@code GET /user/me} 的返回类型直接就是 {@code User} 实体。虽然当时在
 * {@code UserController} 里手工 {@code user.setPassword(null)} 抹过密码，但这种"记得抹"的写法
 * 有三个问题：
 *
 * <ol>
 *   <li><b>漏一次就泄一次。</b>同类接口 {@code /admin/users} 也手工抹了一遍，将来新增第四个
 *       返回用户的接口时，忘抹就是明文哈希密码进响应体。</li>
 *   <li><b>抹得掉值，抹不掉字段。</b>序列化出来的 JSON 里仍然有 {@code "password": null} 这个键，
 *       前端、日志、抓包都能看出这里本来有个密码字段。字段本身就不该出现。</li>
 *   <li><b>实体还会长。</b>以后 {@code User} 加了手机号密文、身份证、实名状态，
 *       所有返回实体的接口自动全部泄露，没人会去逐个检查。</li>
 * </ol>
 *
 * <p>VO 是白名单式设计：{@link #from(User)} 里明确列出每个允许出去的字段。
 * 实体以后加多少字段都不会自动漏出去，要泄露必须有人手动往这里加一行。
 *
 * <p><b>注意</b>：这里刻意没有 {@code password}、{@code deleted} 字段。{@code deleted} 是
 * MyBatis-Plus 的逻辑删除标记，属于实现细节；{@code roleStatus} 和 {@code status} 前端要用
 * （菜单显示、禁用态提示），保留。
 */
public class UserVO {

    private Integer id;
    private String username;
    private String realName;
    private Integer age;
    private String sex;
    private String email;
    private String phone;
    private String avatarUrl;

    /** 0-普通用户, 1-管理员。前端据此决定是否显示管理入口。 */
    private Integer roleStatus;

    /** 0-正常, 1-已禁用。 */
    private Integer status;

    private LocalDateTime createTime;

    public UserVO() {
    }

    /**
     * 从实体转换。这是唯一的出口，新增字段必须在这里显式加。
     *
     * @param user 实体，允许为 {@code null}（返回 {@code null}，调用方自己决定怎么处理）
     */
    public static UserVO from(User user) {
        if (user == null) {
            return null;
        }
        var vo = new UserVO();
        vo.id = user.getId();
        vo.username = user.getUsername();
        vo.realName = user.getRealName();
        vo.age = user.getAge();
        vo.sex = user.getSex();
        vo.email = user.getEmail();
        vo.phone = user.getPhone();
        vo.avatarUrl = user.getAvatarUrl();
        vo.roleStatus = user.getRoleStatus();
        vo.status = user.getStatus();
        vo.createTime = user.getCreateTime();
        return vo;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Integer getRoleStatus() {
        return roleStatus;
    }

    public void setRoleStatus(Integer roleStatus) {
        this.roleStatus = roleStatus;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
