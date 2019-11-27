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

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentMap;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_VARARGS;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_1;

/**
 * jupiter
 * org.jupiter.common.util
 *
 * 通过反射读取了method 的信息 并保存起来便于之后快速访问
 * 并且这些抽取出来的方法不是通过反射调用的， 在使用asm生成动态代理类后，传入需要的参数以及调用invoke方法 能以非反射的方式调用
 *
 * 回想一下一个功能相似的类应该是携带 Method[] String[] 这种 然后方法名参数匹配的时候 通过 method.invoke 这种反射的方式来调用
 * @author jiachun.fjc
 */
public abstract class FastMethodAccessor {

    /**
     * 维护了每个类对应的 fastMethodAccessor 对象
     */
    private static final ConcurrentMap<Class<?>, FastMethodAccessor> fastAccessorCache = Maps.newConcurrentMap();

    /**
     * 某个类对应的全部方法
     */
    private String[] methodNames;

    private Class<?>[][] parameterTypes_s;

    /**
     * 用ASM生成的类继承 {@link FastMethodAccessor} 并实现这个抽象方法
     *
     * 子类 invoke() 以下面的方式规避反射调用:
     *
     *  public Object invoke(Object obj, int methodIndex, Object... args) {
     *      switch(methodIndex) {
     *          case 0:
     *              return 直接调用 this.methodNames[0] 对应的方法;
     *          case 1:
     *              return 直接调用 this.methodNames[1] 对应的方法;
     *          case ...
     *      }
     *      throw new IllegalArgumentException("Method not found: " + methodIndex);
     *  }
     *
     */
    public abstract Object invoke(Object obj, int methodIndex, Object... args);

    /**
     * 根据方法名找到 本方法对应的下标
     * @param obj
     * @param methodName
     * @param parameterTypes
     * @param args
     * @return
     */
    public Object invoke(Object obj, String methodName, Class<?>[] parameterTypes, Object... args) {
        return invoke(obj, getIndex(methodName, parameterTypes), args);
    }

    /**
     * 当方法名和参数列表都匹配的时候
     * @param methodName
     * @param parameterTypes
     * @return
     */
    public int getIndex(String methodName, Class<?>... parameterTypes) {
        for (int i = 0; i < methodNames.length; i++) {
            if (methodNames[i].equals(methodName) && Arrays.equals(parameterTypes, parameterTypes_s[i])) {
                return i;
            }
        }
        throw new IllegalArgumentException(
                "Unable to find non-private method: " + methodName + " " + Arrays.toString(parameterTypes));
    }

    /**
     * 从缓存中获取fastMethodAccessor
     * @param type
     * @return
     */
    public static FastMethodAccessor get(Class<?> type) {
        FastMethodAccessor accessor = fastAccessorCache.get(type);
        if (accessor == null) {
            // avoid duplicate class definition
            synchronized (getClassLock(type)) {
                accessor = fastAccessorCache.get(type);
                if (accessor == null) {
                    accessor = create(type);
                    fastAccessorCache.put(type, accessor);
                }
                return accessor;
            }
        }
        return accessor;
    }

    private static Class<?> getClassLock(Class<?> type) {
        return type;
    }

