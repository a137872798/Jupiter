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
package org.jupiter.rpc.provider.processor.task;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.jupiter.common.concurrent.RejectedRunnable;
import org.jupiter.common.util.Pair;
import org.jupiter.common.util.Reflects;
import org.jupiter.common.util.Requires;
import org.jupiter.common.util.Signal;
import org.jupiter.common.util.StackTraceUtil;
import org.jupiter.common.util.SystemClock;
import org.jupiter.common.util.SystemPropertyUtil;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.rpc.DefaultFilterChain;
import org.jupiter.rpc.JFilter;
import org.jupiter.rpc.JFilterChain;
import org.jupiter.rpc.JFilterContext;
import org.jupiter.rpc.JFilterLoader;
import org.jupiter.rpc.JRequest;
import org.jupiter.rpc.exception.JupiterBadRequestException;
import org.jupiter.rpc.exception.JupiterFlowControlException;
import org.jupiter.rpc.exception.JupiterRemoteException;
import org.jupiter.rpc.exception.JupiterServerBusyException;
import org.jupiter.rpc.exception.JupiterServiceNotFoundException;
import org.jupiter.rpc.flow.control.ControlResult;
import org.jupiter.rpc.flow.control.FlowController;
import org.jupiter.rpc.metric.Metrics;
import org.jupiter.rpc.model.metadata.MessageWrapper;
import org.jupiter.rpc.model.metadata.ResultWrapper;
import org.jupiter.rpc.model.metadata.ServiceWrapper;
import org.jupiter.rpc.provider.ProviderInterceptor;
import org.jupiter.rpc.provider.processor.DefaultProviderProcessor;
import org.jupiter.serialization.Serializer;
import org.jupiter.serialization.SerializerFactory;
import org.jupiter.serialization.io.InputBuf;
import org.jupiter.serialization.io.OutputBuf;
import org.jupiter.transport.CodecConfig;
import org.jupiter.transport.Status;
import org.jupiter.transport.channel.JChannel;
import org.jupiter.transport.channel.JFutureListener;
import org.jupiter.transport.payload.JRequestPayload;
import org.jupiter.transport.payload.JResponsePayload;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;

/**
 *
 * jupiter
 * org.jupiter.rpc.provider.processor.task
 *
 * consumer 的请求会被封装成该对象  该对象内部封装了过滤器 拦截器 流量控制器 等处理consumer请求 并通过provider生成结果的逻辑
 * @author jiachun.fjc
 */
public class MessageTask implements RejectedRunnable {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(MessageTask.class);

    private static final boolean METRIC_NEEDED = SystemPropertyUtil.getBoolean("jupiter.metric.needed", false);

    private static final Signal INVOKE_ERROR = Signal.valueOf(MessageTask.class, "INVOKE_ERROR");

    /**
     * 对应的处理器
     */
    private final DefaultProviderProcessor processor;
    /**
     * 包装后的channel 对象
     */
    private final JChannel channel;
    /**
     * 本次请求数据体
     */
    private final JRequest request;

    public MessageTask(DefaultProviderProcessor processor, JChannel channel, JRequest request) {
        this.processor = processor;
        this.channel = channel;
        this.request = request;
    }

