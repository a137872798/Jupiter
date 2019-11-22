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
package org.jupiter.rpc.consumer.dispatcher;

import java.util.List;

import org.jupiter.rpc.JRequest;
import org.jupiter.rpc.consumer.ConsumerInterceptor;
import org.jupiter.rpc.consumer.future.InvokeFuture;
import org.jupiter.rpc.model.metadata.MethodSpecialConfig;

/**
 * jupiter
 * org.jupiter.rpc.consumer.dispatcher
 *
 * 均衡负载对象
 * @author jiachun.fjc
 */
public interface Dispatcher {

    /**
     * 分发请求并返回future 对象  业务端通过调用 future.getResult 获取本次结果
     * @param request
     * @param returnType
     * @param <T>
     * @return
     */
    <T> InvokeFuture<T> dispatch(JRequest request, Class<T> returnType);

    /**
     * 为本对象添加一组拦截器
     * @param interceptors
     * @return
     */
    Dispatcher interceptors(List<ConsumerInterceptor> interceptors);

    /**
     * 设置调用的超时时间 如果超过该时间则抛出超时异常
     * @param timeoutMillis
     * @return
     */
    Dispatcher timeoutMillis(long timeoutMillis);

    /**
     * 设置针对方法级别的特殊配置  这样某些方法可能就按照特别的集群容错方式调用
     * @param methodSpecialConfigs
     * @return
     */
    Dispatcher methodSpecialConfigs(List<MethodSpecialConfig> methodSpecialConfigs);
}
