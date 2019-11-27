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
package org.jupiter.rpc;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import org.jupiter.common.util.JConstants;
import org.jupiter.common.util.JServiceLoader;
import org.jupiter.common.util.Lists;
import org.jupiter.common.util.Maps;
import org.jupiter.common.util.Pair;
import org.jupiter.common.util.Requires;
import org.jupiter.common.util.StackTraceUtil;
import org.jupiter.common.util.Strings;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.registry.RegisterMeta;
import org.jupiter.registry.RegistryService;
import org.jupiter.rpc.flow.control.ControlResult;
import org.jupiter.rpc.flow.control.FlowController;
import org.jupiter.rpc.model.metadata.ServiceMetadata;
import org.jupiter.rpc.model.metadata.ServiceWrapper;
import org.jupiter.rpc.provider.ProviderInterceptor;
import org.jupiter.rpc.provider.processor.DefaultProviderProcessor;
import org.jupiter.transport.Directory;
import org.jupiter.transport.JAcceptor;

/**
 * Jupiter默认服务端实现.
 *
 * jupiter
 * org.jupiter.rpc
 *
 * @author jiachun.fjc
 */
public class DefaultServer implements JServer {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultServer.class);

    // provider本地容器
    private final ServiceProviderContainer providerContainer = new DefaultServiceProviderContainer();
    // 服务发布(SPI)
    private final RegistryService registryService;

    // 全局拦截器
    private ProviderInterceptor[] globalInterceptors;
    // 全局流量控制
    private FlowController<JRequest> globalFlowController;

    // IO acceptor
    private JAcceptor acceptor;

    public DefaultServer() {
        this(RegistryService.RegistryType.DEFAULT);
    }

    public DefaultServer(RegistryService.RegistryType registryType) {
        registryType = registryType == null ? RegistryService.RegistryType.DEFAULT : registryType;
        registryService = JServiceLoader.load(RegistryService.class).find(registryType.getValue());
    }

    @Override
    public JAcceptor acceptor() {
        return acceptor;
    }

    /**
     * acceptor 对象在接收到请求时 会转发到 processor 上
     * @param acceptor
     * @return
     */
    @Override
    public JServer withAcceptor(JAcceptor acceptor) {
        if (acceptor.processor() == null) {
            acceptor.withProcessor(new DefaultProviderProcessor() {

                /**
                 * 根据本次consumer传入的请求元数据信息来查找对应的服务包装类
                 * @param directory
                 * @return
                 */
                @Override
                public ServiceWrapper lookupService(Directory directory) {
                    return providerContainer.lookupService(directory.directoryString());
                }

                /**
                 * 当processor 处理请求时 首先会通过该方法进行流量控制
                 * @param request
                 * @return
                 */
                @Override
                public ControlResult flowControl(JRequest request) {
                    // 没有设置的话总是允许  一个比较简单的实现就是依赖 信号量
                    if (globalFlowController == null) {
                        return ControlResult.ALLOWED;
                    }
                    return globalFlowController.flowControl(request);
                }
            });
        }
        this.acceptor = acceptor;
        return this;
    }

    @Override
    public RegistryService registryService() {
        return registryService;
    }

    /**
     * 通过registryService 将服务提供者信息注册到 注册中心
     * @param connectString list of servers to connect to [host1:port1,host2:port2....]
     */
    @Override
    public void connectToRegistryServer(String connectString) {
        registryService.connectToRegistryServer(connectString);
    }

    @Override
    public void withGlobalInterceptors(ProviderInterceptor... globalInterceptors) {
        this.globalInterceptors = globalInterceptors;
    }

    @Override
    public FlowController<JRequest> globalFlowController() {
        return globalFlowController;
    }

    @Override
    public void withGlobalFlowController(FlowController<JRequest> globalFlowController) {
        this.globalFlowController = globalFlowController;
    }

    @Override
    public ServiceRegistry serviceRegistry() {
        return new DefaultServiceRegistry();
    }

    @Override
    public ServiceWrapper lookupService(Directory directory) {
        return providerContainer.lookupService(directory.directoryString());
    }

    @Override
    public ServiceWrapper removeService(Directory directory) {
        return providerContainer.removeService(directory.directoryString());
    }

    @Override
    public List<ServiceWrapper> allRegisteredServices() {
        return providerContainer.getAllServices();
    }

    /**
     * 将服务发布到注册中心
     * @param serviceWrapper
     */
    @Override
    public void publish(ServiceWrapper serviceWrapper) {
        ServiceMetadata metadata = serviceWrapper.getMetadata();

        // 这里统一了注册中心需要的元数据
        RegisterMeta meta = new RegisterMeta();
        meta.setPort(acceptor.boundPort());
        meta.setGroup(metadata.getGroup());
        meta.setServiceProviderName(metadata.getServiceProviderName());
        meta.setVersion(metadata.getVersion());
        meta.setWeight(serviceWrapper.getWeight());
        // 这里还指定了连接数
        meta.setConnCount(JConstants.SUGGESTED_CONNECTION_COUNT);

        registryService.register(meta);
    }

    @Override
    public void publish(ServiceWrapper... serviceWrappers) {
        for (ServiceWrapper wrapper : serviceWrappers) {
            publish(wrapper);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> void publishWithInitializer(
            final ServiceWrapper serviceWrapper, final ProviderInitializer<T> initializer, Executor executor) {
        Runnable task = () -> {
            try {
                initializer.init((T) serviceWrapper.getServiceProvider());
                publish(serviceWrapper);
            } catch (Exception e) {
                logger.error("Error on {} #publishWithInitializer: {}.", serviceWrapper.getMetadata(),
                        StackTraceUtil.stackTrace(e));
            }
        };

        if (executor == null) {
            task.run();
        } else {
            executor.execute(task);
        }
    }

    @Override
    public void publishAll() {
        for (ServiceWrapper wrapper : providerContainer.getAllServices()) {
            publish(wrapper);
        }
    }

    @SuppressWarnings("all")
    @Override
    public void unpublish(ServiceWrapper serviceWrapper) {
        ServiceMetadata metadata = serviceWrapper.getMetadata();

        RegisterMeta meta = new RegisterMeta();
        meta.setPort(acceptor.boundPort());
        meta.setGroup(metadata.getGroup());
        meta.setVersion(metadata.getVersion());
        meta.setServiceProviderName(metadata.getServiceProviderName());
        meta.setWeight(serviceWrapper.getWeight());
        meta.setConnCount(JConstants.SUGGESTED_CONNECTION_COUNT);

        registryService.unregister(meta);
    }

    @SuppressWarnings("all")
    @Override
    public void unpublishAll() {
        for (ServiceWrapper wrapper : providerContainer.getAllServices()) {
            unpublish(wrapper);
        }
    }

    @Override
    public void start() throws InterruptedException {
        acceptor.start();
    }

    @Override
    public void start(boolean sync) throws InterruptedException {
        acceptor.start(sync);
    }

    @Override
    public void shutdownGracefully() {
        registryService.shutdownGracefully();
        acceptor.shutdownGracefully();
    }

    public void setAcceptor(JAcceptor acceptor) {
        withAcceptor(acceptor);
    }

    /**
     * 将服务提供者注册到container中
     * @param group
     * @param providerName
     * @param version
     * @param serviceProvider
     * @param interceptors
     * @param extensions
     * @param weight
     * @param executor
     * @param flowController
     * @return
     */
    ServiceWrapper registerService(
            String group,
            String providerName,
            String version,
            Object serviceProvider,
            ProviderInterceptor[] interceptors,
            Map<String, List<Pair<Class<?>[], Class<?>[]>>> extensions,
            int weight,
            Executor executor,
            FlowController<JRequest> flowController) {

        ProviderInterceptor[] allInterceptors = null;
        List<ProviderInterceptor> tempList = Lists.newArrayList();
        // 全局拦截器会设置到所有的提供者中
        if (globalInterceptors != null) {
            Collections.addAll(tempList, globalInterceptors);
        }
        // 如果该提供者自身设置了拦截器 也进行设置
        if (interceptors != null) {
            Collections.addAll(tempList, interceptors);
        }
        if (!tempList.isEmpty()) {
            allInterceptors = tempList.toArray(new ProviderInterceptor[0]);
        }

        // 为什么需要wrapper 这样类似于做一个 泛化 这样无论具体的服务是什么 都可以通过这种方式进行调用
        ServiceWrapper wrapper =
                new ServiceWrapper(group, providerName, version, serviceProvider, allInterceptors, extensions);

        // 设置权重
        wrapper.setWeight(weight);
        wrapper.setExecutor(executor);
        wrapper.setFlowController(flowController);

        providerContainer.registerService(wrapper.getMetadata().directoryString(), wrapper);

        return wrapper;
    }

    /**
     * 服务提供者 以及相关信息 该对象会被包装成 ServiceWrapper
     */
    class DefaultServiceRegistry implements ServiceRegistry {

        /**
         * 服务提供者对象
         */
        private Object serviceProvider;                     // 服务对象
        private ProviderInterceptor[] interceptors;         // 拦截器
        private Class<?> interfaceClass;                    // 接口类型
        private String group;                               // 服务组别
        private String providerName;                        // 服务名称
        private String version;                             // 服务版本号, 通常在接口不兼容时版本号才需要升级
        /**
         * 服务提供者为什么会需要权重???
         */
        private int weight;                                 // 权重
        private Executor executor;                          // 该服务私有的线程池
        private FlowController<JRequest> flowController;    // 该服务私有的流量控制器

        @Override
        public ServiceRegistry provider(Object serviceProvider, ProviderInterceptor... interceptors) {
            this.serviceProvider = serviceProvider;
            this.interceptors = interceptors;
            return this;
        }

        @Override
        public ServiceRegistry interfaceClass(Class<?> interfaceClass) {
            this.interfaceClass = interfaceClass;
            return this;
        }

        @Override
        public ServiceRegistry group(String group) {
            this.group = group;
            return this;
        }

        @Override
        public ServiceRegistry providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        @Override
        public ServiceRegistry version(String version) {
            this.version = version;
            return this;
        }

        @Override
        public ServiceRegistry weight(int weight) {
            this.weight = weight;
            return this;
        }

        @Override
        public ServiceRegistry executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        @Override
        public ServiceRegistry flowController(FlowController<JRequest> flowController) {
            this.flowController = flowController;
            return this;
        }

        /**
         * 将设置完必要信息的提供者对象注册到本地
         * @return
         */
        @Override
        public ServiceWrapper register() {
            Requires.requireNotNull(serviceProvider, "serviceProvider");

            Class<?> providerClass = serviceProvider.getClass();

            // 尝试获取服务提供者相关注解
            ServiceProviderImpl implAnnotation = null;
            ServiceProvider ifAnnotation = null;
            for (Class<?> cls = providerClass; cls != Object.class; cls = cls.getSuperclass()) {
                if (implAnnotation == null) {
                    implAnnotation = cls.getAnnotation(ServiceProviderImpl.class);
                }

                Class<?>[] interfaces = cls.getInterfaces();
                if (interfaces != null) {
                    for (Class<?> i : interfaces) {
                        ifAnnotation = i.getAnnotation(ServiceProvider.class);
                        if (ifAnnotation == null) {
                            continue;
                        }

                        Requires.requireTrue(
                                interfaceClass == null,
                                i.getName() + " has a @ServiceProvider annotation, can't set [interfaceClass] again"
                        );

                        interfaceClass = i;
                        break;
                    }
                }

                if (implAnnotation != null && ifAnnotation != null) {
                    break;
                }
            }

            // 如果有设置对应的注解 可以从注解中获取需要的属性
            if (ifAnnotation != null) {
                Requires.requireTrue(
                        group == null,
                        interfaceClass.getName() + " has a @ServiceProvider annotation, can't set [group] again"
                );
                Requires.requireTrue(
                        providerName == null,
                        interfaceClass.getName() + " has a @ServiceProvider annotation, can't set [providerName] again"
                );

                group = ifAnnotation.group();
                String name = ifAnnotation.name();
                // 如果指定了服务名 使用name 作为查找服务的依据
                providerName = Strings.isNotBlank(name) ? name : interfaceClass.getName();
            }

            if (implAnnotation != null) {
                Requires.requireTrue(
                        version == null,
                        providerClass.getName() + " has a @ServiceProviderImpl annotation, can't set [version] again"
                );

                // 获取版本号信息
                version = implAnnotation.version();
            }

            Requires.requireNotNull(interfaceClass, "interfaceClass");
            Requires.requireTrue(Strings.isNotBlank(group), "group");
            Requires.requireTrue(Strings.isNotBlank(providerName), "providerName");
            Requires.requireTrue(Strings.isNotBlank(version), "version");

            // method's extensions
            //
            // key:     method name
            // value:   pair.first:  方法参数类型(用于根据JLS规则实现方法调用的静态分派)
            //          pair.second: 方法显式声明抛出的异常类型
            Map<String, List<Pair<Class<?>[], Class<?>[]>>> extensions = Maps.newHashMap();
            for (Method method : interfaceClass.getMethods()) {
                String methodName = method.getName();
                List<Pair<Class<?>[], Class<?>[]>> list = extensions.computeIfAbsent(methodName, k -> Lists.newArrayList());
                list.add(Pair.of(method.getParameterTypes(), method.getExceptionTypes()));
            }

            // 将服务相关信息注册到 container 中
            return registerService(
                    group,
                    providerName,
                    version,
                    serviceProvider,
                    interceptors,
                    extensions,
                    weight,
                    executor,
                    flowController
            );
        }
    }

    /**
     * Local service provider container.
     *
     * 本地provider容器  维护本机所有的提供者 这样当consumer传入本次需要的服务信息时 就可以通过下面的方法查找
     */
    interface ServiceProviderContainer {

        /**
         * 注册服务(注意并不是发布服务到注册中心, 只是注册到本地容器)
         */
        void registerService(String uniqueKey, ServiceWrapper serviceWrapper);

        /**
         * 本地容器查找服务
         */
        ServiceWrapper lookupService(String uniqueKey);

        /**
         * 从本地容器移除服务
         */
        ServiceWrapper removeService(String uniqueKey);

        /**
         * 获取本地容器中所有服务
         */
        List<ServiceWrapper> getAllServices();
    }

    // 本地provider容器默认实现
    private static final class DefaultServiceProviderContainer implements ServiceProviderContainer {

        /**
         * serviceWrapper 内部包含了 provider 对象
         */
        private final ConcurrentMap<String, ServiceWrapper> serviceProviders = Maps.newConcurrentMap();

        @Override
        public void registerService(String uniqueKey, ServiceWrapper serviceWrapper) {
            serviceProviders.put(uniqueKey, serviceWrapper);

            logger.info("ServiceProvider [{}, {}] is registered.", uniqueKey, serviceWrapper);
        }

        @Override
        public ServiceWrapper lookupService(String uniqueKey) {
            return serviceProviders.get(uniqueKey);
        }

        @Override
        public ServiceWrapper removeService(String uniqueKey) {
            ServiceWrapper serviceWrapper = serviceProviders.remove(uniqueKey);
            if (serviceWrapper == null) {
                logger.warn("ServiceProvider [{}] not found.", uniqueKey);
            } else {
                logger.info("ServiceProvider [{}, {}] is removed.", uniqueKey, serviceWrapper);
            }
            return serviceWrapper;
        }

        @Override
        public List<ServiceWrapper> getAllServices() {
            return Lists.newArrayList(serviceProviders.values());
        }
    }
}
