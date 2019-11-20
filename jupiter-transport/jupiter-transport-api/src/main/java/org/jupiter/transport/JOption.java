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
package org.jupiter.transport;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.jupiter.common.util.AbstractConstant;
import org.jupiter.common.util.ConstantPool;

/**
 * Jupiter transport option.
 *
 * jupiter
 * org.jupiter.transport
 *
 * 传输层配置项
 * @param <T> the type of the value which is valid for the {@link JOption}
 * @author jiachun.fjc
 */
@SuppressWarnings("all")
public final class JOption<T> extends AbstractConstant<JOption<T>> {

    /**
     * 这个常量池实际上就是 将一组相关的配置 通过 concurrentMap 存储起来了
     */
    private static final ConstantPool<JOption<Object>> pool = new ConstantPool<JOption<Object>>() {

        @Override
        protected JOption<Object> newConstant(int id, String name) {
            return new JOption<>(id, name);
        }
    };

    /**
     * Returns the {@link JOption} of the specified name.
     */
    public static <T> JOption<T> valueOf(String name) {
        return (JOption<T>) pool.valueOf(name);
    }

    /**
     * Shortcut of {@link #valueOf(String) valueOf(firstNameComponent.getName() + "#" + secondNameComponent)}.
     */
    public static <T> JOption<T> valueOf(Class<?> firstNameComponent, String secondNameComponent) {
        return (JOption<T>) pool.valueOf(firstNameComponent, secondNameComponent);
    }

    /**
     * Creates a new {@link JOption} for the given {@param name} or fail with an
     * {@link IllegalArgumentException} if a {@link JOption} for the given {@param name} exists.
     */
    public static <T> JOption<T> newInstance(String name) {
        return (JOption<T>) pool.newInstance(name);
    }

    /**
     * Returns {@code true} if a {@link JOption} exists for the given {@code name}.
     */
    public static boolean exists(String name) {
        return pool.exists(name);
    }

    /**
     * 对此连接禁用Nagle算法.    Nagle 即是为了避免在发送端数据发送过慢 ， 比如每产生一个字节就会发送一个数据包 可是在TCP 报文中
     * 固定的部分占了很大的比重比如40 字节 这样就有很多的浪费 所以诞生了Nagle算法 用于强制发送端发送较大的数据(等待小数据队列) 但是这样数据就会有延时
     * 这里的 No_Delay 就是要禁用该算法
     * 注意 它与塞子算法的不同点是  与某台机器交互中只能存在一个 未确认的消息 也就是发送数据包1 后 在收到1的ack之前有足够的时间 收集将要发送的
     * 下批数据
     */
    public static final JOption<Boolean> TCP_NODELAY = valueOf("TCP_NODELAY");

    /**
     * 为TCP套接字设置keepalive选项时, 如果在2个小时（实际值与具体实现有关）内在
     * 任意方向上都没有跨越套接字交换数据, 则 TCP 会自动将 keepalive 探头发送到对端,
     * 此探头是对端必须响应的TCP段.
     *
     * 期望的响应为以下三种之一:
     * 1. 收到期望的对端ACK响应
     *      不通知应用程序(因为一切正常), 在另一个2小时的不活动时间过后，TCP将发送另一个探头。
     * 2. 对端响应RST
     *      通知本地TCP对端已崩溃并重新启动, 套接字被关闭.
     * 3. 对端没有响
     *      套接字被关闭。
     *
     * 此选项的目的是检测对端主机是否崩溃, 仅对TCP套接字有效.
     *
     * TCP 连接本身在没有主动关闭的情况下就是不会自动关闭的  而KEEP_ALIVE 机制类似于一种心跳，他能够在对端处在非正常状态的情况下自动的关闭长连接
     * 也就是长连接本身不是依靠这个配置来实现的   该配置默认是关闭的  该选项缺点就是增加了网络开销
     *
     * 在HTTP 层面 本身每次通信都是短连接 也就是数据一旦发送完自动关闭（这样也会关闭底层TCP连接） 而keep_alive 就使得HTTP层面每次交互后不需要重新建立连接的过程
     */
    public static final JOption<Boolean> KEEP_ALIVE = valueOf("KEEP_ALIVE");