    /**
     * 传入某个目标类 生成fastMethodAccessor 对象
     * @param type
     * @return
     */
    private static FastMethodAccessor create(Class<?> type) {
        ArrayList<Method> methods = Lists.newArrayList();
        boolean isInterface = type.isInterface();
        if (!isInterface) {
            Class nextClass = type;
            while (nextClass != Object.class) {
                // 选择非Object方法
                addDeclaredMethodsToList(nextClass, methods);
                nextClass = nextClass.getSuperclass();
            }
        } else {
            // 递归添加接口方法
            recursiveAddInterfaceMethodsToList(type, methods);
        }

        int n = methods.size();
        String[] methodNames = new String[n];
        Class<?>[][] parameterTypes_s = new Class[n][];
        Class<?>[] returnTypes = new Class[n];
        // 添加对应的参数列表
        for (int i = 0; i < n; i++) {
            Method method = methods.get(i);
            methodNames[i] = method.getName();
            parameterTypes_s[i] = method.getParameterTypes();
            returnTypes[i] = method.getReturnType();
        }

        // 开始生成动态代理对象
        String className = type.getName();
        // 类名变成比如  java.util.List_FastMethodAccessor
        String accessorClassName = className + "_FastMethodAccessor";
        // java/util/List_FastMethodAccessor
        String accessorClassNameInternal = accessorClassName.replace('.', '/');
        // java/util/List
        String classNameInternal = className.replace('.', '/');
        String superClassNameInternal = FastMethodAccessor.class.getName().replace('.', '/');

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        MethodVisitor mv;
        cw.visit(V1_1, ACC_PUBLIC + ACC_SUPER, accessorClassNameInternal, null, superClassNameInternal, null);

        // 无参构造方法
        {
            mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, superClassNameInternal, "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // 实现父类抽象方法: public Object invoke(Object obj, int methodIndex, Object... args);
        {
            mv = cw.visitMethod(ACC_PUBLIC + ACC_VARARGS, "invoke", "(Ljava/lang/Object;I[Ljava/lang/Object;)Ljava/lang/Object;", null, null);
            mv.visitCode();

            if (n > 0) {
                // 强制转换第一个参数 obj 为 指定类型(Class<?> type)
                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, classNameInternal);
                mv.visitVarInsn(ASTORE, 4);

                // 生成 switch 语句
                mv.visitVarInsn(ILOAD, 2);
                Label[] labels = new Label[n];
                for (int i = 0; i < n; i++) {
                    labels[i] = new Label();
                }
                Label defaultLabel = new Label(); // the default handler block
                mv.visitTableSwitchInsn(0, labels.length - 1, defaultLabel, labels);

                StringBuilder buf = new StringBuilder(128);
                for (int i = 0; i < n; i++) {
                    mv.visitLabel(labels[i]);
                    if (i == 0) {
                        mv.visitFrame(Opcodes.F_APPEND, 1, new Object[] { classNameInternal }, 0, null);
                    } else {
                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                    mv.visitVarInsn(ALOAD, 4);

                    buf.setLength(0);
                    buf.append('(');

                    Class<?>[] parameterTypes = parameterTypes_s[i];
                    Class<?> returnType = returnTypes[i];
                    for (int p_index = 0; p_index < parameterTypes.length; p_index++) {
                        mv.visitVarInsn(ALOAD, 3);
                        mv.visitIntInsn(BIPUSH, p_index);
                        mv.visitInsn(AALOAD);

                        // 基本类型参数强转并拆箱, 对象类型(包含数组)强转
                        Type p_type = Type.getType(parameterTypes[p_index]);
                        switch (p_type.getSort()) {
                            case Type.BOOLEAN:
                                mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                                break;
                            case Type.BYTE:
                                mv.visitTypeInsn(CHECKCAST, "java/lang/Byte");
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
                                break;
                            case Type.CHAR:
                                mv.visitTypeInsn(CHECKCAST, "java/lang/Character");
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                                break;
                            case Type.SHORT:
                                mv.visitTypeInsn(CHECKCAST, "java/lang/Short");
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
                                break;
                            case Type.INT:
                                mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                                break;
                            case Type.FLOAT:
                                mv.visitTypeInsn(CHECKCAST, "java/lang/Float");
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
                                break;
                            case Type.LONG:
                                mv.visitTypeInsn(CHECKCAST, "java/lang/Long");
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
                                break;
                            case Type.DOUBLE:
                                mv.visitTypeInsn(CHECKCAST, "java/lang/Double");
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
                                break;
                            case Type.ARRAY:
                                mv.visitTypeInsn(CHECKCAST, p_type.getDescriptor());
                                break;
                            case Type.OBJECT:
                                mv.visitTypeInsn(CHECKCAST, p_type.getInternalName());
                                break;
                        }
                        buf.append(p_type.getDescriptor());
                    }

                    buf.append(')').append(Type.getDescriptor(returnType));

                    // 生成方法直接调用代码, 规避反射
                    if (isInterface) {
                        mv.visitMethodInsn(INVOKEINTERFACE, classNameInternal, methodNames[i], buf.toString(), true);
                    } else if (Modifier.isStatic(methods.get(i).getModifiers())) {
                        mv.visitMethodInsn(INVOKESTATIC, classNameInternal, methodNames[i], buf.toString(), false);
                    } else {
                        mv.visitMethodInsn(INVOKEVIRTUAL, classNameInternal, methodNames[i], buf.toString(), false);
                    }

                    Type r_type = Type.getType(returnType);
                    switch (r_type.getSort()) {
                        case Type.VOID:
                            mv.visitInsn(ACONST_NULL);
                            break;
                        case Type.BOOLEAN:
                            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                            break;
                        case Type.BYTE:
                            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                            break;
                        case Type.CHAR:
                            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                            break;
                        case Type.SHORT:
                            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                            break;
                        case Type.INT:
                            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                            break;
                        case Type.FLOAT:
                            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                            break;
                        case Type.LONG:
                            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                            break;
                        case Type.DOUBLE:
                            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                            break;
                    }
                    mv.visitInsn(ARETURN);
                }
                mv.visitLabel(defaultLabel); // 空的 default 语句块
                mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            }

            // throw exception (Method not found)
            mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
            mv.visitInsn(DUP);
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("Method not found: ");
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false);
            mv.visitInsn(ATHROW);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();
        byte[] bytes = cw.toByteArray();

        AccessorClassLoader loader = AccessorClassLoader.get(type);
        Class<?> accessorClass = loader.defineClass(accessorClassName, bytes);
        try {
            FastMethodAccessor accessor = (FastMethodAccessor) accessorClass.newInstance();
            accessor.methodNames = methodNames;
            accessor.parameterTypes_s = parameterTypes_s;
            return accessor;
        } catch (Throwable t) {
            throw new RuntimeException("Error constructing method access class: " + accessorClass, t);
        }
    }

    /**
     * 将 非object 方法添加到 methods 中
     * @param type
     * @param methods
     */
    private static void addDeclaredMethodsToList(Class<?> type, ArrayList<Method> methods) {
        Method[] declaredMethods = type.getDeclaredMethods();
        for (Method method : declaredMethods) {
            // 只添加非私有方法
            if (!Modifier.isPrivate(method.getModifiers())) {
                methods.add(method);
            }
        }
    }

    private static void recursiveAddInterfaceMethodsToList(Class<?> interfaceType, ArrayList<Method> methods) {
        addDeclaredMethodsToList(interfaceType, methods);
        for (Class nextInterface : interfaceType.getInterfaces()) {
            recursiveAddInterfaceMethodsToList(nextInterface, methods);
        }
    }

    static class AccessorClassLoader extends ClassLoader {

        private static final WeakHashMap<ClassLoader, WeakReference<AccessorClassLoader>> accessorClassLoaders = new WeakHashMap<>();

        private static final ClassLoader selfContextParentClassLoader = getParentClassLoader(AccessorClassLoader.class);
        private static volatile AccessorClassLoader selfContextAccessorClassLoader = new AccessorClassLoader(selfContextParentClassLoader);

        public AccessorClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> defineClass(String name, byte[] bytes) throws ClassFormatError {
            return defineClass(name, bytes, 0, bytes.length, getClass().getProtectionDomain());
        }

        static AccessorClassLoader get(Class<?> type) {
            ClassLoader parent = getParentClassLoader(type);

            // 1. 最快路径:
            if (selfContextParentClassLoader.equals(parent)) {
                if (selfContextAccessorClassLoader == null) {
                    synchronized (accessorClassLoaders) { // DCL with volatile semantics
                        if (selfContextAccessorClassLoader == null)
                            selfContextAccessorClassLoader = new AccessorClassLoader(selfContextParentClassLoader);
                    }
                }
                return selfContextAccessorClassLoader;
            }

            // 2. 常规查找:
            synchronized (accessorClassLoaders) {
                WeakReference<AccessorClassLoader> ref = accessorClassLoaders.get(parent);
                if (ref != null) {
                    AccessorClassLoader accessorClassLoader = ref.get();
                    if (accessorClassLoader != null) {
                        return accessorClassLoader;
                    } else {
                        accessorClassLoaders.remove(parent); // the value has been GC-reclaimed, but still not the key (defensive sanity)
                    }
                }
                AccessorClassLoader accessorClassLoader = new AccessorClassLoader(parent);
                accessorClassLoaders.put(parent, new WeakReference<>(accessorClassLoader));
                return accessorClassLoader;
            }
        }

        private static ClassLoader getParentClassLoader(Class<?> type) {
            ClassLoader parent = type.getClassLoader();
            if (parent == null) {
                parent = ClassLoader.getSystemClassLoader();
            }
            return parent;
        }
    }
}
