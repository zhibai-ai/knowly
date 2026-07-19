package com.knowly.core.event;

/**
 * 流水线事件监听器（观察者）。
 *
 * <p>实现此接口注册到 PipelineEngine，接收进度事件。
 * CLI 实现打印终端进度，Web 实现转 SSE 推送。
 *
 * <p>注意：onEvent 不应阻塞——PipelineEngine 应异步派发事件，
 * 避免事件处理拖慢流水线主线程。
 */
@FunctionalInterface
public interface PipelineEventListener {

    /**
     * 收到流水线事件。
     *
     * @param event 流水线事件
     */
    void onEvent(PipelineEvent event);
}