    /**
     * [TCP/IP协议详解]中描述:
     * 当TCP执行一个主动关闭, 并发回最后一个ACK ,该连接必须在TIME_WAIT状态停留的时间为2倍的MSL.
     * 这样可让TCP再次发送最后的ACK以防这个ACK丢失(另一端超时并重发最后的FIN).
     * 这种2MSL等待的另一个结果是这个TCP连接在2MSL等待期间, 定义这个连接的插口对(TCP四元组)不能再被使用.
     * 这个连接只能在2MSL结束后才能再被使用.
     *
     * 许多具体的实现中允许一个进程重新使用仍处于2MSL等待的端口(通常是设置选项SO_REUSEADDR),
     * 但TCP不能允许一个新的连接建立在相同的插口对上。
     *
     * 用于减少服务器不可用的时间 在 TIME_WAIT 下
     */
    public static final JOption<Boolean> SO_REUSEADDR = valueOf("SO_REUSEADDR");

    /**
     * 设置snd_buf
     * 一般对于要建立大量连接的应用, 不建议设置这个值, 因为linux内核对snd_buf的大小是动态调整的, 内核是很聪明的.
     */
    public static final JOption<Integer> SO_SNDBUF = valueOf("SO_SNDBUF");

    /**
     * 设置rcv_buf
     * 一般对于要建立大量连接的应用, 不建议设置这个值, 因为linux内核对rcv_buf的大小是动态调整的.
     */
    public static final JOption<Integer> SO_RCVBUF = valueOf("SO_RCVBUF");

    /**
     * SO_LONGER 中包含2个属性 一个是 是否打开 一个是超时时间  未打开 也就是默认情况 在关闭时会将缓冲区数据全部发送完成， 如果设置了超时时间为0 就是抛弃缓冲区数据
     * 如果是正数 就是阻塞等待指定时间 或者 缓冲区数据全部发送提前退出阻塞
     * 如果超时时间是0 不会进入 TIME_WAIT 状态 就是强制关闭TCP 连接
     */
    public static final JOption<Integer> SO_LINGER = valueOf("SO_LINGER");

    /**
     * 在linux内核中TCP握手过程总共会有两个队列:
     *  1) 一个俗称半连接队列, 放着那些握手一半的连接(syn queue)
     *  2) 另一个放着那些握手成功但是还没有被应用层accept的连接的队列(accept queue)
     *
     * backlog控制着accept queue的大小, 但backlog的上限是somaxconn
     * linux 2.6.20版本之前 /proc/sys/net/ipv4/tcp_max_syn_backlog决定syn queue的大小,
     * 2.6.20版本之后syn queue的大小是经过一系列复杂的计算, 那个代码我看不懂...
     *
     * <pre>
     * 参考linux-3.10.28代码(socket.c):
     *
     * sock = sockfd_lookup_light(fd, &err, &fput_needed);
     * if (sock) {
     *     somaxconn = sock_net(sock->sk)->core.sysctl_somaxconn;
     *     if ((unsigned int)backlog > somaxconn)
     *         backlog = somaxconn;
     *
     *     err = security_socket_listen(sock, backlog);
     *     if (!err)
     *         err = sock->ops->listen(sock, backlog);
     *     fput_light(sock->file, fput_needed);
     * }
     *
     * 以上代码可以看到backlog并不是按照应用层所设置的backlog大小, 实际上取的是backlog和somaxconn的最小值.
     * somaxconn的值定义在:
     * /proc/sys/net/core/somaxconn
     * </pre>
     *
     * 还有一点要注意, 对于TCP连接的ESTABLISHED状态, 并不需要应用层accept,
     * 只要在accept queue里就已经变成状态ESTABLISHED, 所以在使用ss或netstat排查这方面问题不要被ESTABLISHED迷惑.
     *
     * 准备好的连接会存放在一个 acceptQueue 队列中  只有当服务端触发 accept时 才会将连接从队列中取出来 当该队列满时(服务器来不及取出来)
     * 这时客户端尝试建立新连接会被拒绝
     */
    public static final JOption<Integer> SO_BACKLOG = valueOf("SO_BACKLOG");