    /**
     * 一般来说触发该方法时 此时已经在 业务线程池中了 比如基于 Disruptor
     */
    @Override
    public void run() {
        // stack copy
        final DefaultProviderProcessor _processor = processor;
        final JRequest _request = request;

        // 全局流量控制  provider 接收消息时会经过流量控制器处理
        ControlResult ctrl = _processor.flowControl(_request);
        // 如果本次请求不被允许 基于限流的拦截 会重建channel  并返回一条错误信息到 consumer
        if (!ctrl.isAllowed()) {
            rejected(Status.APP_FLOW_CONTROL, new JupiterFlowControlException(String.valueOf(ctrl)));
            return;
        }

        MessageWrapper msg;
        try {
            // 获取本次请求内部的 序列化数据 进行反序列化
            JRequestPayload _requestPayload = _request.payload();

            // 获取本次序列化方式
            byte s_code = _requestPayload.serializerCode();
            Serializer serializer = SerializerFactory.getSerializer(s_code);

            // 在业务线程中反序列化, 减轻IO线程负担
            if (CodecConfig.isCodecLowCopy()) {
                InputBuf inputBuf = _requestPayload.inputBuf();
                msg = serializer.readObject(inputBuf, MessageWrapper.class);
            } else {
                // 如果已经在IO 线程处理完毕 那么直接读取就好
                byte[] bytes = _requestPayload.bytes();
                msg = serializer.readObject(bytes, MessageWrapper.class);
            }
            // 释放不必要的内存
            _requestPayload.clear();

            // 将结果设置到req 中
            _request.message(msg);
        } catch (Throwable t) {
            // 当在反序列化时遇到异常  也会选择重建channel
            rejected(Status.BAD_REQUEST, new JupiterBadRequestException("reading request failed", t));
            return;
        }

        // 查找服务
        // 根据本次请求的元数据 查询对应的服务提供者
        final ServiceWrapper service = _processor.lookupService(msg.getMetadata());
        // 代表没有在本机上找到对应的服务提供者   这里也要重建channel吗 应该在什么样的场景下要重建channel?
        if (service == null) {
            rejected(Status.SERVICE_NOT_FOUND, new JupiterServiceNotFoundException(String.valueOf(msg)));
            return;
        }

        // provider私有流量控制
        // 在全局级别 和 provider 级别都提供了流量控制 便于更精确的调控
        FlowController<JRequest> childController = service.getFlowController();
        if (childController != null) {
            ctrl = childController.flowControl(_request);
            if (!ctrl.isAllowed()) {
                rejected(Status.PROVIDER_FLOW_CONTROL, new JupiterFlowControlException(String.valueOf(ctrl)));
                return;
            }
        }

        // processing
        // 每个服务级别有自己的执行器
        Executor childExecutor = service.getExecutor();
        if (childExecutor == null) {
            // 直接在当前业务线程执行
            process(service);
        } else {
            // provider私有线程池执行
            childExecutor.execute(() -> process(service));
        }
    }

    @Override
    public void rejected() {
        rejected(Status.SERVER_BUSY, new JupiterServerBusyException(String.valueOf(request)));
    }

    private void rejected(Status status, JupiterRemoteException cause) {
        if (METRIC_NEEDED) {
            MetricsHolder.rejectionMeter.mark();
        }

        // 当服务拒绝方法被调用时一般分以下几种情况:
        //  1. 非法请求, close当前连接;
        //  2. 服务端处理能力出现瓶颈, close当前连接, jupiter客户端会自动重连, 在加权负载均衡的情况下权重是一点一点升上来的.
        processor.handleRejected(channel, request, status, cause);
    }

    /**
     * 当通过consumer请求中携带的 服务信息 找到对应的服务提供者 并执行
     * @param service
     */
    @SuppressWarnings("unchecked")
    private void process(ServiceWrapper service) {
        final Context invokeCtx = new Context(service);
        try {
            // 通过过滤链处理上下文 此时已经完成了provider 的调用
            final Object invokeResult = Chains.invoke(request, invokeCtx)
                    .getResult();

            // 代表在同步模式下，已经生成了结果 那么直接调用doProcess 将写入结果返回到 consumer
            if (!(invokeResult instanceof CompletableFuture)) {
                doProcess(invokeResult);
                return;
            }

            CompletableFuture<Object> cf = (CompletableFuture<Object>) invokeResult;

            // 如果异步处理已经完成 也是直接写回到consumer
            if (cf.isDone()) {
                doProcess(cf.join());
                return;
            }

            // 如果是future 对象那么在处理完后 将触发 写回到 consumer的功能  也就是在提供者这一端如果调用了某个future方法 那么还是要等待future对象生成结果才能返回
            // 不过在consumer 上 因为api表明了returnType为future类型 所以consumer 也会看作一个普通的异步方法去操作 不会有大影响
            cf.whenComplete((result, throwable) -> {
                if (throwable == null) {
                    try {
                        doProcess(result);
                    } catch (Throwable t) {
                        handleFail(invokeCtx, t);
                    }
                } else {
                    handleFail(invokeCtx, throwable);
                }
            });
        } catch (Throwable t) {
            // 如果本次调用出现了异常
            handleFail(invokeCtx, t);
        }
    }

    /**
     * 通过处理结果
     * @param realResult
     */
    private void doProcess(Object realResult) {
        // 减少返回给consumer的传输数据体大小
        ResultWrapper result = new ResultWrapper();
        result.setResult(realResult);
        // 获取序列化方式
        byte s_code = request.serializerCode();
        Serializer serializer = SerializerFactory.getSerializer(s_code);

        // 返回值使用跟请求体一样的invokeId 这样好处理 请求端的响应池
        JResponsePayload responsePayload = new JResponsePayload(request.invokeId());

        // 代表在 IO 线程进行序列化
        if (CodecConfig.isCodecLowCopy()) {
            OutputBuf outputBuf =
                    serializer.writeObject(channel.allocOutputBuf(), result);
            responsePayload.outputBuf(s_code, outputBuf);
        } else {
            // 在线程池中进行序列化
            byte[] bytes = serializer.writeObject(result);
            responsePayload.bytes(s_code, bytes);
        }

        responsePayload.status(Status.OK.value());

        // 将结果写回对端
        handleWriteResponse(responsePayload);
    }

