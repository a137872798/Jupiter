/*
 * Copyright (c) 2015 The Jupiter Project
 *
 * Licensed under the Apache License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jupiter.rpc.consumer.invoker;

import java.util.List;
import java.util.Map;

import org.jupiter.common.util.Maps;
import org.jupiter.rpc.consumer.cluster.ClusterInvoker;
import org.jupiter.rpc.consumer.cluster.FailfastClusterInvoker;
import org.jupiter.rpc.consumer.cluster.FailoverClusterInvoker;
import org.jupiter.rpc.consumer.cluster.FailsafeClusterInvoker;
import org.jupiter.rpc.consumer.dispatcher.Dispatcher;
import org.jupiter.rpc.model.metadata.ClusterStrategyConfig;
import org.jupiter.rpc.model.metadata.MethodSpecialConfig;

/**
 * Jupiter
 * org.jupiter.rpc.consumer.invoker
 *
 * @author jiachun.fjc
 */
public class ClusterStrategyBridging {

    /**
     * 默认的集群调用者
     */
    private final ClusterInvoker defaultClusterInvoker;
    /**
     * 按方法级别绑定 集群invoker
     */
    private final Map<String, ClusterInvoker> methodSpecialClusterInvokerMapping;

    /**
     * 通过dispatcher 对象进行 集群invoker对象的初始化
     * @param dispatcher
     * @param defaultStrategy  策略配置对象 包含指定的集群容错策略 以及 重试次数
     * @param methodSpecialConfigs  方法级别调用相关的配置
     */
    public ClusterStrategyBridging(Dispatcher dispatcher,
                                   ClusterStrategyConfig defaultStrategy,
                                   List<MethodSpecialConfig> methodSpecialConfigs) {
        // 生成对应的 集群容错对象
        this.defaultClusterInvoker = createClusterInvoker(dispatcher, defaultStrategy);
        // 根据方法配置 生成方法级别的集群容错对象
        this.methodSpecialClusterInvokerMapping = Maps.newHashMap();

        for (MethodSpecialConfig config : methodSpecialConfigs) {
            ClusterStrategyConfig strategy = config.getStrategy();
            if (strategy != null) {
                methodSpecialClusterInvokerMapping.put(
                        config.getMethodName(),
                        createClusterInvoker(dispatcher, strategy)
                );
            }
        }
    }

    public ClusterInvoker findClusterInvoker(String methodName) {
        ClusterInvoker invoker = methodSpecialClusterInvokerMapping.get(methodName);
        return invoker != null ? invoker : defaultClusterInvoker;
    }

    private ClusterInvoker createClusterInvoker(Dispatcher dispatcher, ClusterStrategyConfig strategy) {
        ClusterInvoker.Strategy s = strategy.getStrategy();
        switch (s) {
            case FAIL_FAST:
                return new FailfastClusterInvoker(dispatcher);
            case FAIL_OVER:
                return new FailoverClusterInvoker(dispatcher, strategy.getFailoverRetries());
            case FAIL_SAFE:
                return new FailsafeClusterInvoker(dispatcher);
            default:
                throw new UnsupportedOperationException("Unsupported strategy: " + strategy);
        }
    }
}