    /**
     * Set or receive the Type-Of-Service (TOS) field that is sent with every IP packet originating from this socket.
     * It is used to prioritize packets on the network.  TOS is a byte.  Thereare some standard TOS flags
     * defined: IPTOS_LOWDELAY to mini‐mize delays for interactive traffic, IPTOS_THROUGHPUT to opti‐mize throughput,
     * IPTOS_RELIABILITY to optimize for reliabil‐ity, IPTOS_MINCOST should be used for "filler data" where slow
     * transmission doesn't matter.  At most one of these TOS values can be specified.
     * Other bits are invalid and shall be cleared.  Linux sends IPTOS_LOWDELAY datagrams first by default,
     * but the exact behavior depends on the configured queueing discipline.  Some high-priority levels may require
     * superuser privileges (the CAP_NET_ADMIN capability).
     * 用于标明本次请求的  一些优先级    分为 最小时延、最大吞吐量、最高可靠性和最小费用
     */
    public static final JOption<Integer> IP_TOS = valueOf("IP_TOS");

    /**
     * 如果该值为false 当对端关闭连接时本端连接自动关闭 如果该值为true 对端连接关闭时 会触发channelInboundHandler的 ALLOW_HALF_CLOSURE
     */
    public static final JOption<Boolean> ALLOW_HALF_CLOSURE = valueOf("ALLOW_HALF_CLOSURE");

    /**
     * Netty的选项, write高水位线.  该属性对应 channel.isWriterable()  代表缓冲区是否有足够的空间
     */
    public static final JOption<Integer> WRITE_BUFFER_HIGH_WATER_MARK = valueOf("WRITE_BUFFER_HIGH_WATER_MARK");

    /**
     * Netty的选项, write低水位线.
     */
    public static final JOption<Integer> WRITE_BUFFER_LOW_WATER_MARK = valueOf("WRITE_BUFFER_LOW_WATER_MARK");

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the child event loops.
     * The default value is {@code 50}, which means the event loop will try to spend the same
     * amount of time for I/O as for non-I/O tasks.
     */
    public static final JOption<Integer> IO_RATIO = valueOf("IO_RATIO");

    public static final JOption<Integer> CONNECT_TIMEOUT_MILLIS = valueOf("CONNECT_TIMEOUT_MILLIS");

    /** ==== Netty native epoll options ============================================================================ */

    /**
     * Set the SO_REUSEPORT option on the underlying channel. This will allow to bind multiple
     * epoll socket channels to the same port and so accept connections with multiple threads.
     *
     * Be aware this method needs be called before channel#bind to have any affect.
     * 套接字将会以线程为单位 每个线程对应的套接字使用相同的端口和 ip 通过内核级别进行负载
     */
    public static final JOption<Boolean> SO_REUSEPORT = valueOf("SO_REUSEPORT");

    /**
     * If set, don't send out partial frames. All queued partial frames are sent when the option is cleared again.
     * This is useful for prepending headers before calling sendfile(2),
     * or for throughput optimization. As currently implemented,
     * there is a 200 millisecond ceiling on the time for which output is corked by TCP_CORK.
     * If this ceiling is reached, then queued data is automatically transmitted.
     * This option can be combined with TCP_NODELAY only since Linux 2.5.71.
     * This option should not be used in code intended to be portable.
     * 塞子算法 将小的数据包收集组合后发送
     */
    public static final JOption<Boolean> TCP_CORK = valueOf("TCP_CORK");

    /**
     * 发送缓存的 资料很少 先忽略
     */
    public static final JOption<Long> TCP_NOTSENT_LOWAT = valueOf("TCP_NOTSENT_LOWAT");