    private void handleFail(Context invokeCtx, Throwable t) {
        if (INVOKE_ERROR == t) {
            // handle biz exception
            handleException(invokeCtx.getExpectCauseTypes(), invokeCtx.getCause());
        } else {
            processor.handleException(channel, request, Status.SERVER_ERROR, t);
        }
    }

    private void handleWriteResponse(JResponsePayload response) {
        channel.write(response, new JFutureListener<JChannel>() {

            @Override
            public void operationSuccess(JChannel channel) throws Exception {
                if (METRIC_NEEDED) {
                    long duration = SystemClock.millisClock().now() - request.timestamp();
                    MetricsHolder.processingTimer.update(duration, TimeUnit.MILLISECONDS);
                }
            }

            @Override
            public void operationFailure(JChannel channel, Throwable cause) throws Exception {
                long duration = SystemClock.millisClock().now() - request.timestamp();
                logger.error("Response sent failed, duration: {} millis, channel: {}, cause: {}.",
                        duration, channel, cause);
            }
        });
    }

    private void handleException(Class<?>[] exceptionTypes, Throwable failCause) {
        if (exceptionTypes != null && exceptionTypes.length > 0) {
            Class<?> failType = failCause.getClass();
            for (Class<?> eType : exceptionTypes) {
                // 如果抛出声明异常的子类, 客户端可能会因为不存在子类类型而无法序列化, 会在客户端抛出无法反序列化异常
                if (eType.isAssignableFrom(failType)) {
                    // 预期内的异常
                    processor.handleException(channel, request, Status.SERVICE_EXPECTED_ERROR, failCause);
                    return;
                }
            }
        }

        // 预期外的异常
        processor.handleException(channel, request, Status.SERVICE_UNEXPECTED_ERROR, failCause);
    }

    /**
     * 通过provider 生成本次RPC 调用结果
     * @param msg
     * @param invokeCtx
     * @return
     * @throws Signal
     */
    private static Object invoke(MessageWrapper msg, Context invokeCtx) throws Signal {
        // 获取provider 对象
        ServiceWrapper service = invokeCtx.getService();
        Object provider = service.getServiceProvider();
        // 获取本次方法和参数
        String methodName = msg.getMethodName();
        Object[] args = msg.getArgs();

        Timer.Context timerCtx = null;
        if (METRIC_NEEDED) {
            timerCtx = Metrics.timer(msg.getOperationName()).time();
        }

        Class<?>[] expectCauseTypes = null;
        try {
            // 通过传入方法名 找到额外信息  Pair:key:本方法参数类型数组  value:本方法声明的异常
            List<Pair<Class<?>[], Class<?>[]>> methodExtension = service.getMethodExtension(methodName);
            if (methodExtension == null) {
                throw new NoSuchMethodException(methodName);
            }

            // 根据JLS方法调用的静态分派规则查找最匹配的方法parameterTypes
            Pair<Class<?>[], Class<?>[]> bestMatch = Reflects.findMatchingParameterTypesExt(methodExtension, args);
            Class<?>[] parameterTypes = bestMatch.getFirst();
            expectCauseTypes = bestMatch.getSecond();

            // 传入参数和指定的方法名 通过反射调用并获取结果  这里对目标class 做了优化，通过动态代理生成了一个invoke方法 之后根据方法名匹配class内部已经携带的方法
            // 进而规避反射的高开销  那么异步调用是怎么做的???
            return Reflects.fastInvoke(provider, methodName, parameterTypes, args);
        } catch (Throwable t) {
            // 设置cause 和 预期出现的异常
            invokeCtx.setCauseAndExpectTypes(t, expectCauseTypes);
            throw INVOKE_ERROR;
        } finally {
            if (METRIC_NEEDED) {
                timerCtx.stop();
            }
        }
    }

    @SuppressWarnings("all")
    private static void handleBeforeInvoke(ProviderInterceptor[] interceptors,
                                           Object provider,
                                           String methodName,
                                           Object[] args) {

        for (int i = 0; i < interceptors.length; i++) {
            try {
                interceptors[i].beforeInvoke(provider, methodName, args);
            } catch (Throwable t) {
                logger.error("Interceptor[{}#beforeInvoke]: {}.", Reflects.simpleClassName(interceptors[i]),
                        StackTraceUtil.stackTrace(t));
            }
        }
    }

