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

import org.jupiter.rpc.DefaultFilterChain;
import org.jupiter.rpc.JFilter;
import org.jupiter.rpc.JFilterChain;
import org.jupiter.rpc.JFilterContext;
import org.jupiter.rpc.JFilterLoader;
import org.jupiter.rpc.JRequest;
import org.jupiter.rpc.consumer.cluster.ClusterInvoker;
import org.jupiter.rpc.consumer.dispatcher.Dispatcher;
import org.jupiter.rpc.consumer.future.InvokeFuture;
import org.jupiter.rpc.model.metadata.ClusterStrategyConfig;
import org.jupiter.rpc.model.metadata.MessageWrapper;
import org.jupiter.rpc.model.metadata.MethodSpecialConfig;
import org.jupiter.rpc.model.metadata.ServiceMetadata;

/**
 * jupiter
 * org.jupiter.rpc.consumer.invoker
 *
 * 调用者抽象类
 * @author jiachun.fjc
 */
public abstract class AbstractInvoker {

    /**
     * 对应的应用名
     */
    private final String appName;
    /**
     * 本调用者 绑定的 服务对象 一个serviceMetaData 对应一个provider 对象
     */
    private final ServiceMetadata metadata; // 目标服务元信息
    /**
     * 内部绑定了 集群相关的信息
     */
    private final ClusterStrategyBridging clusterStrategyBridging;

    public AbstractInvoker(String appName,
                           ServiceMetadata metadata,
                           Dispatcher dispatcher,
                           ClusterStrategyConfig defaultStrategy,
                           List<MethodSpecialConfig> methodSpecialConfigs) {
        this.appName = appName;
        this.metadata = metadata;
        // 通过传入的集群策略来初始化 invoker 对象
        clusterStrategyBridging = new ClusterStrategyBridging(dispatcher, defaultStrategy, methodSpecialConfigs);
    }

    /**
     * 实际执行逻辑
     * @param methodName
     * @param args
     * @param returnType
     * @param sync
     * @return
     * @throws Throwable
     */
    protected Object doInvoke(String methodName, Object[] args, Class<?> returnType, boolean sync) throws Throwable {
        // 将方法名和参数封装成req 对象
        JRequest request = createRequest(methodName, args);
        // 找到方法名对应的集群调用对象
        ClusterInvoker invoker = clusterStrategyBridging.findClusterInvoker(methodName);

        // 通过过滤链模式 灵活插拔功能
        Context invokeCtx = new Context(invoker, returnType, sync);
        Chains.invoke(request, invokeCtx);

        return invokeCtx.getResult();
    }

    /**
     * 创建请求对象
     * @param methodName
     * @param args
     * @return
     */
    private JRequest createRequest(String methodName, Object[] args) {
        MessageWrapper message = new MessageWrapper(metadata);
        message.setAppName(appName);
        message.setMethodName(methodName);
        // 不需要方法参数类型, 服务端会根据args具体类型按照JLS规则动态dispatch
        message.setArgs(args);

        JRequest request = new JRequest();
        request.message(message);

        return request;
    }

    /**
     * 本次调用的上下文信息
     */
    static class Context implements JFilterContext {

        /**
         * 集群调用对象
         */
        private final ClusterInvoker invoker;
        /**
         * 本次调用返回值
         */
        private final Class<?> returnType;
        /**
         * 是否是同步调用
         */
        private final boolean sync;

        /**
         * 本次执行结果
         */
        private Object result;

        Context(ClusterInvoker invoker, Class<?> returnType, boolean sync) {
            this.invoker = invoker;
            this.returnType = returnType;
            this.sync = sync;
        }

        @Override
        public JFilter.Type getType() {
            return JFilter.Type.CONSUMER;
        }

        public ClusterInvoker getInvoker() {
            return invoker;
        }

        public Class<?> getReturnType() {
            return returnType;
        }

        public boolean isSync() {
            return sync;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }
    }

    /**
     * 消费端过滤器 当consumer 需要调用某个服务时 通过该过滤器实现（集群-均衡负载）调用
     * 将整个集群调用 通过过滤器的模式实现灵活插拔
     */
    static class ClusterInvokeFilter implements JFilter {

        @Override
        public Type getType() {
            return JFilter.Type.CONSUMER;
        }

        @Override
        public <T extends JFilterContext> void doFilter(JRequest request, T filterCtx, JFilterChain next) throws Throwable {
            // 每个拦截节点都包含一个context 对象 该对象有足够的参数
            Context invokeCtx = (Context) filterCtx;
            ClusterInvoker invoker = invokeCtx.getInvoker();
            Class<?> returnType = invokeCtx.getReturnType();
            // invoke
            InvokeFuture<?> future = invoker.invoke(request, returnType);

            // 同步调用的话阻塞等待结果设置
            if (invokeCtx.isSync()) {
                invokeCtx.setResult(future.getResult());
            } else {
                // 将future对象设置到上下文中
                invokeCtx.setResult(future);
            }
        }
    }

    /**
     * 调用链对象
     */
    static class Chains {

        /**
         * 调用链头节点
         */
        private static final JFilterChain headChain;

        static {
            JFilterChain invokeChain = new DefaultFilterChain(new ClusterInvokeFilter(), null);
            // 加载所有 Consumer 端的过滤器 并连接到 chain 上 这样 ClusterInvokeFilter 会变成最后一个节点 而该节点内部包含了真正的调用逻辑
            headChain = JFilterLoader.loadExtFilters(invokeChain, JFilter.Type.CONSUMER);
        }

        static <T extends JFilterContext> T invoke(JRequest request, T invokeCtx) throws Throwable {
            headChain.doFilter(request, invokeCtx);
            // 到这里时 invokeContext 中已经存在 result了 （可能是阻塞后已经获得了结果 或者是返回一个future对象）
            return invokeCtx;
        }
    }
}
