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
package org.jupiter.common.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.ServiceConfigurationError;

/**
 * A simple service-provider loading facility (SPI).
 *
 * jupiter
 * org.jupiter.common.util
 *
 * SPI工厂
 *
 * @author jiachun.fjc
 */
public final class JServiceLoader<S> implements Iterable<S> {

    /**
     * 指定文件路径
     */
    private static final String PREFIX = "META-INF/services/";

    // the class or interface representing the service being loaded
    /**
     * 将要加载的class
     */
    private final Class<S> service;

    // the class loader used to locate, load, and instantiate providers
    /**
     * 加载该类指定的类加载器
     */
    private final ClassLoader loader;

    // cached providers, in instantiation order
    // 加载出来的多种实现类
    private LinkedHashMap<String, S> providers = new LinkedHashMap<>();

    // the current lazy-lookup iterator  惰性迭代器 只有在尝试获取的时候才进行加载 因为本身加载比较耗时 所以没有一开始就加载全部数据
    private LazyIterator lookupIterator;

    public static <S> JServiceLoader<S> load(Class<S> service) {
        return JServiceLoader.load(service, Thread.currentThread().getContextClassLoader());
    }

    public static <S> JServiceLoader<S> load(Class<S> service, ClassLoader loader) {
        return new JServiceLoader<>(service, loader);
    }

    /**
     * 同一接口可能会加载到多个实现类 那么就需要进行排序
     * @return
     */
    public List<S> sort() {
        List<S> sortList = Lists.newArrayList(iterator());

        if (sortList.size() <= 1) {
            return sortList;
        }

        sortList.sort((o1, o2) -> {
            SpiMetadata o1_spi = o1.getClass().getAnnotation(SpiMetadata.class);
            SpiMetadata o2_spi = o2.getClass().getAnnotation(SpiMetadata.class);

            int o1_priority = o1_spi == null ? 0 : o1_spi.priority();
            int o2_priority = o2_spi == null ? 0 : o2_spi.priority();

            // 优先级高的排前边
            return o2_priority - o1_priority;
        });

        return sortList;
    }

    /**
     * 找到SPI 多个实现类中的第一个
     * @return
     */
    public S first() {
        List<S> sortList = sort();
        if (sortList.isEmpty()) {
            throw fail(service, "could not find any implementation for class");
        }
        return sortList.get(0);
    }

    /**
     * 查询某个类
     * @param implName
     * @return
     */
    public S find(String implName) {
        for (S s : providers.values()) {
            // 必须元数据中的name 匹配才能返回
            SpiMetadata spi = s.getClass().getAnnotation(SpiMetadata.class);
            if (spi != null && spi.name().equalsIgnoreCase(implName)) {
                return s;
            }
        }
        while (lookupIterator.hasNext()) {
            Class<S> cls = lookupIterator.next();
            // 从元数据中找到匹配的实现类
            SpiMetadata spi = cls.getAnnotation(SpiMetadata.class);
            if (spi != null && spi.name().equalsIgnoreCase(implName)) {
                try {
                    S provider = service.cast(cls.newInstance());
                    providers.put(cls.getName(), provider);
                    return provider;
                } catch (Throwable x) {
                    throw fail(service, "provider " + cls.getName() + " could not be instantiated", x);
                }
            }
        }
        throw fail(service, "provider " + implName + " could not be found");
    }

    public void reload() {
        providers.clear();
        // 生成惰性加载器 当需要获取数据时 开始加载
        lookupIterator = new LazyIterator(service, loader);
    }

    private JServiceLoader(Class<S> service, ClassLoader loader) {
        this.service = Requires.requireNotNull(service, "service interface cannot be null");
        // 当没有指定类加载器时 默认使用 AppClassLoader 即应用级别类加载器 一般情况下使用的类(用户类而不是jdk自带的)都是通过该类加载的
        this.loader = (loader == null) ? ClassLoader.getSystemClassLoader() : loader;
        reload();
    }

    private static ServiceConfigurationError fail(Class<?> service, String msg, Throwable cause) {
        return new ServiceConfigurationError(service.getName() + ": " + msg, cause);
    }

    private static ServiceConfigurationError fail(Class<?> service, String msg) {
        return new ServiceConfigurationError(service.getName() + ": " + msg);
    }

    private static ServiceConfigurationError fail(Class<?> service, URL url, int line, String msg) {
        return fail(service, url + ":" + line + ": " + msg);
    }

