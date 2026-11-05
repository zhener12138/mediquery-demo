package com.evilprobe;

/**
 * 专门用来测 Redis 反序列化白名单的探针类。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>最开始我用 {@code java.lang.Runtime} 当靶子，写了个"反序列化必须抛异常"的断言。
 * 结果把白名单改回放任模式（LaissezFaire），测试照样是绿的——因为 Runtime 没有公有无参构造器，
 * Jackson 压根实例化不出来，异常是从"造不出对象"来的，跟白名单一点关系没有。
 *
 * <p>那种测试是安全测试里最危险的一种：看着在保护什么，其实一直在空转，
 * 哪天防线拆了它也发现不了。
 *
 * <h3>这个类为什么能区分</h3>
 *
 * <p>它在 {@code com.evilprobe} 包下——不在白名单（{@code com.mediquery.} /
 * {@code java.util.} / {@code java.time.}）里，但结构上是 Jackson 最爱的样子：
 * 公有无参构造器 + setter。放任模式下它一定会被成功实例化，
 * 白名单生效时它一定会被拒。两种行为完全不同，测试才有判别力。
 *
 * <p>注意：这个类只出现在 {@code src/test} 里，不会打进生产包。
 */
public class DeserializationProbe {

    private String flag;

    public DeserializationProbe() {
    }

    public String getFlag() {
        return flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }
}