    /**
     * The time (in seconds) the connection needs to remain idle before TCP starts sending keepalive probes,
     * if the socket option SO_KEEPALIVE has been set on this socket.
     * This option should not be used in code intended to be portable.
     * 控制 首次触发 SO_KEEPALIVE探测包的间隔时间
     */
    public static final JOption<Integer> TCP_KEEPIDLE = valueOf("TCP_KEEPIDLE");

    /**
     * The time (in seconds) between individual keepalive probes.
     * This option should not be used in code intended to be portable.
     * 2次探测间的时间间隔
     */
    public static final JOption<Integer> TCP_KEEPINTVL = valueOf("TCP_KEEPINTVL");

    /**
     * The maximum number of keepalive probes TCP should send before dropping the connection.
     * This option should not be used in code intended to be portable.
     * 确认非活跃的最大重试次数
     */
    public static final JOption<Integer> TCP_KEEPCNT = valueOf("TCP_KEEPCNT");

    /**
     * This option takes an unsigned int as an argument.  When the value is greater than 0,
     * it specifies the maximum amount of time in milliseconds that transmitted data may remain
     * unacknowledged before TCP will forcibly close the corresponding connection and return ETIMEDOUT to the
     * application.  If the option value is specified as 0, TCP will to use the system default.
     *
     * Increasing user timeouts allows a TCP connection to survive extended periods without end-to-end connectivity.
     * Decreasing user timeouts allows applications to "fail fast", if so desired.  Otherwise,
     * failure may take up to 20 minutes with the current system defaults in a normal WAN environment.
     *
     * This option can be set during any state of a TCP connection, but is effective only during
     * the synchronized states of a connection (ESTABLISHED, FIN-WAIT-1, FIN-WAIT-2, CLOSE-WAIT,
     * CLOSING, and LAST-ACK).  Moreover, when used with the TCP keepalive (SO_KEEPALIVE) option,
     * TCP_USER_TIMEOUT will override keepalive to determine when to close a connection due
     * to keepalive failure.
     *
     * The option has no effect on when TCP retransmits a packet, nor when a keepalive probe is sent.
     *
     * This option, like many others, will be inherited by the socket returned by accept(2),
     * if it was set on the listening socket.
     *
     * Further details on the user timeout feature can be found in
     * RFC 793 and RFC 5482 ("TCP User Timeout Option").
     * 允许ack 延时的最大时间 在此之后就认为本次发送失败
     */
    public static final JOption<Integer> TCP_USER_TIMEOUT = valueOf("TCP_USER_TIMEOUT");

    /**
     * If enabled, this boolean option allows binding to an IP address that is nonlocal or does not (yet) exist.
     * This per‐mits listening on a socket, without requiring the underlying network interface or
     * the specified dynamic IP address to be up at the time that the application is trying to bind to it.
     * This option is the per-socket equivalent of the ip_nonlo‐cal_bind /proc interface described below.
     * 虚拟端口??? 看不懂
     */
    public static final JOption<Boolean> IP_FREEBIND = valueOf("IP_FREEBIND");

    /**
     * Setting this boolean option enables transparent proxying on this socket.
     * This socket option allows the calling applica‐tion to bind to a nonlocal IP address and operate both as a
     * client and a server with the foreign address as the local end‐point.  NOTE: this requires that routing
     * be set up in a way that packets going to the foreign address are routed through the
     * TProxy box (i.e., the system hosting the application that employs the IP_TRANSPARENT socket option).
     * Enabling this socket option requires superuser privileges (the CAP_NET_ADMIN capability).
     * 开启代理路由 应该用不到 先不管
     */
    public static final JOption<Boolean> IP_TRANSPARENT = valueOf("IP_TRANSPARENT");

    /**
     * Enables tcpFastOpen on the server channel. If the underlying os doesnt support TCP_FASTOPEN setting this has no
     * effect. This has to be set before doing listen on the socket otherwise this takes no effect.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7413">RFC 7413 TCP FastOpen</a>
     * 节省三次握手中最后一次ack 改成直接发送消息
     */
    public static final JOption<Integer> TCP_FASTOPEN = valueOf("TCP_FASTOPEN");