    // parse a single line from the given configuration file, adding the name
    // on the line to the names list.
    private int parseLine(Class<?> service, URL u, BufferedReader r, int lc, List<String> names)
            throws IOException, ServiceConfigurationError {

        String ln = r.readLine();
        if (ln == null) {
            return -1;
        }
        int ci = ln.indexOf('#');
        if (ci >= 0) {
            ln = ln.substring(0, ci);
        }
        ln = ln.trim();
        int n = ln.length();
        if (n != 0) {
            if ((ln.indexOf(' ') >= 0) || (ln.indexOf('\t') >= 0)) {
                throw fail(service, u, lc, "illegal configuration-file syntax");
            }
            int cp = ln.codePointAt(0);
            if (!Character.isJavaIdentifierStart(cp)) {
                throw fail(service, u, lc, "illegal provider-class name: " + ln);
            }
            for (int i = Character.charCount(cp); i < n; i += Character.charCount(cp)) {
                cp = ln.codePointAt(i);
                if (!Character.isJavaIdentifierPart(cp) && (cp != '.')) {
                    throw fail(service, u, lc, "Illegal provider-class name: " + ln);
                }
            }
            if (!providers.containsKey(ln) && !names.contains(ln)) {
                names.add(ln);
            }
        }
        return lc + 1;
    }

    /**
     * 解析 spi文件数据
     * @param service
     * @param url
     * @return
     */
    private Iterator<String> parse(Class<?> service, URL url) {
        ArrayList<String> names = new ArrayList<>();
        try (InputStream in = url.openStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            int lc = 1;
            // noinspection StatementWithEmptyBody
            while ((lc = parseLine(service, url, r, lc, names)) >= 0)
                ;
        } catch (IOException x) {
            throw fail(service, "error reading configuration file", x);
        }
        return names.iterator();
    }

    @Override
    public Iterator<S> iterator() {
        return new Iterator<S>() {

            Iterator<Map.Entry<String, S>> knownProviders = providers.entrySet().iterator();

            @Override
            public boolean hasNext() {
                return knownProviders.hasNext() || lookupIterator.hasNext();
            }

            @Override
            public S next() {
                if (knownProviders.hasNext()) {
                    return knownProviders.next().getValue();
                }
                Class<S> cls = lookupIterator.next();
                try {
                    S provider = service.cast(cls.newInstance());
                    providers.put(cls.getName(), provider);
                    return provider;
                } catch (Throwable x) {
                    throw fail(service, "provider " + cls.getName() + " could not be instantiated", x);
                }
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * 惰性迭代器
     */
    private class LazyIterator implements Iterator<Class<S>> {
        /**
         * 查询的目标类
         */
        Class<S> service;
        /**
         * 加载类使用的类加载器
         */
        ClassLoader loader;
        Enumeration<URL> configs = null;
        Iterator<String> pending = null;
        String nextName = null;

        private LazyIterator(Class<S> service, ClassLoader loader) {
            this.service = service;
            this.loader = loader;
        }

        @Override
        public boolean hasNext() {
            // nextName 不为null 代表已经找到了某个类
            if (nextName != null) {
                return true;
            }
            // 当config 还没初始化时 去默认的地址加载数据
            if (configs == null) {
                try {
                    String fullName = PREFIX + service.getName();
                    if (loader == null) {
                        configs = ClassLoader.getSystemResources(fullName);
                    } else {
                        configs = loader.getResources(fullName);
                    }
                } catch (IOException x) {
                    throw fail(service, "error locating configuration files", x);
                }
            }
            // 当还有资源可以遍历时
            while ((pending == null) || !pending.hasNext()) {
                if (!configs.hasMoreElements()) {
                    return false;
                }
                // 将每行 services 文件中的数据都转换成 pending
                pending = parse(service, configs.nextElement());
            }
            nextName = pending.next();
            return true;
        }

        /**
         * 从惰性迭代器中加载类  一开始SPI 的实现类都在 hasNext中获取了 但是还没有被JVM加载
         * @return
         */
        @SuppressWarnings("unchecked")
        @Override
        public Class<S> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            String name = nextName;
            nextName = null;
            Class<?> cls;
            try {
                cls = Class.forName(name, false, loader);
            } catch (ClassNotFoundException x) {
                throw fail(service, "provider " + name + " not found");
            }
            if (!service.isAssignableFrom(cls)) {
                throw fail(service, "provider " + name + " not a subtype");
            }
            return (Class<S>) cls;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Returns a string describing this service.
     */
    @Override
    public String toString() {
        return "org.jupiter.common.util.JServiceLoader[" + service.getName() + "]";
    }
}
