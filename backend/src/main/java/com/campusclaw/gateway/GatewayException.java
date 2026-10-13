package com.campusclaw.gateway;

/** 网关调用失败（嵌入 / Qdrant / 对话模型不可用）。 */
public class GatewayException extends RuntimeException {

    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