    /**
     * Set the {@code TCP_FASTOPEN_CONNECT} option on the socket. Requires Linux kernel 4.11 or later.
     * See
     * <a href="https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/commit/?id=19f6d3f3">this commit</a>
     * for more details.
     * 不懂
     */
        public static final JOption<Boolean> TCP_FASTOPEN_CONNECT = valueOf("TCP_FASTOPEN_CONNECT");

    /**
     * Allow a listener to be awakened only when data arrives on the socket.
     * Takes an integer value (seconds), this can bound the maximum number of
     * attempts TCP will make to complete the connection.
     * This option should not be used in code intended to be portable.
     * 设置该标识后 只有在 对端发送数据了才会从accept()中返回  默认情况只要通过3次握手就能建立连接
     */
    public static final JOption<Integer> TCP_DEFER_ACCEPT = valueOf("TCP_DEFER_ACCEPT");

    /**
     * Enable quickack mode if set or disable quickack mode if cleared.
     * In quickack mode, acks are sent immediately, rather than delayed if needed in accordance to normal TCP operation.
     * This flag is not permanent, it only enables a switch to or from quickack mode.
     * Subsequent operation of the TCP protocol will once again enter/leave quickack mode depending on internal protocol
     * processing and factors such as delayed ack timeouts occurring and data transfer.
     * This option should not be used in code intended to be portable.
     *
     * TCP_QUICKACK不是永久的, 所以TCP_QUICKACK选项应该是需要在每次调用recv后重新设置的
     * Netty代码的实现可能忽略了这个问题(只设置了一次)
     *
     * 快速ACK 默认情况下接收端 在收到数据后会 等待一段时间 如果时间内没有新的数据需要发送到对端就返回纯ACK
     * 如果有数据包 就整合到一个数据包中发送
     * 设置该标识后每次都立即返回ACK包
     */
    public static final JOption<Boolean> TCP_QUICKACK = valueOf("TCP_QUICKACK");

    /**
     * Default is EDGE_TRIGGERED. If you want to use #isAutoRead() {@code false} or #getMaxMessagesPerRead()
     * and have an accurate behaviour you should use LEVEL_TRIGGERED.
     *
     * Be aware this config setting can only be adjusted before the channel was registered.
     * linux 下 epoll相关的选项 因为只了解 select 就先不看这个
     */
    public static final JOption<Boolean> EDGE_TRIGGERED = valueOf("EDGE_TRIGGERED");

    /**
     * ==== Netty native epoll options ============================================================================
     */

    public static final Set<JOption<?>> ALL_OPTIONS;

    static {
        Set<JOption<?>> options = new HashSet<>();

        options.add(TCP_NODELAY);
        options.add(KEEP_ALIVE);
        options.add(SO_REUSEADDR);
        options.add(SO_SNDBUF);
        options.add(SO_RCVBUF);
        options.add(SO_LINGER);
        options.add(SO_BACKLOG);
        options.add(IP_TOS);
        options.add(ALLOW_HALF_CLOSURE);
        options.add(WRITE_BUFFER_HIGH_WATER_MARK);
        options.add(WRITE_BUFFER_LOW_WATER_MARK);
        options.add(IO_RATIO);
        options.add(CONNECT_TIMEOUT_MILLIS);
        options.add(SO_REUSEPORT);
        options.add(TCP_CORK);
        options.add(TCP_NOTSENT_LOWAT);
        options.add(TCP_KEEPIDLE);
        options.add(TCP_KEEPINTVL);
        options.add(TCP_KEEPCNT);
        options.add(TCP_USER_TIMEOUT);
        options.add(IP_FREEBIND);
        options.add(IP_TRANSPARENT);
        options.add(TCP_FASTOPEN);
        options.add(TCP_FASTOPEN_CONNECT);
        options.add(TCP_DEFER_ACCEPT);
        options.add(TCP_QUICKACK);
        options.add(EDGE_TRIGGERED);

        ALL_OPTIONS = Collections.unmodifiableSet(options);
    }

    private JOption(int id, String name) {
        super(id, name);
    }
}
