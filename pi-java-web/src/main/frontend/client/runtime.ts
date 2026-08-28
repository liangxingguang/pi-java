// 运行时共享桥：面板模块经此间接调用 main.ts 的 send / renderApp，
// 避免面板 ↔ main 之间的循环依赖。
import type { ClientMessage } from "../shared/protocol.js";

/** 发送一条客户端消息（WS 未开时为空操作）。 */
export let send: (m: ClientMessage) => void = () => {};

/** 触发整体重渲染（main.ts 的 renderApp）。 */
export let requestRender: () => void = () => {};

/** main.ts 初始化时注入真实的 send 与 renderApp。 */
export function initRuntime(
  s: (m: ClientMessage) => void,
  r: () => void,
): void {
  send = s;
  requestRender = r;
}
