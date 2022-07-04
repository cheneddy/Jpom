package io.jpom.system.init;

import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONArray;
import io.jpom.model.data.NodeModel;
import io.jpom.service.system.SystemParametersServer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author bwcx_jzy
 * @since 2022/7/4
 */
@Slf4j
@Configuration
public class ProxySelectorConfig extends ProxySelector implements InitializingBean {

    private final SystemParametersServer systemParametersServer;
    private volatile List<ProxyConfigItem> proxyConfigItems;
    private static ProxySelector defaultProxySelector;

    public ProxySelectorConfig(SystemParametersServer systemParametersServer) {
        this.systemParametersServer = systemParametersServer;
    }

    @Override
    public List<Proxy> select(URI uri) {
        String url = uri.toString();
        return proxyConfigItems.stream()
            .filter(proxyConfigItem -> {
                if (StrUtil.equals(proxyConfigItem.getPattern(), "*")) {
                    return true;
                }
                if (ReUtil.isMatch(proxyConfigItem.getPattern(), url)) {
                    // 满足正则条件
                    return true;
                }
                return StrUtil.containsIgnoreCase(url, proxyConfigItem.getPattern());
            })
            .map(proxyConfigItem -> NodeModel.crateProxy(proxyConfigItem.getProxyType(), proxyConfigItem.getProxyAddress()))
            .filter(Objects::nonNull)
            .findFirst()
            .map(Collections::singletonList)
            .orElseGet(() -> {
                // revert to the default behaviour
                return defaultProxySelector == null ? Collections.singletonList(Proxy.NO_PROXY) : defaultProxySelector.select(uri);
            });

    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        if (uri == null || sa == null || ioe == null) {
            throw new IllegalArgumentException(
                "Arguments can't be null.");
        }
    }

    /**
     * 刷新
     */
    public void refresh() {
        JSONArray array = systemParametersServer.getConfigDefNewInstance("global_proxy", JSONArray.class);
        proxyConfigItems = array.toJavaList(ProxyConfigItem.class).stream().filter(proxyConfigItem -> StrUtil.isAllNotEmpty(proxyConfigItem.pattern, proxyConfigItem.proxyAddress, proxyConfigItem.proxyType)).collect(Collectors.toList());
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        defaultProxySelector = ProxySelector.getDefault();
        //
        ProxySelector.setDefault(this);
        this.refresh();
    }


    /**
     * @author bwcx_jzy
     * @since 2022/7/4
     */
    @Data
    public static class ProxyConfigItem {

        private String pattern;

        private String proxyType;

        private String proxyAddress;
    }

}
