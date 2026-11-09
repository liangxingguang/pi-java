package com.pijava.web;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.AgentSession;

/**
 * 服务端共享的当前会话（批 31，会话单写者收口）。
 *
 * <p>此前 {@code PiWebServer.onOpen} 为<b>每个 WS 连接</b>各建一个
 * {@code AgentSession.createWeb}——每个实例各自 resume 同一份会话文件、各自持有
 * 独立的内存 leaf/seq，追加时互不协调。两个客户端并发（第二个标签页、页面重载、
 * 探针）就会写出分叉链：实测一份会话文件的用户消息 {@code parentId} 落在两轮
 * run 之前的旧叶子上，resume 校验 {@code does not chain to the lane leaf} 直接
 * 拒读，会话彻底打不开。</p>
 *
 * <p>pi 的形态是<b>服务端持有单一会话，多客户端都是它的视图</b>——本类即此收口：
 * 惰性建一个会话，所有 dispatcher 共享同一实例；{@code newSession} /
 * {@code fork} 等替换会话时经 {@link #swap} 广播给全部在线 dispatcher（换订阅＋
 * 回推 {@code SessionChanged}/{@code stateSync}），保证所有客户端视图一致。</p>
 */
final class WebSessionState {

    private final Args args;
    private volatile AgentSession session;
    private final List<WebDispatcher> dispatchers = new CopyOnWriteArrayList<>();

    WebSessionState(Args args) {
        this.args = args;
    }

    /** 测试缝：直接种入已建会话（不走 {@link #ensure} 的 createWeb）。 */
    WebSessionState(Args args, AgentSession existing) {
        this.args = args;
        this.session = existing;
    }

    /** 惰性创建共享会话（首个客户端连接时）；失败由调用方回 {@code error} 帧。 */
    synchronized AgentSession ensure() {
        if (session == null) {
            session = AgentSession.createWeb(args);
        }
        return session;
    }

    /** 当前共享会话（权威读点；dispatcher 内部视图由 {@link #swap} 同步）。 */
    AgentSession session() {
        return session;
    }

    void attach(WebDispatcher dispatcher) {
        dispatchers.add(dispatcher);
    }

    void detach(WebDispatcher dispatcher) {
        dispatchers.remove(dispatcher);
    }

    /**
     * 替换当前会话：所有在线 dispatcher 同步换视图、重订阅，并向各自客户端
     * 回推 {@code SessionChanged} ＋ {@code stateSync}。调用方先处理旧会话的
     * 收尾（{@code closeForReplacement} / fork 内部关闭）。
     */
    void swap(AgentSession next) {
        session = next;
        for (var d : dispatchers) {
            d.onSessionSwapped(next);
        }
    }

    /**
     * 原地切换（{@code switchTo}）后的广播：会话对象不变但内部上下文已换，
     * 全部 dispatcher 重订阅并回推当前状态。
     */
    void rebroadcast() {
        for (var d : dispatchers) {
            d.onSessionSwapped(session);
        }
    }
}
