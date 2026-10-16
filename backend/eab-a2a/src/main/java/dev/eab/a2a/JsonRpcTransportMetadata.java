package dev.eab.a2a;

import org.a2aproject.sdk.server.TransportMetadata;
import org.a2aproject.sdk.spec.TransportProtocol;

/** 向 SDK 声明本进程提供 JSON-RPC 传输（SDK 校验 Agent Card 里声明的接口是否可用），由 ServiceLoader 发现 */
public final class JsonRpcTransportMetadata implements TransportMetadata {

    @Override
    public String getTransportProtocol() {
        return TransportProtocol.JSONRPC.asString();
    }
}