    private static void handleAfterInvoke(ProviderInterceptor[] interceptors,
                                          Object provider,
                                          String methodName,
                                          Object[] args,
                                          Object invokeResult,
                                          Throwable failCause) {

        for (int i = interceptors.length - 1; i >= 0; i--) {
            try {
                interceptors[i].afterInvoke(provider, methodName, args, invokeResult, failCause);
            } catch (Throwable t) {
                logger.error("Interceptor[{}#afterInvoke]: {}.", Reflects.simpleClassName(interceptors[i]),
                        StackTraceUtil.stackTrace(t));
            }
        }
    }

    /**
     * 在处理前还要经过一系列的过滤器 这里需要将service 包装成Context
     */
    public static class Context implements JFilterContext {

        /**
         * 内部 包含provider 对象
         */
        private final ServiceWrapper service;

        private Object result;                  // 服务调用结果
        private Throwable cause;                // 业务异常
        private Class<?>[] expectCauseTypes;    // 预期内的异常类型

        public Context(ServiceWrapper service) {
            this.service = Requires.requireNotNull(service, "service");
        }

        public ServiceWrapper getService() {
            return service;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Throwable getCause() {
            return cause;
        }

        public Class<?>[] getExpectCauseTypes() {
            return expectCauseTypes;
        }

        public void setCauseAndExpectTypes(Throwable cause, Class<?>[] expectCauseTypes) {
            this.cause = cause;
            this.expectCauseTypes = expectCauseTypes;
        }

        @Override
        public JFilter.Type getType() {
            return JFilter.Type.PROVIDER;
        }
    }

    /**
     * 倒数第二个过滤器 该对象内部组装了service的拦截器
     */
    static class InterceptorsFilter implements JFilter {

        @Override
        public Type getType() {
            return Type.PROVIDER;
        }

        @Override
        public <T extends JFilterContext> void doFilter(JRequest request, T filterCtx, JFilterChain next) throws Throwable {
            // 针对consumer 的请求 会被包装成Context 对象
            Context invokeCtx = (Context) filterCtx;
            // 获取provider包装类
            ServiceWrapper service = invokeCtx.getService();
            // 拦截器
            ProviderInterceptor[] interceptors = service.getInterceptors();

            if (interceptors == null || interceptors.length == 0) {
                // 没有拦截器的情况下传递到过滤器的下一环
                next.doFilter(request, filterCtx);
            } else {
                // 获取内部的provider 对象
                Object provider = service.getServiceProvider();

                // consumer的请求体最终就是会被反序列化成 MessageWrapper  内部包含 方法名和本次调用的参数
                MessageWrapper msg = request.message();
                String methodName = msg.getMethodName();
                Object[] args = msg.getArgs();

                // 触发拦截器前置钩子
                handleBeforeInvoke(interceptors, provider, methodName, args);
                try {
                    // 下一环过滤器会执行provider
                    next.doFilter(request, filterCtx);
                } finally {
                    // 触发后置钩子  此时的 invokeContext.getResult() 可能是future 对象
                    handleAfterInvoke(
                            interceptors, provider, methodName, args, invokeCtx.getResult(), invokeCtx.getCause());
                }
            }
        }
    }

    /**
     * 最后一环 provider过滤器 在这里会生成结果
     */
    static class InvokeFilter implements JFilter {

        @Override
        public Type getType() {
            return Type.PROVIDER;
        }

        @Override
        public <T extends JFilterContext> void doFilter(JRequest request, T filterCtx, JFilterChain next) throws Throwable {
            MessageWrapper msg = request.message();
            Context invokeCtx = (Context) filterCtx;

            // 调用并生成结果
            Object invokeResult = MessageTask.invoke(msg, invokeCtx);

            // 将结果设置到上下文中
            invokeCtx.setResult(invokeResult);
        }
    }

    static class Chains {

        private static final JFilterChain headChain;

        static {
            // 最后2个过滤器
            JFilterChain invokeChain = new DefaultFilterChain(new InvokeFilter(), null);
            JFilterChain interceptChain = new DefaultFilterChain(new InterceptorsFilter(), invokeChain);
            // 通过SPI 机制加载的所有过滤器
            headChain = JFilterLoader.loadExtFilters(interceptChain, JFilter.Type.PROVIDER);
        }

        static <T extends JFilterContext> T invoke(JRequest request, T invokeCtx) throws Throwable {
            // 内部组装了一个过滤链
            headChain.doFilter(request, invokeCtx);
            return invokeCtx;
        }
    }

    // - Metrics -------------------------------------------------------------------------------------------------------
    static class MetricsHolder {
        static final Timer processingTimer              = Metrics.timer("processing");
        // 请求被拒绝次数统计
        static final Meter rejectionMeter               = Metrics.meter("rejection");
    }
}
