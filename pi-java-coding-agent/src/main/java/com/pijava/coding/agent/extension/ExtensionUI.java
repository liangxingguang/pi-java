package com.pijava.coding.agent.extension;

import com.pijava.coding.agent.rpc.RpcExtensionUIRequest;
import com.pijava.coding.agent.rpc.RpcExtensionUIResponse;

/**
 * 扩展 UI 服务 —— 扩展请求用户输入的统一入口（RPC 模式经 extension_ui_request/
 * response 双向通道；其他模式回落 noop）。
 */
public interface ExtensionUI {

    /** 发起 UI 请求并阻塞等待响应。 */
    RpcExtensionUIResponse request(RpcExtensionUIRequest request);

    /** 无 UI 通道时的 no-op 实现（立即取消）。 */
    static ExtensionUI noop() {
        return request -> RpcExtensionUIResponse.confirm(request.id(), false);
    }
}
