package com.zzkingcc.domainplane.server;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * 服务端版本信息的唯一来源。
 * @author zzkingcc
 */
@Component
public class ServerInfo {

    private final String version;

    public ServerInfo(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties properties = buildProperties.getIfAvailable();
        this.version = properties != null ? properties.getVersion() : "dev";
    }

    /** 构建版本号；在探测接口里回报给调用方 */
    public String version() {
        return version;
    }
}
